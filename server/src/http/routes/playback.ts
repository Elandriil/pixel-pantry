import { Hono } from "hono";
import { stat } from "node:fs/promises";
import { join } from "node:path";
import type { FileRow } from "../../library/repo";
import { listHistory, stats } from "../../playback/history";
import { reportProgress, setSeasonWatched, setShowWatched, setTrackChoice, setWatched } from "../../playback/progress";
import { listSidecars } from "../../subtitles/sidecar";
import type { Settings } from "../../types";
import { jsonBody } from "../body";
import type { Deps } from "../deps";
import { HttpError } from "../errors";
import { liveItem, liveShow, parseId, parseJson, toItemSummary } from "./catalog";

const SETTINGS_KEY = "client";
const DEFAULT_SETTINGS: Settings = { skipBackSeconds: 10, skipForwardSeconds: 10, autoplayNext: true };

const isSkip = (v: unknown) => Number.isInteger(v) && (v as number) >= 1 && (v as number) <= 300;
/** `undefined` (leave unchanged), `null` (clear) or a string. */
const isChoice = (v: unknown) => v === undefined || v === null || typeof v === "string";

function readSettings(deps: Deps): Settings {
  return { ...DEFAULT_SETTINGS, ...parseJson<Partial<Settings>>(deps.repo.getSetting(SETTINGS_KEY), {}) };
}

/** Video files and their sidecar subtitles, as absolute paths. */
async function fileTargets(root: string, f: FileRow): Promise<string[]> {
  const abs = join(root, f.rel_path);
  try {
    return [abs, ...(await listSidecars(abs)).map((t) => t.path)];
  } catch {
    return [abs]; // folder unreadable or gone: trash just the video
  }
}

/**
 * What to send to the bin for `files`. With `folders`, a top-level folder below the category
 * (`tv/Severance`) whose every live video belongs to `files` is trashed whole instead of file by file.
 */
async function trashTargets(deps: Deps, files: FileRow[], folders: boolean): Promise<string[]> {
  const root = deps.config().mediaRoot;
  const ours = new Set(files.map((f) => f.id));
  const all = folders ? deps.repo.listLiveFiles() : [];
  const out = new Set<string>();
  const wholeFolders = new Set<string>();
  for (const f of files) {
    const parts = f.rel_path.split("/");
    if (folders && parts.length >= 3) {
      const folder = parts.slice(0, 2).join("/");
      if (wholeFolders.has(folder)) continue;
      if (all.filter((x) => x.rel_path.startsWith(`${folder}/`)).every((x) => ours.has(x.id))) {
        wholeFolders.add(folder);
        out.add(join(root, folder));
        continue;
      }
    }
    for (const p of await fileTargets(root, f)) out.add(p);
  }
  return [...out];
}

const exists = (p: string) => stat(p).then(() => true, () => false);

async function trashFiles(deps: Deps, files: FileRow[], folders: boolean): Promise<string[]> {
  const targets = await trashTargets(deps, files, folders);
  // Paths already gone from disk are skipped; the rows are still marked deleted below.
  const present = await Promise.all(targets.map(exists));
  const paths = targets.filter((_, i) => present[i]);
  try {
    if (paths.length > 0) await deps.trash(paths);
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    deps.log.error("trash failed", { paths, error });
    throw new HttpError(500, error);
  }
  const now = deps.now();
  deps.db.transaction(() => {
    for (const f of files) deps.repo.setFileDeleted(f.id, now);
    deps.repo.hideEmpty(now);
  })();
  deps.log.info("trashed", { paths });
  void deps.scanner.scan();
  return paths;
}

const liveFilesOf = (deps: Deps, itemIds: number[]): FileRow[] =>
  itemIds.flatMap((id) => deps.repo.filesForItem(id)).filter((f) => f.deleted_at === null);

