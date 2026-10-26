import type { Database } from "bun:sqlite";

const V1 = `
CREATE TABLE shows (
  id INTEGER PRIMARY KEY,
  category TEXT,
  parsed_key TEXT UNIQUE,
  parsed_title TEXT,
  title TEXT,
  year INT,
  tmdb_id INT,
  overview TEXT,
  poster_path TEXT,
  backdrop_path TEXT,
  rating REAL,
  genres TEXT,
  first_air_date TEXT,
  match_error TEXT,
  match_attempted_at INT,
  created_at INT,
  deleted_at INT
);
CREATE TABLE seasons (
  id INTEGER PRIMARY KEY,
  show_id INT REFERENCES shows(id),
  number INT,
  name TEXT,
  poster_path TEXT,
  episode_count INT,
  UNIQUE(show_id, number)
);
CREATE TABLE items (
  id INTEGER PRIMARY KEY,
  type TEXT,
  category TEXT,
  show_id INT NULL REFERENCES shows(id),
  season_number INT NULL,
  episode_number INT NULL,
  episode_end INT NULL,
  parsed_key TEXT NULL,
  parsed_title TEXT,
  title TEXT,
  year INT,
  tmdb_id INT,
  overview TEXT,
  poster_path TEXT,
  backdrop_path TEXT,
  still_path TEXT,
  rating REAL,
  genres TEXT,
  air_date TEXT,
  runtime INT,
  match_error TEXT,
  match_attempted_at INT,
  created_at INT,
  deleted_at INT,
  UNIQUE(show_id, season_number, episode_number)
);
CREATE TABLE files (
  id INTEGER PRIMARY KEY,
  rel_path TEXT UNIQUE,
  category TEXT,
  size INT,
  mtime INT,
  parsed TEXT,
  item_id INT NULL REFERENCES items(id),
  duration REAL,
  audio_tracks TEXT,
  subtitle_tracks TEXT,
  probe_error TEXT,
  added_at INT,
  deleted_at INT
);
CREATE INDEX files_item_id ON files(item_id);
CREATE INDEX items_show_id ON items(show_id);
CREATE TABLE playback (
  item_id INT PRIMARY KEY,
  position REAL,
  duration REAL,
  watched INT,
  last_played_at INT,
  subtitle_choice TEXT,
  audio_choice TEXT
);
CREATE TABLE sessions (
  id INTEGER PRIMARY KEY,
  item_id INT,
  started_at INT,
  ended_at INT,
  last_position REAL,
  seconds_watched REAL
);
CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE match_overrides (parsed_key TEXT PRIMARY KEY, item_type TEXT, tmdb_id INT);
CREATE TABLE tmdb_cache (key TEXT PRIMARY KEY, json TEXT, fetched_at INT);
`;

const MIGRATIONS: string[] = [V1];

export function migrate(db: Database): void {
  db.run("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
  const row = db.query("SELECT MAX(version) AS v FROM schema_version").get() as { v: number | null };
  const current = row.v ?? 0;
  for (let v = current; v < MIGRATIONS.length; v++) {
    db.transaction(() => {
      db.exec(MIGRATIONS[v]!);
      db.run("INSERT INTO schema_version(version) VALUES (?)", [v + 1]);
    })();
  }
}
