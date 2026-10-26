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

const CATEGORIES: readonly string[] = ["tv", "anime", "movies"];
const SEASON_FOLDER = [/^season\s*(\d{1,2})$/i, /^S(\d{1,2})$/i];

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

export function parseVideoPath(relPath: string): ParsedVideo | null {
  const parts = relPath.split("/").filter(Boolean);
  const category = parts[0] as Category;
  if (parts.length < 2 || !CATEGORIES.includes(category)) return null;
  const kind = category === "movies" ? "movie" : "episode";
  const base = parts[parts.length - 1]!;
  const folders = parts.slice(1, -1);
  const stem = base.slice(0, base.length - posix.extname(base).length);

  let lib: ReturnType<typeof filenameParse> | null = null;
  try {
    lib = filenameParse(stem, kind === "episode");
  } catch {
    lib = null;
  }
  const libAny = lib as { title?: string; year?: string | number; seasons?: number[]; episodeNumbers?: number[] } | null;

  let episodes = kind === "episode" ? [...(libAny?.episodeNumbers ?? [])] : [];
  let season: number | null = libAny?.seasons?.[0] ?? null;
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
  let title = (libAny?.title ?? "").trim();
  if (!title && titleFolder) title = cleanFolderTitle(titleFolder);
  if (!title) {
    const head = markerIndex > 0 ? stem.slice(0, markerIndex) : stem;
    title = head.replace(/\[[^\]]*\]|\([^)]*\)/g, " ").replace(/[._]/g, " ").replace(/[\s-]+$/, "").replace(/\s+/g, " ").trim();
  }
  if (!title) title = folders[folders.length - 1] ?? stem;

  let year: number | null = libAny?.year ? Number(libAny.year) || null : null;
  if (year === null && titleFolder) year = folderYear(titleFolder);

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
