import { normalizeTitle } from "../library/parse";
import type { TmdbSearchResult } from "./tmdb";

function titleScore(candidate: string, query: string): number {
  if (!candidate || !query) return 0;
  if (candidate === query) return 3;
  if (candidate.startsWith(query) || query.startsWith(candidate)) return 2;
  return 0;
}

function yearScore(a: number | null, b: number | null): number {
  if (a === null || b === null) return 0;
  const d = Math.abs(a - b);
  return d === 0 ? 2 : d === 1 ? 1 : 0;
}

export function pickMatch(
  results: TmdbSearchResult[],
  title: string,
  year: number | null,
): TmdbSearchResult | null {
  const query = normalizeTitle(title);
  let best: TmdbSearchResult | null = null;
  let bestScore = 0;
  for (const r of results) {
    const t = titleScore(normalizeTitle(r.title), query);
    if (t === 0) continue; // a year match alone is not enough
    const score = t + yearScore(r.year, year);
    if (score < 2) continue;
    if (score > bestScore || (score === bestScore && best && r.popularity > best.popularity)) {
      best = r;
      bestScore = score;
    }
  }
  return best;
}
