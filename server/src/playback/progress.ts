import type { Database } from "bun:sqlite";
import type { Category } from "../types";

export interface PlaybackRow {
  item_id: number;
  position: number;
  duration: number | null;
  watched: number;
  last_played_at: number | null;
  subtitle_choice: string | null;
  audio_choice: string | null;
}

export interface ContinueEntry {
  itemId: number;
  reason: "resume" | "next";
  lastPlayedAt: number;
}

const WATCHED_RATIO = 0.9;
const RESUME_MIN_POSITION = 30;
const MAX_SECONDS_PER_REPORT = 30;

/** Episode order key: regular seasons first, season 0 specials after them. */
const SPECIALS_LAST = "CASE WHEN i.season_number = 0 THEN 1 ELSE 0 END";

/** Items that are not hidden and whose show (if any) is not hidden. */
const LIVE_ITEM = "i.deleted_at IS NULL AND (i.show_id IS NULL OR s.deleted_at IS NULL)";

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);

export function getPlayback(db: Database, itemId: number): PlaybackRow | null {
  return (db.query("SELECT * FROM playback WHERE item_id = ?").get(itemId) as PlaybackRow | null) ?? null;
}

function ensurePlayback(db: Database, itemId: number): void {
  db.run("INSERT OR IGNORE INTO playback(item_id, position, duration, watched) VALUES (?, 0, NULL, 0)", [itemId]);
}

export function reportProgress(
  db: Database,
  args: { itemId: number; position: number; duration: number; sessionId?: number | null; now: number },
): { sessionId: number; watched: boolean } {
  const { itemId, now } = args;
  const duration = Math.max(args.duration, 0);
  const position = clamp(args.position, 0, duration);
  return db.transaction(() => {
    ensurePlayback(db, itemId);
    const prev = getPlayback(db, itemId)!;
    const watched = prev.watched === 1 || (duration > 0 && position / duration >= WATCHED_RATIO);
    db.run(
      "UPDATE playback SET position = ?, duration = ?, watched = ?, last_played_at = ? WHERE item_id = ?",
      [position, duration, watched ? 1 : 0, now, itemId],
    );

    const session =
      args.sessionId == null
        ? null
        : (db.query("SELECT id, last_position FROM sessions WHERE id = ?").get(args.sessionId) as
            | { id: number; last_position: number }
            | null);
    let sessionId: number;
    if (session) {
      sessionId = session.id;
      const delta = clamp(position - session.last_position, 0, MAX_SECONDS_PER_REPORT);
      db.run(
        "UPDATE sessions SET seconds_watched = seconds_watched + ?, last_position = ?, ended_at = ? WHERE id = ?",
        [delta, position, now, sessionId],
      );
    } else {
      const r = db.run(
        "INSERT INTO sessions(item_id, started_at, ended_at, last_position, seconds_watched) VALUES (?, ?, ?, ?, 0)",
        [itemId, now, now, position],
      );
      sessionId = Number(r.lastInsertRowid);
    }
    return { sessionId, watched };
  })();
}

export function setWatched(db: Database, itemId: number, watched: boolean, now: number): void {
  ensurePlayback(db, itemId);
  if (watched) {
    // Duration from playback, else from the item's probed files, else unknown (0).
    db.run(
      `UPDATE playback SET watched = 1, last_played_at = ?2,
         position = COALESCE(duration,
           (SELECT MAX(f.duration) FROM files f WHERE f.item_id = ?1 AND f.deleted_at IS NULL), 0)
       WHERE item_id = ?1`,
      [itemId, now],
    );
  } else {
    db.run("UPDATE playback SET watched = 0, position = 0 WHERE item_id = ?", [itemId]);
  }
}

function setManyWatched(db: Database, sql: string, params: number[], watched: boolean, now: number): void {
  const ids = (db.query(sql).all(...params) as { id: number }[]).map((r) => r.id);
  db.transaction(() => {
    for (const id of ids) setWatched(db, id, watched, now);
  })();
}

export function setSeasonWatched(db: Database, showId: number, season: number, watched: boolean, now: number): void {
  setManyWatched(
    db,
    "SELECT id FROM items WHERE show_id = ? AND season_number = ? AND deleted_at IS NULL",
    [showId, season],
    watched,
    now,
  );
}

