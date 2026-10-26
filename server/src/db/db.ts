import { Database } from "bun:sqlite";
import { migrate } from "./schema";

export function openDb(path: string | ":memory:"): Database {
  const db = new Database(path);
  db.run("PRAGMA journal_mode=WAL");
  db.run("PRAGMA foreign_keys=ON");
  migrate(db);
  return db;
}
