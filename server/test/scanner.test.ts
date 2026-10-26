import { expect, test } from "bun:test";
import { rmSync, utimesSync } from "node:fs";
import { join } from "node:path";
import { openDb } from "../src/db/db";
import type { ProbeResult } from "../src/library/probe";
import { Repo } from "../src/library/repo";
import { Scanner } from "../src/library/scanner";
import { Logger } from "../src/log";
import type { TmdbSeason, TmdbTvDetails } from "../src/metadata/tmdb";
import { fakeProbe, fakeTmdb, tmpDir, touch } from "./helpers";

const DAY = 24 * 60 * 60 * 1000;

function setup(opts: { root?: string; probe?: (absPath: string) => Promise<ProbeResult> } = {}) {
  const root = opts.root ?? tmpDir();
  const repo = new Repo(openDb(":memory:"));
  const tmdb = fakeTmdb();
  const probe = fakeProbe();
  const clock = { t: 1_700_000_000_000 };
  const scanner = new Scanner({
    repo,
    tmdb,
    probe: opts.probe ?? probe.probe,
    mediaRoot: () => root,
    log: new Logger(join(tmpDir(), "pantry.log")),
    now: () => clock.t,
  });
  const file = (rel: string) => {
    touch(join(root, rel), 10);
    return rel;
  };
  return { root, repo, tmdb, probe, clock, scanner, file };
}

function tvDetails(id: number, name: string, seasons: [number, number][]): TmdbTvDetails {
  return {
    id,
    name,
    first_air_date: "2022-02-18",
    overview: `${name} overview`,
    poster_path: "/poster.jpg",
    backdrop_path: "/backdrop.jpg",
    vote_average: 8.4,
    genres: [{ name: "Drama" }],
    seasons: seasons.map(([n, count]) => ({
      season_number: n,
      name: n === 0 ? "Specials" : `Season ${n}`,
      episode_count: count,
      poster_path: null,
    })),
  };
}

function season(n: number, episodes: number[], idBase: number): TmdbSeason {
  return {
    season_number: n,
    name: `Season ${n}`,
    poster_path: null,
    episodes: episodes.map((e) => ({
      id: idBase + e,
      episode_number: e,
      name: `Ep title ${e}`,
      overview: `Ep overview ${e}`,
      still_path: `/still${e}.jpg`,
      air_date: "2025-01-01",
      runtime: 50,
    })),
  };
}

function severance(ctx: ReturnType<typeof setup>) {
  ctx.tmdb.tvSearchResults = [{ id: 95396, title: "Severance", year: 2022, popularity: 50 }];
  ctx.tmdb.tvDetailsById[95396] = tvDetails(95396, "Severance", [[1, 9], [2, 10]]);
  ctx.tmdb.seasonsByIdAndNumber["95396:2"] = season(2, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 1000);
  const a = ctx.file("tv/Severance/Season 02/Severance S02E04.mkv");
  const b = ctx.file("tv/Severance/Season 02/Severance S02E05.mkv");
  return [a, b] as const;
}

