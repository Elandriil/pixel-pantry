import { expect, test } from "bun:test";
import { existsSync, rmSync } from "node:fs";
import { join } from "node:path";
import { buildApp } from "../../src/http/app";
import { touch } from "../helpers";
import { makeDeps, NOW, request, seedMovie, seedShow } from "./helpers";

function setup(overrides: Parameters<typeof makeDeps>[0] = {}) {
  const deps = makeDeps(overrides);
  return { deps, app: buildApp(deps) };
}

const post = (body: unknown) => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});
const put = (body: unknown) => ({ ...post(body), method: "PUT" });

test("progress happy path returns a session and marks watched past 90%", async () => {
  const { deps, app } = setup();
  const id = seedMovie(deps.repo, "Heat");
  const a = await request(app, `/api/items/${id}/progress`, post({ position: 100, duration: 1000 }));
  expect(a.status).toBe(200);
  expect(a.json).toEqual({ sessionId: expect.any(Number), watched: false });
  const b = await request(app, `/api/items/${id}/progress`, post({ position: 950, duration: 1000, sessionId: a.json.sessionId }));
  expect(b.json).toEqual({ sessionId: a.json.sessionId, watched: true });
  const item = await request(app, `/api/items/${id}`);
  expect(item.json.item.progress).toEqual({ position: 950, duration: 1000, watched: true });
});

test("progress without numbers is 400; missing item is 404", async () => {
  const { deps, app } = setup();
  const id = seedMovie(deps.repo, "Heat");
  expect((await request(app, `/api/items/${id}/progress`, post({ position: "1", duration: 1000 }))).status).toBe(400);
  expect((await request(app, `/api/items/${id}/progress`, post({ position: 1 }))).status).toBe(400);
  expect((await request(app, `/api/items/${id}/progress`, post({ position: 1, duration: 9, sessionId: "x" }))).status).toBe(400);
  expect((await request(app, `/api/items/${id}/progress`, { method: "POST", body: "nope" })).status).toBe(400);
  expect((await request(app, `/api/items/999/progress`, post({ position: 1, duration: 9 }))).status).toBe(404);
});

test("watched toggles are reflected in GET /api/items/:id", async () => {
  const { deps, app } = setup();
  const id = seedMovie(deps.repo, "Heat");
  expect((await request(app, `/api/items/${id}/watched`, post({ watched: true }))).status).toBe(200);
  expect((await request(app, `/api/items/${id}`)).json.item.progress).toEqual({ position: 1200, duration: 0, watched: true });
  await request(app, `/api/items/${id}/watched`, post({ watched: false }));
  expect((await request(app, `/api/items/${id}`)).json.item.progress.watched).toBe(false);
  expect((await request(app, `/api/items/${id}/watched`, post({ watched: "yes" }))).status).toBe(400);
  expect((await request(app, `/api/items/999/watched`, post({ watched: true }))).status).toBe(404);
});

test("season watched marks only that season; omitted season marks the whole show", async () => {
  const { deps, app } = setup();
  const s = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [[1, 2], [1]] });
  expect((await request(app, `/api/shows/${s.showId}/watched`, post({ watched: true, season: 1 }))).status).toBe(200);
  const watchedOf = async () =>
    (await request(app, `/api/shows/${s.showId}`)).json.seasons.map((se: any) =>
      se.episodes.map((e: any) => e.progress?.watched ?? false),
    );
  expect(await watchedOf()).toEqual([[true, true], [false]]);
  await request(app, `/api/shows/${s.showId}/watched`, post({ watched: true }));
  expect(await watchedOf()).toEqual([[true, true], [true]]);
  expect((await request(app, `/api/shows/${s.showId}/watched`, post({ watched: true, season: "1" }))).status).toBe(400);
  expect((await request(app, `/api/shows/999/watched`, post({ watched: true }))).status).toBe(404);
});

test("track choices are stored and shown on the item", async () => {
  const { deps, app } = setup();
  const id = seedMovie(deps.repo, "Heat");
  expect((await request(app, `/api/items/${id}/tracks`, post({ subtitle: "sidecar:0", audio: "1" }))).status).toBe(200);
  await request(app, `/api/items/${id}/tracks`, post({ audio: null }));
  const r = await request(app, `/api/items/${id}`);
  expect([r.json.subtitleChoice, r.json.audioChoice]).toEqual(["sidecar:0", null]);
  expect((await request(app, `/api/items/${id}/tracks`, post({ subtitle: 3 }))).status).toBe(400);
});

