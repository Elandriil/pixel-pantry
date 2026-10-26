import type { Database } from "bun:sqlite";
import { Hono } from "hono";
import { basename } from "node:path";
import type { Track } from "../../library/probe";
import type { FileRow, ItemRow, ShowRow } from "../../library/repo";
import { imageUrl } from "../../metadata/images";
import { continueWatching, getPlayback } from "../../playback/progress";
import type {
  Card, Category, CategoryPage, ItemDetail, ItemSummary, Row, SeasonDetail, ShowDetail, ShowSummary, SubtitleTrack,
} from "../../types";
import type { Deps } from "../deps";
import { HttpError } from "../errors";

const CATEGORIES: readonly Category[] = ["tv", "anime", "movies"];
const RECENT_LIMIT = 30;
const CONTINUE_LIMIT = 30;

const parseJson = <T>(s: string | null, fallback: T): T => {
  if (!s) return fallback;
  try {
    return (JSON.parse(s) as T) ?? fallback;
  } catch {
    return fallback;
  }
};

const byTitle = (a: string, b: string) => a.localeCompare(b, undefined, { sensitivity: "base" });

/** Regular seasons in order, then specials (season 0), then by episode number. */
function episodeOrder(a: ItemRow, b: ItemRow): number {
  const sa = a.season_number ?? 0;
  const sb = b.season_number ?? 0;
  return (sa === 0 ? 1 : 0) - (sb === 0 ? 1 : 0) || sa - sb || (a.episode_number ?? 0) - (b.episode_number ?? 0);
}

/** Live files of an item, newest first (added_at, then id). */
export function liveFiles(files: FileRow[]): FileRow[] {
  return files
    .filter((f) => f.deleted_at === null)
    .sort((a, b) => (b.added_at ?? 0) - (a.added_at ?? 0) || b.id - a.id);
}

export function toItemSummary(deps: Deps, row: ItemRow, show?: ShowRow | null): ItemSummary {
  const s = show !== undefined ? show : row.show_id !== null ? deps.repo.getShow(row.show_id) : null;
  const p = getPlayback(deps.db, row.id);
  const newest = liveFiles(deps.repo.filesForItem(row.id))[0];
  return {
    id: row.id,
    type: row.type,
    category: row.category as Category,
    title: row.title,
    showId: row.show_id,
    showTitle: s?.title ?? null,
    season: row.season_number,
    episode: row.episode_number,
    episodeEnd: row.episode_end,
    year: row.year,
    runtime: row.runtime,
    overview: row.overview,
    poster: imageUrl("w500", s ? s.poster_path : row.poster_path),
    backdrop: imageUrl("w1280", row.backdrop_path ?? s?.backdrop_path ?? null),
    still: imageUrl("w500", row.still_path),
    airDate: row.air_date,
    progress: p ? { position: p.position, duration: p.duration ?? 0, watched: p.watched === 1 } : null,
    addedAt: newest?.added_at ?? row.created_at ?? 0,
  };
}

/** `addedAt` is the newest live file of the show, so a show with new episodes sorts as recent. */
export function toShowSummary(db: Database, row: ShowRow): ShowSummary {
  const stats = db
    .query(
      `SELECT COUNT(*) AS episodeCount,
              COALESCE(SUM(CASE WHEN COALESCE(p.watched, 0) = 0 THEN 1 ELSE 0 END), 0) AS unwatchedCount,
              (SELECT MAX(f.added_at) FROM files f JOIN items fi ON fi.id = f.item_id
               WHERE fi.show_id = ?1 AND fi.deleted_at IS NULL AND f.deleted_at IS NULL) AS addedAt
       FROM items i LEFT JOIN playback p ON p.item_id = i.id
       WHERE i.show_id = ?1 AND i.type = 'episode' AND i.deleted_at IS NULL`,
    )
    .get(row.id) as { episodeCount: number; unwatchedCount: number; addedAt: number | null };
  return {
    id: row.id,
    category: row.category as Category,
    title: row.title,
    year: row.year,
    overview: row.overview,
    poster: imageUrl("w500", row.poster_path),
    backdrop: imageUrl("w1280", row.backdrop_path),
    rating: row.rating,
    genres: parseJson<string[]>(row.genres, []),
    episodeCount: stats.episodeCount,
    unwatchedCount: stats.unwatchedCount,
    matched: row.tmdb_id !== null,
    addedAt: stats.addedAt ?? row.created_at ?? 0,
  };
}

function parseId(raw: string): number {
  const id = Number(raw);
  if (!Number.isSafeInteger(id) || id <= 0) throw new HttpError(400, "Invalid id");
  return id;
}

/** A show that exists and is not hidden, or 404. */
function liveShow(deps: Deps, id: number): ShowRow {
  const show = deps.repo.getShow(id);
  if (!show || show.deleted_at !== null) throw new HttpError(404, "Show not found");
  return show;
}

function continueRow(deps: Deps, category: Category | null): Row {
  const cards: Card[] = [];
  for (const e of continueWatching(deps.db, category, CONTINUE_LIMIT)) {
    const item = deps.repo.getItem(e.itemId);
    if (item) cards.push({ kind: "item", item: toItemSummary(deps, item), reason: e.reason });
  }
  return { key: "continue", title: "Continue watching", cards };
}

