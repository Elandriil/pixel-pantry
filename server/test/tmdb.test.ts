import { expect, test } from "bun:test";
import { TmdbClient } from "../src/metadata/tmdb";
import type { FetchFn } from "../src/types";

function setup(body: unknown, status = 200, apiKey = "KEY") {
  const urls: string[] = [];
  const headers: Headers[] = [];
  const fetchFn = (async (input: RequestInfo | URL, init?: RequestInit) => {
    urls.push(String(input));
    headers.push(new Headers(init?.headers));
    return new Response(JSON.stringify(body), { status });
  }) as FetchFn;
  const store = new Map<string, string>();
  const cache = {
    get: (key: string) => store.get(key) ?? null,
    set: (key: string, json: string) => void store.set(key, json),
  };
  return { urls, headers, store, client: new TmdbClient({ apiKey, fetchFn, cache }) };
}

test("searchTv requests the right URL and normalises results", async () => {
  const { urls, client } = setup({
    results: [{ id: 1, name: "Severance", original_name: "Severance", first_air_date: "2022-02-18", popularity: 50 }],
  });
  const r = await client.searchTv("Severance", 2022);
  expect(urls).toEqual([
    "https://api.themoviedb.org/3/search/tv?query=Severance&first_air_date_year=2022&api_key=KEY",
  ]);
  expect(r).toEqual([{ id: 1, title: "Severance", originalTitle: "Severance", year: 2022, popularity: 50 }]);
});

test("searchMovie omits year when null and normalises missing fields", async () => {
  const { urls, client } = setup({ results: [{ id: 2, title: "Heat", release_date: "" }] });
  const r = await client.searchMovie("Heat", null);
  expect(urls[0]).toBe("https://api.themoviedb.org/3/search/movie?query=Heat&api_key=KEY");
  expect(r).toEqual([{ id: 2, title: "Heat", originalTitle: null, year: null, popularity: 0 }]);
});

test("movie search keeps original_title", async () => {
  const { client } = setup({ results: [{ id: 3, title: "Spirited Away", original_title: "千と千尋の神隠し", release_date: "2001-07-20" }] });
  expect((await client.searchMovie("Spirited Away"))[0]!.originalTitle).toBe("千と千尋の神隠し");
});

test("movie search uses year param", async () => {
  const { urls, client } = setup({ results: [] });
  await client.searchMovie("Dune", 2024);
  expect(urls[0]).toContain("/3/search/movie?query=Dune&year=2024&api_key=KEY");
});

test("second identical call is served from cache", async () => {
  const { urls, store, client } = setup({ results: [] });
  await client.searchTv("Severance", 2022);
  await client.searchTv("Severance", 2022);
  expect(urls.length).toBe(1);
  expect([...store.keys()]).toEqual(["/3/search/tv?query=Severance&first_air_date_year=2022"]);
});

test("bearer header used for eyJ keys, no api_key param", async () => {
  const { urls, headers, client } = setup({ results: [] }, 200, "eyJtoken");
  await client.searchTv("X", null);
  expect(headers[0].get("authorization")).toBe("Bearer eyJtoken");
  expect(urls[0]).not.toContain("api_key");
});

test("non-2xx throws with status", async () => {
  const { client } = setup({ status_message: "nope" }, 401);
  await expect(client.tvDetails(1)).rejects.toThrow(/^TMDB 401: /);
});

test("empty key throws", async () => {
  const { client } = setup({}, 200, "");
  await expect(client.tvDetails(1)).rejects.toThrow("TMDB API key not set");
});

test("details and season endpoints", async () => {
  const { urls, client } = setup({ id: 5 });
  await client.tvDetails(5);
  await client.tvSeason(5, 2);
  await client.movieDetails(7);
  expect(urls.map((u) => new URL(u).pathname)).toEqual(["/3/tv/5", "/3/tv/5/season/2", "/3/movie/7"]);
});

test("apiKey getter is re-read on each call", async () => {
  let key = "";
  const urls: string[] = [];
  const fetchFn = (async (input: RequestInfo | URL) => {
    urls.push(String(input));
    return new Response(JSON.stringify({ results: [] }));
  }) as FetchFn;
  const client = new TmdbClient({ apiKey: () => key, fetchFn, cache: { get: () => null, set: () => {} } });
  await expect(client.searchTv("Severance")).rejects.toThrow("TMDB API key not set");
  key = "NEW";
  await client.searchTv("Severance");
  expect(urls).toEqual(["https://api.themoviedb.org/3/search/tv?query=Severance&api_key=NEW"]);
});

test("a request that never answers is rejected with TMDB timeout", async () => {
  let signal: AbortSignal | undefined;
  const fetchFn = ((_input: RequestInfo | URL, init?: RequestInit) => {
    signal = init?.signal ?? undefined;
    return new Promise<Response>(() => {});
  }) as FetchFn;
  const client = new TmdbClient({ apiKey: "K", fetchFn, cache: { get: () => null, set: () => {} }, timeoutMs: 20 });
  await expect(client.tvDetails(1)).rejects.toThrow("TMDB timeout");
  expect(signal?.aborted).toBe(true);
});

test("an abort error from fetch is reported as TMDB timeout", async () => {
  const fetchFn = ((_input: RequestInfo | URL, init?: RequestInit) =>
    new Promise<Response>((_, reject) => init?.signal?.addEventListener("abort", () => reject(init.signal!.reason)))) as FetchFn;
  const client = new TmdbClient({ apiKey: "K", fetchFn, cache: { get: () => null, set: () => {} }, timeoutMs: 20 });
  await expect(client.searchTv("x")).rejects.toThrow("TMDB timeout");
});

test("TV details and seasons are cached for 12 h, searches and movies for the configured age", async () => {
  const ages: [string, number][] = [];
  const fetchFn = (async () => new Response(JSON.stringify({ results: [] }))) as unknown as FetchFn;
  const cache = { get: (key: string, maxAgeMs: number) => (ages.push([key, maxAgeMs]), null), set: () => {} };
  const client = new TmdbClient({ apiKey: "K", fetchFn, cache });
  await client.tvDetails(1);
  await client.tvSeason(1, 2);
  await client.movieDetails(3);
  await client.searchTv("x");
  const H = 60 * 60 * 1000;
  expect(ages).toEqual([["/3/tv/1", 12 * H], ["/3/tv/1/season/2", 12 * H], ["/3/movie/3", 168 * H], ["/3/search/tv?query=x", 168 * H]]);
});
