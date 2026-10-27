import { expect, spyOn, test } from "bun:test";
import { mkdirSync } from "node:fs";
import { buildApp } from "../../src/http/app";
import type { Scanner } from "../../src/library/scanner";
import { join } from "node:path";
import { makeDeps, request, seedMovie, seedShow } from "./helpers";
import { tmpDir } from "../helpers";

const LOCAL = { remoteAddr: "127.0.0.1" };
const MASK = "••••";
const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});
const idleStatus = (s: Scanner) => Promise.resolve(s.status());

test("non-loopback callers get 403 on admin routes and the page", async () => {
  const app = buildApp(makeDeps());
  for (const path of ["/admin", "/admin/api/status", "/admin/api/config"]) {
    const r = await request(app, path, undefined, { remoteAddr: "192.168.1.20" });
    expect(r.status).toBe(403);
    expect(r.json).toEqual({ error: "admin is localhost only" });
  }
  // unknown address is treated as remote; X-Forwarded-For is never trusted
  const unknown = await request(app, "/admin/api/status", { headers: { "X-Forwarded-For": "127.0.0.1" } });
  expect(unknown.status).toBe(403);
  for (const addr of ["::1", "::ffff:127.0.0.1"]) {
    expect((await request(app, "/admin/api/status", undefined, { remoteAddr: addr })).status).toBe(200);
  }
});

test("a loopback caller with a non-local Host header gets 403", async () => {
  const app = buildApp(makeDeps());
  for (const host of ["evil.example:8765", "192.168.1.5:8765", "localhost.evil.example"]) {
    const r = await request(app, "/admin/api/status", { headers: { Host: host } }, LOCAL);
    expect(r.status).toBe(403);
    expect(r.json).toEqual({ error: "admin is localhost only" });
  }
  for (const host of ["localhost", "localhost:8765", "127.0.0.1:8765", "[::1]:8765", "LOCALHOST:1"]) {
    expect((await request(app, "/admin/api/status", { headers: { Host: host } }, LOCAL)).status).toBe(200);
  }
  // no Host header (tests, HTTP/1.0): the request URL's host decides
  expect((await request(app, "http://evil.example/admin/api/status", undefined, LOCAL)).status).toBe(403);
});

test("remote address falls back to the Bun server's requestIP", async () => {
  const app = buildApp(makeDeps());
  const server = { requestIP: () => ({ address: "127.0.0.1", family: "IPv4", port: 1 }) };
  expect((await request(app, "/admin/api/status", undefined, { server })).status).toBe(200);
  const remote = { requestIP: () => ({ address: "10.0.0.5", family: "IPv4", port: 1 }) };
  expect((await request(app, "/admin/api/status", undefined, { server: remote })).status).toBe(403);
});

test("status reports config, scan state and counts from seeded data", async () => {
  const deps = makeDeps();
  deps.saveConfig({ ...deps.config(), tmdbApiKey: "k" });
  seedShow(deps.repo, { title: "Show A", category: "tv", seasons: [[1]] });
  seedShow(deps.repo, { title: "Show B", category: "tv", seasons: [[1, 2]] });
  seedShow(deps.repo, { title: "Anime A", category: "anime", seasons: [[1]] });
  seedMovie(deps.repo, "Film A");
  const app = buildApp(deps);
  const r = await request(app, "/admin/api/status", undefined, LOCAL);
  expect(r.status).toBe(200);
  expect(r.json).toMatchObject({
    version: "1",
    port: 8765,
    libraries: deps.config().libraries.map((l) => ({ ...l, found: false })),
    scan: { state: "idle" },
    counts: { tv: 2, anime: 1, movies: 1 },
    lastScanError: null,
    tmdbConfigured: true,
    opensubtitlesConfigured: false,
  });
  expect(Array.isArray(r.json.lanAddresses)).toBe(true);
  for (const a of r.json.lanAddresses) expect(a).toMatch(/^\d+\.\d+\.\d+\.\d+$/);
  expect(r.json.lanAddresses).not.toContain("127.0.0.1");
});