test("first scan creates show, seasons, episodes, attaches files", async () => {
  const ctx = setup();
  const [a, b] = severance(ctx);
  const st = await ctx.scanner.scan();

  expect(st.state).toBe("idle");
  expect(st.phase).toBe("done");
  expect(st.lastError).toBeNull();
  expect(st.filesSeen).toBe(2);
  expect(st.filesProcessed).toBe(2);
  expect(st.finishedAt).toBe(ctx.clock.t);

  const show = ctx.repo.getShowByKey("tv:severance:")!;
  expect(show.tmdb_id).toBe(95396);
  expect(show.title).toBe("Severance");
  expect(show.year).toBe(2022);
  expect(show.genres).toBe(JSON.stringify(["Drama"]));
  expect(show.match_error).toBeNull();
  expect(ctx.repo.listSeasons(show.id).map((s) => [s.number, s.episode_count])).toEqual([[1, 9], [2, 10]]);

  const eps = ctx.repo.listEpisodes(show.id);
  expect(eps.map((e) => [e.season_number, e.episode_number])).toEqual([[2, 4], [2, 5]]);
  expect(eps[0]!.title).toBe("Ep title 4");
  expect(eps[0]!.tmdb_id).toBe(1004);
  expect(eps[0]!.still_path).toBe("/still4.jpg");
  expect(eps[0]!.runtime).toBe(50);
  expect(ctx.tmdb.calls.tvSeason).toBe(1);

  const fa = ctx.repo.getFileByPath(a)!;
  const fb = ctx.repo.getFileByPath(b)!;
  expect(fa.item_id).toBe(eps[0]!.id);
  expect(fb.item_id).toBe(eps[1]!.id);
  expect(fa.duration).toBe(1200);
  expect(fa.probe_error).toBeNull();
  expect(ctx.probe.calls).toEqual([join(ctx.root, a), join(ctx.root, b)]);
});

test("rescan keeps ids and does not re-probe unchanged files", async () => {
  const ctx = setup();
  severance(ctx);
  await ctx.scanner.scan();
  const show1 = ctx.repo.getShowByKey("tv:severance:")!;
  const ids1 = ctx.repo.listEpisodes(show1.id).map((e) => e.id);
  const calls1 = { ...ctx.tmdb.calls };

  ctx.clock.t += 2 * DAY;
  const st = await ctx.scanner.scan();
  expect(st.filesSeen).toBe(2);
  expect(st.filesProcessed).toBe(0);
  expect(ctx.probe.calls.length).toBe(2);
  const show2 = ctx.repo.getShowByKey("tv:severance:")!;
  expect(show2.id).toBe(show1.id);
  expect(ctx.repo.listEpisodes(show2.id).map((e) => e.id)).toEqual(ids1);
  expect(ctx.tmdb.calls).toEqual(calls1);
});

test("changed mtime re-probes that file only", async () => {
  const ctx = setup();
  const [a, b] = severance(ctx);
  await ctx.scanner.scan();
  const before = ctx.repo.getFileByPath(b)!;
  ctx.probe.calls.length = 0;

  const later = new Date(Date.now() + 60_000);
  utimesSync(join(ctx.root, b), later, later);
  const st = await ctx.scanner.scan();

  expect(st.filesProcessed).toBe(1);
  expect(ctx.probe.calls).toEqual([join(ctx.root, b)]);
  const after = ctx.repo.getFileByPath(b)!;
  expect(after.id).toBe(before.id);
  expect(after.mtime).not.toBe(before.mtime);
  expect(after.item_id).toBe(before.item_id);
  expect(after.duration).toBe(1200);
  expect(ctx.repo.getFileByPath(a)!.item_id).not.toBeNull();
});

test("vanished file hides item and show; restored file revives them", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [];
  const rel = ctx.file("tv/Andor/Season 01/Andor S01E01.mkv");
  await ctx.scanner.scan();
  const show = ctx.repo.getShowByKey("tv:andor:")!;
  const item = ctx.repo.getEpisode(show.id, 1, 1)!;

  rmSync(join(ctx.root, rel));
  ctx.clock.t += 1000;
  await ctx.scanner.scan();
  expect(ctx.repo.getFileByPath(rel)!.deleted_at).toBe(ctx.clock.t);
  expect(ctx.repo.getItem(item.id)!.deleted_at).toBe(ctx.clock.t);
  expect(ctx.repo.getShow(show.id)!.deleted_at).toBe(ctx.clock.t);
  expect(ctx.repo.listShows()).toEqual([]);

  ctx.file(rel);
  ctx.clock.t += 1000;
  await ctx.scanner.scan();
  expect(ctx.repo.getFileByPath(rel)!.deleted_at).toBeNull();
  expect(ctx.repo.getItem(item.id)!.deleted_at).toBeNull();
  expect(ctx.repo.getShow(show.id)!.deleted_at).toBeNull();
  expect(ctx.repo.getFileByPath(rel)!.item_id).toBe(item.id);
});

