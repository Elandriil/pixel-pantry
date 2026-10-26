import { existsSync } from "node:fs";
import { join } from "node:path";
import type { Logger } from "../log";
import { absoluteToSeasonEpisode } from "../metadata/anime";
import { pickMatch } from "../metadata/matcher";
import type { TmdbClient } from "../metadata/tmdb";
import { parseVideoPath, type ParsedVideo } from "./parse";
import type { ProbeResult } from "./probe";
import type { FileRow, ItemRow, Repo, ShowRow } from "./repo";
import { listVideoFiles } from "./walk";

export type TmdbLike = Pick<TmdbClient, "searchTv" | "searchMovie" | "tvDetails" | "tvSeason" | "movieDetails">;

export interface ScanStatus {
  state: "idle" | "scanning";
  startedAt: number | null;
  finishedAt: number | null;
  filesSeen: number;
  filesProcessed: number;
  phase: "walking" | "probing" | "matching" | "done";
  lastError: string | null;
}

export interface ScannerDeps {
  repo: Repo;
  tmdb: TmdbLike;
  probe: (absPath: string) => Promise<ProbeResult>;
  mediaRoot: () => string;
  log: Logger;
  now?: () => number;
}

interface LiveFile {
  file: FileRow;
  parsed: ParsedVideo;
}

const MATCH_RETRY_MS = 24 * 60 * 60 * 1000;

const message = (err: unknown): string => (err instanceof Error ? err.message : String(err));
/**
 * Only "TMDB knows nothing" (404) is worth backing off for 24 h. A missing key, auth failures, network
 * errors, timeouts and 5xx are transient or fixable by the user, so the next scan retries.
 */
const isNotFound = (err: unknown): boolean => message(err).startsWith("TMDB 404");
const yearOf = (date: string | null | undefined): number | null => {
  const y = date ? Number.parseInt(date.slice(0, 4), 10) : Number.NaN;
  return Number.isNaN(y) ? null : y;
};
const genreJson = (genres: { name: string }[]): string => JSON.stringify(genres.map((g) => g.name));

export class Scanner {
  private st: ScanStatus = {
    state: "idle",
    startedAt: null,
    finishedAt: null,
    filesSeen: 0,
    filesProcessed: 0,
    phase: "done",
    lastError: null,
  };
  /** The current run plus any rerun requested during it. */
  private inFlight: Promise<ScanStatus> | null = null;
  /** Set once a run has yielded and begun walking; calls made after that ask for a rerun. */
  private walkStarted = false;
  private rerunRequested = false;
  private finishedCbs: (() => void)[] = [];
  private readonly now: () => number;

  constructor(private readonly deps: ScannerDeps) {
    this.now = deps.now ?? Date.now;
  }

  status(): ScanStatus {
    return { ...this.st };
  }

  onFinished(cb: () => void): void {
    this.finishedCbs.push(cb);
  }

  /**
   * Starts a scan. Calls made before the running scan starts walking join it. Calls made once it is
   * walking (it may already have listed files that changed since, e.g. a delete) queue exactly one more
   * run after it; every caller's promise resolves when the last queued run finishes.
   */
  scan(): Promise<ScanStatus> {
    if (!this.inFlight) {
      this.inFlight = this.runUntilSettled().finally(() => {
        this.inFlight = null;
      });
    } else if (this.walkStarted) {
      this.rerunRequested = true;
    }
    return this.inFlight;
  }

  private async runUntilSettled(): Promise<ScanStatus> {
    let st: ScanStatus;
    do {
      this.rerunRequested = false;
      st = await this.run();
    } while (this.rerunRequested);
    return st;
  }

