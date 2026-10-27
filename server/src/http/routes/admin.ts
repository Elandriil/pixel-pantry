import { Hono, type MiddlewareHandler } from "hono";
import { existsSync } from "node:fs";
import { lanAddresses } from "../../net";
import { isCategory, libraryConflict, newLibraryId, type Config, type Library } from "../../config";
import { LOW_CONFIDENCE } from "../../library/scanner";
import adminHtmlModule from "../admin/index.html" with { type: "text" };
import { jsonBody } from "../body";
import type { Deps } from "../deps";
import { HttpError } from "../errors";

// `type: "text"` makes Bun (and the compiled exe) embed the file as a string; bun-types declares *.html as an HTMLBundle.
const ADMIN_HTML = adminHtmlModule as unknown as string;

export const MASK = "••••";
const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);
const LOG_MAX = 2000;

/** `remoteAddr` is set by tests; the entry point forwards the Bun server so its requestIP can be used. */
export interface AdminEnv {
  remoteAddr?: string;
  server?: { requestIP(req: Request): { address: string } | null };
}
type Env = { Bindings: AdminEnv | undefined };

/** `localhost`, `127.0.0.1` or `[::1]`, with an optional port. */
const LOCAL_HOST = /^(?:localhost|127\.0\.0\.1|\[::1\])(?::\d+)?$/i;

/**
 * Only the socket address counts: proxies' X-Forwarded-For is never trusted. The Host header must name the
 * loopback too, so a web page on a rebinding domain that resolves to 127.0.0.1 cannot drive the admin API.
 */
const loopbackOnly: MiddlewareHandler<Env> = async (c, next) => {
  const addr = c.env?.remoteAddr ?? c.env?.server?.requestIP(c.req.raw)?.address;
  const host = c.req.header("Host") ?? new URL(c.req.url).host;
  if (!addr || !LOOPBACK.has(addr) || !LOCAL_HOST.test(host)) return c.json({ error: "admin is localhost only" }, 403);
  await next();
};

const mask = (v: string) => (v ? MASK : "");

function maskedConfig(c: Config): Config {
  return {
    libraries: c.libraries,
    port: c.port,
    tmdbApiKey: mask(c.tmdbApiKey),
    opensubtitles: {
      apiKey: mask(c.opensubtitles.apiKey),
      username: c.opensubtitles.username,
      password: mask(c.opensubtitles.password),
    },
    startWithWindows: c.startWithWindows,
  };
}

function str(v: unknown, field: string): string | undefined {
  if (v === undefined) return undefined;
  if (typeof v !== "string") throw new HttpError(400, `${field} must be a string`);
  return v;
}

/** A secret field: undefined or the mask keeps the stored value. */
const secret = (v: unknown, field: string, old: string): string => {
  const s = str(v, field);
  return s === undefined || s === MASK ? old : s;
};

/** Trimmed, with one pair of surrounding quotes removed (as pasted from Explorer's "Copy as path"). */
function cleanPath(v: string): string {
  const t = v.trim();
  const m = /^"(.*)"$/s.exec(t) ?? /^'(.*)'$/s.exec(t);
  return (m ? m[1]! : t).trim();
}

/**
 * The new library list. An entry without an id is a new folder and gets one; an entry with an id keeps it (so its
 * files' rel_paths stay valid). Entries left out are removed, and their files leave the library at the next scan.
 */
