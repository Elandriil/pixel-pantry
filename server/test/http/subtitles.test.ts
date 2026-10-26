import { expect, test } from "bun:test";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { buildApp } from "../../src/http/app";
import { OsClient } from "../../src/subtitles/opensubtitles";
import type { FetchFn } from "../../src/types";
import { touch } from "../helpers";
import { makeDeps, request, seedMovie, seedShow } from "./helpers";

const SRT = "1\n00:00:01,000 --> 00:00:02,000\nHello\n";

function setup(os?: OsClient) {
  const deps = makeDeps(os ? { os } : {});
  const id = seedMovie(deps.repo, "Heat");
  const video = join(deps.config().mediaRoot, "movies/Heat.mkv");
  touch(video, 1024);
  return { deps, id, video, app: buildApp(deps) };
}

function fakeOs(handler: (url: string, init?: RequestInit) => Response) {
  const urls: string[] = [];
  const fetchFn = (async (input: RequestInfo | URL, init?: RequestInit) => {
    urls.push(String(input));
    return handler(String(input), init);
  }) as FetchFn;
  return { urls, os: new OsClient({ apiKey: "K", username: "u", password: "p", fetchFn }) };
}

const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status });

test("subtitle list is embedded tracks first, then sidecars", async () => {
  const { deps, id, video, app } = setup();
  deps.db.run("UPDATE files SET subtitle_tracks = ? WHERE item_id = ?", [
    JSON.stringify([{ index: 3, codec: "subrip", language: "eng", title: "English", default: false, forced: false }]),
    id,
  ]);
  writeFileSync(video.replace(".mkv", ".en.srt"), SRT);
  writeFileSync(video.replace(".mkv", ".vtt"), "WEBVTT\n");

  const r = await request(app, `/api/items/${id}/subtitles`);
  expect(r.status).toBe(200);
  expect(r.json.tracks).toEqual([
    { id: "embedded:3", label: "English", lang: "eng", url: null, format: "embedded" },
    { id: "sidecar:0", label: "English", lang: "en", url: `/api/items/${id}/subtitles/sidecar/0`, format: "srt" },
    { id: "sidecar:1", label: "Sidecar (VTT)", lang: null, url: `/api/items/${id}/subtitles/sidecar/1`, format: "vtt" },
  ]);
  expect((await request(app, `/api/items/999/subtitles`)).status).toBe(404);
});

test("sidecar is served with its content type, shifted when ?shift=500", async () => {
  const { id, video, app } = setup();
  writeFileSync(video.replace(".mkv", ".en.srt"), SRT);
  const plain = await app.request(`/api/items/${id}/subtitles/sidecar/0`);
  expect(plain.headers.get("Content-Type")).toStartWith("application/x-subrip");
  expect(await plain.text()).toBe(SRT);
  const shifted = await app.request(`/api/items/${id}/subtitles/sidecar/0?shift=500`);
  expect(await shifted.text()).toBe("1\n00:00:01,500 --> 00:00:02,500\nHello\n");
  expect((await app.request(`/api/items/${id}/subtitles/sidecar/1`)).status).toBe(404);
  expect((await app.request(`/api/items/${id}/subtitles/sidecar/0?shift=abc`)).status).toBe(400);
});

test("search returns 503 when OpenSubtitles is not configured", async () => {
  const { id, app } = setup();
  const r = await request(app, `/api/items/${id}/subtitles/search`);
  expect(r.status).toBe(503);
  expect(r.json).toEqual({ error: "OpenSubtitles not configured" });
});

test("search sends hash and episode info, upstream failure is 503", async () => {
  let fail = false;
  const { urls, os } = fakeOs(() =>
    fail
      ? new Response("boom", { status: 500 })
      : json({ data: [{ attributes: { files: [{ file_id: 7 }], release: "r", moviehash_match: true, download_count: 1, language: "en" } }] }),
  );
  const deps = makeDeps({ os });
  const s = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [[1, 2]] });
  deps.repo.updateShowMeta(s.showId, { tmdb_id: 4607 });
  const ep = s.itemIds[0]![1]!;
  touch(join(deps.config().mediaRoot, "tv/Lost/S1E2.mkv"), 1024);
  const app = buildApp(deps);

  const r = await request(app, `/api/items/${ep}/subtitles/search`);
  expect(r.status).toBe(200);
  expect(r.json.results).toEqual([{ fileId: 7, release: "r", hashMatch: true, downloads: 1, language: "en", fps: null }]);
  const q = Object.fromEntries(new URL(urls[0]!).searchParams);
  expect(q).toMatchObject({ moviehash: expect.stringMatching(/^[0-9a-f]{16}$/), query: "Lost", season_number: "1", episode_number: "2", tmdb_id: "4607", type: "episode" });

  fail = true;
  const f = await request(app, `/api/items/${ep}/subtitles/search`);
  expect(f.status).toBe(503);
  expect(f.json.error).toContain("OpenSubtitles 500");
});

test("download creates <base>.en.srt and the list then includes it", async () => {
  const { os } = fakeOs((url) =>
    url.endsWith("/login") ? json({ token: "T" }) : url.endsWith("/download") ? json({ link: "https://dl/x.srt" }) : new Response(SRT),
  );
  const { id, video, app } = setup(os);
  writeFileSync(video.replace(".mkv", ".vtt"), "WEBVTT\n");

  const r = await request(app, `/api/items/${id}/subtitles/download`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fileId: 7 }),
  });
  expect(r.status).toBe(200);
  expect(r.json).toEqual({ id: "sidecar:0", label: "English", lang: "en", url: `/api/items/${id}/subtitles/sidecar/0`, format: "srt" });
  expect(readFileSync(video.replace(".mkv", ".en.srt"), "utf8")).toBe(SRT);
  const list = await request(app, `/api/items/${id}/subtitles`);
  expect(list.json.tracks.map((t: any) => t.id)).toEqual(["sidecar:0", "sidecar:1"]);
  expect(
    (await request(app, `/api/items/${id}/subtitles/download`, { method: "POST", body: JSON.stringify({ fileId: "7" }) })).status,
  ).toBe(400);
});