test("anime absolute numbering maps through TMDB season counts", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [{ id: 209867, title: "Frieren", year: 2023, popularity: 30 }];
  ctx.tmdb.tvDetailsById[209867] = tvDetails(209867, "Frieren", [[0, 3], [1, 12], [2, 16]]);
  ctx.tmdb.seasonsByIdAndNumber["209867:2"] = season(2, [1, 2, 3], 5000);
  ctx.file("anime/Frieren/Frieren - 13.mkv");
  await ctx.scanner.scan();

  const show = ctx.repo.getShowByKey("anime:frieren:")!;
  expect(ctx.repo.listSeasons(show.id).map((s) => s.number)).toEqual([0, 1, 2]);
  const eps = ctx.repo.listEpisodes(show.id);
  expect(eps.map((e) => [e.season_number, e.episode_number])).toEqual([[2, 1]]);
  expect(eps[0]!.title).toBe("Ep title 1");
  expect(eps[0]!.tmdb_id).toBe(5001);
});

test("anime absolute without TMDB falls back to season 1", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [];
  ctx.file("anime/Frieren/Frieren - 13.mkv");
  await ctx.scanner.scan();

  const show = ctx.repo.getShowByKey("anime:frieren:")!;
  expect(show.tmdb_id).toBeNull();
  expect(show.match_error).not.toBeNull();
  const eps = ctx.repo.listEpisodes(show.id);
  expect(eps.map((e) => [e.season_number, e.episode_number, e.title])).toEqual([[1, 13, "Episode 13"]]);
  expect(ctx.tmdb.calls.tvSeason).toBe(0);
});

test("tmdb failure leaves show unmatched with match_error and scan finishes", async () => {
  const ctx = setup();
  ctx.tmdb.searchTv = async () => {
    ctx.tmdb.calls.searchTv++;
    throw new Error("TMDB 401");
  };
  ctx.tmdb.searchMovie = async () => {
    ctx.tmdb.calls.searchMovie++;
    throw new Error("TMDB 401");
  };
  const rel = ctx.file("tv/Severance/Season 02/Severance S02E04.mkv");
  const movie = ctx.file("movies/Heat (1995)/Heat (1995).mkv");
  const st = await ctx.scanner.scan();

  expect(st.state).toBe("idle");
  expect(st.lastError).toBeNull();
  const show = ctx.repo.getShowByKey("tv:severance:")!;
  expect(show.tmdb_id).toBeNull();
  expect(show.match_error).toBe("TMDB 401");
  expect(show.match_attempted_at).toBe(ctx.clock.t);
  expect(ctx.repo.getFileByPath(rel)!.item_id).toBe(ctx.repo.getEpisode(show.id, 2, 4)!.id);
  const m = ctx.repo.getMovieByKey("movies:heat:1995")!;
  expect(m.tmdb_id).toBeNull();
  expect(m.match_error).toBe("TMDB 401");
  expect(ctx.repo.getFileByPath(movie)!.item_id).toBe(m.id);

  // within 24 h: no retry; after 24 h: retried
  ctx.clock.t += DAY - 1;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.searchTv).toBe(1);
  expect(ctx.tmdb.calls.searchMovie).toBe(1);
  ctx.clock.t += 2;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.searchTv).toBe(2);
  expect(ctx.tmdb.calls.searchMovie).toBe(2);
});

test("unparseable file still becomes an item under folder title", async () => {
  const ctx = setup();
  const rel = ctx.file("tv/Weird Folder/clip.mkv");
  await ctx.scanner.scan();

  const show = ctx.repo.getShowByKey("tv:weird folder:")!;
  expect(show.title).toBe("Weird Folder");
  const item = ctx.repo.getEpisode(show.id, 1, 1)!;
  expect(item).not.toBeNull();
  expect(ctx.repo.getFileByPath(rel)!.item_id).toBe(item.id);
});

