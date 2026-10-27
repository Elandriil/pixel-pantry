import type { Hono } from "hono";
import { join } from "node:path";
import { DEFAULT_CONFIG, type Config } from "../../src/config";
import { openDb } from "../../src/db/db";
import type { Deps } from "../../src/http/deps";
import { Repo } from "../../src/library/repo";
import { Scanner } from "../../src/library/scanner";
import { Logger } from "../../src/log";
import { ImageStore } from "../../src/metadata/images";
import { TmdbClient } from "../../src/metadata/tmdb";
import { OsClient } from "../../src/subtitles/opensubtitles";
import type { FetchFn } from "../../src/types";
import { fakeProbe, fakeTmdb, legacyLibraries, tmpDir } from "../helpers";

export { seedMovie, seedShow } from "../helpers";

export const NOW = 1_700_000_000_000;

const noNetwork = (async (input: RequestInfo | URL) => {
  throw new Error(`unexpected network call: ${String(input)}`);
}) as unknown as FetchFn;

export interface TestDeps extends Deps {
  /** The folder whose `tv`, `anime` and `movies` subfolders are the configured libraries. */
  mediaRoot: string;
  /** Paths passed to `trash`, one array per call. */
  trashed: string[][];
  /** Number of `quit()` calls. */
  quitCalls: () => number;
  /** Moves the injected clock. */
  setNow: (t: number) => void;
  fakeTmdb: ReturnType<typeof fakeTmdb>;
  fakeProbe: ReturnType<typeof fakeProbe>;
}

export function makeDeps(overrides: Partial<Deps> = {}): TestDeps {
  const dir = tmpDir();
  const db = openDb(":memory:");
  const repo = new Repo(db);
  const log = new Logger(join(dir, "logs", "server.log"));
  const mediaRoot = join(dir, "media");
  let cfg: Config = { ...DEFAULT_CONFIG, libraries: legacyLibraries(mediaRoot) };
  let clock = NOW;
  let quits = 0;
  const trashed: string[][] = [];
  const tmdbFake = fakeTmdb();
  const probeFake = fakeProbe();
  const now = () => clock;
  const deps: TestDeps = {
    db,
    repo,
    config: () => cfg,
    saveConfig: (c) => {
      cfg = c;
    },
    scanner: new Scanner({ repo, tmdb: tmdbFake, probe: probeFake.probe, libraries: () => cfg.libraries, log, now }),
    tmdb: new TmdbClient({
      apiKey: "test",
      fetchFn: noNetwork,
      cache: { get: (k, age) => repo.cacheGet(k, age), set: (k, j) => repo.cacheSet(k, j) },
    }),
    os: new OsClient({ apiKey: "", username: "", password: "", fetchFn: noNetwork }),
    images: new ImageStore({ dir: join(dir, "images"), fetchFn: noNetwork }),
    log,
    trash: async (paths) => {
      trashed.push(paths);
    },
    now,
    mediaRoot,
    quit: () => {
      quits++;
    },
    trashed,
    quitCalls: () => quits,
    setNow: (t) => {
      clock = t;
    },
    fakeTmdb: tmdbFake,
    fakeProbe: probeFake,
  };
  return Object.assign(deps, overrides);
}

export async function request(
  app: Hono,
  path: string,
  init?: RequestInit,
  env?: Record<string, unknown>,
): Promise<{ status: number; json: any; headers: Headers }> {
  const res = await app.request(path, init, env);
  const text = await res.text();
  let json: any = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = text;
  }
  return { status: res.status, json, headers: res.headers };
}