  private async run(): Promise<ScanStatus> {
    const { repo, log } = this.deps;
    this.st = {
      state: "scanning",
      startedAt: this.now(),
      finishedAt: null,
      filesSeen: 0,
      filesProcessed: 0,
      phase: "walking",
      lastError: null,
    };
    // Yield once so calls made in the same tick as the start join this run instead of queueing another.
    await null;
    this.walkStarted = true;
    try {
      const root = this.deps.mediaRoot();
      if (!root || !existsSync(root)) {
        this.st.lastError = "Media folder not set";
      } else {
        log.info("scan started", { root });
        await this.walkAndProbe(root);
        this.st.phase = "matching";
        await this.matchAll();
        repo.hideEmpty(this.now());
        log.info("scan finished", { filesSeen: this.st.filesSeen, filesProcessed: this.st.filesProcessed });
      }
    } catch (err) {
      this.st.lastError = message(err);
      log.error("scan failed", { error: this.st.lastError });
    }
    this.walkStarted = false;
    this.st.state = "idle";
    this.st.phase = "done";
    this.st.finishedAt = this.now();
    for (const cb of this.finishedCbs) {
      try {
        cb();
      } catch (err) {
        log.error("scan finished callback failed", { error: message(err) });
      }
    }
    return this.status();
  }

  // phase 1: walk, then probe new or changed files one at a time

  private async walkAndProbe(root: string): Promise<void> {
    const { repo, log } = this.deps;
    const entries = await listVideoFiles(root);
    this.st.filesSeen = entries.length;
    this.st.phase = "probing";
    for (const e of entries) {
      const existing = repo.getFileByPath(e.relPath);
      if (existing && existing.deleted_at === null && existing.size === e.size && existing.mtime === e.mtime) continue;
      const parsed = parseVideoPath(e.relPath);
      const id = repo.upsertFile({
        rel_path: e.relPath,
        category: e.category,
        size: e.size,
        mtime: e.mtime,
        parsed: parsed ? JSON.stringify(parsed) : null,
        item_id: null,
        duration: null,
        audio_tracks: null,
        subtitle_tracks: null,
        probe_error: null,
        added_at: this.now(),
        deleted_at: null,
      });
      try {
        repo.setFileProbe(id, await this.deps.probe(join(root, e.relPath)), null);
      } catch (err) {
        log.warn("probe failed", { path: e.relPath, error: message(err) });
        repo.setFileProbe(id, null, message(err));
      }
      this.st.filesProcessed++;
    }
    repo.markFilesDeletedExcept(
      entries.map((e) => e.relPath),
      this.now(),
    );
  }

  // phase 2: group live files by parsed key and attach them to shows/items

  private async matchAll(): Promise<void> {
    const groups = new Map<string, LiveFile[]>();
    for (const file of this.deps.repo.listLiveFiles()) {
      if (!file.parsed) continue;
      const parsed = JSON.parse(file.parsed) as ParsedVideo;
      const group = groups.get(parsed.parsedKey);
      if (group) group.push({ file, parsed });
      else groups.set(parsed.parsedKey, [{ file, parsed }]);
    }
    for (const [key, files] of groups) {
      if (files[0]!.parsed.kind === "movie") await this.syncMovie(key, files);
      else await this.syncShow(key, files);
    }
  }

  private shouldMatch(row: { tmdb_id: number | null; match_attempted_at: number | null }): boolean {
    if (row.tmdb_id !== null) return false;
    return row.match_attempted_at === null || this.now() - row.match_attempted_at >= MATCH_RETRY_MS;
  }

  // shows

