import type { Database } from "bun:sqlite";
import { join } from "node:path";
import { loadConfig, saveConfig, type Config } from "./config";
import { openDb } from "./db/db";
import { trashPaths } from "./fs/trash";
import type { Deps } from "./http/deps";
import { probeFile } from "./library/probe";
import { Repo } from "./library/repo";
import { Scanner } from "./library/scanner";
import type { Logger } from "./log";
import { ImageStore } from "./metadata/images";
import { TmdbClient } from "./metadata/tmdb";
import { resolveFfprobePath } from "./paths";
import { OsClient } from "./subtitles/opensubtitles";

export const configPath = (dir: string) => join(dir, "config.json");

/**
 * Production wiring of the HTTP `Deps`, in the startup order of the brief. `onConfigSaved` runs after
 * each save with the previous and new config; `quit` is late-bound by the caller.
 */
export function buildDeps(opts: {
  dataDir: string;
  log: Logger;
  onConfigSaved: (prev: Config, next: Config) => void;
  quit: () => void;
}): Deps & { db: Database } {
  const { dataDir, log } = opts;
  const cfgFile = configPath(dataDir);
  let cfg = loadConfig(cfgFile);
  const config = () => cfg;

  const db = openDb(join(dataDir, "pantry.db"));
  const repo = new Repo(db);
  const tmdb = new TmdbClient({
    apiKey: () => cfg.tmdbApiKey,
    fetchFn: fetch,
    cache: { get: (k, age) => repo.cacheGet(k, age), set: (k, j) => repo.cacheSet(k, j) },
  });
  const os = new OsClient({
    apiKey: () => cfg.opensubtitles.apiKey,
    username: () => cfg.opensubtitles.username,
    password: () => cfg.opensubtitles.password,
    fetchFn: fetch,
  });
  const images = new ImageStore({ dir: join(dataDir, "images"), fetchFn: fetch });
  const ffprobe = resolveFfprobePath();
  const scanner = new Scanner({ repo, tmdb, probe: (p) => probeFile(ffprobe, p), libraries: () => cfg.libraries, log });

  return {
    db,
    repo,
    config,
    saveConfig: (next) => {
      saveConfig(cfgFile, next);
      const prev = cfg;
      cfg = next;
      opts.onConfigSaved(prev, next);
    },
    scanner,
    tmdb,
    os,
    images,
    log,
    trash: (paths) => trashPaths(paths),
    now: Date.now,
    quit: () => opts.quit(),
  };
}
