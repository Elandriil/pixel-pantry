import { expect, test } from "bun:test";
import { buildApp } from "../../src/http/app";
import { reportProgress, setTrackChoice, setWatched } from "../../src/playback/progress";
import { makeDeps, NOW, request, seedMovie, seedShow } from "./helpers";

const MIN = 60 * 1000;

function setup() {
  const deps = makeDeps();
  return { deps, app: buildApp(deps) };
}

function play(deps: ReturnType<typeof makeDeps>, itemId: number, position: number, at: number) {
  reportProgress(deps.db, { itemId, position, duration: 1000, now: at });
}

function setAddedAt(deps: ReturnType<typeof makeDeps>, itemId: number, at: number) {
  deps.db.run("UPDATE files SET added_at = ? WHERE item_id = ?", [at, itemId]);
}

test("home continue row is ordered by last played with a reason", async () => {
  const { deps, app } = setup();
  const movie = seedMovie(deps.repo, "Heat");
  const show = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [[1, 2]] });
  play(deps, movie, 300, NOW + 1 * MIN); // resume
  play(deps, show.itemIds[0]![0]!, 950, NOW + 2 * MIN); // watched -> next is S1E2

  const r = await request(app, "/api/home");
  expect(r.status).toBe(200);
  expect(r.json.category).toBe("home");
  const cont = r.json.rows.find((row: any) => row.key === "continue");
  expect(cont.cards.map((c: any) => [c.kind, c.item.id, c.reason])).toEqual([
    ["item", show.itemIds[0]![1], "next"],
    ["item", movie, "resume"],
  ]);
  expect(cont.cards[1].item.progress).toEqual({ position: 300, duration: 1000, watched: false });
  expect(cont.cards[0].item.progress).toBeNull();
});

test("home recent row mixes shows and movies by newest file first", async () => {
  const { deps, app } = setup();
  const old = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [[1, 2]] });
  const movie = seedMovie(deps.repo, "Heat");
  const anime = seedShow(deps.repo, { title: "Naruto", category: "anime", seasons: [[1]] });
  setAddedAt(deps, movie, NOW + 2);
  setAddedAt(deps, anime.itemIds[0]![0]!, NOW + 1);
  setAddedAt(deps, old.itemIds[0]![1]!, NOW + 3); // new episode bubbles the show up

  const r = await request(app, "/api/home");
  const recent = r.json.rows.find((row: any) => row.key === "recent");
  expect(recent.cards.map((c: any) => (c.kind === "show" ? `show:${c.show.title}` : `item:${c.item.title}`))).toEqual([
    "show:Lost", "item:Heat", "show:Naruto",
  ]);
  expect(recent.cards[0].show).toMatchObject({ episodeCount: 2, unwatchedCount: 2, addedAt: NOW + 3, matched: false });
});

test("category all row is sorted A-Z case-insensitively and excludes hidden shows", async () => {
  const { deps, app } = setup();
  seedShow(deps.repo, { title: "zeta", category: "tv", seasons: [[1]] });
  seedShow(deps.repo, { title: "Alpha", category: "tv", seasons: [[1]] });
  seedShow(deps.repo, { title: "beta", category: "tv", seasons: [[1]] });
  const hidden = seedShow(deps.repo, { title: "Gone", category: "tv", seasons: [[1]] });
  seedShow(deps.repo, { title: "Bleach", category: "anime", seasons: [[1]] });
  deps.repo.setShowDeleted(hidden.showId, NOW);

  const r = await request(app, "/api/categories/tv");
  expect(r.status).toBe(200);
  expect(r.json.category).toBe("tv");
  expect(r.json.rows.map((row: any) => row.key)).toEqual(["continue", "recent", "all"]);
  const all = r.json.rows.find((row: any) => row.key === "all");
  expect(all.cards.map((c: any) => c.show.title)).toEqual(["Alpha", "beta", "zeta"]);
});

test("movies category lists movie items A-Z", async () => {
  const { deps, app } = setup();
  seedMovie(deps.repo, "Zodiac");
  seedMovie(deps.repo, "alien");
  const r = await request(app, "/api/categories/movies");
  const all = r.json.rows.find((row: any) => row.key === "all");
  expect(all.cards.map((c: any) => [c.kind, c.item.title])).toEqual([["item", "alien"], ["item", "Zodiac"]]);
});

