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

async function walk(root: string, rel: string, category: Category, out: FileEntry[]): Promise<void> {
  const entries = await readdir(join(root, rel), { withFileTypes: true });
  for (const e of entries) {
    if (e.name.startsWith(".")) continue;
    const childRel = `${rel}/${e.name}`;
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name.toLowerCase())) continue;
      await walk(root, childRel, category, out);
    } else if (e.isFile()) {
      if (!VIDEO_EXT.has(extname(e.name).toLowerCase())) continue;
      if (/\bsample\b/i.test(e.name)) continue;
      const st = await stat(join(root, childRel));
      out.push({ relPath: childRel, category, size: st.size, mtime: Math.floor(st.mtimeMs) });
    }
  }
}

export async function listVideoFiles(root: string): Promise<FileEntry[]> {
  const out: FileEntry[] = [];
  for (const cat of CATEGORIES) {
    try {
      await walk(root, cat, cat, out);
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code !== "ENOENT") throw err;
    }
  }
  return out.sort((a, b) => (a.relPath < b.relPath ? -1 : a.relPath > b.relPath ? 1 : 0));
}