test("history joins item summaries and stats are returned", async () => {
  const { deps, app } = setup();
  const id = seedMovie(deps.repo, "Heat");
  deps.db.run(
    "INSERT INTO sessions(item_id, started_at, ended_at, last_position, seconds_watched) VALUES (?, ?, ?, 0, 600), (999, ?, ?, 0, 600)",
    [id, NOW - 1000, NOW, NOW - 500, NOW],
  );
  const h = await request(app, "/api/history?limit=10");
  expect(h.status).toBe(200);
  expect(h.json.entries).toHaveLength(1);
  expect(h.json.entries[0]).toMatchObject({ itemId: id, secondsWatched: 600, item: { id, title: "Heat" } });
  const s = await request(app, "/api/stats");
  expect(s.json).toMatchObject({ hoursAll: 1200 / 3600, moviesAll: 0, topShowMonth: null });
});

test("settings: defaults, partial merge, validation", async () => {
  const { app } = setup();
  const defaults = { skipBackSeconds: 10, skipForwardSeconds: 10, autoplayNext: true };
  expect((await request(app, "/api/settings")).json).toEqual(defaults);
  const r = await request(app, "/api/settings", put({ skipForwardSeconds: 30, junk: 1 }));
  expect(r.status).toBe(200);
  expect(r.json).toEqual({ ...defaults, skipForwardSeconds: 30 });
  await request(app, "/api/settings", put({ autoplayNext: false }));
  expect((await request(app, "/api/settings")).json).toEqual({ skipBackSeconds: 10, skipForwardSeconds: 30, autoplayNext: false });
  for (const bad of [{ skipForwardSeconds: 0 }, { skipBackSeconds: 301 }, { skipBackSeconds: 1.5 }, { autoplayNext: "no" }]) {
    expect((await request(app, "/api/settings", put(bad))).status).toBe(400);
  }
  expect((await request(app, "/api/settings")).json.skipForwardSeconds).toBe(30);
});

test("delete show trashes the show folder and the show is gone after rescan", async () => {
  // A trash fake that really removes the paths, so the follow-up scan does not find them again.
  const trashed: string[][] = [];
  const { deps, app } = setup({
    trash: async (paths) => {
      trashed.push(paths);
      for (const p of paths) rmSync(p, { recursive: true, force: true });
    },
  });
  const root = deps.config().mediaRoot;
  const s = seedShow(deps.repo, { title: "Severance", category: "tv", seasons: [[1, 2]] });
  touch(join(root, "tv/Severance/S1E1.mkv"));
  touch(join(root, "tv/Severance/S1E2.mkv"));
  touch(join(root, "tv/Severance/S1E1.en.srt"));

  const r = await request(app, `/api/shows/${s.showId}`, { method: "DELETE" });
  expect(r.status).toBe(200);
  expect(r.json).toEqual({ trashed: [join(root, "tv/Severance")] });
  expect(trashed).toEqual([[join(root, "tv/Severance")]]);
  expect((await request(app, `/api/shows/${s.showId}`)).status).toBe(404);
  await deps.scanner.scan();
  expect(existsSync(join(root, "tv/Severance"))).toBe(false);
  expect((await request(app, `/api/shows/${s.showId}`)).status).toBe(404);
  expect((await request(app, `/api/shows/${s.showId}`, { method: "DELETE" })).status).toBe(404);
});

test("delete show trashes single files when its folder holds another show's files", async () => {
  const { deps, app } = setup();
  const root = deps.config().mediaRoot;
  const s = seedShow(deps.repo, { title: "Mixed", category: "tv", seasons: [[1]] });
  const other = seedShow(deps.repo, { title: "Other", category: "tv", seasons: [[1]] });
  deps.db.run("UPDATE files SET rel_path = 'tv/Mixed/Other.S01E01.mkv' WHERE item_id = ?", [other.itemIds[0]![0]!]);
  touch(join(root, "tv/Mixed/S1E1.mkv"));
  touch(join(root, "tv/Mixed/S1E1.en.srt"));
  touch(join(root, "tv/Mixed/Other.S01E01.mkv"));

  const r = await request(app, `/api/shows/${s.showId}`, { method: "DELETE" });
  expect(r.json.trashed).toEqual([join(root, "tv/Mixed/S1E1.mkv"), join(root, "tv/Mixed/S1E1.en.srt")]);
  await deps.scanner.scan();
});

