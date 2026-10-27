import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import type { Category } from "./types";

/** A folder the scanner indexes. `id` is the first segment of every file's rel_path, so it must stay stable. */
export interface Library {
  id: string;
  path: string;
  category: Category;
}

export interface Config {
  libraries: Library[];
  port: number;
  tmdbApiKey: string;
  opensubtitles: { apiKey: string; username: string; password: string };
  startWithWindows: boolean;
}

export const CATEGORIES: readonly Category[] = ["tv", "anime", "movies"];

export const DEFAULT_CONFIG: Config = {
  libraries: [],
  port: 8765,
  tmdbApiKey: "",
  opensubtitles: { apiKey: "", username: "", password: "" },
  startWithWindows: true,
};

export const isCategory = (v: unknown): v is Category => typeof v === "string" && (CATEGORIES as string[]).includes(v);

/** `lib-` plus 8 hex chars: never a category name, never contains `/`. */
export function newLibraryId(): string {
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  return `lib-${Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("")}`;
}

/**
 * Pre-library configs had one `mediaRoot` with `tv`, `anime` and `movies` subfolders. They become one library per
 * subfolder, with the category as id, so the `tv/...` rel_paths already in the database keep resolving. Subfolders
 * missing right now are still migrated when none exists (an unplugged drive), otherwise only the present ones.
 */
export function librariesFromMediaRoot(mediaRoot: string, exists: (p: string) => boolean = existsSync): Library[] {
  if (!mediaRoot) return [];
  const all = CATEGORIES.map((c) => ({ id: c, path: join(mediaRoot, c), category: c }));
  const present = all.filter((l) => exists(l.path));
  return present.length > 0 ? present : all;
}

function parseLibraries(raw: unknown): Library[] | null {
  if (!Array.isArray(raw)) return null;
  const out: Library[] = [];
  const ids = new Set<string>();
  for (const e of raw) {
    if (!e || typeof e !== "object") continue;
    const { id, path, category } = e as Record<string, unknown>;
    if (typeof id !== "string" || !id || id.includes("/") || typeof path !== "string" || !path || !isCategory(category)) continue;
    if (ids.has(id)) continue;
    ids.add(id);
    out.push({ id, path, category });
  }
  return out;
}

export function loadConfig(path: string, exists: (p: string) => boolean = existsSync): Config {
  let raw: any = {};
  try {
    raw = JSON.parse(readFileSync(path, "utf8")) ?? {};
  } catch {}
  const os = raw.opensubtitles ?? {};
  const str = (v: unknown, d: string) => (typeof v === "string" ? v : d);
  const libraries = parseLibraries(raw.libraries) ?? librariesFromMediaRoot(str(raw.mediaRoot, ""), exists);
  return {
    libraries,
    port: typeof raw.port === "number" ? raw.port : DEFAULT_CONFIG.port,
    tmdbApiKey: str(raw.tmdbApiKey, DEFAULT_CONFIG.tmdbApiKey),
    opensubtitles: {
      apiKey: str(os.apiKey, ""),
      username: str(os.username, ""),
      password: str(os.password, ""),
    },
    startWithWindows: typeof raw.startWithWindows === "boolean" ? raw.startWithWindows : DEFAULT_CONFIG.startWithWindows,
  };
}

export function saveConfig(path: string, c: Config): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(c, null, 2));
}

/** The library whose id is the first segment of relPath, or null once it has been removed. */
export function libraryOf(libraries: Library[], relPath: string): Library | null {
  const id = relPath.split("/", 1)[0];
  return libraries.find((l) => l.id === id) ?? null;
}

/** Absolute path of a stored rel_path (`<library id>/<path inside the library>`), or null once the library is gone. */
export function libraryFilePath(libraries: Library[], relPath: string): string | null {
  const lib = libraryOf(libraries, relPath);
  if (!lib) return null;
  const inside = relPath.slice(lib.id.length + 1);
  return inside ? join(lib.path, inside) : lib.path;
}

/** Case-insensitive on Windows (its paths are), with a trailing separator so `D:\TV` is not a prefix of `D:\TV2`. */
function folderKey(p: string): string {
  const r = resolve(p).replace(/[\\/]+$/, "") + sep;
  return process.platform === "win32" ? r.toLowerCase() : r;
}

/** Why `candidate` cannot join `others`: the same folder, or inside / around one of them (files would index twice). */
export function libraryConflict(candidate: Library, others: Library[]): string | null {
  const key = folderKey(candidate.path);
  for (const o of others) {
    const ok = folderKey(o.path);
    if (ok === key) return `${candidate.path} is already a library folder`;
    if (key.startsWith(ok)) return `${candidate.path} is inside the library folder ${o.path}`;
    if (ok.startsWith(key)) return `${candidate.path} contains the library folder ${o.path}`;
  }
  return null;
}
