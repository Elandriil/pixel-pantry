import type { Database, Statement } from "bun:sqlite";

export interface FileRow {
  id: number;
  rel_path: string;
  category: string | null;
  size: number | null;
  mtime: number | null;
  parsed: string | null;
  item_id: number | null;
  duration: number | null;
  audio_tracks: string | null;
  subtitle_tracks: string | null;
  probe_error: string | null;
  added_at: number | null;
  deleted_at: number | null;
}

export interface ShowRow {
  id: number;
  category: string;
  parsed_key: string;
  parsed_title: string;
  title: string;
  year: number | null;
  tmdb_id: number | null;
  overview: string | null;
  poster_path: string | null;
  backdrop_path: string | null;
  rating: number | null;
  genres: string | null;
  first_air_date: string | null;
  match_error: string | null;
  match_attempted_at: number | null;
  created_at: number | null;
  deleted_at: number | null;
}

export interface SeasonRow {
  id: number;
  show_id: number;
  number: number;
  name: string | null;
  poster_path: string | null;
  episode_count: number | null;
}

export interface ItemRow {
  id: number;
  type: "episode" | "movie";
  category: string;
  show_id: number | null;
  season_number: number | null;
  episode_number: number | null;
  episode_end: number | null;
  parsed_key: string | null;
  parsed_title: string;
  title: string;
  year: number | null;
  tmdb_id: number | null;
  overview: string | null;
  poster_path: string | null;
  backdrop_path: string | null;
  still_path: string | null;
  rating: number | null;
  genres: string | null;
  air_date: string | null;
  runtime: number | null;
  match_error: string | null;
  match_attempted_at: number | null;
  created_at: number | null;
  deleted_at: number | null;
}

export interface OverrideRow {
  parsed_key: string;
  item_type: string;
  tmdb_id: number;
}

export interface ProbeInput {
  duration: number | null;
  audio: unknown[];
  subtitles: unknown[];
}

const SHOW_META = [
  "title", "year", "tmdb_id", "overview", "poster_path", "backdrop_path", "rating", "genres", "first_air_date",
] as const;
const ITEM_META = [
  "title", "year", "tmdb_id", "overview", "poster_path", "backdrop_path", "still_path", "rating", "genres",
  "air_date", "runtime", "episode_end",
] as const;
const SEASON_META = ["name", "poster_path", "episode_count"] as const;
const SHOW_NULLABLE = [...SHOW_META, "match_error", "match_attempted_at"] as const;
const ITEM_NULLABLE = [
  ...ITEM_META, "show_id", "season_number", "episode_number", "parsed_key", "match_error", "match_attempted_at",
] as const;

type Col = string | number | null;
export type ShowMeta = Partial<Pick<ShowRow, (typeof SHOW_META)[number]>>;
export type ItemMeta = Partial<Pick<ItemRow, (typeof ITEM_META)[number]>>;
export type SeasonMeta = Partial<Pick<SeasonRow, (typeof SEASON_META)[number]>>;
export type NewShow = Pick<ShowRow, "category" | "parsed_key" | "parsed_title" | "title"> &
  Partial<Pick<ShowRow, (typeof SHOW_NULLABLE)[number]>>;
export type NewItem = Pick<ItemRow, "type" | "category" | "parsed_title" | "title"> &
  Partial<Pick<ItemRow, (typeof ITEM_NULLABLE)[number]>>;

export class Repo {
  private q: Record<string, Statement>;

