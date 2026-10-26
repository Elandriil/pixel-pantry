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
  private inFlight: Promise<ScanStatus> | null = null;
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

  /** Starts a scan, or returns the one already running. */
  scan(): Promise<ScanStatus> {
    if (!this.inFlight) {
      this.inFlight = this.run().finally(() => {
        this.inFlight = null;
      });
    }
    return this.inFlight;
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
    const seasonsNeedingMeta = new Set<number>();
    for (const { file, parsed } of files) {
      const item = this.attachEpisode(show, file, parsed, counts);
      if (item.tmdb_id === null && item.season_number !== null) seasonsNeedingMeta.add(item.season_number);
    }
    if (show.tmdb_id !== null) {
      for (const season of seasonsNeedingMeta) await this.fetchSeasonMeta(show, show.tmdb_id, season);
    }
  }

  private async matchShow(show: ShowRow): Promise<void> {
    const { repo, tmdb, log } = this.deps;
    try {
      let tmdbId = repo.getOverride(show.parsed_key)?.tmdb_id ?? null;
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
      repo.setShowMatchError(show.id, message(err), this.now());
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

  private async fetchSeasonMeta(show: ShowRow, tmdbId: number, season: number): Promise<void> {
    const { repo, tmdb, log } = this.deps;
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
      log.warn("tmdb season fetch failed", { key: show.parsed_key, season, error: message(err) });
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
      let tmdbId = repo.getOverride(key)?.tmdb_id ?? null;
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
      repo.setItemMatchError(item.id, message(err), this.now());
    }
  }
}