export function playbackRoutes(deps: Deps): Hono {
  const r = new Hono();

  r.post("/items/:id/progress", async (c) => {
    const item = liveItem(deps, parseId(c.req.param("id")));
    const b = await jsonBody(c);
    if (typeof b.position !== "number" || typeof b.duration !== "number") {
      throw new HttpError(400, "position and duration must be numbers");
    }
    if (b.sessionId != null && typeof b.sessionId !== "number") throw new HttpError(400, "sessionId must be a number");
    return c.json(
      reportProgress(deps.db, {
        itemId: item.id, position: b.position, duration: b.duration, sessionId: b.sessionId ?? null, now: deps.now(),
      }),
    );
  });

  r.post("/items/:id/watched", async (c) => {
    const item = liveItem(deps, parseId(c.req.param("id")));
    const b = await jsonBody(c);
    if (typeof b.watched !== "boolean") throw new HttpError(400, "watched must be a boolean");
    setWatched(deps.db, item.id, b.watched, deps.now());
    return c.json({ ok: true });
  });

  r.post("/shows/:id/watched", async (c) => {
    const show = liveShow(deps, parseId(c.req.param("id")));
    const b = await jsonBody(c);
    if (typeof b.watched !== "boolean") throw new HttpError(400, "watched must be a boolean");
    if (b.season !== undefined && !Number.isInteger(b.season)) throw new HttpError(400, "season must be an integer");
    if (b.season === undefined) setShowWatched(deps.db, show.id, b.watched, deps.now());
    else setSeasonWatched(deps.db, show.id, b.season as number, b.watched, deps.now());
    return c.json({ ok: true });
  });

  r.post("/items/:id/tracks", async (c) => {
    const item = liveItem(deps, parseId(c.req.param("id")));
    const b = await jsonBody(c);
    if (!isChoice(b.subtitle) || !isChoice(b.audio)) throw new HttpError(400, "subtitle and audio must be strings or null");
    setTrackChoice(deps.db, item.id, { subtitle: b.subtitle as string | null | undefined, audio: b.audio as string | null | undefined });
    return c.json({ ok: true });
  });

  r.get("/history", (c) => {
    const raw = c.req.query("limit");
    const limit = raw === undefined ? undefined : Number(raw);
    if (limit !== undefined && (!Number.isSafeInteger(limit) || limit <= 0)) throw new HttpError(400, "Invalid limit");
    const entries = [];
    for (const e of listHistory(deps.db, limit)) {
      const item = deps.repo.getItem(e.itemId);
      if (item) entries.push({ ...e, item: toItemSummary(deps, item) });
    }
    return c.json({ entries });
  });

  r.get("/stats", (c) => c.json(stats(deps.db, deps.now())));

  r.get("/settings", (c) => c.json(readSettings(deps)));

  r.put("/settings", async (c) => {
    const b = await jsonBody(c);
    const next = readSettings(deps);
    for (const key of ["skipBackSeconds", "skipForwardSeconds"] as const) {
      if (b[key] === undefined) continue;
      if (!isSkip(b[key])) throw new HttpError(400, `${key} must be an integer from 1 to 300`);
      next[key] = b[key] as number;
    }
    if (b.autoplayNext !== undefined) {
      if (typeof b.autoplayNext !== "boolean") throw new HttpError(400, "autoplayNext must be a boolean");
      next.autoplayNext = b.autoplayNext;
    }
    deps.repo.setSetting(SETTINGS_KEY, JSON.stringify(next));
    return c.json(next);
  });

  r.delete("/shows/:id", async (c) => {
    const show = liveShow(deps, parseId(c.req.param("id")));
    const ids = deps.repo.listEpisodes(show.id, { includeDeleted: true }).map((i) => i.id);
    return c.json({ trashed: await trashFiles(deps, liveFilesOf(deps, ids), true) });
  });

  r.delete("/movies/:id", async (c) => {
    const item = liveItem(deps, parseId(c.req.param("id")));
    if (item.type !== "movie") throw new HttpError(404, "Movie not found");
    return c.json({ trashed: await trashFiles(deps, liveFilesOf(deps, [item.id]), false) });
  });

  return r;
}