test("config GET masks secrets and PUT with masked values keeps the stored secrets", async () => {
  const deps = makeDeps();
  deps.saveConfig({
    ...deps.config(),
    tmdbApiKey: "tmdb-secret",
    opensubtitles: { apiKey: "os-key", username: "me", password: "pw" },
  });
  const app = buildApp(deps);
  const g = await request(app, "/admin/api/config", undefined, LOCAL);
  expect(g.status).toBe(200);
  expect(g.json).toEqual({
    libraries: deps.config().libraries,
    port: 8765,
    tmdbApiKey: MASK,
    opensubtitles: { apiKey: MASK, username: "me", password: MASK },
    startWithWindows: true,
  });

  const scan = spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const p = await request(
    app,
    "/admin/api/config",
    json("PUT", { ...g.json, opensubtitles: { ...g.json.opensubtitles, username: "you" } }),
    LOCAL,
  );
  expect(p.status).toBe(200);
  expect(p.json).toEqual({ restartRequired: false });
  expect(deps.config().tmdbApiKey).toBe("tmdb-secret");
  expect(deps.config().opensubtitles).toEqual({ apiKey: "os-key", username: "you", password: "pw" });
  expect(scan).not.toHaveBeenCalled();

  const empty = await request(app, "/admin/api/config", json("PUT", { tmdbApiKey: "" }), LOCAL);
  expect(empty.status).toBe(200);
  expect(deps.config().tmdbApiKey).toBe("");
  expect((await request(app, "/admin/api/config", undefined, LOCAL)).json.tmdbApiKey).toBe("");
});

test("config PUT with a new library list saves it and triggers a scan; port change requires restart", async () => {
  const deps = makeDeps();
  const scan = spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);
  const [tv] = deps.config().libraries;
  const body = { libraries: [tv, { path: "  D:\\October Movies  ", category: "movies" }], port: 9000 };
  const r = await request(app, "/admin/api/config", json("PUT", body), LOCAL);
  expect(r.status).toBe(200);
  expect(r.json).toEqual({ restartRequired: true });
  expect(deps.config().port).toBe(9000);
  const libs = deps.config().libraries;
  expect(libs.length).toBe(2);
  expect(libs[0]).toEqual(tv);
  expect(libs[1]).toMatchObject({ path: "D:\\October Movies", category: "movies" });
  expect(libs[1]!.id).toMatch(/^lib-[0-9a-f]{8}$/);
  expect(scan).toHaveBeenCalledTimes(1);

  // the same list again: nothing changes and no scan starts
  const again = await request(app, "/admin/api/config", json("PUT", { libraries: libs }), LOCAL);
  expect(again.status).toBe(200);
  expect(deps.config().libraries).toEqual(libs);
  expect(scan).toHaveBeenCalledTimes(1);
});

test("config PUT with a new TMDB key resets unmatched attempts and triggers a scan", async () => {
  const deps = makeDeps();
  const unmatched = seedShow(deps.repo, { title: "Mystery", category: "tv", seasons: [[1]] });
  deps.repo.setShowMatchError(unmatched.showId, "No TMDB match", 10);
  const movieId = seedMovie(deps.repo, "Odd Film");
  deps.repo.setItemMatchError(movieId, "TMDB 401", 10);
  const matched = seedShow(deps.repo, { title: "Known", category: "tv", seasons: [[1]] });
  deps.repo.updateShowMeta(matched.showId, { tmdb_id: 42 });
  deps.repo.setShowMatchError(matched.showId, null, 10);
  const scan = spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);

  const same = await request(app, "/admin/api/config", json("PUT", { tmdbApiKey: MASK }), LOCAL);
  expect(same.status).toBe(200);
  expect(scan).not.toHaveBeenCalled();
  expect(deps.repo.getShow(unmatched.showId)!.match_attempted_at).toBe(10);

  const r = await request(app, "/admin/api/config", json("PUT", { tmdbApiKey: "new-key" }), LOCAL);
  expect(r.status).toBe(200);
  expect(scan).toHaveBeenCalledTimes(1);
  expect(deps.repo.getShow(unmatched.showId)).toMatchObject({ match_attempted_at: null, match_error: null });
  expect(deps.repo.getItem(movieId)).toMatchObject({ match_attempted_at: null, match_error: null });
  expect(deps.repo.getItem(unmatched.itemIds[0]![0]!)!.match_attempted_at).toBeNull();
  expect(deps.repo.getShow(matched.showId)!.match_attempted_at).toBe(10);
});