  private async syncShow(key: string, files: LiveFile[]): Promise<void> {
    const { repo } = this.deps;
    const p = files[0]!.parsed;
    const showId =
      repo.getShowByKey(key)?.id ??
      repo.insertShow({ category: p.category, parsed_key: key, parsed_title: p.title, title: p.title, year: p.year }, this.now());
    let show = repo.getShow(showId)!;
    if (this.shouldMatch(show)) {
      await this.matchShow(show);
      show = repo.getShow(showId)!;
    }

    const counts =
      show.tmdb_id === null
        ? []
        : repo
            .listSeasons(showId)
            .filter((s) => s.number > 0)
            .map((s) => s.episode_count ?? 0);
    const itemsBySeason = new Map<number, Set<number>>();
    for (const { file, parsed } of files) {
      const item = this.attachEpisode(show, file, parsed, counts);
      const season = item.season_number ?? 1;
      if (!itemsBySeason.has(season)) itemsBySeason.set(season, new Set());
      itemsBySeason.get(season)!.add(item.id);
    }
    if (show.tmdb_id === null) return;
    for (const [season, ids] of itemsBySeason) {
      // the 24 h retry rule applies per episode, so episodes TMDB does not list don't refetch every scan
      if (![...ids].some((id) => this.shouldMatch(repo.getItem(id)!))) continue;
      await this.fetchSeasonMeta(show, show.tmdb_id, season, ids);
    }
  }

  /** Only overrides of the right kind apply: a movie override never feeds tvDetails and vice versa. */
  private overrideId(key: string, kind: "movie" | "show"): number | null {
    const o = this.deps.repo.getOverride(key);
    if (!o) return null;
    return (o.item_type === "movie") === (kind === "movie") ? o.tmdb_id : null;
  }

  private async matchShow(show: ShowRow): Promise<void> {
    const { repo, tmdb, log } = this.deps;
    try {
      let tmdbId = this.overrideId(show.parsed_key, "show");
      if (tmdbId === null) {
        const results = await tmdb.searchTv(show.parsed_title, show.year);
        tmdbId = pickMatch(results, show.parsed_title, show.year)?.id ?? null;
      }
      if (tmdbId === null) {
        repo.setShowMatchError(show.id, "No TMDB match", this.now());
        return;
      }
      const d = await tmdb.tvDetails(tmdbId);
      repo.updateShowMeta(show.id, {
        tmdb_id: d.id,
        title: d.name,
        year: yearOf(d.first_air_date),
        overview: d.overview,
        poster_path: d.poster_path,
        backdrop_path: d.backdrop_path,
        rating: d.vote_average,
        genres: genreJson(d.genres),
        first_air_date: d.first_air_date,
      });
      for (const s of d.seasons) {
        repo.upsertSeason(show.id, s.season_number, {
          name: s.name,
          poster_path: s.poster_path,
          episode_count: s.episode_count,
        });
      }
      repo.setShowMatchError(show.id, null, this.now());
    } catch (err) {
      log.warn("tmdb show match failed", { key: show.parsed_key, error: message(err) });
      repo.setShowMatchError(show.id, message(err), isNotFound(err) ? this.now() : show.match_attempted_at);
    }
  }

  private resolveEpisode(parsed: ParsedVideo, counts: number[]): { season: number; episode: number } {
    const first = parsed.episodes[0] ?? 1;
    if (parsed.absolute && counts.length > 0) {
      return absoluteToSeasonEpisode(first, counts) ?? { season: 1, episode: first };
    }
    return { season: parsed.season ?? 1, episode: first };
  }

  private attachEpisode(show: ShowRow, file: FileRow, parsed: ParsedVideo, counts: number[]): ItemRow {
    const { repo } = this.deps;
    const { season, episode } = this.resolveEpisode(parsed, counts);
    const eps = parsed.episodes;
    const end = eps.length > 1 ? episode + (eps[eps.length - 1]! - eps[0]!) : null;
    let item = repo.getEpisode(show.id, season, episode);
    if (!item) {
      const fallback = this.fallbackItemFor(show, file, parsed);
      if (fallback) {
        // the show matched after this file got a (1, abs) item: renumber it so its id (and progress) survive
        repo.moveEpisode(fallback.id, season, episode, end, `Episode ${episode}`);
        item = repo.getItem(fallback.id)!;
      }
    }
    if (!item) {
      const id = repo.insertItem(
        {
          type: "episode",
          category: show.category,
          show_id: show.id,
          season_number: season,
          episode_number: episode,
          episode_end: end,
          parsed_title: parsed.title,
          title: `Episode ${episode}`,
        },
        this.now(),
      );
      item = repo.getItem(id)!;
    } else if (end !== null && item.episode_end !== end) {
      repo.updateItemMeta(item.id, { episode_end: end });
    }
    if (file.item_id !== item.id) repo.setFileItem(file.id, item.id);
    return item;
  }

