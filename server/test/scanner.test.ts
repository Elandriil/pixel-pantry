import { expect, test } from "bun:test";
import { rmSync, utimesSync } from "node:fs";
import { join } from "node:path";
import { openDb } from "../src/db/db";
import type { ProbeResult } from "../src/library/probe";
import { Repo } from "../src/library/repo";
import { Scanner, type TmdbLike } from "../src/library/scanner";
import { Logger } from "../src/log";
import { TmdbClient, type TmdbSeason, type TmdbTvDetails } from "../src/metadata/tmdb";
import type { FetchFn } from "../src/types";
import { fakeProbe, fakeTmdb, tmpDir, touch } from "./helpers";

const DAY = 24 * 60 * 60 * 1000;

function setup(opts: { root?: string; probe?: (absPath: string) => Promise<ProbeResult>; tmdb?: TmdbLike } = {}) {
  const root = opts.root === undefined ? tmpDir() : opts.root;
  const repo = new Repo(openDb(":memory:"));
  const tmdb = fakeTmdb();
  const probe = fakeProbe();
  const clock = { t: 1_700_000_000_000 };
  const scanner = new Scanner({
    repo,
    tmdb: opts.tmdb ?? tmdb,
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
  ctx.tmdb.tvSearchResults = [{ id: 95396, title: "Severance", originalTitle: null, year: 2022, popularity: 50 }];
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
  ctx.tmdb.tvSearchResults = [{ id: 209867, title: "Frieren", originalTitle: null, year: 2023, popularity: 30 }];
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

test("tmdb failure leaves show unmatched with match_error, scan finishes, and the next scan retries", async () => {
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
  expect(show.match_attempted_at).toBeNull();
  expect(ctx.repo.getFileByPath(rel)!.item_id).toBe(ctx.repo.getEpisode(show.id, 2, 4)!.id);
  const m = ctx.repo.getMovieByKey("movies:heat:1995")!;
  expect(m.tmdb_id).toBeNull();
  expect(m.match_error).toBe("TMDB 401");
  expect(m.match_attempted_at).toBeNull();
  expect(ctx.repo.getFileByPath(movie)!.item_id).toBe(m.id);

  // an auth failure is not backed off: a scan a minute later retries
  ctx.clock.t += 60_000;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.searchTv).toBe(2);
  expect(ctx.tmdb.calls.searchMovie).toBe(2);
});

test("no TMDB match is backed off for 24 h", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [];
  ctx.file("tv/Andor/Season 01/Andor S01E01.mkv");
  await ctx.scanner.scan();
  const show = ctx.repo.getShowByKey("tv:andor:")!;
  expect(show.match_error).toBe("No TMDB match");
  expect(show.match_attempted_at).toBe(ctx.clock.t);

  ctx.clock.t += DAY - 1;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.searchTv).toBe(1);
  ctx.clock.t += 2;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.searchTv).toBe(2);
});

test("a scan without a TMDB key retries as soon as the key is set", async () => {
  let key = "";
  const paths: string[] = [];
  const bodies: Record<string, unknown> = {
    "/3/search/tv": { results: [{ id: 95396, name: "Severance", first_air_date: "2022-02-18", popularity: 50 }] },
    "/3/tv/95396": tvDetails(95396, "Severance", [[1, 9]]),
    "/3/tv/95396/season/1": season(1, [1, 2], 1000),
  };
  const fetchFn = (async (input: RequestInfo | URL) => {
    const path = new URL(String(input)).pathname;
    paths.push(path);
    const body = bodies[path];
    return body ? new Response(JSON.stringify(body)) : new Response("{}", { status: 404 });
  }) as FetchFn;
  const tmdb = new TmdbClient({ apiKey: () => key, fetchFn, cache: { get: () => null, set: () => {} } });
  const ctx = setup({ tmdb });
  ctx.file("tv/Severance/Season 01/Severance S01E01.mkv");

  await ctx.scanner.scan();
  const before = ctx.repo.getShowByKey("tv:severance:")!;
  expect(before.tmdb_id).toBeNull();
  expect(before.match_error).toBe("TMDB API key not set");
  expect(before.match_attempted_at).toBeNull();
  expect(paths).toEqual([]);

  key = "KEY";
  ctx.clock.t += 60_000;
  await ctx.scanner.scan();
  expect(paths[0]).toBe("/3/search/tv");
  const after = ctx.repo.getShowByKey("tv:severance:")!;
  expect(after.tmdb_id).toBe(95396);
  expect(after.match_error).toBeNull();
  expect(ctx.repo.getEpisode(after.id, 1, 1)!.tmdb_id).toBe(1001);
});