test("config PUT keeps a known library's id and type, strips quotes, and accepts an empty list", async () => {
  const deps = makeDeps();
  spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);
  const [tv] = deps.config().libraries;
  const moved = { id: tv!.id, path: ' "E:\\My TV" ', category: "anime" };
  expect((await request(app, "/admin/api/config", json("PUT", { libraries: [moved] }), LOCAL)).status).toBe(200);
  expect(deps.config().libraries).toEqual([{ id: "tv", path: "E:\\My TV", category: "tv" }]);
  expect((await request(app, "/admin/api/config", json("PUT", { libraries: [] }), LOCAL)).status).toBe(200);
  expect(deps.config().libraries).toEqual([]);
});

test("config PUT rejects bad libraries", async () => {
  const deps = makeDeps();
  spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);
  const before = deps.config().libraries;
  const tv = before[0]!;
  const cases: [unknown, string][] = [
    [{ libraries: "x" }, "libraries must be an array"],
    [{ libraries: [{ path: "", category: "tv" }] }, "Library folder path is required"],
    [{ libraries: [{ path: '""', category: "tv" }] }, "Library folder path is required"],
    [{ libraries: [{ path: "D:\\X", category: "photos" }] }, "Library type must be tv, anime or movies"],
    [{ libraries: [{ id: "lib-nope", path: "D:\\X", category: "tv" }] }, "Unknown library id: lib-nope"],
    [{ libraries: [tv, tv] }, "Duplicate library id: tv"],
    [{ libraries: [tv, { path: tv.path, category: "movies" }] }, `${tv.path} is already a library folder`],
    [{ libraries: [tv, { path: join(tv.path, "Sub"), category: "movies" }] }, `${join(tv.path, "Sub")} is inside the library folder ${tv.path}`],
    [{ libraries: [tv, { path: deps.mediaRoot, category: "movies" }] }, `${deps.mediaRoot} contains the library folder ${tv.path}`],
    [{ startWithWindows: "yes" }, "startWithWindows must be a boolean"],
  ];
  for (const [body, error] of cases) {
    const r = await request(app, "/admin/api/config", json("PUT", body), LOCAL);
    expect([r.status, r.json]).toEqual([400, { error }]);
  }
  expect(deps.config().libraries).toEqual(before);
});

test("config PUT saves startWithWindows without starting a scan", async () => {
  const deps = makeDeps();
  const scan = spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);
  expect((await request(app, "/admin/api/config", json("PUT", { startWithWindows: false }), LOCAL)).status).toBe(200);
  expect(deps.config().startWithWindows).toBe(false);
  expect((await request(app, "/admin/api/config", undefined, LOCAL)).json.startWithWindows).toBe(false);
  expect(scan).not.toHaveBeenCalled();
});

test("status lists each library folder with whether it exists", async () => {
  const deps = makeDeps();
  const app = buildApp(deps);
  const existing = tmpDir();
  deps.saveConfig({
    ...deps.config(),
    libraries: [
      { id: "lib-11111111", path: existing, category: "movies" },
      { id: "lib-22222222", path: join(existing, "nope"), category: "tv" },
    ],
  });
  const r = await request(app, "/admin/api/status", undefined, LOCAL);
  expect(r.json.libraries).toEqual([
    { id: "lib-11111111", path: existing, category: "movies", found: true },
    { id: "lib-22222222", path: join(existing, "nope"), category: "tv", found: false },
  ]);
});

test("config PUT rejects an invalid port", async () => {
  const deps = makeDeps();
  const app = buildApp(deps);
  for (const port of [0, 65536, 80.5, "8080"]) {
    const r = await request(app, "/admin/api/config", json("PUT", { port }), LOCAL);
    expect(r.status).toBe(400);
  }
  expect(deps.config().port).toBe(8765);
});

