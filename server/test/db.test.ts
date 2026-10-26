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
  expect(db.query("SELECT COUNT(*) AS n FROM schema_version").get()).toEqual({ n: 1 });
});

test("foreign keys are enforced", () => {
  const db = openDb(":memory:");
  expect(() => db.run("INSERT INTO seasons(show_id, number) VALUES (999, 1)")).toThrow();
});
