import { expect, test } from "bun:test";
import { existsSync, mkdirSync, rmSync, utimesSync } from "node:fs";
import { join } from "node:path";
import { openDb } from "../src/db/db";
import type { ProbeResult } from "../src/library/probe";
import { Repo } from "../src/library/repo";
import { Scanner, type TmdbLike } from "../src/library/scanner";
import { Logger } from "../src/log";
import { TmdbClient, type TmdbSeason, type TmdbTvDetails } from "../src/metadata/tmdb";
import type { FetchFn } from "../src/types";
import type { Library } from "../src/config";
import { fakeProbe, fakeTmdb, legacyLibraries, tmpDir, touch } from "./helpers";

const DAY = 24 * 60 * 60 * 1000;

function setup(
  opts: { root?: string; libraries?: Library[]; probe?: (absPath: string) => Promise<ProbeResult>; tmdb?: TmdbLike } = {},
) {
  const root = opts.root === undefined ? tmpDir() : opts.root;
  let libraries = opts.libraries ?? legacyLibraries(root);
  // the default layout's three folders exist (a missing one is reported as lastError); a missing root stays missing
  if (opts.libraries === undefined && existsSync(root)) for (const l of libraries) mkdirSync(l.path, { recursive: true });
  const repo = new Repo(openDb(":memory:"));
  const tmdb = fakeTmdb();
  const probe = fakeProbe();
  const clock = { t: 1_700_000_000_000 };
  const log = new Logger(join(tmpDir(), "pantry.log"));
  const scanner = new Scanner({
    repo,
    tmdb: opts.tmdb ?? tmdb,
    probe: opts.probe ?? probe.probe,
    libraries: () => libraries,
    log,
    now: () => clock.t,
  });
  const file = (rel: string) => {
    touch(join(root, rel), 10);
    return rel;
  };
  const setLibraries = (l: Library[]) => {
    libraries = l;
  };
  return { root, repo, tmdb, probe, clock, scanner, file, log, setLibraries };
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

test("a group that throws is logged and skipped; other groups and hideEmpty still run", async () => {
  const ctx = setup();
  const andor = ctx.file("tv/Andor/Season 01/Andor S01E01.mkv");
  const heat = ctx.file("movies/Heat (1995)/Heat (1995).mkv");
  await ctx.scanner.scan();
  const heatItem = ctx.repo.getMovieByKey("movies:heat:1995")!;

  const insertShow = ctx.repo.insertShow.bind(ctx.repo);
  ctx.repo.insertShow = (s, now) => {
    if (s.parsed_key === "tv:bad show:") throw new Error("constraint failed");
    return insertShow(s, now);
  };
  ctx.file("tv/Bad Show/Season 01/Bad Show S01E01.mkv");
  const severanceFile = ctx.file("tv/Severance/Season 01/Severance S01E01.mkv");
  rmSync(join(ctx.root, heat));
  ctx.clock.t += 1000;
  const st = await ctx.scanner.scan();

  expect(st.state).toBe("idle");
  expect(st.lastError).toBeNull();
  expect(ctx.repo.getShowByKey("tv:bad show:")).toBeNull();
  expect(ctx.repo.getFileByPath(severanceFile)!.item_id).not.toBeNull();
  expect(ctx.repo.getFileByPath(andor)!.item_id).not.toBeNull();
  expect(ctx.repo.getItem(heatItem.id)!.deleted_at).toBe(ctx.clock.t); // hideEmpty ran
  expect(ctx.log.recent(50).some((l) => l.includes("scan group failed") && l.includes("tv:bad show:"))).toBe(true);
});

test("a merged alias key maps absolute anime numbers with the canonical show's seasons", async () => {
  const ctx = setup();
  ctx.tmdb.tvSearchResults = [{ id: 209867, title: "Frieren", originalTitle: null, year: 2023, popularity: 30 }];
  ctx.tmdb.tvDetailsById[209867] = tvDetails(209867, "Frieren", [[1, 28]]);
  ctx.tmdb.seasonsByIdAndNumber["209867:1"] = season(1, [1, 13, 14], 5000);
  ctx.tmdb.seasonsByIdAndNumber["209867:2"] = season(2, [1, 2], 6000);
  ctx.file("anime/Frieren (2023)/Frieren - 13.mkv"); // key anime:frieren:2023, sorts first: canonical
  ctx.file("anime/Frieren/Frieren - 01.mkv"); // key anime:frieren: -> merged alias
  await ctx.scanner.scan();
  const canonical = ctx.repo.getShowByKey("anime:frieren:2023")!;
  const alias = ctx.repo.getShowByKey("anime:frieren:")!;
  expect(alias.deleted_at).not.toBeNull();

  // TMDB later splits the season; only the canonical row is refreshed (the alias keeps its stale [28])
  ctx.repo.upsertSeason(canonical.id, 1, { episode_count: 12 });
  ctx.repo.upsertSeason(canonical.id, 2, { episode_count: 16 });
  const rel = ctx.file("anime/Frieren/Frieren - 14.mkv");
  ctx.clock.t += 1000;
  await ctx.scanner.scan();
  const item = ctx.repo.getItem(ctx.repo.getFileByPath(rel)!.item_id!)!;
  expect([item.show_id, item.season_number, item.episode_number]).toEqual([canonical.id, 2, 2]);
});

test("a low-confidence show with a matched show's TMDB id stays separate until confirmed", async () => {
  const ctx = setup();
  ctx.tmdb.tvDetailsById[209867] = tvDetails(209867, "Frieren: Beyond Journey's End", [[1, 28]]);
  ctx.tmdb.seasonsByIdAndNumber["209867:1"] = season(1, [1, 2], 5000);
  ctx.tmdb.tvSearchResults = [
    { id: 209867, title: "Frieren: Beyond Journey's End", originalTitle: "葬送のフリーレン", year: 2023, popularity: 30 },
  ];
  ctx.repo.setOverride("anime:frieren:", "show", 209867); // the canonical show, confirmed
  ctx.file("anime/Frieren/Frieren S01E01.mkv");
  ctx.file("anime/Sousou no Frieren/Sousou no Frieren S01E02.mkv"); // no title hit: low confidence
  await ctx.scanner.scan();

  const canonical = ctx.repo.getShowByKey("anime:frieren:")!;
  const low = ctx.repo.getShowByKey("anime:sousou no frieren:")!;
  expect(canonical).toMatchObject({ tmdb_id: 209867, match_error: null, deleted_at: null });
  expect(low).toMatchObject({ tmdb_id: 209867, deleted_at: null });
  expect(low.match_error).toStartWith("Low-confidence match");
  expect(ctx.repo.listEpisodes(low.id).map((e) => e.episode_number)).toEqual([2]);
  expect(ctx.repo.listUnmatchedShows().map((s) => s.id)).toEqual([low.id]);

  // the owner confirms it as POST /admin/api/match does: override, cleared match state
  ctx.repo.setOverride(low.parsed_key, "show", 209867);
  ctx.repo.updateShowMeta(low.id, { tmdb_id: null });
  ctx.repo.setShowMatchError(low.id, null, null);
  ctx.repo.resetShowEpisodesMatch(low.id);
  ctx.clock.t += 1000;
  await ctx.scanner.scan();

  expect(ctx.repo.getShow(low.id)!.deleted_at).not.toBeNull();
  expect(ctx.repo.listShows("anime").map((s) => s.id)).toEqual([canonical.id]);
  expect(ctx.repo.listEpisodes(canonical.id).map((e) => e.episode_number)).toEqual([1, 2]);
  expect(ctx.repo.listUnmatchedShows()).toEqual([]);
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

test("a parser improvement re-groups an unchanged file at the next scan without probing it again", async () => {
  const ctx = setup();
  const rel = ctx.file("tv/Ape maga[1970] 01- L'ape maga comincia a volare.mp4");
  await ctx.scanner.scan();
  expect(ctx.repo.listShows("tv").map((s) => s.title)).toEqual(["Ape maga"]);
  const file = ctx.repo.getFileByPath(rel)!;
  // what an older parser stored: the bracketed year left in the title
  const stale = { ...JSON.parse(file.parsed!), title: "Ape maga[1970]", year: null, parsedKey: "tv:ape maga1970:" };
  ctx.repo.setFileParsed(file.id, JSON.stringify(stale));
  const probes = ctx.probe.calls.length;

  ctx.clock.t += 1000;
  await ctx.scanner.scan();
  expect(ctx.probe.calls.length).toBe(probes);
  expect(JSON.parse(ctx.repo.getFileByPath(rel)!.parsed!).title).toBe("Ape maga");
  expect(ctx.repo.listShows("tv").map((s) => s.title)).toEqual(["Ape maga"]);
});

test("a file re-classified as an extra is detached from the episode it played as", async () => {
  const ctx = setup();
  const real = ctx.file("tv/Buffy/Buffy.S01E01.mkv");
  const extra = ctx.file("tv/Buffy/Buffy.S01EX1.Interview.mkv");
  await ctx.scanner.scan();
  const realRow = ctx.repo.getFileByPath(real)!;
  const extraRow = ctx.repo.getFileByPath(extra)!;
  expect(extraRow.parsed).toBeNull();
  expect(extraRow.item_id).toBeNull();
  // what an older parser did: the extra parsed as S01E01 and attached to that episode
  ctx.repo.setFileParsed(extraRow.id, realRow.parsed);
  ctx.repo.setFileItem(extraRow.id, realRow.item_id);
  expect(ctx.repo.filesForItem(realRow.item_id!).length).toBe(2);

  ctx.clock.t += 1000;
  await ctx.scanner.scan();
  expect(ctx.repo.getFileByPath(extra)!.item_id).toBeNull();
  expect(ctx.repo.filesForItem(realRow.item_id!).map((f) => f.rel_path)).toEqual([real]);
});

test("no library folder found sets lastError and touches nothing", async () => {
  const root = join(tmpDir(), "nope");
  const ctx = setup({ root });
  const st = await ctx.scanner.scan();
  expect(st.lastError).toBe(`Library folder not found: ${join(root, "tv")}, ${join(root, "anime")}, ${join(root, "movies")}`);
  expect(st.state).toBe("idle");
  expect(st.phase).toBe("done");
  expect(ctx.probe.calls).toEqual([]);
});

test("no library folders set sets lastError", async () => {
  const ctx = setup({ libraries: [] });
  expect((await ctx.scanner.scan()).lastError).toBe("No library folders set");
});

test("libraries with generated ids: files are stored under the id and parsed by the library's type", async () => {
  const base = tmpDir();
  const libraries: Library[] = [
    { id: "lib-aa11", path: join(base, "October Movies"), category: "movies" },
    { id: "lib-bb22", path: join(base, "Shows"), category: "tv" },
  ];
  touch(join(base, "October Movies", "Heat (1995)", "Heat.mkv"), 10);
  touch(join(base, "Shows", "Severance", "Severance S01E01.mkv"), 10);
  touch(join(base, "Downloads", "stray.mkv"), 10);
  const ctx = setup({ libraries });
  const st = await ctx.scanner.scan();
  expect(st.lastError).toBeNull();
  expect(ctx.repo.listLiveFiles().map((f) => [f.rel_path, f.category])).toEqual([
    ["lib-aa11/Heat (1995)/Heat.mkv", "movies"],
    ["lib-bb22/Severance/Severance S01E01.mkv", "tv"],
  ]);
  expect(ctx.probe.calls).toEqual([join(base, "October Movies", "Heat (1995)", "Heat.mkv"), join(base, "Shows", "Severance", "Severance S01E01.mkv")]);
  expect(ctx.repo.listMovies("movies").map((m) => m.title)).toEqual(["Heat"]);
  expect(ctx.repo.listShows("tv").map((s) => s.title)).toEqual(["Severance"]);
});

test("two libraries of one type group the same show together", async () => {
  const base = tmpDir();
  const libraries: Library[] = [
    { id: "tv", path: join(base, "tv"), category: "tv" },
    { id: "lib-cc33", path: join(base, "More TV"), category: "tv" },
  ];
  touch(join(base, "tv", "Severance", "Severance S01E01.mkv"), 10);
  touch(join(base, "More TV", "Severance S01E02.mkv"), 10);
  const ctx = setup({ libraries });
  await ctx.scanner.scan();
  const shows = ctx.repo.listShows("tv");
  expect(shows.map((s) => s.title)).toEqual(["Severance"]);
  expect(ctx.repo.listEpisodes(shows[0]!.id).map((e) => e.episode_number)).toEqual([1, 2]);
});

test("a missing library folder keeps its files while the others are scanned", async () => {
  const base = tmpDir();
  const libraries: Library[] = [
    { id: "tv", path: join(base, "tv"), category: "tv" },
    { id: "movies", path: join(base, "movies"), category: "movies" },
  ];
  touch(join(base, "tv", "Severance", "Severance S01E01.mkv"), 10);
  touch(join(base, "movies", "Heat (1995).mkv"), 10);
  const ctx = setup({ libraries });
  await ctx.scanner.scan();
  expect(ctx.repo.listLiveFiles().length).toBe(2);

  rmSync(join(base, "movies"), { recursive: true });
  touch(join(base, "tv", "Severance", "Severance S01E02.mkv"), 10);
  const st = await ctx.scanner.scan();
  expect(st.lastError).toBe(`Library folder not found: ${join(base, "movies")}`);
  expect(ctx.repo.listLiveFiles().map((f) => f.rel_path)).toEqual([
    "movies/Heat (1995).mkv",
    "tv/Severance/Severance S01E01.mkv",
    "tv/Severance/Severance S01E02.mkv",
  ]);
  expect(ctx.repo.listMovies("movies").length).toBe(1);
});

test("removing a library from the settings hides its files at the next scan", async () => {
  const base = tmpDir();
  const tv: Library = { id: "tv", path: join(base, "tv"), category: "tv" };
  const movies: Library = { id: "movies", path: join(base, "movies"), category: "movies" };
  touch(join(base, "tv", "Severance", "Severance S01E01.mkv"), 10);
  touch(join(base, "movies", "Heat (1995).mkv"), 10);
  const ctx = setup({ libraries: [tv, movies] });
  await ctx.scanner.scan();
  expect(ctx.repo.listMovies("movies").length).toBe(1);

  ctx.setLibraries([tv]);
  const st = await ctx.scanner.scan();
  expect(st.lastError).toBeNull();
  expect(ctx.repo.listLiveFiles().map((f) => f.rel_path)).toEqual(["tv/Severance/Severance S01E01.mkv"]);
  expect(ctx.repo.listMovies("movies").length).toBe(0);
  // the file comes back, with its id and the movie's progress, when the folder is added again under the same id
  ctx.setLibraries([tv, movies]);
  await ctx.scanner.scan();
  expect(ctx.repo.listMovies("movies").length).toBe(1);
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