test("low-confidence match sets tmdb_id and names the match in match_error; a later high match clears it", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [
    { id: 209867, title: "Frieren: Beyond Journey's End", originalTitle: "葬送のフリーレン", year: 2023, popularity: 30 },
  ];
  ctx.tmdb.tvDetailsById[209867] = tvDetails(209867, "Frieren: Beyond Journey's End", [[1, 28]]);
  ctx.tmdb.seasonsByIdAndNumber["209867:1"] = season(1, [5], 5000);
  ctx.tmdb.movieSearchResults = [{ id: 129, title: "Spirited Away", originalTitle: "千と千尋の神隠し", year: 2001, popularity: 9 }];
  ctx.tmdb.movieDetailsById[129] = {
    id: 129, title: "Spirited Away", release_date: "2001-07-20", overview: "", poster_path: null, backdrop_path: null,
    vote_average: 8.5, genres: [], runtime: 125,
  };
  ctx.file("anime/Sousou no Frieren/Sousou no Frieren - 05.mkv");
  ctx.file("movies/Sen to Chihiro no Kamikakushi (2001)/Sen to Chihiro no Kamikakushi (2001).mkv");
  await ctx.scanner.scan();

  const show = ctx.repo.getShowByKey("anime:sousou no frieren:")!;
  expect(show.tmdb_id).toBe(209867);
  expect(show.match_error).toBe("Low-confidence match: Frieren: Beyond Journey's End");
  const movie = ctx.repo.getMovieByKey("movies:sen to chihiro no kamikakushi:2001")!;
  expect(movie.tmdb_id).toBe(129);
  expect(movie.match_error).toBe("Low-confidence match: Spirited Away");
});

test("two parsed keys matching the same TMDB show end up as one show with all episodes", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [{ id: 57243, title: "Doctor Who", originalTitle: null, year: 2005, popularity: 90 }];
  ctx.tmdb.tvDetailsById[57243] = tvDetails(57243, "Doctor Who", [[1, 13]]);
  ctx.tmdb.seasonsByIdAndNumber["57243:1"] = season(1, [1, 2, 3], 7000);
  const a = ctx.file("tv/Doctor Who/Season 01/Doctor Who S01E01.mkv"); // key tv:doctor who:
  const b = ctx.file("tv/Doctor Who (2005)/Doctor Who S01E02.mkv"); // key tv:doctor who:2005
  const c = ctx.file("tv/Doctor Who (2005)/Doctor Who S01E01 Proper.mkv"); // same episode as a, other key
  await ctx.scanner.scan();

  const shows = ctx.repo.listShows("tv");
  // the (2005) folder sorts first, so its show is the older row the other key folds into
  expect(shows.map((s) => [s.parsed_key, s.tmdb_id])).toEqual([["tv:doctor who:2005", 57243]]);
  const merged = ctx.repo.getShowByKey("tv:doctor who:")!;
  expect(merged.deleted_at).not.toBeNull();
  expect(ctx.repo.listEpisodes(merged.id)).toEqual([]);
  const eps = ctx.repo.listEpisodes(shows[0]!.id);
  expect(eps.map((e) => [e.season_number, e.episode_number, e.title])).toEqual([[1, 1, "Ep title 1"], [1, 2, "Ep title 2"]]);
  expect(ctx.repo.getFileByPath(a)!.item_id).toBe(eps[0]!.id);
  expect(ctx.repo.getFileByPath(c)!.item_id).toBe(eps[0]!.id);
  expect(ctx.repo.getFileByPath(b)!.item_id).toBe(eps[1]!.id);

  // later scans keep it merged and do not search again for the hidden duplicate
  const calls = ctx.tmdb.calls.searchTv;
  ctx.file("tv/Doctor Who (2005)/Doctor Who S01E03.mkv");
  ctx.clock.t += DAY;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.searchTv).toBe(calls);
  expect(ctx.repo.listShows("tv").map((s) => s.id)).toEqual([shows[0]!.id]);
  expect(ctx.repo.listEpisodes(shows[0]!.id).map((e) => e.episode_number)).toEqual([1, 2, 3]);
});