export function setShowWatched(db: Database, showId: number, watched: boolean, now: number): void {
  setManyWatched(db, "SELECT id FROM items WHERE show_id = ? AND deleted_at IS NULL", [showId], watched, now);
}

/** Updates only the choices that are passed (`undefined` leaves a choice unchanged, `null` clears it). */
export function setTrackChoice(
  db: Database,
  itemId: number,
  choice: { subtitle?: string | null; audio?: string | null },
): void {
  db.transaction(() => {
    ensurePlayback(db, itemId);
    if (choice.subtitle !== undefined) {
      db.run("UPDATE playback SET subtitle_choice = ? WHERE item_id = ?", [choice.subtitle, itemId]);
    }
    if (choice.audio !== undefined) {
      db.run("UPDATE playback SET audio_choice = ? WHERE item_id = ?", [choice.audio, itemId]);
    }
  })();
}

export function continueWatching(db: Database, category: Category | null, limit = 30): ContinueEntry[] {
  const resumes = db
    .query(
      `SELECT p.item_id AS itemId, p.last_played_at AS lastPlayedAt, i.show_id AS showId
       FROM playback p JOIN items i ON i.id = p.item_id LEFT JOIN shows s ON s.id = i.show_id
       WHERE ${LIVE_ITEM} AND p.watched = 0 AND p.position >= ?2 AND p.last_played_at IS NOT NULL
         AND (?1 IS NULL OR i.category = ?1)
       ORDER BY p.last_played_at DESC`,
    )
    .all(category, RESUME_MIN_POSITION) as { itemId: number; lastPlayedAt: number; showId: number | null }[];

  const entries: ContinueEntry[] = [];
  const showsWithEntry = new Set<number>();
  for (const r of resumes) {
    if (r.showId !== null) {
      if (showsWithEntry.has(r.showId)) continue; // one entry per show: the most recent resume
      showsWithEntry.add(r.showId);
    }
    entries.push({ itemId: r.itemId, reason: "resume", lastPlayedAt: r.lastPlayedAt });
  }

  // Each live show's most recently played live episode (ties: latest in episode order).
  const latest = db
    .query(
      `SELECT showId, watched, lastPlayedAt, sp, season, episode FROM (
         SELECT i.show_id AS showId, p.watched, p.last_played_at AS lastPlayedAt, ${SPECIALS_LAST} AS sp,
                i.season_number AS season, i.episode_number AS episode,
                ROW_NUMBER() OVER (PARTITION BY i.show_id
                  ORDER BY p.last_played_at DESC, ${SPECIALS_LAST} DESC, i.season_number DESC, i.episode_number DESC) AS rn
         FROM playback p JOIN items i ON i.id = p.item_id JOIN shows s ON s.id = i.show_id
         WHERE ${LIVE_ITEM} AND i.type = 'episode' AND p.last_played_at IS NOT NULL
           AND (?1 IS NULL OR i.category = ?1)
       ) WHERE rn = 1 AND watched = 1`,
    )
    .all(category) as { showId: number; lastPlayedAt: number; sp: number; season: number; episode: number }[];

  const nextQuery = db.query(
    `SELECT i.id FROM items i LEFT JOIN playback p ON p.item_id = i.id
     WHERE i.show_id = ?1 AND i.type = 'episode' AND i.deleted_at IS NULL AND COALESCE(p.watched, 0) = 0
       AND (${SPECIALS_LAST}, i.season_number, i.episode_number) > (?2, ?3, ?4)
     ORDER BY ${SPECIALS_LAST}, i.season_number, i.episode_number
     LIMIT 1`,
  );
  for (const l of latest) {
    if (showsWithEntry.has(l.showId)) continue; // resume beats next
    const next = nextQuery.get(l.showId, l.sp, l.season, l.episode) as { id: number } | null;
    if (next) entries.push({ itemId: next.id, reason: "next", lastPlayedAt: l.lastPlayedAt });
  }

  entries.sort((a, b) => b.lastPlayedAt - a.lastPlayedAt);
  return entries.slice(0, limit);
}
