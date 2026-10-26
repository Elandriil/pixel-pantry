import { expect, test } from "bun:test";
import { openDb } from "../src/db/db";
import { Repo } from "../src/library/repo";
import {
  continueWatching, getPlayback, reportProgress, setSeasonWatched, setShowWatched, setTrackChoice, setWatched,
} from "../src/playback/progress";
import { seedMovie, seedShow } from "./helpers";

const T0 = 1_700_000_000_000;
const MIN = 60 * 1000;

function setup() {
  const db = openDb(":memory:");
  return { db, repo: new Repo(db) };
}

function sessionSeconds(db: ReturnType<typeof openDb>, id: number): number {
  return (db.query("SELECT seconds_watched AS s FROM sessions WHERE id = ?").get(id) as { s: number }).s;
}

test("reaching 90 % of duration sets watched", () => {
  const { db, repo } = setup();
  const id = seedMovie(repo, "Heat");
  const a = reportProgress(db, { itemId: id, position: 899, duration: 1000, now: T0 });
  expect(a.watched).toBe(false);
  const b = reportProgress(db, { itemId: id, position: 900, duration: 1000, sessionId: a.sessionId, now: T0 + 1 });
  expect(b.watched).toBe(true);
  expect(b.sessionId).toBe(a.sessionId);
  // stays watched after seeking back
  const c = reportProgress(db, { itemId: id, position: 10, duration: 1000, sessionId: a.sessionId, now: T0 + 2 });
  expect(c.watched).toBe(true);
  expect(getPlayback(db, id)!.watched).toBe(1);
});

test("duration 0 never sets watched and does not throw", () => {
  const { db, repo } = setup();
  const id = seedMovie(repo, "Heat");
  const r = reportProgress(db, { itemId: id, position: 50, duration: 0, now: T0 });
  expect(r.watched).toBe(false);
  expect(getPlayback(db, id)).toMatchObject({ position: 0, duration: null, watched: 0, last_played_at: T0 });
});

test("a duration-0 report after real progress keeps position and duration and the resume entry", () => {
  const { db, repo } = setup();
  const id = seedMovie(repo, "Heat");
  const { sessionId } = reportProgress(db, { itemId: id, position: 3000, duration: 7000, now: T0 });
  const r = reportProgress(db, { itemId: id, position: 0, duration: 0, sessionId, now: T0 + 10_000 });
  expect(r).toEqual({ sessionId, watched: false });
  expect(getPlayback(db, id)).toMatchObject({ position: 3000, duration: 7000, last_played_at: T0 + 10_000 });
  expect(sessionSeconds(db, sessionId)).toBe(0);
  expect(continueWatching(db, null)).toEqual([{ itemId: id, reason: "resume", lastPlayedAt: T0 + 10_000 }]);
});

test("a NaN position writes no NULL or NaN anywhere", () => {
  const { db, repo } = setup();
  const id = seedMovie(repo, "Heat");
  const { sessionId } = reportProgress(db, { itemId: id, position: 600, duration: 7000, now: T0 });
  reportProgress(db, { itemId: id, position: NaN, duration: 7000, sessionId, now: T0 + 10_000 });
  const fresh = reportProgress(db, { itemId: id, position: NaN, duration: NaN, now: T0 + 20_000 });
  expect(getPlayback(db, id)).toMatchObject({ position: 600, duration: 7000, watched: 0 });
  for (const sid of [sessionId, fresh.sessionId]) {
    const s = db.query("SELECT last_position, seconds_watched FROM sessions WHERE id = ?").get(sid) as {
      last_position: number | null;
      seconds_watched: number | null;
    };
    expect(s).toEqual({ last_position: 600, seconds_watched: 0 });
  }
});

test("a session id that belongs to another item starts a new session", () => {
  const { db, repo } = setup();
  const { itemIds } = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2]] });
  const [e1, e2] = [itemIds[0]![0]!, itemIds[0]![1]!];
  const first = reportProgress(db, { itemId: e1, position: 1190, duration: 1200, now: T0 });
  reportProgress(db, { itemId: e1, position: 1200, duration: 1200, sessionId: first.sessionId, now: T0 + 10_000 });
  // autoplay moved to E2 but the client still sends E1's session id
  const second = reportProgress(db, { itemId: e2, position: 5, duration: 1200, sessionId: first.sessionId, now: T0 + 20_000 });
  expect(second.sessionId).not.toBe(first.sessionId);
  expect(sessionSeconds(db, first.sessionId)).toBe(10);
  const s = db.query("SELECT item_id, last_position FROM sessions WHERE id = ?").get(second.sessionId);
  expect(s).toEqual({ item_id: e2, last_position: 5 });
});