test("delete movie trashes the file and its sidecars; non-movie is 404; trash failure is 500", async () => {
  const { deps, app } = setup();
  const root = deps.config().mediaRoot;
  const id = seedMovie(deps.repo, "Heat");
  touch(join(root, "movies/Heat.mkv"));
  touch(join(root, "movies/Heat.en.srt"));
  const ep = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [[1]] }).itemIds[0]![0]!;
  expect((await request(app, `/api/movies/${ep}`, { method: "DELETE" })).status).toBe(404);

  const r = await request(app, `/api/movies/${id}`, { method: "DELETE" });
  expect(r.json).toEqual({ trashed: [join(root, "movies/Heat.mkv"), join(root, "movies/Heat.en.srt")] });
  expect(deps.trashed).toEqual([r.json.trashed]);
  expect((await request(app, `/api/items/${id}`)).status).toBe(404);
  await deps.scanner.scan();

  const failing = setup({ trash: async () => { throw new Error("Finder said no"); } });
  const m = seedMovie(failing.deps.repo, "Alien");
  touch(join(failing.deps.config().mediaRoot, "movies/Alien.mkv"));
  const f = await request(failing.app, `/api/movies/${m}`, { method: "DELETE" });
  expect(f.status).toBe(500);
  expect(f.json).toEqual({ error: "Finder said no" });
  expect((await request(failing.app, `/api/items/${m}`)).status).toBe(200);
});

const stored = (deps: ReturnType<typeof makeDeps>, id: number) =>
  deps.db.query("SELECT position, duration, watched FROM playback WHERE item_id = ?").get(id);

test("progress with duration 0 is accepted and leaves stored progress unchanged", async () => {
  const { deps, app } = setup();
  const id = seedMovie(deps.repo, "Heat");
  await request(app, `/api/items/${id}/progress`, post({ position: 100, duration: 1000 }));
  const r = await request(app, `/api/items/${id}/progress`, post({ position: 500, duration: 0 }));
  expect(r.status).toBe(200);
  expect(r.json.watched).toBe(false);
  expect(stored(deps, id)).toEqual({ position: 100, duration: 1000, watched: 0 });
});

test("progress with a negative position is stored as 0", async () => {
  const { deps, app } = setup();
  const id = seedMovie(deps.repo, "Heat");
  const r = await request(app, `/api/items/${id}/progress`, post({ position: -5, duration: 1000 }));
  expect(r.status).toBe(200);
  expect(r.json.watched).toBe(false);
  expect(stored(deps, id)).toEqual({ position: 0, duration: 1000, watched: 0 });
});

test("progress past the duration is clamped and counts as watched", async () => {
  const { deps, app } = setup();
  const id = seedMovie(deps.repo, "Heat");
  const r = await request(app, `/api/items/${id}/progress`, post({ position: 2000, duration: 1000 }));
  expect(r.status).toBe(200);
  expect(r.json.watched).toBe(true);
  expect(stored(deps, id)).toEqual({ position: 1000, duration: 1000, watched: 1 });
});

test("delete with the video already gone from disk trashes nothing and still hides the item", async () => {
  const { deps, app } = setup();
  const root = deps.config().mediaRoot;
  const gone = seedMovie(deps.repo, "Gone");
  const id = seedMovie(deps.repo, "Heat");
  touch(join(root, "movies/Heat.mkv"));

  const r = await request(app, `/api/movies/${gone}`, { method: "DELETE" });
  expect(r.status).toBe(200);
  expect(r.json).toEqual({ trashed: [] });
  expect(deps.trashed).toEqual([]);
  expect((await request(app, `/api/items/${gone}`)).status).toBe(404);
  expect((await request(app, `/api/items/${id}`)).status).toBe(200);
  await deps.scanner.scan();
});