test("unknown category is 404", async () => {
  const { app } = setup();
  const r = await request(app, "/api/categories/music");
  expect(r.status).toBe(404);
  expect(r.json).toEqual({ error: expect.any(String) });
});

test("show detail orders seasons with Specials last and picks resume", async () => {
  const { deps, app } = setup();
  const show = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [[2, 1], [1]] });
  const special = deps.repo.insertItem(
    { type: "episode", category: "tv", show_id: show.showId, season_number: 0, episode_number: 1, parsed_title: "Lost", title: "Lost S0E1" },
    NOW,
  );
  deps.repo.setFileItem(
    deps.repo.upsertFile({
      rel_path: "tv/Lost/S0E1.mkv", category: "tv", size: 1, mtime: NOW, parsed: null, item_id: null, duration: 1,
      audio_tracks: null, subtitle_tracks: null, probe_error: null, added_at: NOW, deleted_at: null,
    }),
    special,
  );
  deps.repo.upsertSeason(show.showId, 1, { name: "Season One", poster_path: "/s1.jpg" });
  deps.repo.upsertSeason(show.showId, 0, { name: "Extras" });

  const r = await request(app, `/api/shows/${show.showId}`);
  expect(r.status).toBe(200);
  expect(r.json.seasons.map((s: any) => [s.number, s.name])).toEqual([[1, "Season One"], [2, "Season 2"], [0, "Specials"]]);
  expect(r.json.seasons[0].poster).toBe("/api/images/w500/s1.jpg");
  expect(r.json.seasons[0].episodes.map((e: any) => e.episode)).toEqual([1, 2]);
  const s1e1 = show.itemIds[0]![1]!;
  const s1e2 = show.itemIds[0]![0]!;
  expect(r.json.resume.id).toBe(s1e1); // first unwatched

  setWatched(deps.db, s1e1, true, NOW);
  expect((await request(app, `/api/shows/${show.showId}`)).json.resume.id).toBe(s1e2);

  const s2e1 = show.itemIds[1]![0]!;
  play(deps, s2e1, 300, NOW + MIN);
  const after = await request(app, `/api/shows/${show.showId}`);
  expect(after.json.resume.id).toBe(s2e1); // continue-watching entry wins
  expect(after.json.show).toMatchObject({ id: show.showId, title: "Lost", episodeCount: 4, unwatchedCount: 3 });
});

test("show detail counts the show's live files", async () => {
  const { deps, app } = setup();
  const show = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [[1, 2, 3]] });
  const [e1, e2] = show.itemIds[0]!;
  const extra = deps.repo.upsertFile({
    rel_path: "tv/Lost/S1E1.1080p.mkv", category: "tv", size: 1, mtime: NOW, parsed: null, item_id: null, duration: 1,
    audio_tracks: null, subtitle_tracks: null, probe_error: null, added_at: NOW, deleted_at: null,
  });
  deps.repo.setFileItem(extra, e1!);
  deps.repo.setFileDeleted(deps.repo.filesForItem(e2!)[0]!.id, NOW);
  deps.repo.hideEmpty(NOW);
  expect((await request(app, `/api/shows/${show.showId}`)).json.fileCount).toBe(3);
});

test("GET /api/info identifies the server, uncached", async () => {
  const { app } = setup();
  const r = await request(app, "/api/info");
  expect(r.status).toBe(200);
  expect(r.json).toEqual({ name: "Pixel Pantry", version: "1" });
  expect(r.headers.get("Cache-Control")).toBe("no-store");
});

test("show detail is 404 for missing or hidden shows", async () => {
  const { deps, app } = setup();
  const show = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [[1]] });
  deps.repo.setShowDeleted(show.showId, NOW);
  expect((await request(app, `/api/shows/${show.showId}`)).status).toBe(404);
  expect((await request(app, "/api/shows/9999")).status).toBe(404);
  expect((await request(app, "/api/shows/abc")).status).toBe(400);
});

