import { readdir, stat } from "node:fs/promises";
import { extname, join } from "node:path";
import type { Category } from "../types";

export interface FileEntry {
  relPath: string;
  category: Category;
  size: number;
  mtime: number;
}

const VIDEO_EXT = new Set([".mkv", ".mp4", ".m4v", ".avi", ".webm", ".ts", ".mov", ".wmv"]);
const SKIP_DIRS = new Set(["extras", "featurettes", "samples"]);
const CATEGORIES: Category[] = ["tv", "anime", "movies"];

type StatFn = (path: string) => Promise<{ size: number; mtimeMs: number }>;

function isEnoent(err: unknown): boolean {
  return (err as NodeJS.ErrnoException).code === "ENOENT";
}

// ENOENT means the entry vanished (missing category folder or a mid-scan race): skip it.
// Any other error propagates so a scan fails loudly instead of returning a partial list.
async function walk(root: string, rel: string, category: Category, out: FileEntry[], statFn: StatFn): Promise<void> {
  let entries;
  try {
    entries = await readdir(join(root, rel), { withFileTypes: true });
  } catch (err) {
    if (isEnoent(err)) return;
    throw err;
  }
  for (const e of entries) {
    if (e.name.startsWith(".")) continue;
    const childRel = `${rel}/${e.name}`;
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name.toLowerCase())) continue;
      await walk(root, childRel, category, out, statFn);
    } else if (e.isFile()) {
      if (!VIDEO_EXT.has(extname(e.name).toLowerCase())) continue;
      if (/\bsample\b/i.test(e.name)) continue;
      let st;
      try {
        st = await statFn(join(root, childRel));
      } catch (err) {
        if (isEnoent(err)) continue;
        throw err;
      }
      out.push({ relPath: childRel, category, size: st.size, mtime: Math.floor(st.mtimeMs) });
    }
  }
}

export async function listVideoFiles(root: string, statFn: StatFn = stat): Promise<FileEntry[]> {
  const out: FileEntry[] = [];
  for (const cat of CATEGORIES) {
    await walk(root, cat, cat, out, statFn);
  }
  return out.sort((a, b) => (a.relPath < b.relPath ? -1 : a.relPath > b.relPath ? 1 : 0));
}
