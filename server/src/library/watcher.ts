import chokidar from "chokidar";
import { extname, relative, sep } from "node:path";

const VIDEO_EXTS = new Set([".mkv", ".mp4", ".m4v", ".avi", ".webm", ".ts", ".mov", ".wmv"]);
const CATEGORIES = new Set(["tv", "anime", "movies"]);

/** Everything outside `tv/`, `anime/` and `movies/` (the scanner never reads it), except the root itself. */
export function outsideCategories(root: string, path: string): boolean {
  const rel = relative(root, path);
  if (rel === "") return false;
  return !CATEGORIES.has(rel.split(sep)[0]!);
}

export interface WatchOptions {
  debounceMs?: number;
  stabilityThresholdMs?: number;
  onError?: (err: Error) => void;
}

/** Calls onChange once per quiet period after any add/change/unlink of a video file under root's category folders. */
export function watchRoot(
  root: string,
  onChange: () => void,
  opts: WatchOptions = {},
): { close(): Promise<void> } {
  const debounceMs = opts.debounceMs ?? 5000;
  const watcher = chokidar.watch(root, {
    ignoreInitial: true,
    ignored: (path: string) => outsideCategories(root, path),
    awaitWriteFinish: { stabilityThreshold: opts.stabilityThresholdMs ?? 2000 },
  });
  let timer: ReturnType<typeof setTimeout> | null = null;
  let closed = false;

  const trigger = (path: string) => {
    if (closed || !VIDEO_EXTS.has(extname(path).toLowerCase())) return;
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      if (!closed) onChange();
    }, debounceMs);
  };

  watcher.on("add", trigger).on("change", trigger).on("unlink", trigger);
  watcher.on("error", (err) => opts.onError?.(err instanceof Error ? err : new Error(String(err))));

  return {
    async close() {
      closed = true;
      if (timer) clearTimeout(timer);
      timer = null;
      await watcher.close();
    },
  };
}
