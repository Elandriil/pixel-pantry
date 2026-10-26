import { Hono, type MiddlewareHandler } from "hono";
import { networkInterfaces } from "node:os";
import type { Config } from "../../config";
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

/** Only the socket address counts: proxies' X-Forwarded-For is never trusted. */
const loopbackOnly: MiddlewareHandler<Env> = async (c, next) => {
  const addr = c.env?.remoteAddr ?? c.env?.server?.requestIP(c.req.raw)?.address;
  if (!addr || !LOOPBACK.has(addr)) return c.json({ error: "admin is localhost only" }, 403);
  await next();
};

function lanAddresses(): string[] {
  const out: string[] = [];
  for (const list of Object.values(networkInterfaces())) {
    for (const a of list ?? []) if (a.family === "IPv4" && !a.internal) out.push(a.address);
  }
  return out;
}

const mask = (v: string) => (v ? MASK : "");

function maskedConfig(c: Config): Config {
  return {
    mediaRoot: c.mediaRoot,
    port: c.port,
    tmdbApiKey: mask(c.tmdbApiKey),
    opensubtitles: {
      apiKey: mask(c.opensubtitles.apiKey),
      username: c.opensubtitles.username,
      password: mask(c.opensubtitles.password),
    },
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
  return {
    mediaRoot: str(body.mediaRoot, "mediaRoot")?.trim() ?? old.mediaRoot,
    port: (port as number | undefined) ?? old.port,
    tmdbApiKey: secret(body.tmdbApiKey, "tmdbApiKey", old.tmdbApiKey),
    opensubtitles: {
      apiKey: secret(o.apiKey, "opensubtitles.apiKey", old.opensubtitles.apiKey),
      username: str(o.username, "opensubtitles.username") ?? old.opensubtitles.username,
      password: secret(o.password, "opensubtitles.password", old.opensubtitles.password),
    },
  };
}

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
      mediaRoot: cfg.mediaRoot,
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
    if (next.mediaRoot !== old.mediaRoot) void scanner.scan();
    return c.json({ restartRequired: next.port !== old.port });
  });

  r.get("/admin/api/unmatched", (c) =>
    c.json({
      shows: repo.listUnmatchedShows().map((s) => ({
        id: s.id,
        parsedTitle: s.parsed_title,
        year: s.year,
        category: s.category,
        error: s.match_error,
        fileCount: s.file_count,
      })),
      movies: repo.listUnmatchedMovies().map((m) => ({
        id: m.id,
        parsedTitle: m.parsed_title,
        year: m.year,
        category: m.category,
        error: m.match_error,
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
  r.post("/admin/api/scan", (c) => {
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
