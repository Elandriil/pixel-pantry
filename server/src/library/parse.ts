import { posix } from "node:path";
import { filenameParse } from "@ctrl/video-filename-parser";
import type { Category } from "../types";

export interface ParsedVideo {
  category: Category;
  kind: "episode" | "movie";
  title: string;
  year: number | null;
  season: number | null;
  episodes: number[];
  absolute: boolean;
  parsedKey: string;
}

const SEASON_FOLDER = [/^season\s*(\d{1,2})$/i, /^S(\d{1,2})$/i];
/**
 * Bonus material named the Plex way: `Show.S01EX1.Interview.mkv` (EX = extra, also `S06EXA`), `Extra.Trailer.mkv`,
 * `Movie-trailer.mkv`, `...-featurette.mkv`. Never an episode: `S01EX1` would otherwise land on S01E01 and play
 * instead of the real episode.
 */
const EXTRA_MARKER = /\bS\d{1,2}EX[0-9A-Z]{1,3}\b/i;
const EXTRA_NAMES = [/^extras?[ ._-]/i, /[ ._-](trailer|featurette|behind[ ._-]?the[ ._-]?scenes|deleted[ ._-]?scenes?|extras?|sample)$/i];
/** An explicit episode marker; without one, a 4-digit year (`Documentary 2001`) is not season 20 episode 1. */
const EXPLICIT_EPISODE = /\bS\d{1,2}\s*E\d{1,3}\b|\b\d{1,2}x\d{1,3}\b/i;
/** `Extras S01E01` is an episode of the show "Extras"; `Extra.Trailer` is not an episode of anything. */
export const isExtra = (stem: string): boolean =>
  EXTRA_MARKER.test(stem) || (!EXPLICIT_EPISODE.test(stem) && EXTRA_NAMES.some((re) => re.test(stem)));
