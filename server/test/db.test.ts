import { expect, test } from "bun:test";
import { openDb } from "../src/db/db";
import { migrate } from "../src/db/schema";

const TABLES = ["shows", "seasons", "items", "files", "playback", "sessions", "settings", "match_overrides", "tmdb_cache"];

test("openDb creates all tables", () => {
  const db = openDb(":memory:");
  const names = (db.query("SELECT name FROM sqlite_master WHERE type='table'").all() as { name: string }[]).map((r) => r.name);
  for (const t of TABLES) expect(names).toContain(t);
});

test("migrate run twice is a no-op", () => {
  const db = openDb(":memory:");
  db.run("INSERT INTO settings(key, value) VALUES ('k', 'v')");
  migrate(db);
  expect(db.query("SELECT value FROM settings WHERE key='k'").get()).toEqual({ value: "v" });
  expect(db.query("SELECT COUNT(*) AS n FROM schema_version").get()).toEqual({ n: 2 });
});

test("foreign keys are enforced", () => {
  const db = openDb(":memory:");
  expect(() => db.run("INSERT INTO seasons(show_id, number) VALUES (999, 1)")).toThrow();
});

test("a version 1 database gains the shows.audio_language column and keeps its rows", () => {
  const db = openDb(":memory:");
  db.run("ALTER TABLE shows DROP COLUMN audio_language");
  db.run("DELETE FROM schema_version WHERE version > 1");
  db.run("INSERT INTO shows(id, category, parsed_key, title) VALUES (1, 'tv', 'tv:lost', 'Lost')");
  migrate(db);
  expect(db.query("SELECT title, audio_language FROM shows WHERE id = 1").get()).toEqual({ title: "Lost", audio_language: null });
});