test("position beyond duration clamps to duration and sets watched", () => {
  const { db, repo } = setup();
  const id = seedMovie(repo, "Heat");
  const r = reportProgress(db, { itemId: id, position: 5000, duration: 1000, now: T0 });
  expect(r.watched).toBe(true);
  expect(getPlayback(db, id)!.position).toBe(1000);
});

test("negative position clamps to 0", () => {
  const { db, repo } = setup();
  const id = seedMovie(repo, "Heat");
  reportProgress(db, { itemId: id, position: -20, duration: 1000, now: T0 });
  const p = getPlayback(db, id)!;
  expect(p.position).toBe(0);
  expect(p.last_played_at).toBe(T0);
});

test("same session accumulates seconds_watched with a 30 s cap per report", () => {
  const { db, repo } = setup();
  const id = seedMovie(repo, "Heat");
  const { sessionId } = reportProgress(db, { itemId: id, position: 100, duration: 1000, now: T0 });
  expect(sessionSeconds(db, sessionId)).toBe(0);
  reportProgress(db, { itemId: id, position: 110, duration: 1000, sessionId, now: T0 + 10_000 });
  expect(sessionSeconds(db, sessionId)).toBe(10);
  reportProgress(db, { itemId: id, position: 400, duration: 1000, sessionId, now: T0 + 20_000 }); // seek forward
  expect(sessionSeconds(db, sessionId)).toBe(40);
  reportProgress(db, { itemId: id, position: 200, duration: 1000, sessionId, now: T0 + 30_000 }); // seek back
  expect(sessionSeconds(db, sessionId)).toBe(40);
  reportProgress(db, { itemId: id, position: 215, duration: 1000, sessionId, now: T0 + 45_000 });
  expect(sessionSeconds(db, sessionId)).toBe(55);
  const s = db.query("SELECT * FROM sessions WHERE id = ?").get(sessionId) as { ended_at: number; started_at: number };
  expect(s.started_at).toBe(T0);
  expect(s.ended_at).toBe(T0 + 45_000);
  // unknown session id → a new session
  const other = reportProgress(db, { itemId: id, position: 220, duration: 1000, sessionId: 9999, now: T0 + 50_000 });
  expect(other.sessionId).not.toBe(sessionId);
  expect(other.sessionId).not.toBe(9999);
});

test("continueWatching returns resume for a half-watched episode", () => {
  const { db, repo } = setup();
  const { itemIds } = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2, 3]] });
  reportProgress(db, { itemId: itemIds[0]![1]!, position: 600, duration: 1200, now: T0 });
  expect(continueWatching(db, null)).toEqual([{ itemId: itemIds[0]![1]!, reason: "resume", lastPlayedAt: T0 }]);
});

test("continueWatching returns next for S1E2 after S1E1 is watched", () => {
  const { db, repo } = setup();
  const { itemIds } = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2, 3]] });
  reportProgress(db, { itemId: itemIds[0]![0]!, position: 1200, duration: 1200, now: T0 });
  expect(continueWatching(db, null)).toEqual([{ itemId: itemIds[0]![1]!, reason: "next", lastPlayedAt: T0 }]);
  expect(continueWatching(db, "tv")).toHaveLength(1);
  expect(continueWatching(db, "anime")).toEqual([]);
});

test("a short start of the next episode keeps the show in Continue Watching as next", () => {
  const { db, repo } = setup();
  const { itemIds } = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2, 3]] });
  reportProgress(db, { itemId: itemIds[0]![0]!, position: 1200, duration: 1200, now: T0 });
  reportProgress(db, { itemId: itemIds[0]![1]!, position: 5, duration: 1200, now: T0 + MIN });
  expect(continueWatching(db, null)).toEqual([{ itemId: itemIds[0]![1]!, reason: "next", lastPlayedAt: T0 + MIN }]);
});

test("continueWatching returns nothing after the last episode is watched", () => {
  const { db, repo } = setup();
  const { itemIds } = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2], [1]] });
  setWatched(db, itemIds[0]![0]!, true, T0);
  setWatched(db, itemIds[0]![1]!, true, T0 + MIN);
  expect(continueWatching(db, null)).toEqual([{ itemId: itemIds[1]![0]!, reason: "next", lastPlayedAt: T0 + MIN }]);
  setWatched(db, itemIds[1]![0]!, true, T0 + 2 * MIN);
  expect(continueWatching(db, null)).toEqual([]);
});

