import type { FetchFn } from "../types";

const BASE = "https://api.themoviedb.org";
const DEFAULT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

export type TmdbSearchResult = { id: number; title: string; year: number | null; popularity: number };

export type TmdbTvDetails = {
  id: number;
  name: string;
  first_air_date: string;
  overview: string;
  poster_path: string | null;
  backdrop_path: string | null;
  vote_average: number;
  genres: { name: string }[];
  seasons: { season_number: number; name: string; episode_count: number; poster_path: string | null }[];
};

export type TmdbSeason = {
  season_number: number;
  name: string;
  poster_path: string | null;
  episodes: {
    id: number;
    episode_number: number;
    name: string;
    overview: string;
    still_path: string | null;
    air_date: string | null;
    runtime: number | null;
  }[];
};

export type TmdbMovieDetails = {
  id: number;
  title: string;
  release_date: string;
  overview: string;
  poster_path: string | null;
  backdrop_path: string | null;
  vote_average: number;
  genres: { name: string }[];
  runtime: number | null;
};

export type TmdbCache = { get(key: string, maxAgeMs: number): string | null; set(key: string, json: string): void };

type RawResult = {
  id: number;
  name?: string;
  title?: string;
  first_air_date?: string;
  release_date?: string;
  popularity?: number;
};

export class TmdbClient {
  private readonly apiKey: string;
  private readonly fetchFn: FetchFn;
  private readonly cache: TmdbCache;
  private readonly maxAgeMs: number;

  constructor(opts: { apiKey: string; fetchFn: FetchFn; cache: TmdbCache; cacheMaxAgeMs?: number }) {
    this.apiKey = opts.apiKey;
    this.fetchFn = opts.fetchFn;
    this.cache = opts.cache;
    this.maxAgeMs = opts.cacheMaxAgeMs ?? DEFAULT_MAX_AGE_MS;
  }

  searchTv(query: string, year?: number | null): Promise<TmdbSearchResult[]> {
    return this.search("tv", query, "first_air_date_year", year);
  }

  searchMovie(query: string, year?: number | null): Promise<TmdbSearchResult[]> {
    return this.search("movie", query, "year", year);
  }

  tvDetails(id: number): Promise<TmdbTvDetails> {
    return this.get(`/3/tv/${id}`);
  }

  tvSeason(id: number, season: number): Promise<TmdbSeason> {
    return this.get(`/3/tv/${id}/season/${season}`);
  }

  movieDetails(id: number): Promise<TmdbMovieDetails> {
    return this.get(`/3/movie/${id}`);
  }

  private async search(
    kind: "tv" | "movie",
    query: string,
    yearParam: string,
    year?: number | null,
  ): Promise<TmdbSearchResult[]> {
    const params = new URLSearchParams({ query });
    if (year != null) params.set(yearParam, String(year));
    const data = await this.get<{ results?: RawResult[] }>(`/3/search/${kind}?${params}`);
    return (data.results ?? []).map((r) => {
      const date = (kind === "tv" ? r.first_air_date : r.release_date) ?? "";
      const y = date ? Number.parseInt(date.slice(0, 4), 10) : Number.NaN;
      return {
        id: r.id,
        title: (kind === "tv" ? r.name : r.title) ?? "",
        year: Number.isNaN(y) ? null : y,
        popularity: r.popularity ?? 0,
      };
    });
  }

  private async get<T>(path: string): Promise<T> {
    if (!this.apiKey) throw new Error("TMDB API key not set");
    const cached = this.cache.get(path, this.maxAgeMs);
    if (cached !== null) return JSON.parse(cached) as T;

    const bearer = this.apiKey.startsWith("eyJ");
    const url = bearer
      ? `${BASE}${path}`
      : `${BASE}${path}${path.includes("?") ? "&" : "?"}api_key=${encodeURIComponent(this.apiKey)}`;
    const res = await this.fetchFn(url, {
      headers: bearer ? { Authorization: `Bearer ${this.apiKey}`, Accept: "application/json" } : { Accept: "application/json" },
    });
    const body = await res.text();
    if (!res.ok) throw new Error(`TMDB ${res.status}: ${body}`);
    this.cache.set(path, body);
    return JSON.parse(body) as T;
  }
}
