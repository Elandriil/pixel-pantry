import { expect, spyOn, test } from "bun:test";
import { mkdirSync } from "node:fs";
import { buildApp } from "../../src/http/app";
import type { Scanner } from "../../src/library/scanner";
import { makeDeps, request, seedMovie, seedShow } from "./helpers";

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
    mediaRoot: deps.config().mediaRoot,
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
    mediaRoot: deps.config().mediaRoot,
    port: 8765,
    tmdbApiKey: MASK,
    opensubtitles: { apiKey: MASK, username: "me", password: MASK },
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

test("config PUT with a new mediaRoot saves it and triggers a scan; port change requires restart", async () => {
  const deps = makeDeps();
  const scan = spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);
  const r = await request(app, "/admin/api/config", json("PUT", { mediaRoot: "  D:\\Media  ", port: 9000 }), LOCAL);
  expect(r.status).toBe(200);
  expect(r.json).toEqual({ restartRequired: true });
  expect(deps.config().mediaRoot).toBe("D:\\Media");
  expect(deps.config().port).toBe(9000);
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

test("config PUT strips surrounding quotes from mediaRoot and rejects clearing it", async () => {
  const deps = makeDeps();
  spyOn(deps.scanner, "scan").mockImplementation(() => idleStatus(deps.scanner));
  const app = buildApp(deps);
  for (const [raw, stored] of [[' "D:\\My Media" ', "D:\\My Media"], ["'E:\\TV'", "E:\\TV"]]) {
    expect((await request(app, "/admin/api/config", json("PUT", { mediaRoot: raw }), LOCAL)).status).toBe(200);
    expect(deps.config().mediaRoot).toBe(stored!);
  }
  const empty = await request(app, "/admin/api/config", json("PUT", { mediaRoot: '  ""  ' }), LOCAL);
  expect(empty.status).toBe(400);
  expect(empty.json).toEqual({ error: "Media folder is required" });
  expect(deps.config().mediaRoot).toBe("E:\\TV");
});

test("config PUT accepts an empty mediaRoot while none is set", async () => {
  const deps = makeDeps();
  deps.saveConfig({ ...deps.config(), mediaRoot: "" });
  const app = buildApp(deps);
  expect((await request(app, "/admin/api/config", json("PUT", { mediaRoot: "", port: 8765 }), LOCAL)).status).toBe(200);
  expect(deps.config().mediaRoot).toBe("");
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
  expect(r.json.shows).toEqual([
    { id: showId, parsedTitle: "Mystery", year: null, category: "anime", error: "No TMDB match", fileCount: 2 },
  ]);
  expect(r.json.movies).toEqual([{ id: movieId, parsedTitle: "Odd Film", year: null, category: "movies", error: null }]);
});

test("tmdb search validates input and maps TMDB failures to 503", async () => {
  const deps = makeDeps();
  const app = buildApp(deps);
  expect((await request(app, "/admin/api/tmdb/search?type=book&q=x", undefined, LOCAL)).status).toBe(400);
  expect((await request(app, "/admin/api/tmdb/search?type=tv&q=", undefined, LOCAL)).status).toBe(400);
  const down = await request(app, "/admin/api/tmdb/search?type=tv&q=Lost", undefined, LOCAL);
  expect(down.status).toBe(503);
  expect(typeof down.json.error).toBe("string");

  const results = [{ id: 7, title: "Lost", year: 2004, popularity: 1 }];
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
  mkdirSync(deps.config().mediaRoot, { recursive: true });
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