test("multi-episode file sets episode_end", async () => {
  const ctx = setup();
  const rel = ctx.file("tv/The Office/Season 03/The Office S03E01-E02.mkv");
  await ctx.scanner.scan();

  const show = ctx.repo.getShowByKey("tv:the office:")!;
  const eps = ctx.repo.listEpisodes(show.id);
  expect(eps.length).toBe(1);
  expect(eps[0]!.season_number).toBe(3);
  expect(eps[0]!.episode_number).toBe(1);
  expect(eps[0]!.episode_end).toBe(2);
  expect(ctx.repo.getFileByPath(rel)!.item_id).toBe(eps[0]!.id);
});

test("movie with override uses override tmdb id", async () => {
  const ctx = setup();
  ctx.repo.setOverride("movies:heat:1995", "movie", 949);
  ctx.tmdb.movieSearchResults = [{ id: 1, title: "Heat", year: 1995, popularity: 1 }];
  ctx.tmdb.movieDetailsById[949] = {
    id: 949,
    title: "Heat",
    release_date: "1995-12-15",
    overview: "A group of high-end professional thieves",
    poster_path: "/heat.jpg",
    backdrop_path: "/heatbg.jpg",
    vote_average: 7.9,
    genres: [{ name: "Crime" }, { name: "Drama" }],
    runtime: 170,
  };
  const rel = ctx.file("movies/Heat (1995)/Heat (1995).mkv");
  await ctx.scanner.scan();

  expect(ctx.tmdb.calls.searchMovie).toBe(0);
  const m = ctx.repo.getMovieByKey("movies:heat:1995")!;
  expect(m.type).toBe("movie");
  expect(m.tmdb_id).toBe(949);
  expect(m.year).toBe(1995);
  expect(m.runtime).toBe(170);
  expect(m.genres).toBe(JSON.stringify(["Crime", "Drama"]));
  expect(m.match_error).toBeNull();
  expect(ctx.repo.getFileByPath(rel)!.item_id).toBe(m.id);
});

test("scan() calls made before the run starts walking share one run", async () => {
  const ctx = setup();
  severance(ctx);
  let finished = 0;
  ctx.scanner.onFinished(() => finished++);
  const p1 = ctx.scanner.scan();
  const p2 = ctx.scanner.scan();
  expect(p2).toBe(p1);
  expect(ctx.scanner.status().state).toBe("scanning");
  await Promise.all([p1, p2]);
  expect(ctx.probe.calls.length).toBe(2);
  expect(finished).toBe(1);
  expect(ctx.scanner.status().state).toBe("idle");
});

test("scan() called mid-run queues exactly one rerun that sees later changes", async () => {
  const probed: string[] = [];
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  const ctx = setup({
    probe: async (absPath) => {
      probed.push(absPath);
      if (probed.length === 1) await gate; // first probe of the first run blocks
      return { duration: 1200, audio: [], subtitles: [], video: null };
    },
  });
  const [a, b] = severance(ctx);
  let finished = 0;
  ctx.scanner.onFinished(() => finished++);

  const p1 = ctx.scanner.scan();
  while (probed.length === 0) await Bun.sleep(1); // first run is walking/probing
  const c = ctx.file("tv/Severance/Season 02/Severance S02E06.mkv"); // appears after the first walk
  let p2Finished = -1;
  const p2 = ctx.scanner.scan().then((st) => ((p2Finished = finished), st));
  ctx.scanner.scan(); // a third mid-run call does not queue a second rerun
  release();
  await Promise.all([p1, p2]);

  expect(finished).toBe(2);
  expect(p2Finished).toBe(2);
  expect(probed.map((p) => p.slice(ctx.root.length + 1))).toEqual([a, b, c]);
  expect(ctx.repo.getFileByPath(c)?.item_id).not.toBeNull();
  expect(ctx.scanner.status().state).toBe("idle");
});