/** Show cards for the category's shows plus item cards for its movies. */
function libraryCards(deps: Deps, categories: readonly Category[]): { card: Card; title: string; addedAt: number }[] {
  const out: { card: Card; title: string; addedAt: number }[] = [];
  for (const category of categories) {
    for (const row of deps.repo.listShows(category)) {
      const show = toShowSummary(deps.db, row);
      out.push({ card: { kind: "show", show }, title: show.title, addedAt: show.addedAt });
    }
    for (const row of deps.repo.listMovies(category)) {
      const item = toItemSummary(deps, row, null);
      out.push({ card: { kind: "item", item }, title: item.title, addedAt: item.addedAt });
    }
  }
  return out;
}

function recentRow(entries: ReturnType<typeof libraryCards>): Row {
  const cards = [...entries]
    .sort((a, b) => b.addedAt - a.addedAt || byTitle(a.title, b.title))
    .slice(0, RECENT_LIMIT)
    .map((e) => e.card);
  return { key: "recent", title: "Recently added", cards };
}

function allRow(entries: ReturnType<typeof libraryCards>): Row {
  const cards = [...entries].sort((a, b) => byTitle(a.title, b.title)).map((e) => e.card);
  return { key: "all", title: "All", cards };
}

function showDetail(deps: Deps, show: ShowRow): ShowDetail {
  const episodes = deps.repo.listEpisodes(show.id).sort(episodeOrder);
  const seasonRows = new Map(deps.repo.listSeasons(show.id).map((s) => [s.number, s]));
  const bySeason = new Map<number, ItemSummary[]>();
  for (const ep of episodes) {
    const n = ep.season_number ?? 0;
    if (!bySeason.has(n)) bySeason.set(n, []);
    bySeason.get(n)!.push(toItemSummary(deps, ep, show));
  }
  // Only seasons with at least one live episode; TMDB season rows for seasons we have no files for are skipped.
  const seasons: SeasonDetail[] = [...bySeason.entries()].map(([number, eps]) => {
    const row = seasonRows.get(number);
    return {
      number,
      name: number === 0 ? "Specials" : row?.name ?? `Season ${number}`,
      poster: imageUrl("w500", row?.poster_path ?? null),
      episodes: eps,
    };
  });

  const entry = continueWatching(deps.db, show.category as Category, Number.MAX_SAFE_INTEGER).find(
    (e) => deps.repo.getItem(e.itemId)?.show_id === show.id,
  );
  const all = seasons.flatMap((s) => s.episodes);
  const resume =
    (entry && all.find((e) => e.id === entry.itemId)) || all.find((e) => !e.progress?.watched) || null;
  return { show: toShowSummary(deps.db, show), seasons, resume };
}

function itemDetail(deps: Deps, item: ItemRow): ItemDetail {
  const show = item.show_id !== null ? liveShow(deps, item.show_id) : null;
  const file = liveFiles(deps.repo.filesForItem(item.id))[0];
  if (!file) throw new HttpError(404, "Item has no file");

  let next: ItemSummary | null = null;
  let prev: ItemSummary | null = null;
  if (show) {
    const eps = deps.repo.listEpisodes(show.id).sort(episodeOrder);
    const i = eps.findIndex((e) => e.id === item.id);
    if (i > 0) prev = toItemSummary(deps, eps[i - 1]!, show);
    if (i >= 0 && i < eps.length - 1) next = toItemSummary(deps, eps[i + 1]!, show);
  }

  const subtitles: SubtitleTrack[] = parseJson<Track[]>(file.subtitle_tracks, []).map((t) => ({
    id: `embedded:${t.index}`,
    label: t.title ?? t.language ?? `Track ${t.index}`,
    lang: t.language,
    url: null,
    format: "embedded",
  }));
  const p = getPlayback(deps.db, item.id);
  return {
    item: toItemSummary(deps, item, show),
    streamUrl: `/api/items/${item.id}/stream`,
    fileName: basename(file.rel_path),
    fileSize: file.size ?? 0,
    audio: parseJson<Track[]>(file.audio_tracks, []),
    subtitles,
    next,
    prev,
    subtitleChoice: p?.subtitle_choice ?? null,
    audioChoice: p?.audio_choice ?? null,
  };
}

export function catalogRoutes(deps: Deps): Hono {
  const r = new Hono();

  r.get("/home", (c) => {
    const page: CategoryPage = {
      category: "home",
      rows: [continueRow(deps, null), recentRow(libraryCards(deps, CATEGORIES))],
    };
    return c.json(page);
  });

  r.get("/categories/:category", (c) => {
    const category = c.req.param("category") as Category;
    if (!CATEGORIES.includes(category)) throw new HttpError(404, "Unknown category");
    const entries = libraryCards(deps, [category]);
    const page: CategoryPage = { category, rows: [continueRow(deps, category), recentRow(entries), allRow(entries)] };
    return c.json(page);
  });

  r.get("/shows/:id", (c) => c.json(showDetail(deps, liveShow(deps, parseId(c.req.param("id"))))));

  r.get("/items/:id", (c) => {
    const item = deps.repo.getItem(parseId(c.req.param("id")));
    if (!item || item.deleted_at !== null) throw new HttpError(404, "Item not found");
    return c.json(itemDetail(deps, item));
  });

  r.get("/search", (c) => {
    const q = (c.req.query("q") ?? "").trim();
    if (!q) return c.json({ shows: [], movies: [] });
    const found = deps.repo.searchTitles(q);
    return c.json({
      shows: found.shows.map((s) => toShowSummary(deps.db, s)),
      movies: found.movies.map((m) => toItemSummary(deps, m, null)),
    });
  });

  return r;
}
