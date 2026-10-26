import { expect, test } from "bun:test";
import { openDb } from "../src/db/db";
import { Repo } from "../src/library/repo";
import { listHistory, stats } from "../src/playback/history";
import { seedMovie, seedShow } from "./helpers";

const NOW = 1_700_000_000_000;
const DAY = 24 * 60 * 60 * 1000;

function setup() {
  const db = openDb(":memory:");
  const addSession = (itemId: number, startedAt: number, seconds: number) => {
    const r = db.run(
      "INSERT INTO sessions(item_id, started_at, ended_at, last_position, seconds_watched) VALUES (?, ?, ?, 0, ?)",
      [itemId, startedAt, startedAt + seconds * 1000, seconds],
    );
    return Number(r.lastInsertRowid);
  };
  return { db, repo: new Repo(db), addSession };
}

test("listHistory excludes sessions under 60 s and orders by started_at desc", () => {
  const { db, repo, addSession } = setup();
  const movie = seedMovie(repo, "Heat");
  const older = addSession(movie, NOW - 2 * DAY, 600);
  addSession(movie, NOW - DAY, 59);
  const newer = addSession(movie, NOW - 1000, 60);
  expect(listHistory(db)).toEqual([
    { sessionId: newer, itemId: movie, startedAt: NOW - 1000, endedAt: NOW - 1000 + 60_000, secondsWatched: 60 },
    { sessionId: older, itemId: movie, startedAt: NOW - 2 * DAY, endedAt: NOW - 2 * DAY + 600_000, secondsWatched: 600 },
  ]);
  expect(listHistory(db, 1)).toHaveLength(1);
});

test("stats sums hours across week, month and all-time boundaries", () => {
  const { db, repo, addSession } = setup();
  const movie = seedMovie(repo, "Heat");
  const { itemIds } = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2]] });
  addSession(movie, NOW - DAY, 3600); // week
  addSession(movie, NOW - 7 * DAY + 1000, 1800); // still week
  addSession(itemIds[0]![0]!, NOW - 8 * DAY, 7200); // month only
  addSession(itemIds[0]![0]!, NOW - 30 * DAY + 1000, 1800); // still month
  addSession(itemIds[0]![1]!, NOW - 31 * DAY, 3600); // all only
  db.run("INSERT INTO playback(item_id, position, duration, watched, last_played_at) VALUES (?, 1, 1, 1, ?)", [movie, NOW]);
  db.run("INSERT INTO playback(item_id, position, duration, watched, last_played_at) VALUES (?, 1, 1, 1, ?)", [itemIds[0]![0]!, NOW]);
  db.run("INSERT INTO playback(item_id, position, duration, watched, last_played_at) VALUES (?, 0, 1, 0, ?)", [itemIds[0]![1]!, NOW]);
  const s = stats(db, NOW);
  expect(s.hoursWeek).toBe(1.5);
  expect(s.hoursMonth).toBe(4);
  expect(s.hoursAll).toBe(5);
  expect(s.episodesAll).toBe(1);
  expect(s.moviesAll).toBe(1);
});

test("topShowMonth picks the show with the most seconds in the last 30 days", () => {
  const { db, repo, addSession } = setup();
  const movie = seedMovie(repo, "Heat");
  const a = seedShow(repo, { title: "Severance", category: "tv", seasons: [[1, 2]] });
  const b = seedShow(repo, { title: "Andor", category: "tv", seasons: [[1]] });
  addSession(movie, NOW - DAY, 36_000); // movies never count
  addSession(a.itemIds[0]![0]!, NOW - DAY, 1800);
  addSession(a.itemIds[0]![1]!, NOW - 2 * DAY, 1800);
  addSession(b.itemIds[0]![0]!, NOW - 3 * DAY, 3000);
  addSession(b.itemIds[0]![0]!, NOW - 40 * DAY, 99_000); // outside the month
  expect(stats(db, NOW).topShowMonth).toEqual({ showId: a.showId, title: "Severance", hours: 1 });
});

test("stats with no sessions has zero hours and null topShowMonth", () => {
  const { db } = setup();
  expect(stats(db, NOW)).toEqual({
    hoursWeek: 0, hoursMonth: 0, hoursAll: 0, episodesAll: 0, moviesAll: 0, topShowMonth: null,
  });
});
