import { afterAll } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import type { ProbeResult } from "../src/library/probe";
import type { Repo } from "../src/library/repo";
import type { TmdbMovieDetails, TmdbSearchResult, TmdbSeason, TmdbTvDetails } from "../src/metadata/tmdb";
import type { Category } from "../src/types";
import { CATEGORIES, type Library } from "../src/config";

const created: string[] = [];

afterAll(() => {
  for (const d of created.splice(0)) rmSync(d, { recursive: true, force: true });
});

export function tmpDir(): string {
  const d = mkdtempSync(join(tmpdir(), "pantry-test-"));
  created.push(d);
  return d;
}

/** The pre-library layout: `tv`, `anime` and `movies` under `base`, with the category as library id. */
export function legacyLibraries(base: string): Library[] {
  return CATEGORIES.map((c) => ({ id: c, path: join(base, c), category: c }));
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

const SEED_NOW = 1_600_000_000_000;

function attachFile(repo: Repo, itemId: number, relPath: string, category: string): void {
  const fileId = repo.upsertFile({
    rel_path: relPath, category, size: 10, mtime: SEED_NOW, parsed: null, item_id: null, duration: 1200,
    audio_tracks: null, subtitle_tracks: null, probe_error: null, added_at: SEED_NOW, deleted_at: null,
  });
  repo.setFileItem(fileId, itemId);
}

/**
 * Seeds a show with one live file per episode. `seasons[i]` lists the episode numbers of season `i + 1`.
 * Returns `itemIds[i][j]` = id of season `i + 1`, episode `seasons[i][j]`.
 */
export function seedShow(
  repo: Repo,
  opts: { title: string; category: Category; seasons: number[][] },
): { showId: number; itemIds: number[][] } {
  const key = `${opts.category}:${opts.title.toLowerCase()}`;
  const showId = repo.insertShow(
    { category: opts.category, parsed_key: key, parsed_title: opts.title, title: opts.title }, SEED_NOW,
  );
  const itemIds = opts.seasons.map((episodes, i) =>
    episodes.map((ep) => {
      const season = i + 1;
      const itemId = repo.insertItem(
        {
          type: "episode", category: opts.category, show_id: showId, season_number: season, episode_number: ep,
          parsed_title: opts.title, title: `${opts.title} S${season}E${ep}`,
        },
        SEED_NOW,
      );
      attachFile(repo, itemId, `${opts.category}/${opts.title}/S${season}E${ep}.mkv`, opts.category);
      return itemId;
    }),
  );
  return { showId, itemIds };
}

/** Seeds a movie (category `movies`) with one live file. Returns the item id. */
export function seedMovie(repo: Repo, title: string): number {
  const itemId = repo.insertItem(
    { type: "movie", category: "movies", parsed_key: `movie:${title.toLowerCase()}`, parsed_title: title, title },
    SEED_NOW,
  );
  attachFile(repo, itemId, `movies/${title}.mkv`, "movies");
  return itemId;
}
