import { normalizeTitle } from "../library/parse";
import type { TmdbSearchResult } from "./tmdb";

export interface Match {
  result: TmdbSearchResult;
  /** `low`: no title matched; TMDB's top result was taken because its year fits. */
  confidence: "high" | "low";
}

/** 3 for equal titles, 2 when the shorter is a whole-word prefix of the longer ("Heat" is not a prefix of "Heated"). */
function titleScore(candidate: string, query: string): number {
  if (!candidate || !query) return 0;
  if (candidate === query) return 3;
  const [short, long] = candidate.length < query.length ? [candidate, query] : [query, candidate];
  return long.startsWith(short) && long[short.length] === " " ? 2 : 0;
}

function yearScore(a: number | null, b: number | null): number {
  if (a === null || b === null) return 0;
  const d = Math.abs(a - b);
  return d === 0 ? 2 : d === 1 ? 1 : 0;
}

const yearFits = (resultYear: number | null, year: number | null): boolean =>
  year === null || (resultYear !== null && Math.abs(resultYear - year) <= 1);

export function pickMatch(results: TmdbSearchResult[], title: string, year: number | null): Match | null {
  const query = normalizeTitle(title);
  let best: TmdbSearchResult | null = null;
  let bestScore = 0;
  for (const r of results) {
    // the better of the localized and the original title, e.g. romaji anime names match original_name
    const t = Math.max(
      titleScore(normalizeTitle(r.title), query),
      r.originalTitle ? titleScore(normalizeTitle(r.originalTitle), query) : 0,
    );
    if (t === 0) continue; // a year match alone is not enough
    const score = t + yearScore(r.year, year);
    if (score < 2) continue;
    if (score > bestScore || (score === bestScore && best && r.popularity > best.popularity)) {
      best = r;
      bestScore = score;
    }
  }
  if (best) return { result: best, confidence: "high" };
  // TMDB's own ranking found something under a title we cannot compare (e.g. a translated name):
  // take its top result when the year does not contradict it, flagged for review.
  const top = results[0];
  return top && yearFits(top.year, year) ? { result: top, confidence: "low" } : null;
}
