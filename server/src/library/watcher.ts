import chokidar from "chokidar";
import { extname } from "node:path";

const VIDEO_EXTS = new Set([".mkv", ".mp4", ".m4v", ".avi", ".webm", ".ts", ".mov", ".wmv"]);

export interface WatchOptions {
  debounceMs?: number;
  stabilityThresholdMs?: number;
  onError?: (err: Error) => void;
}

/** Calls onChange once per quiet period after any add/change/unlink of a video file under root. */
export function watchRoot(
  root: string,
  onChange: () => void,
  opts: WatchOptions = {},
): { close(): Promise<void> } {
  const debounceMs = opts.debounceMs ?? 5000;
  const watcher = chokidar.watch(root, {
    ignoreInitial: true,
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