function librariesOf(v: unknown, old: Library[]): Library[] {
  if (v === undefined) return old;
  if (!Array.isArray(v)) throw new HttpError(400, "libraries must be an array");
  const out: Library[] = [];
  for (const e of v) {
    if (!e || typeof e !== "object" || Array.isArray(e)) throw new HttpError(400, "each library must be an object");
    const { id, path, category } = e as Record<string, unknown>;
    const folder = cleanPath(str(path, "libraries[].path") ?? "");
    if (!folder) throw new HttpError(400, "Library folder path is required");
    if (!isCategory(category)) throw new HttpError(400, "Library type must be tv, anime or movies");
    const known = typeof id === "string" && id ? old.find((l) => l.id === id) : undefined;
    if (id !== undefined && id !== null && !known) throw new HttpError(400, `Unknown library id: ${String(id)}`);
    if (known && out.some((l) => l.id === known.id)) throw new HttpError(400, `Duplicate library id: ${known.id}`);
    // a known id keeps its category too: changing it would leave files parsed one way attached to shows of the other
    const lib: Library = { id: known?.id ?? newLibraryId(), path: folder, category: known?.category ?? category };
    const conflict = libraryConflict(lib, out);
    if (conflict) throw new HttpError(400, conflict);
    out.push(lib);
  }
  return out;
}

/** Merges a partial Config-shaped body onto the current config. */
function mergeConfig(old: Config, body: Record<string, unknown>): Config {
  const port = body.port;
  if (port !== undefined && (typeof port !== "number" || !Number.isInteger(port) || port < 1 || port > 65535)) {
    throw new HttpError(400, "port must be an integer between 1 and 65535");
  }
  const os = body.opensubtitles;
  if (os !== undefined && (typeof os !== "object" || os === null || Array.isArray(os))) {
    throw new HttpError(400, "opensubtitles must be an object");
  }
  const o = (os ?? {}) as Record<string, unknown>;
  const sww = body.startWithWindows;
  if (sww !== undefined && typeof sww !== "boolean") throw new HttpError(400, "startWithWindows must be a boolean");
  return {
    libraries: librariesOf(body.libraries, old.libraries),
    port: (port as number | undefined) ?? old.port,
    tmdbApiKey: secret(body.tmdbApiKey, "tmdbApiKey", old.tmdbApiKey),
    opensubtitles: {
      apiKey: secret(o.apiKey, "opensubtitles.apiKey", old.opensubtitles.apiKey),
      username: str(o.username, "opensubtitles.username") ?? old.opensubtitles.username,
      password: secret(o.password, "opensubtitles.password", old.opensubtitles.password),
    },
    startWithWindows: (sww as boolean | undefined) ?? old.startWithWindows,
  };
}

const sameLibraries = (a: Library[], b: Library[]) => JSON.stringify(a) === JSON.stringify(b);

const positiveInt = (v: unknown): v is number => typeof v === "number" && Number.isInteger(v) && v > 0;

