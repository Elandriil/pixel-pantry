import { expect, test } from "bun:test";
import { OsClient } from "../../src/subtitles/opensubtitles";
import type { FetchFn } from "../../src/types";

type Call = { url: string; method: string; headers: Headers; body: string | undefined };

function setup(handler: (c: Call, n: number) => Response, creds = { apiKey: "K", username: "u", password: "p" }) {
  const calls: Call[] = [];
  const fetchFn = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const c = {
      url: String(input), method: init?.method ?? "GET", headers: new Headers(init?.headers),
      body: init?.body as string | undefined,
    };
    calls.push(c);
    return handler(c, calls.length);
  }) as FetchFn;
  return { calls, client: new OsClient({ ...creds, fetchFn }) };
}

const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status });

test("search sends params and ranks hash matches first, then downloads", async () => {
  const { calls, client } = setup(() =>
    json({
      data: [
        { attributes: { files: [{ file_id: 1 }], release: "a", moviehash_match: false, download_count: 900, language: "en", fps: 24 } },
        { attributes: { files: [{ file_id: 2 }], release: "b", moviehash_match: true, download_count: 5, language: "en", fps: null } },
        { attributes: { files: [{ file_id: 3 }], release: "c", moviehash_match: false, download_count: 10, language: "en" } },
      ],
    }),
  );
  const r = await client.search({ hash: "abc", query: "Show", season: 1, episode: 2, tmdbId: null, type: "episode" });
  const u = new URL(calls[0]!.url);
  expect(u.origin + u.pathname).toBe("https://api.opensubtitles.com/api/v1/subtitles");
  expect(Object.fromEntries(u.searchParams)).toEqual({
    moviehash: "abc", query: "Show", season_number: "1", episode_number: "2", languages: "en", type: "episode",
  });
  expect(calls[0]!.headers.get("Api-Key")).toBe("K");
  expect(calls[0]!.headers.get("User-Agent")).toBe("PixelPantry v1");
  expect(calls[0]!.headers.get("Accept")).toBe("application/json");
  expect(r.map((x) => x.fileId)).toEqual([2, 1, 3]);
  expect(r[0]).toEqual({ fileId: 2, release: "b", hashMatch: true, downloads: 5, language: "en", fps: null });
  expect(r[2]!.fps).toBeNull();
});

test("episode search passes the show id as parent_tmdb_id", async () => {
  const { calls, client } = setup(() => json({ data: [] }));
  await client.search({ query: "Lost", season: 1, episode: 2, tmdbId: 4607, type: "episode" });
  const q = Object.fromEntries(new URL(calls[0]!.url).searchParams);
  expect(q.parent_tmdb_id).toBe("4607");
  expect(q.tmdb_id).toBeUndefined();
});

test("search omits empty params and passes tmdb_id", async () => {
  const { calls, client } = setup(() => json({ data: [] }));
  await client.search({ query: "Heat", tmdbId: 949, type: "movie" });
  expect(Object.fromEntries(new URL(calls[0]!.url).searchParams)).toEqual({
    query: "Heat", tmdb_id: "949", languages: "en", type: "movie",
  });
});

test("download logs in once for two downloads", async () => {
  const { calls, client } = setup((c) => {
    if (c.url.endsWith("/login")) return json({ token: "T1" });
    if (c.url.endsWith("/download")) return json({ link: "https://dl.example/x.srt" });
    return new Response("SUBTITLE");
  });
  expect(await client.download(7)).toBe("SUBTITLE");
  expect(await client.download(8)).toBe("SUBTITLE");
  expect(calls.filter((c) => c.url.endsWith("/login"))).toHaveLength(1);
  const login = calls[0]!;
  expect(login.method).toBe("POST");
  expect(login.headers.get("Content-Type")).toBe("application/json");
  expect(JSON.parse(login.body!)).toEqual({ username: "u", password: "p" });
  const dl = calls.filter((c) => c.url.endsWith("/download"));
  expect(dl[0]!.headers.get("Authorization")).toBe("Bearer T1");
  expect(JSON.parse(dl[0]!.body!)).toEqual({ file_id: 7 });
});

test("download re-logs in after a 401 and retries once", async () => {
  let tokens = 0;
  let downloads = 0;
  const { calls, client } = setup((c) => {
    if (c.url.endsWith("/login")) return json({ token: `T${++tokens}` });
    if (c.url.endsWith("/download")) {
      downloads++;
      return downloads === 1 ? json({ message: "expired" }, 401) : json({ link: "https://dl.example/x.srt" });
    }
    return new Response("OK");
  });
  expect(await client.download(1)).toBe("OK");
  const dl = calls.filter((c) => c.url.endsWith("/download"));
  expect(dl.map((c) => c.headers.get("Authorization"))).toEqual(["Bearer T1", "Bearer T2"]);
});

test("errors: not configured and non-2xx", async () => {
  const a = setup(() => json({}), { apiKey: "", username: "u", password: "p" });
  await expect(a.client.search({ type: "movie" })).rejects.toThrow("OpenSubtitles not configured");
  const b = setup(() => new Response("nope", { status: 500 }));
  await expect(b.client.search({ type: "movie" })).rejects.toThrow("OpenSubtitles 500: nope");
});

test("credential getters are re-read on each call and a changed login drops the cached token", async () => {
  const creds = { apiKey: "", username: "", password: "" };
  const calls: Call[] = [];
  let tokens = 0;
  const fetchFn = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const c = { url: String(input), method: init?.method ?? "GET", headers: new Headers(init?.headers), body: init?.body as string | undefined };
    calls.push(c);
    if (c.url.endsWith("/login")) return json({ token: `T${++tokens}` });
    if (c.url.endsWith("/download")) return json({ link: "https://dl.example/x.srt" });
    return new Response("SUB");
  }) as FetchFn;
  const client = new OsClient({
    apiKey: () => creds.apiKey,
    username: () => creds.username,
    password: () => creds.password,
    fetchFn,
  });
  await expect(client.download(1)).rejects.toThrow("OpenSubtitles not configured");
  Object.assign(creds, { apiKey: "K", username: "u1", password: "p1" });
  await client.download(1);
  await client.download(1);
  Object.assign(creds, { username: "u2", password: "p2" });
  await client.download(1);
  const logins = calls.filter((c) => c.url.endsWith("/login"));
  expect(logins.map((c) => JSON.parse(c.body!))).toEqual([
    { username: "u1", password: "p1" },
    { username: "u2", password: "p2" },
  ]);
  const downloads = calls.filter((c) => c.url.endsWith("/download"));
  expect(downloads.map((c) => c.headers.get("Authorization"))).toEqual(["Bearer T1", "Bearer T1", "Bearer T2"]);
  expect(downloads[0]!.headers.get("Api-Key")).toBe("K");
});