  /** The file's current item when it is an absolute-number fallback item, (1, abs), used by this file alone. */
  private fallbackItemFor(show: ShowRow, file: FileRow, parsed: ParsedVideo): ItemRow | null {
    const { repo } = this.deps;
    if (!parsed.absolute || file.item_id === null) return null;
    const cur = repo.getItem(file.item_id);
    if (!cur || cur.show_id !== show.id || cur.season_number !== 1 || cur.episode_number !== parsed.episodes[0]) return null;
    const others = repo.filesForItem(cur.id).filter((f) => f.deleted_at === null && f.id !== file.id);
    return others.length === 0 ? cur : null;
  }

  private async fetchSeasonMeta(show: ShowRow, tmdbId: number, season: number, itemIds: Set<number>): Promise<void> {
    const { repo, tmdb, log } = this.deps;
    let missing = "Not on TMDB";
    let backOff = true;
    try {
      const s = await tmdb.tvSeason(tmdbId, season);
      for (const ep of s.episodes) {
        const item = repo.getEpisode(show.id, season, ep.episode_number);
        if (!item) continue;
        repo.updateItemMeta(item.id, {
          tmdb_id: ep.id,
          title: ep.name || item.title,
          overview: ep.overview,
          still_path: ep.still_path,
          air_date: ep.air_date,
          runtime: ep.runtime,
        });
      }
    } catch (err) {
      missing = message(err);
      backOff = isNotFound(err);
      log.warn("tmdb season fetch failed", { key: show.parsed_key, season, error: missing });
    }
    for (const id of itemIds) {
      const item = repo.getItem(id)!;
      if (item.tmdb_id === null) repo.setItemMatchError(id, missing, backOff ? this.now() : item.match_attempted_at);
    }
  }

  // movies

  private async syncMovie(key: string, files: LiveFile[]): Promise<void> {
    const { repo } = this.deps;
    const p = files[0]!.parsed;
    const id =
      repo.getMovieByKey(key)?.id ??
      repo.insertItem(
        { type: "movie", category: p.category, parsed_key: key, parsed_title: p.title, title: p.title, year: p.year },
        this.now(),
      );
    const item = repo.getItem(id)!;
    if (this.shouldMatch(item)) await this.matchMovie(item, key);
    for (const { file } of files) {
      if (file.item_id !== id) repo.setFileItem(file.id, id);
    }
  }

  private async matchMovie(item: ItemRow, key: string): Promise<void> {
    const { repo, tmdb, log } = this.deps;
    try {
      let tmdbId = this.overrideId(key, "movie");
      if (tmdbId === null) {
        const results = await tmdb.searchMovie(item.parsed_title, item.year);
        tmdbId = pickMatch(results, item.parsed_title, item.year)?.id ?? null;
      }
      if (tmdbId === null) {
        repo.setItemMatchError(item.id, "No TMDB match", this.now());
        return;
      }
      const d = await tmdb.movieDetails(tmdbId);
      repo.updateItemMeta(item.id, {
        tmdb_id: d.id,
        title: d.title,
        year: yearOf(d.release_date),
        overview: d.overview,
        poster_path: d.poster_path,
        backdrop_path: d.backdrop_path,
        rating: d.vote_average,
        genres: genreJson(d.genres),
        runtime: d.runtime,
      });
      repo.setItemMatchError(item.id, null, this.now());
    } catch (err) {
      log.warn("tmdb movie match failed", { key, error: message(err) });
      repo.setItemMatchError(item.id, message(err), isNotFound(err) ? this.now() : item.match_attempted_at);
    }
  }
}