test("probe failure is stored and scan continues", async () => {
  const ctx = setup();
  const [a, b] = severance(ctx);
  ctx.probe.failOn.set(join(ctx.root, a), "ffprobe failed: bad file");
  const st = await ctx.scanner.scan();

  expect(st.lastError).toBeNull();
  const fa = ctx.repo.getFileByPath(a)!;
  expect(fa.probe_error).toBe("ffprobe failed: bad file");
  expect(fa.duration).toBeNull();
  expect(fa.item_id).not.toBeNull();
  expect(ctx.repo.getFileByPath(b)!.duration).toBe(1200);
});

test("missing media folder sets lastError", async () => {
  const ctx = setup({ root: join(tmpDir(), "nope") });
  const st = await ctx.scanner.scan();
  expect(st.lastError).toBe("Media folder not set");
  expect(st.state).toBe("idle");
});

test("episodes TMDB does not list do not refetch their season on rescan", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [{ id: 95396, title: "Severance", year: 2022, popularity: 50 }];
  ctx.tmdb.tvDetailsById[95396] = tvDetails(95396, "Severance", [[1, 9], [2, 10]]);
  ctx.tmdb.seasonsByIdAndNumber["95396:2"] = season(2, [1, 2, 3, 4], 1000); // E05 not listed
  ctx.file("tv/Severance/Season 02/Severance S02E04.mkv");
  ctx.file("tv/Severance/Season 02/Severance S02E05.mkv");
  ctx.file("tv/Severance/Season 03/Severance S03E01.mkv"); // season TMDB lacks: 404
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.tvSeason).toBe(2);
  const show = ctx.repo.getShowByKey("tv:severance:")!;
  const e5 = ctx.repo.getEpisode(show.id, 2, 5)!;
  expect(e5.tmdb_id).toBeNull();
  expect(e5.match_error).toBe("Not on TMDB");
  expect(ctx.repo.getEpisode(show.id, 3, 1)!.match_error).toStartWith("TMDB 404");
  expect(ctx.repo.getEpisode(show.id, 2, 4)!.tmdb_id).toBe(1004);

  ctx.clock.t += 60 * 60 * 1000;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.tvSeason).toBe(2);

  ctx.clock.t += DAY;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.tvSeason).toBe(4);
});

test("anime item keeps its id when the show matches later", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [];
  const rel = ctx.file("anime/Frieren/Frieren - 13.mkv");
  await ctx.scanner.scan();
  const show = ctx.repo.getShowByKey("anime:frieren:")!;
  const before = ctx.repo.getEpisode(show.id, 1, 13)!;

  ctx.tmdb.tvSearchResults = [{ id: 209867, title: "Frieren", year: 2023, popularity: 30 }];
  ctx.tmdb.tvDetailsById[209867] = tvDetails(209867, "Frieren", [[1, 12], [2, 16]]);
  ctx.tmdb.seasonsByIdAndNumber["209867:2"] = season(2, [1, 2], 5000);
  ctx.clock.t += DAY;
  await ctx.scanner.scan();

  expect(ctx.probe.calls.length).toBe(1);
  const after = ctx.repo.getItem(before.id)!;
  expect([after.season_number, after.episode_number]).toEqual([2, 1]);
  expect(after.deleted_at).toBeNull();
  expect(after.tmdb_id).toBe(5001);
  expect(after.title).toBe("Ep title 1");
  expect(ctx.repo.getEpisode(show.id, 1, 13)).toBeNull();
  expect(ctx.repo.listEpisodes(show.id, { includeDeleted: true }).map((e) => e.id)).toEqual([before.id]);
  expect(ctx.repo.getFileByPath(rel)!.item_id).toBe(before.id);
});

test("override of the wrong kind is ignored", async () => {
  const ctx = setup();
  severance(ctx);
  ctx.repo.setOverride("tv:severance:", "movie", 949);
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.searchTv).toBe(1);
  expect(ctx.repo.getShowByKey("tv:severance:")!.tmdb_id).toBe(95396);
});