export function adminRoutes(deps: Deps): Hono<Env> {
  const r = new Hono<Env>();
  const { repo, scanner } = deps;

  r.use("/", loopbackOnly);
  r.use("/admin", loopbackOnly);
  r.use("/admin/*", loopbackOnly);
  r.use("/admin/api/*", async (c, next) => {
    await next();
    if (!c.res.headers.has("Cache-Control")) c.res.headers.set("Cache-Control", "no-store");
  });

  r.get("/", (c) => c.redirect("/admin", 302));
  r.get("/admin", (c) => c.html(ADMIN_HTML));

  r.get("/admin/api/status", (c) => {
    const cfg = deps.config();
    const scan = scanner.status();
    return c.json({
      version: "1",
      port: cfg.port,
      lanAddresses: lanAddresses(),
      libraries: cfg.libraries.map((l) => ({ ...l, found: existsSync(l.path) })),
      scan,
      counts: {
        tv: repo.listShows("tv").length,
        anime: repo.listShows("anime").length,
        movies: repo.listMovies("movies").length,
      },
      lastScanError: scan.lastError,
      tmdbConfigured: cfg.tmdbApiKey !== "",
      opensubtitlesConfigured: cfg.opensubtitles.apiKey !== "",
    });
  });

  r.get("/admin/api/config", (c) => c.json(maskedConfig(deps.config())));

  r.put("/admin/api/config", async (c) => {
    const old = deps.config();
    const next = mergeConfig(old, await jsonBody(c));
    deps.saveConfig(next);
    const keyChanged = next.tmdbApiKey !== old.tmdbApiKey;
    // a new key may match what the old (or missing) one could not
    if (keyChanged) repo.resetUnmatchedAttempts();
    if (keyChanged || !sameLibraries(next.libraries, old.libraries)) void scanner.scan();
    return c.json({ restartRequired: next.port !== old.port });
  });

  // Low-confidence matches have a tmdb_id but are listed too, so they can be confirmed or corrected.
  const review = (r: { tmdb_id: number | null; title: string; match_error: string | null }) => {
    const lowConfidence = r.tmdb_id !== null && (r.match_error ?? "").startsWith(LOW_CONFIDENCE);
    return { lowConfidence, tmdbId: lowConfidence ? r.tmdb_id : null, matchedTitle: lowConfidence ? r.title : null };
  };
  r.get("/admin/api/unmatched", (c) =>
    c.json({
      shows: repo.listUnmatchedShows().map((s) => ({
        id: s.id,
        parsedTitle: s.parsed_title,
        year: s.year,
        category: s.category,
        error: s.match_error,
        fileCount: s.file_count,
        ...review(s),
      })),
      movies: repo.listUnmatchedMovies().map((m) => ({
        id: m.id,
        parsedTitle: m.parsed_title,
        year: m.year,
        category: m.category,
        error: m.match_error,
        ...review(m),
      })),
    }),
  );

  r.get("/admin/api/tmdb/search", async (c) => {
    const type = c.req.query("type");
    const q = (c.req.query("q") ?? "").trim();
    if (type !== "tv" && type !== "movie") throw new HttpError(400, "type must be tv or movie");
    if (!q) throw new HttpError(400, "q is required");
    const y = Number.parseInt(c.req.query("year") ?? "", 10);
    const year = Number.isNaN(y) ? null : y;
    try {
      return c.json(type === "tv" ? await deps.tmdb.searchTv(q, year) : await deps.tmdb.searchMovie(q, year));
    } catch (err) {
      throw new HttpError(503, err instanceof Error ? err.message : String(err));
    }
  });

  r.post("/admin/api/match", async (c) => {
    const { type, id, tmdbId } = await jsonBody(c);
    if (type !== "show" && type !== "movie") throw new HttpError(400, "type must be show or movie");
    if (!positiveInt(id) || !positiveInt(tmdbId)) throw new HttpError(400, "id and tmdbId must be positive integers");
    if (type === "show") {
      const show = repo.getShow(id);
      if (!show) throw new HttpError(404, "Show not found");
      // "show" is the item_type Scanner.overrideId accepts for shows
      repo.setOverride(show.parsed_key, "show", tmdbId);
      repo.updateShowMeta(id, { tmdb_id: null });
      repo.setShowMatchError(id, null, null);
      // otherwise episodes keep their old TMDB ids and season metadata is never refetched
      repo.resetShowEpisodesMatch(id);
    } else {
      const movie = repo.getItem(id);
      if (!movie || movie.type !== "movie" || movie.parsed_key === null) throw new HttpError(404, "Movie not found");
      repo.setOverride(movie.parsed_key, "movie", tmdbId);
      repo.updateItemMeta(id, { tmdb_id: null });
      repo.setItemMatchError(id, null, null);
    }
    void scanner.scan();
    return c.json({ ok: true }, 202);
  });

  // Mid-scan, scanner.scan() queues at most one rerun instead of starting a parallel scan.
  // A manual rescan always retries unmatched items, ignoring the 24 h back-off.
  r.post("/admin/api/scan", (c) => {
    repo.resetUnmatchedAttempts();
    void scanner.scan();
    return c.json({ status: scanner.status() }, 202);
  });

  r.get("/admin/api/log", (c) => {
    const n = Number.parseInt(c.req.query("n") ?? "200", 10);
    const count = Number.isNaN(n) || n < 1 ? 200 : Math.min(n, LOG_MAX);
    return c.json({ lines: deps.log.recent(count) });
  });

  r.post("/admin/api/quit", (c) => {
    // give the response time to flush before the process goes away
    setTimeout(() => deps.quit(), 50);
    return c.json({ ok: true });
  });

  return r;
}