test("unmatched lists shows and movies without a tmdb id, not hidden", async () => {
  const deps = makeDeps();
  const { showId } = seedShow(deps.repo, { title: "Mystery", category: "anime", seasons: [[1, 2]] });
  deps.repo.setShowMatchError(showId, "No TMDB match", 1);
  const matched = seedShow(deps.repo, { title: "Known", category: "tv", seasons: [[1]] });
  deps.repo.updateShowMeta(matched.showId, { tmdb_id: 42 });
  const hidden = seedShow(deps.repo, { title: "Gone", category: "tv", seasons: [[1]] });
  deps.repo.setShowDeleted(hidden.showId, 5);
  const movieId = seedMovie(deps.repo, "Odd Film");
  const app = buildApp(deps);
  const r = await request(app, "/admin/api/unmatched", undefined, LOCAL);
  expect(r.status).toBe(200);
  const none = { lowConfidence: false, tmdbId: null, matchedTitle: null };
  expect(r.json.shows).toEqual([
    { id: showId, parsedTitle: "Mystery", year: null, category: "anime", error: "No TMDB match", fileCount: 2, ...none },
  ]);
  expect(r.json.movies).toEqual([
    { id: movieId, parsedTitle: "Odd Film", year: null, category: "movies", error: null, ...none },
  ]);
});

test("unmatched also lists low-confidence matches, flagged", async () => {
  const deps = makeDeps();
  const { showId } = seedShow(deps.repo, { title: "Sousou no Frieren", category: "anime", seasons: [[1]] });
  deps.repo.updateShowMeta(showId, { tmdb_id: 209867, title: "Frieren: Beyond Journey's End" });
  deps.repo.setShowMatchError(showId, "Low-confidence match: Frieren: Beyond Journey's End", 1);
  const movieId = seedMovie(deps.repo, "Sen to Chihiro");
  deps.repo.updateItemMeta(movieId, { tmdb_id: 129, title: "Spirited Away" });
  deps.repo.setItemMatchError(movieId, "Low-confidence match: Spirited Away", 1);
  const app = buildApp(deps);
  const r = await request(app, "/admin/api/unmatched", undefined, LOCAL);
  expect(r.json.shows).toEqual([
    {
      id: showId, parsedTitle: "Sousou no Frieren", year: null, category: "anime",
      error: "Low-confidence match: Frieren: Beyond Journey's End", fileCount: 1,
      lowConfidence: true, tmdbId: 209867, matchedTitle: "Frieren: Beyond Journey's End",
    },
  ]);
  expect(r.json.movies).toMatchObject([{ id: movieId, lowConfidence: true, tmdbId: 129, matchedTitle: "Spirited Away" }]);
});

test("tmdb search validates input and maps TMDB failures to 503", async () => {
  const deps = makeDeps();
  const app = buildApp(deps);
  expect((await request(app, "/admin/api/tmdb/search?type=book&q=x", undefined, LOCAL)).status).toBe(400);
  expect((await request(app, "/admin/api/tmdb/search?type=tv&q=", undefined, LOCAL)).status).toBe(400);
  const down = await request(app, "/admin/api/tmdb/search?type=tv&q=Lost", undefined, LOCAL);
  expect(down.status).toBe(503);
  expect(typeof down.json.error).toBe("string");

  const results = [{ id: 7, title: "Lost", originalTitle: null, year: 2004, popularity: 1 }];
  const search = spyOn(deps.tmdb, "searchMovie").mockResolvedValue(results);
  const ok = await request(app, "/admin/api/tmdb/search?type=movie&q=Lost&year=2004", undefined, LOCAL);
  expect(ok.status).toBe(200);
  expect(ok.json).toEqual(results);
  expect(search).toHaveBeenCalledWith("Lost", 2004);
});

