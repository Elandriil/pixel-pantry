import { afterAll } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import type { ProbeResult } from "../src/library/probe";
import type { TmdbMovieDetails, TmdbSearchResult, TmdbSeason, TmdbTvDetails } from "../src/metadata/tmdb";

const created: string[] = [];

afterAll(() => {
  for (const d of created.splice(0)) rmSync(d, { recursive: true, force: true });
});

export function tmpDir(): string {
  const d = mkdtempSync(join(tmpdir(), "pantry-test-"));
  created.push(d);
  return d;
}

export function touch(path: string, bytes = 0): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, new Uint8Array(bytes));
}

export function fakeTmdb() {
  const calls = { searchTv: 0, searchMovie: 0, tvDetails: 0, tvSeason: 0, movieDetails: 0 };
  const need = <T>(v: T | undefined, what: string): T => {
    if (v === undefined) throw new Error(`TMDB 404: ${what}`);
    return v;
  };
  return {
    calls,
    tvSearchResults: [] as TmdbSearchResult[],
    movieSearchResults: [] as TmdbSearchResult[],
    tvDetailsById: {} as Record<number, TmdbTvDetails>,
    /** keyed `${tvId}:${seasonNumber}` */
    seasonsByIdAndNumber: {} as Record<string, TmdbSeason>,
    movieDetailsById: {} as Record<number, TmdbMovieDetails>,
    async searchTv(_q: string, _y?: number | null): Promise<TmdbSearchResult[]> {
      calls.searchTv++;
      return this.tvSearchResults;
    },
    async searchMovie(_q: string, _y?: number | null): Promise<TmdbSearchResult[]> {
      calls.searchMovie++;
      return this.movieSearchResults;
    },
    async tvDetails(id: number): Promise<TmdbTvDetails> {
      calls.tvDetails++;
      return need(this.tvDetailsById[id], `tv ${id}`);
    },
    async tvSeason(id: number, season: number): Promise<TmdbSeason> {
      calls.tvSeason++;
      return need(this.seasonsByIdAndNumber[`${id}:${season}`], `tv ${id} season ${season}`);
    },
    async movieDetails(id: number): Promise<TmdbMovieDetails> {
      calls.movieDetails++;
      return need(this.movieDetailsById[id], `movie ${id}`);
    },
  };
}

/** Probe stub: records each path; paths in `failOn` reject with the mapped message. */
export function fakeProbe() {
  const calls: string[] = [];
  const failOn = new Map<string, string>();
  const probe = async (absPath: string): Promise<ProbeResult> => {
    calls.push(absPath);
    const msg = failOn.get(absPath);
    if (msg !== undefined) throw new Error(msg);
    return { duration: 1200, audio: [], subtitles: [], video: null };
  };
  return { probe, calls, failOn };
}
