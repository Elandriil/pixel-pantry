import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import type { Category } from "./types";

/** A folder the scanner indexes. `id` is the first segment of every file's rel_path, so it must stay stable. */
export interface Library {
  id: string;
  path: string;
  category: Category;
}

/**
 * What the TV app pairs with. `serverId` names this installation (the TV checks it before sending its token),
 * `pairingPin` is shown on the admin page and typed once on the TV, `clientToken` is what a paired TV sends on
 * every API request. All three are generated on first start; `newPairing` rotates the pin and token.
 */
export interface AuthConfig {
  serverId: string;
  pairingPin: string;
  clientToken: string;
}

export interface Config {
  libraries: Library[];
  port: number;
  tmdbApiKey: string;
  opensubtitles: { apiKey: string; username: string; password: string };
  startWithWindows: boolean;
  auth: AuthConfig;
}

export const CATEGORIES: readonly Category[] = ["tv", "anime", "movies"];

export const DEFAULT_CONFIG: Config = {
  libraries: [],
  port: 8765,
  tmdbApiKey: "",
  opensubtitles: { apiKey: "", username: "", password: "" },
  startWithWindows: true,
  auth: { serverId: "", pairingPin: "", clientToken: "" },
};

export const isCategory = (v: unknown): v is Category => typeof v === "string" && (CATEGORIES as string[]).includes(v);

/** `lib-` plus 8 hex chars: never a category name, never contains `/`. */
export function newLibraryId(): string {
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  return `lib-${Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("")}`;
}

const hex = (bytes: number): string => {
  const b = new Uint8Array(bytes);
  crypto.getRandomValues(b);
  return Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
};

/** Six digits, uniformly distributed (rejection sampling over a 32-bit value). */
export function newPairingPin(): string {
  const limit = 4_294_000_000; // largest multiple of 1 000 000 below 2^32
  const v = new Uint32Array(1);
  do crypto.getRandomValues(v);
  while (v[0]! >= limit);
  return String(v[0]! % 1_000_000).padStart(6, "0");
}

/** Fills whichever auth values are missing (first start, or a config written by an older version). */
export function ensureAuth(c: Config): { config: Config; changed: boolean } {
  const auth = { ...c.auth };
  let changed = false;
  if (!auth.serverId) (auth.serverId = hex(16)), (changed = true);
  if (!auth.pairingPin) (auth.pairingPin = newPairingPin()), (changed = true);
  if (!auth.clientToken) (auth.clientToken = hex(32)), (changed = true);
  return { config: changed ? { ...c, auth } : c, changed };
}

/** A fresh pin and token: every TV paired so far has to pair again. The server id stays. */
export function newPairing(c: Config): Config {
  return { ...c, auth: { serverId: c.auth.serverId, pairingPin: newPairingPin(), clientToken: hex(32) } };
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
  const auth = raw.auth ?? {};
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
    auth: {
      serverId: str(auth.serverId, ""),
      pairingPin: str(auth.pairingPin, ""),
      clientToken: str(auth.clientToken, ""),
    },
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