  constructor(private db: Database) {
    const p = (sql: string) => db.query(sql);
    this.q = {
      fileByPath: p("SELECT * FROM files WHERE rel_path = ?"),
      fileInsert: p(
        `INSERT INTO files(rel_path, category, size, mtime, parsed, item_id, duration, audio_tracks, subtitle_tracks, probe_error, added_at, deleted_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ),
      fileUpdate: p("UPDATE files SET size = ?, mtime = ?, category = ?, parsed = ?, deleted_at = NULL WHERE id = ?"),
      fileLive: p("SELECT * FROM files WHERE deleted_at IS NULL ORDER BY rel_path"),
      fileLivePaths: p("SELECT rel_path FROM files WHERE deleted_at IS NULL"),
      fileMarkDeleted: p("UPDATE files SET deleted_at = ? WHERE rel_path = ? AND deleted_at IS NULL"),
      fileUndelete: p("UPDATE files SET deleted_at = NULL WHERE id = ?"),
      fileSetItem: p("UPDATE files SET item_id = ? WHERE id = ?"),
      fileSetProbe: p("UPDATE files SET duration = ?, audio_tracks = ?, subtitle_tracks = ?, probe_error = ? WHERE id = ?"),
      filesForItem: p("SELECT * FROM files WHERE item_id = ? ORDER BY id"),

      showByKey: p("SELECT * FROM shows WHERE parsed_key = ?"),
      showGet: p("SELECT * FROM shows WHERE id = ?"),
      showMatchError: p("UPDATE shows SET match_error = ?, match_attempted_at = ? WHERE id = ?"),
      showDeleted: p("UPDATE shows SET deleted_at = ? WHERE id = ?"),
      showList: p("SELECT * FROM shows WHERE deleted_at IS NULL AND (?1 IS NULL OR category = ?1) ORDER BY title COLLATE NOCASE"),
      showListAll: p("SELECT * FROM shows WHERE (?1 IS NULL OR category = ?1) ORDER BY title COLLATE NOCASE"),

      seasonGet: p("SELECT * FROM seasons WHERE show_id = ? AND number = ?"),
      seasonInsert: p("INSERT INTO seasons(show_id, number, name, poster_path, episode_count) VALUES (?, ?, ?, ?, ?)"),
      seasonList: p("SELECT * FROM seasons WHERE show_id = ? ORDER BY number"),

      episodeGet: p("SELECT * FROM items WHERE show_id = ? AND season_number = ? AND episode_number = ?"),
      movieByKey: p("SELECT * FROM items WHERE type = 'movie' AND parsed_key = ?"),
      itemGet: p("SELECT * FROM items WHERE id = ?"),
      itemMatchError: p("UPDATE items SET match_error = ?, match_attempted_at = ? WHERE id = ?"),
      itemDeleted: p("UPDATE items SET deleted_at = ? WHERE id = ?"),
      episodeList: p(
        "SELECT * FROM items WHERE show_id = ? AND deleted_at IS NULL ORDER BY season_number, episode_number",
      ),
      episodeListAll: p("SELECT * FROM items WHERE show_id = ? ORDER BY season_number, episode_number"),
      movieList: p(
        "SELECT * FROM items WHERE type = 'movie' AND category = ? AND deleted_at IS NULL ORDER BY title COLLATE NOCASE",
      ),
      searchShows: p("SELECT * FROM shows WHERE deleted_at IS NULL AND title LIKE ? COLLATE NOCASE ORDER BY title COLLATE NOCASE"),
      searchMovies: p(
        "SELECT * FROM items WHERE type = 'movie' AND deleted_at IS NULL AND title LIKE ? COLLATE NOCASE ORDER BY title COLLATE NOCASE",
      ),

      hideItems: p(
        `UPDATE items SET deleted_at = ?1 WHERE deleted_at IS NULL
         AND NOT EXISTS (SELECT 1 FROM files f WHERE f.item_id = items.id AND f.deleted_at IS NULL)`,
      ),
      reviveItems: p(
        `UPDATE items SET deleted_at = NULL WHERE deleted_at IS NOT NULL
         AND EXISTS (SELECT 1 FROM files f WHERE f.item_id = items.id AND f.deleted_at IS NULL)`,
      ),
      hideShows: p(
        `UPDATE shows SET deleted_at = ?1 WHERE deleted_at IS NULL
         AND NOT EXISTS (SELECT 1 FROM items i WHERE i.show_id = shows.id AND i.deleted_at IS NULL)`,
      ),
      reviveShows: p(
        `UPDATE shows SET deleted_at = NULL WHERE deleted_at IS NOT NULL
         AND EXISTS (SELECT 1 FROM items i WHERE i.show_id = shows.id AND i.deleted_at IS NULL)`,
      ),

      settingGet: p("SELECT value FROM settings WHERE key = ?"),
      settingSet: p("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"),
      overrideGet: p("SELECT * FROM match_overrides WHERE parsed_key = ?"),
      overrideSet: p(
        `INSERT INTO match_overrides(parsed_key, item_type, tmdb_id) VALUES (?, ?, ?)
         ON CONFLICT(parsed_key) DO UPDATE SET item_type = excluded.item_type, tmdb_id = excluded.tmdb_id`,
      ),
      cacheGet: p("SELECT json FROM tmdb_cache WHERE key = ? AND fetched_at > ?"),
      cacheSet: p(
        `INSERT INTO tmdb_cache(key, json, fetched_at) VALUES (?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET json = excluded.json, fetched_at = excluded.fetched_at`,
      ),
    };
  }

  // files

  getFileByPath(relPath: string): FileRow | null {
    return (this.q.fileByPath!.get(relPath) as FileRow | null) ?? null;
  }

  upsertFile(f: Omit<FileRow, "id">): number {
    const existing = this.getFileByPath(f.rel_path);
    if (existing) {
      this.q.fileUpdate!.run(f.size, f.mtime, f.category, f.parsed, existing.id);
      return existing.id;
    }
    const r = this.q.fileInsert!.run(
      f.rel_path, f.category, f.size, f.mtime, f.parsed, f.item_id, f.duration,
      f.audio_tracks, f.subtitle_tracks, f.probe_error, f.added_at, f.deleted_at,
    );
    return Number(r.lastInsertRowid);
  }

  /** Marks every live file whose rel_path is not in livePaths as deleted. Returns how many changed. */
  markFilesDeletedExcept(livePaths: string[], now: number): number {
    const keep = new Set(livePaths);
    const stale = (this.q.fileLivePaths!.all() as { rel_path: string }[]).filter((r) => !keep.has(r.rel_path));
    this.db.transaction(() => {
      for (const r of stale) this.q.fileMarkDeleted!.run(now, r.rel_path);
    })();
    return stale.length;
  }

  undeleteFile(id: number): void {
    this.q.fileUndelete!.run(id);
  }

  listLiveFiles(): FileRow[] {
    return this.q.fileLive!.all() as FileRow[];
  }

  setFileItem(fileId: number, itemId: number | null): void {
    this.q.fileSetItem!.run(itemId, fileId);
  }

  setFileProbe(fileId: number, probe: ProbeInput | null, error: string | null): void {
    this.q.fileSetProbe!.run(
      probe?.duration ?? null,
      probe ? JSON.stringify(probe.audio) : null,
      probe ? JSON.stringify(probe.subtitles) : null,
      error,
      fileId,
    );
  }

  filesForItem(itemId: number): FileRow[] {
    return this.q.filesForItem!.all(itemId) as FileRow[];
  }

  // shows

  getShowByKey(parsedKey: string): ShowRow | null {
    return (this.q.showByKey!.get(parsedKey) as ShowRow | null) ?? null;
  }

  getShow(id: number): ShowRow | null {
    return (this.q.showGet!.get(id) as ShowRow | null) ?? null;
  }

  insertShow(s: NewShow, now: number): number {
    return this.insertRow("shows", ["category", "parsed_key", "parsed_title", ...SHOW_NULLABLE], s, now);
  }

  updateShowMeta(id: number, meta: ShowMeta): void {
    this.updatePartial("shows", id, SHOW_META, meta);
  }

  setShowMatchError(id: number, err: string | null, at: number | null): void {
    this.q.showMatchError!.run(err, at, id);
  }

  listShows(category?: string, opts: { includeDeleted?: boolean } = {}): ShowRow[] {
    const q = opts.includeDeleted ? this.q.showListAll! : this.q.showList!;
    return q.all(category ?? null) as ShowRow[];
  }

  setShowDeleted(id: number, deletedAt: number | null): void {
    this.q.showDeleted!.run(deletedAt, id);
  }

  // seasons

  upsertSeason(showId: number, number: number, meta: SeasonMeta): void {
    const existing = this.q.seasonGet!.get(showId, number) as SeasonRow | null;
    if (!existing) {
      this.q.seasonInsert!.run(showId, number, meta.name ?? null, meta.poster_path ?? null, meta.episode_count ?? null);
      return;
    }
    this.updatePartial("seasons", existing.id, SEASON_META, meta);
  }

  listSeasons(showId: number): SeasonRow[] {
    return this.q.seasonList!.all(showId) as SeasonRow[];
  }

  // items

  getEpisode(showId: number, season: number, episode: number): ItemRow | null {
    return (this.q.episodeGet!.get(showId, season, episode) as ItemRow | null) ?? null;
  }

  getMovieByKey(parsedKey: string): ItemRow | null {
    return (this.q.movieByKey!.get(parsedKey) as ItemRow | null) ?? null;
  }

  insertItem(i: NewItem, now: number): number {
    return this.insertRow("items", ["type", "category", "parsed_title", ...ITEM_NULLABLE], i, now);
  }

  updateItemMeta(id: number, meta: ItemMeta): void {
    this.updatePartial("items", id, ITEM_META, meta);
  }

  setItemMatchError(id: number, err: string | null, at: number | null): void {
    this.q.itemMatchError!.run(err, at, id);
  }

  getItem(id: number): ItemRow | null {
    return (this.q.itemGet!.get(id) as ItemRow | null) ?? null;
  }

  listEpisodes(showId: number, opts: { includeDeleted?: boolean } = {}): ItemRow[] {
    const q = opts.includeDeleted ? this.q.episodeListAll! : this.q.episodeList!;
    return q.all(showId) as ItemRow[];
  }

  listMovies(category: string): ItemRow[] {
    return this.q.movieList!.all(category) as ItemRow[];
  }

  setItemDeleted(id: number, at: number | null): void {
    this.q.itemDeleted!.run(at, id);
  }

  searchTitles(q: string): { shows: ShowRow[]; movies: ItemRow[] } {
    const like = `%${q}%`;
    return {
      shows: this.q.searchShows!.all(like) as ShowRow[],
      movies: this.q.searchMovies!.all(like) as ItemRow[],
    };
  }

  // housekeeping

  hideEmpty(now: number): void {
    this.db.transaction(() => {
      this.q.hideItems!.run(now);
      this.q.reviveItems!.run();
      this.q.hideShows!.run(now);
      this.q.reviveShows!.run();
    })();
  }

  // settings / overrides / cache

  getSetting(key: string): string | null {
    const r = this.q.settingGet!.get(key) as { value: string } | null;
    return r?.value ?? null;
  }

  setSetting(key: string, value: string): void {
    this.q.settingSet!.run(key, value);
  }

  getOverride(parsedKey: string): OverrideRow | null {
    return (this.q.overrideGet!.get(parsedKey) as OverrideRow | null) ?? null;
  }

  setOverride(parsedKey: string, type: string, tmdbId: number): void {
    this.q.overrideSet!.run(parsedKey, type, tmdbId);
  }

  cacheGet(key: string, maxAgeMs: number): string | null {
    const r = this.q.cacheGet!.get(key, Date.now() - maxAgeMs) as { json: string } | null;
    return r?.json ?? null;
  }

  cacheSet(key: string, json: string): void {
    this.q.cacheSet!.run(key, json, Date.now());
  }

  // helpers (column names always come from the const whitelists above)

  private insertRow(table: "shows" | "items", cols: readonly string[], row: object, now: number): number {
    const src = row as Record<string, Col | undefined>;
    const names = [...cols, "created_at"];
    const values = [...cols.map((c) => src[c] ?? null), now];
    const r = this.db
      .query(`INSERT INTO ${table}(${names.join(", ")}) VALUES (${names.map(() => "?").join(", ")})`)
      .run(...values);
    return Number(r.lastInsertRowid);
  }

  private updatePartial(table: string, id: number, allowed: readonly string[], meta: object): void {
    const src = meta as Record<string, Col | undefined>;
    const keys = allowed.filter((k) => k in src && src[k] !== undefined);
    if (keys.length === 0) return;
    this.db
      .query(`UPDATE ${table} SET ${keys.map((k) => `${k} = ?`).join(", ")} WHERE id = ?`)
      .run(...keys.map((k) => src[k] as Col), id);
  }
}