test("next for a show never surfaces when a resume exists for it", () => {
  const { db, repo } = setup();
  const { itemIds } = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2, 3, 4]] });
  reportProgress(db, { itemId: itemIds[0]![1]!, position: 300, duration: 1200, now: T0 }); // E2 in progress
  setWatched(db, itemIds[0]![2]!, true, T0 + MIN); // E3 watched later → would suggest E4
  expect(continueWatching(db, null)).toEqual([{ itemId: itemIds[0]![1]!, reason: "resume", lastPlayedAt: T0 }]);
});

test("movies get resume only, never next", () => {
  const { db, repo } = setup();
  const done = seedMovie(repo, "Heat");
  const half = seedMovie(repo, "Ronin");
  reportProgress(db, { itemId: done, position: 7000, duration: 7000, now: T0 });
  reportProgress(db, { itemId: half, position: 3000, duration: 7000, now: T0 + MIN });
  expect(continueWatching(db, null)).toEqual([{ itemId: half, reason: "resume", lastPlayedAt: T0 + MIN }]);
  expect(continueWatching(db, "movies")).toHaveLength(1);
});

test("hidden (deleted) items are excluded", () => {
  const { db, repo } = setup();
  const movie = seedMovie(repo, "Heat");
  const a = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2]] });
  const b = seedShow(repo, { title: "Andor", category: "tv", seasons: [[1, 2]] });
  reportProgress(db, { itemId: movie, position: 600, duration: 7000, now: T0 });
  reportProgress(db, { itemId: a.itemIds[0]![0]!, position: 1200, duration: 1200, now: T0 }); // next = E2
  reportProgress(db, { itemId: b.itemIds[0]![0]!, position: 600, duration: 1200, now: T0 }); // resume
  repo.setItemDeleted(movie, T0);
  repo.setItemDeleted(a.itemIds[0]![1]!, T0); // the next episode is hidden
  repo.setShowDeleted(b.showId, T0); // the whole show is hidden
  expect(continueWatching(db, null)).toEqual([]);
});

test("continueWatching is ordered by last played desc", () => {
  const { db, repo } = setup();
  const movie = seedMovie(repo, "Heat");
  const a = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2]] });
  const b = seedShow(repo, { title: "Frieren", category: "anime", seasons: [[1, 2]] });
  reportProgress(db, { itemId: a.itemIds[0]![0]!, position: 1200, duration: 1200, now: T0 + MIN });
  reportProgress(db, { itemId: movie, position: 600, duration: 7000, now: T0 });
  reportProgress(db, { itemId: b.itemIds[0]![0]!, position: 100, duration: 1200, now: T0 + 2 * MIN });
  expect(continueWatching(db, null)).toEqual([
    { itemId: b.itemIds[0]![0]!, reason: "resume", lastPlayedAt: T0 + 2 * MIN },
    { itemId: a.itemIds[0]![1]!, reason: "next", lastPlayedAt: T0 + MIN },
    { itemId: movie, reason: "resume", lastPlayedAt: T0 },
  ]);
  expect(continueWatching(db, null, 2)).toHaveLength(2);
});

test("setWatched / setSeasonWatched / setShowWatched set position and watched; setTrackChoice keeps unspecified choices", () => {
  const { db, repo } = setup();
  const { showId, itemIds } = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2], [1]] });
  reportProgress(db, { itemId: itemIds[0]![0]!, position: 100, duration: 1000, now: T0 });
  setWatched(db, itemIds[0]![0]!, true, T0 + 1);
  expect(getPlayback(db, itemIds[0]![0]!)).toMatchObject({ watched: 1, position: 1000, last_played_at: T0 + 1 });
  setSeasonWatched(db, showId, 1, true, T0 + 2);
  expect(getPlayback(db, itemIds[0]![1]!)).toMatchObject({ watched: 1, position: 1200 }); // file duration
  expect(getPlayback(db, itemIds[1]![0]!)).toBeNull();
  setShowWatched(db, showId, false, T0 + 3);
  for (const id of itemIds.flat()) expect(getPlayback(db, id)).toMatchObject({ watched: 0, position: 0 });

  setTrackChoice(db, itemIds[0]![0]!, { subtitle: "eng", audio: "jpn" });
  setTrackChoice(db, itemIds[0]![0]!, { subtitle: null });
  expect(getPlayback(db, itemIds[0]![0]!)).toMatchObject({ subtitle_choice: null, audio_choice: "jpn" });
});