test("item detail next/prev cross the season boundary and are null at the end", async () => {
  const { deps, app } = setup();
  const twelve = Array.from({ length: 12 }, (_, i) => i + 1);
  const show = seedShow(deps.repo, { title: "Lost", category: "tv", seasons: [twelve, [1]] });
  const s1e11 = show.itemIds[0]![10]!;
  const s1e12 = show.itemIds[0]![11]!;
  const s2e1 = show.itemIds[1]![0]!;

  const r = await request(app, `/api/items/${s1e12}`);
  expect(r.status).toBe(200);
  expect(r.json.streamUrl).toBe(`/api/items/${s1e12}/stream`);
  expect(r.json.next.id).toBe(s2e1);
  expect(r.json.prev.id).toBe(s1e11);
  expect(r.json.item).toMatchObject({ showTitle: "Lost", season: 1, episode: 12, type: "episode" });

  const end = await request(app, `/api/items/${s2e1}`);
  expect(end.json.next).toBeNull();
  expect(end.json.prev.id).toBe(s1e12);
  expect((await request(app, `/api/items/${show.itemIds[0]![0]}`)).json.prev).toBeNull();
});

test("item detail reports the newest file, tracks and choices; movies have no next/prev", async () => {
  const { deps, app } = setup();
  const movie = seedMovie(deps.repo, "Heat");
  const newer = deps.repo.upsertFile({
    rel_path: "movies/Heat.1080p.mkv", category: "movies", size: 4242, mtime: NOW, parsed: null, item_id: null,
    duration: 1, audio_tracks: null, subtitle_tracks: null, probe_error: null, added_at: NOW + 5, deleted_at: null,
  });
  deps.repo.setFileItem(newer, movie);
  deps.repo.setFileProbe(newer, {
    duration: 1,
    audio: [{ index: 1, codec: "aac", language: "eng", title: null, channels: 2, default: true, forced: false }],
    subtitles: [{ index: 3, codec: "subrip", language: "est", title: "Estonian", default: false, forced: false }],
  }, null);
  setTrackChoice(deps.db, movie, { subtitle: "embedded:3", audio: "1" });

  const r = await request(app, `/api/items/${movie}`);
  expect(r.status).toBe(200);
  expect(r.json).toMatchObject({
    fileName: "Heat.1080p.mkv", fileSize: 4242, next: null, prev: null, subtitleChoice: "embedded:3", audioChoice: "1",
  });
  expect(r.json.audio).toEqual([{ index: 1, codec: "aac", language: "eng", title: null, channels: 2, default: true, forced: false }]);
  expect(r.json.subtitles).toEqual([{ id: "embedded:3", label: "Estonian", lang: "est", url: null, format: "embedded" }]);
  expect(r.json.item).toMatchObject({ type: "movie", showId: null, addedAt: NOW + 5 });
});

test("item detail is 404 for missing or hidden items", async () => {
  const { deps, app } = setup();
  const movie = seedMovie(deps.repo, "Heat");
  deps.repo.setItemDeleted(movie, NOW);
  expect((await request(app, `/api/items/${movie}`)).status).toBe(404);
  expect((await request(app, "/api/items/9999")).status).toBe(404);
});

test("search is case-insensitive and trims; empty q returns empty lists", async () => {
  const { deps, app } = setup();
  seedShow(deps.repo, { title: "Breaking Bad", category: "tv", seasons: [[1]] });
  seedMovie(deps.repo, "Bad Boys");
  seedMovie(deps.repo, "Heat");

  const r = await request(app, "/api/search?q=%20bAD%20");
  expect(r.status).toBe(200);
  expect(r.json.shows.map((s: any) => s.title)).toEqual(["Breaking Bad"]);
  expect(r.json.movies.map((m: any) => m.title)).toEqual(["Bad Boys"]);

  expect((await request(app, "/api/search?q=%20%20")).json).toEqual({ shows: [], movies: [] });
  expect((await request(app, "/api/search")).json).toEqual({ shows: [], movies: [] });
});

test("api responses carry Cache-Control: no-store, including errors", async () => {
  const { app } = setup();
  expect((await request(app, "/api/home")).headers.get("cache-control")).toBe("no-store");
  const missing = await request(app, "/api/nope");
  expect(missing.status).toBe(404);
  expect(missing.json).toEqual({ error: "Not found" });
  expect(missing.headers.get("cache-control")).toBe("no-store");
  expect((await request(app, "/api/items/9999")).headers.get("cache-control")).toBe("no-store");
});

test("unexpected errors become a logged 500 JSON response", async () => {
  const { deps, app } = setup();
  deps.db.close();
  const r = await request(app, "/api/home");
  expect(r.status).toBe(500);
  expect(r.json).toEqual({ error: expect.any(String) });
  expect(deps.log.recent().some((l) => l.includes("ERROR"))).toBe(true);
});