test("match on a show sets the override, clears show and episode match state, triggers a scan", async () => {
  const deps = makeDeps();
  const { showId, itemIds } = seedShow(deps.repo, { title: "Mystery", category: "tv", seasons: [[1, 2]] });
  deps.repo.updateShowMeta(showId, { tmdb_id: 1 });
  deps.repo.setShowMatchError(showId, "wrong", 10);
  for (const id of itemIds.flat()) {
    deps.repo.updateItemMeta(id, { tmdb_id: 100 + id });
    deps.repo.setItemMatchError(id, "Not on TMDB", 10);
  }
  const scan = spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);
  const r = await request(app, "/admin/api/match", json("POST", { type: "show", id: showId, tmdbId: 555 }), LOCAL);
  expect(r.status).toBe(202);
  expect(r.json).toEqual({ ok: true });
  const show = deps.repo.getShow(showId)!;
  expect(deps.repo.getOverride(show.parsed_key)).toEqual({ parsed_key: show.parsed_key, item_type: "show", tmdb_id: 555 });
  expect(show).toMatchObject({ tmdb_id: null, match_attempted_at: null, match_error: null });
  for (const id of itemIds.flat()) {
    expect(deps.repo.getItem(id)).toMatchObject({ tmdb_id: null, match_attempted_at: null, match_error: null });
  }
  expect(scan).toHaveBeenCalledTimes(1);
});

test("match on a movie sets a movie override and clears its tmdb id; missing entity is 404", async () => {
  const deps = makeDeps();
  const movieId = seedMovie(deps.repo, "Odd Film");
  deps.repo.updateItemMeta(movieId, { tmdb_id: 9 });
  deps.repo.setItemMatchError(movieId, null, 10);
  spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);
  const r = await request(app, "/admin/api/match", json("POST", { type: "movie", id: movieId, tmdbId: 77 }), LOCAL);
  expect(r.status).toBe(202);
  expect(deps.repo.getOverride("movie:odd film")).toMatchObject({ item_type: "movie", tmdb_id: 77 });
  expect(deps.repo.getItem(movieId)).toMatchObject({ tmdb_id: null, match_attempted_at: null });

  expect((await request(app, "/admin/api/match", json("POST", { type: "movie", id: 9999, tmdbId: 1 }), LOCAL)).status).toBe(404);
  expect((await request(app, "/admin/api/match", json("POST", { type: "show", id: 9999, tmdbId: 1 }), LOCAL)).status).toBe(404);
  expect((await request(app, "/admin/api/match", json("POST", { type: "x", id: movieId, tmdbId: 1 }), LOCAL)).status).toBe(400);
});

test("admin scan, log and quit", async () => {
  const deps = makeDeps();
  mkdirSync(deps.mediaRoot, { recursive: true });
  const movieId = seedMovie(deps.repo, "Odd Film");
  deps.repo.setItemMatchError(movieId, "No TMDB match", 10);
  const app = buildApp(deps);
  const s = await request(app, "/admin/api/scan", { method: "POST" }, LOCAL);
  expect(s.status).toBe(202);
  expect(s.json.status.state).toBe("scanning");
  // the manual rescan clears the back-off before scanning
  expect(deps.repo.getItem(movieId)!.match_attempted_at).toBeNull();
  await deps.scanner.scan();

  deps.log.info("hello admin");
  const l = await request(app, "/admin/api/log?n=1", undefined, LOCAL);
  expect(l.status).toBe(200);
  expect(l.json.lines).toHaveLength(1);
  expect(l.json.lines[0]).toContain("hello admin");

  const q = await request(app, "/admin/api/quit", { method: "POST" }, LOCAL);
  expect(q.json).toEqual({ ok: true });
  expect(deps.quitCalls()).toBe(0);
  await Bun.sleep(80);
  expect(deps.quitCalls()).toBe(1);
});

test("GET / redirects to /admin", async () => {
  const app = buildApp(makeDeps());
  const res = await app.request("/", undefined, LOCAL);
  expect(res.status).toBe(302);
  expect(res.headers.get("Location")).toBe("/admin");
});

test("GET /admin serves the admin page", async () => {
  const app = buildApp(makeDeps());
  const res = await app.request("/admin", undefined, LOCAL);
  expect(res.status).toBe(200);
  expect(res.headers.get("Content-Type")).toContain("text/html");
  expect(await res.text()).toContain("Pixel Pantry");
});