/** Release tags: everything from the first one on is not part of a folder's title. */
const RELEASE_TAGS =
  /[\s._(\[-]+(?:\d{3,4}p|2160p|4k|uhd|bluray|blu-ray|brrip|bdrip|webrip|web-dl|webdl|hdtv|dvdrip|dvdscr|hdrip|s\d{2}(?:e\d{2,3})?|season[\s._-]*\d{1,2}|x264|x265|h\.?264|h\.?265|hevc|avc|xvid|complete(?:[\s._-]+series)?|proper|repack|remux|multi|dual[\s._-]?audio)\b.*$/i;

export function normalizeTitle(s: string): string {
  return s
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

function seasonFromFolder(name: string): number | null {
  for (const re of SEASON_FOLDER) {
    const m = re.exec(name);
    if (m) return Number(m[1]);
  }
  return null;
}

function cleanFolderTitle(name: string): string {
  return name
    .replace(RELEASE_TAGS, "")
    .replace(/\s*\(?\b(?:19|20)\d{2}\b\)?\s*$/, "")
    .replace(/[._]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function folderYear(name: string): number | null {
  const m = /\((\d{4})\)/.exec(name) ?? /\b((?:19|20)\d{2})\b/.exec(name);
  return m ? Number(m[1]) : null;
}

interface EpisodeMatch {
  season: number | null;
  episodes: number[];
  index: number;
}

function range(a: number, b?: number): number[] {
  if (b === undefined || b < a || b - a > 50) return [a];
  return Array.from({ length: b - a + 1 }, (_, i) => a + i);
}

function fallbackEpisode(stem: string): EpisodeMatch | null {
  const s = stem.replace(/\[[^\]]*\]|\([^)]*\)/g, " ").replace(/\s+/g, " ").trim();
  let m: RegExpExecArray | null;
  if ((m = /\bS(\d{1,2})E(\d{1,3})(?:-E?(\d{1,3}))?\b/i.exec(s)))
    return { season: Number(m[1]), episodes: range(Number(m[2]), m[3] ? Number(m[3]) : undefined), index: m.index };
  if ((m = /\b(\d{1,2})x(\d{1,3})\b/i.exec(s)))
    return { season: Number(m[1]), episodes: [Number(m[2])], index: m.index };
  if ((m = /\bepisode\s*(\d{1,3})\b/i.exec(s))) return { season: null, episodes: [Number(m[1])], index: m.index };
  if ((m = /\s-\s(\d{1,3})(?:v\d+)?(?:\s|$)/.exec(s))) return { season: null, episodes: [Number(m[1])], index: m.index };
  if ((m = /\bE(\d{1,3})\b/i.exec(s))) return { season: null, episodes: [Number(m[1])], index: m.index };
  if ((m = /^(\d{1,3})\b/.exec(s))) return { season: null, episodes: [Number(m[1])], index: 0 };
  return null;
}

/** A season marker at the end of an anime title: `S2`, `2nd Season`, `Season 2`, `Part 2`. */
const SEASON_MARKER = String.raw`(?:S(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)\s+Season|Season\s+(\d{1,2})|Part\s+(\d{1,2}))`;
const TITLE_SEASON = new RegExp(String.raw`^(.+?)\s+${SEASON_MARKER}$`, "i");
const RELEASE_SEASON = new RegExp(String.raw`^(.+?)\s+${SEASON_MARKER}\s+-\s+(\d{1,4})(?:v\d+)?(?:\s|$)`, "i");
const RELEASE_ABSOLUTE_4 = /^(.+?)\s+-\s+(\d{4})(?:v\d+)?(?:\s|$)/;

const markerSeason = (m: RegExpExecArray): number => Number(m[2] ?? m[3] ?? m[4] ?? m[5]);

/** `Title S2` -> { title: "Title", season: 2 }, or null without a trailing season marker. */
function splitSeasonMarker(title: string): { title: string; season: number } | null {
  const m = TITLE_SEASON.exec(title);
  return m ? { title: m[1]!.trim(), season: markerSeason(m) } : null;
}

/**
 * Anime release names the generic parser gets wrong: `Title S2 - 03` / `Title 2nd Season - 03` / `Title Part 2 - 03`
 * (season marker, then the episode) and `Title - 1100` (a 4-digit absolute episode, not S11E00).
 */
function animeRelease(stem: string): { title: string; season: number | null; episodes: number[] } | null {
  const s = stem.replace(/\[[^\]]*\]|\([^)]*\)/g, " ").replace(/_/g, " ").replace(/\s+/g, " ").trim();
  let m = RELEASE_SEASON.exec(s);
  if (m) return { title: m[1]!.trim(), season: markerSeason(m), episodes: [Number(m[6])] };
  m = RELEASE_ABSOLUTE_4.exec(s);
  if (m) return { title: m[1]!.trim(), season: null, episodes: [Number(m[2])] };
  return null;
}

/** `relPath` is `<library id>/<path inside the library>`; the library's category decides how the name is read. */
export function parseVideoPath(relPath: string, category: Category): ParsedVideo | null {
  const parts = relPath.split("/").filter(Boolean);
  if (parts.length < 2) return null;
  const kind = category === "movies" ? "movie" : "episode";
  const base = parts[parts.length - 1]!;
  const folders = parts.slice(1, -1);
  const stem = base.slice(0, base.length - posix.extname(base).length);
  if (isExtra(stem)) return null;

  let lib: ReturnType<typeof filenameParse> | null = null;
  try {
    lib = filenameParse(stem, kind === "episode");
  } catch {
    lib = null;
  }
  const libAny = lib as { title?: string; year?: string | number; seasons?: number[]; episodeNumbers?: number[] } | null;
  if (libAny && kind === "episode" && !EXPLICIT_EPISODE.test(stem) && libAny.seasons?.[0] !== undefined && libAny.episodeNumbers?.[0] !== undefined) {
    // the generic parser reads `2001` as 20x01 when nothing else looks like an episode
    const asYear = `${libAny.seasons[0]}${String(libAny.episodeNumbers[0]).padStart(2, "0")}`;
    if (/^(?:19|20)\d{2}$/.test(asYear) && new RegExp(`\\b${asYear}\\b`).test(stem)) {
      libAny.seasons = [];
      libAny.episodeNumbers = [];
      libAny.year ??= asYear;
    }
  }

  const anime = category === "anime" ? animeRelease(stem) : null;
  let episodes = anime ? anime.episodes : kind === "episode" ? [...(libAny?.episodeNumbers ?? [])] : [];
  let season: number | null = anime ? anime.season : libAny?.seasons?.[0] ?? null;
  let markerIndex = -1;
  if (kind === "episode" && episodes.length === 0) {
    const fb = fallbackEpisode(stem);
    if (fb) {
      episodes = fb.episodes;
      season ??= fb.season;
      markerIndex = fb.index;
    }
  }
  if (season === null) {
    for (const f of [...folders].reverse()) {
      season = seasonFromFolder(f);
      if (season !== null) break;
    }
  }
  if (kind === "movie") season = null;

  const titleFolder = [...folders].reverse().find((f) => seasonFromFolder(f) === null);
  let title = (anime?.title ?? libAny?.title ?? "").replace(/[._]/g, " ").replace(/\s+/g, " ").trim();
  if (!title && titleFolder) title = cleanFolderTitle(titleFolder);
  if (!title) {
    const head = markerIndex > 0 ? stem.slice(0, markerIndex) : stem;
    title = head.replace(/\[[^\]]*\]|\([^)]*\)/g, " ").replace(/[._]/g, " ").replace(/[\s-]+$/, "").replace(/\s+/g, " ").trim();
  }
  if (!title) title = folders[folders.length - 1] ?? stem;

  let year: number | null = libAny?.year ? Number(libAny.year) || null : null;
  if (year === null && titleFolder) year = folderYear(titleFolder);

  // `Ape maga[1970] 01- ...`: a bracketed year the generic parser leaves in the title (it only reads `(1970)`)
  const by = /^(.*\S)\s*[[(]((?:19|20)\d{2})[\])]$/.exec(title);
  if (by) {
    title = by[1]!;
    year ??= Number(by[2]);
  }
  // `Doctor Who 2005`: a trailing year that is the parsed year (or, for episodes, the only year) is not part of
  // the title, so `Doctor.Who.2005.S01E01` and `Doctor Who (2005)/...` group as one show. `Blade Runner 2049 (2017)` keeps it.
  const ty = /^(.*\S)\s+((?:19|20)\d{2})$/.exec(title);
  if (ty && (year === Number(ty[2]) || (year === null && kind === "episode"))) {
    title = ty[1]!;
    year = Number(ty[2]);
  }
  if (category === "anime" && season === null) {
    const split = splitSeasonMarker(title);
    if (split) {
      title = split.title;
      season = split.season;
    }
  }

  const absolute = category === "anime" && season === null && episodes.length > 0;
  return {
    category,
    kind,
    title,
    year,
    season,
    episodes,
    absolute,
    parsedKey: `${category}:${normalizeTitle(title)}:${year ?? ""}`,
  };
}