test("a show matched 8 days ago refetches its TMDB details and gains the new season", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [{ id: 95396, title: "Severance", originalTitle: null, year: 2022, popularity: 50 }];
  ctx.tmdb.tvDetailsById[95396] = tvDetails(95396, "Severance", [[1, 9]]);
  ctx.tmdb.seasonsByIdAndNumber["95396:1"] = season(1, [1], 1000);
  ctx.file("tv/Severance/Season 01/Severance S01E01.mkv");
  await ctx.scanner.scan();
  const show = ctx.repo.getShowByKey("tv:severance:")!;
  expect(ctx.tmdb.calls.tvDetails).toBe(1);

  ctx.clock.t += 6 * DAY;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.tvDetails).toBe(1);

  ctx.tmdb.tvDetailsById[95396] = { ...tvDetails(95396, "Severance", [[1, 9], [2, 10]]), vote_average: 9.1 };
  ctx.tmdb.seasonsByIdAndNumber["95396:2"] = season(2, [1], 2000);
  ctx.file("tv/Severance/Season 02/Severance S02E01.mkv");
  ctx.clock.t += 2 * DAY;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.tvDetails).toBe(2);
  expect(ctx.tmdb.calls.searchTv).toBe(1);
  const after = ctx.repo.getShow(show.id)!;
  expect(after.rating).toBe(9.1);
  expect(after.match_attempted_at).toBe(ctx.clock.t);
  expect(ctx.repo.listSeasons(show.id).map((s) => [s.number, s.episode_count])).toEqual([[1, 9], [2, 10]]);
  expect(ctx.repo.getEpisode(show.id, 2, 1)!.tmdb_id).toBe(2001);
});

test("a refresh keeps a low-confidence note", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [{ id: 1, title: "Something Else", originalTitle: null, year: null, popularity: 1 }];
  ctx.tmdb.tvDetailsById[1] = tvDetails(1, "Something Else", [[1, 3]]);
  ctx.file("tv/Odd/Season 01/Odd S01E01.mkv");
  await ctx.scanner.scan();
  ctx.clock.t += 8 * DAY;
  await ctx.scanner.scan();
  expect(ctx.tmdb.calls.tvDetails).toBe(2);
  expect(ctx.repo.getShowByKey("tv:odd:")!.match_error).toBe("Low-confidence match: Something Else");
});

test("an episode TMDB lists later is filled in on the next day's scan", async () => {
  const ctx = setup();
  severance(ctx);
  ctx.tmdb.seasonsByIdAndNumber["95396:2"] = season(2, [1, 2, 3, 4], 1000); // E05 not listed yet
  await ctx.scanner.scan();
  const show = ctx.repo.getShowByKey("tv:severance:")!;
  expect(ctx.repo.getEpisode(show.id, 2, 5)!.tmdb_id).toBeNull();

  ctx.tmdb.seasonsByIdAndNumber["95396:2"] = season(2, [1, 2, 3, 4, 5], 1000);
  ctx.clock.t += DAY;
  await ctx.scanner.scan();
  const e5 = ctx.repo.getEpisode(show.id, 2, 5)!;
  expect(e5.tmdb_id).toBe(1005);
  expect(e5.title).toBe("Ep title 5");
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
  ctx.tmdb.movieSearchResults = [{ id: 1, title: "Heat", originalTitle: null, year: 1995, popularity: 1 }];
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
  const root = join(tmpDir(), "nope");
  const ctx = setup({ root });
  const st = await ctx.scanner.scan();
  expect(st.lastError).toBe(`Media folder not found: ${root}`);
  expect(st.state).toBe("idle");
});

test("media folder without tv, anime or movies sets lastError", async () => {
  const root = tmpDir();
  touch(join(root, "Severance", "Severance S01E01.mkv"), 10);
  const ctx = setup({ root });
  const st = await ctx.scanner.scan();
  expect(st.lastError).toBe(`No tv, anime or movies folder in ${root}`);
  expect(st.state).toBe("idle");
  expect(st.phase).toBe("done");
  expect(ctx.probe.calls).toEqual([]);
});

test("empty media folder setting sets lastError", async () => {
  const ctx = setup({ root: "" });
  expect((await ctx.scanner.scan()).lastError).toBe("Media folder not set");
});

test("episodes TMDB does not list do not refetch their season on rescan", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [{ id: 95396, title: "Severance", originalTitle: null, year: 2022, popularity: 50 }];
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

  ctx.tmdb.tvSearchResults = [{ id: 209867, title: "Frieren", originalTitle: null, year: 2023, popularity: 30 }];
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
