import type { Database } from "bun:sqlite";

export interface HistoryEntry {
  sessionId: number;
  itemId: number;
  startedAt: number;
  endedAt: number;
  secondsWatched: number;
}

export interface Stats {
  hoursWeek: number;
  hoursMonth: number;
  hoursAll: number;
  episodesAll: number;
  moviesAll: number;
  topShowMonth: { showId: number; title: string; hours: number } | null;
}

const MIN_HISTORY_SECONDS = 60;
const DAY_MS = 24 * 60 * 60 * 1000;

export function listHistory(db: Database, limit = 200): HistoryEntry[] {
  return db
    .query(
      `SELECT id AS sessionId, item_id AS itemId, started_at AS startedAt, ended_at AS endedAt,
              seconds_watched AS secondsWatched
       FROM sessions WHERE seconds_watched >= ? ORDER BY started_at DESC, id DESC LIMIT ?`,
    )
    .all(MIN_HISTORY_SECONDS, limit) as HistoryEntry[];
}

export function stats(db: Database, now: number): Stats {
  const weekStart = now - 7 * DAY_MS;
  const monthStart = now - 30 * DAY_MS;
  const secs = db
    .query(
      `SELECT COALESCE(SUM(CASE WHEN started_at >= ?1 THEN seconds_watched END), 0) AS week,
              COALESCE(SUM(CASE WHEN started_at >= ?2 THEN seconds_watched END), 0) AS month,
              COALESCE(SUM(seconds_watched), 0) AS total
       FROM sessions`,
    )
    .get(weekStart, monthStart) as { week: number; month: number; total: number };
  const counts = db
    .query(
      `SELECT COALESCE(SUM(i.type = 'episode'), 0) AS episodes, COALESCE(SUM(i.type = 'movie'), 0) AS movies
       FROM playback p JOIN items i ON i.id = p.item_id WHERE p.watched = 1`,
    )
    .get() as { episodes: number; movies: number };
  const top = db
    .query(
      `SELECT sh.id AS showId, sh.title AS title, SUM(se.seconds_watched) AS seconds
       FROM sessions se JOIN items i ON i.id = se.item_id JOIN shows sh ON sh.id = i.show_id
       WHERE se.started_at >= ?
       GROUP BY sh.id HAVING seconds > 0 ORDER BY seconds DESC, sh.id LIMIT 1`,
    )
    .get(monthStart) as { showId: number; title: string; seconds: number } | null;
  return {
    hoursWeek: secs.week / 3600,
    hoursMonth: secs.month / 3600,
    hoursAll: secs.total / 3600,
    episodesAll: counts.episodes,
    moviesAll: counts.movies,
    topShowMonth: top ? { showId: top.showId, title: top.title, hours: top.seconds / 3600 } : null,
  };
}
