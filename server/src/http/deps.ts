import type { Database } from "bun:sqlite";
import type { Config } from "../config";
import type { Repo } from "../library/repo";
import type { Scanner } from "../library/scanner";
import type { Logger } from "../log";
import type { ImageStore } from "../metadata/images";
import type { TmdbClient } from "../metadata/tmdb";
import type { OsClient } from "../subtitles/opensubtitles";

/** Everything the HTTP layer needs, injected so tests can swap in fakes. */
export interface Deps {
  db: Database;
  repo: Repo;
  config: () => Config;
  saveConfig: (c: Config) => void;
  scanner: Scanner;
  tmdb: TmdbClient;
  os: OsClient;
  images: ImageStore;
  log: Logger;
  trash: (paths: string[]) => Promise<void>;
  now: () => number;
  quit: () => void;
}
