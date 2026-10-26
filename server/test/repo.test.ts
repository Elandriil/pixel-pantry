import { expect, test } from "bun:test";
import { openDb } from "../src/db/db";
import { Repo, type FileRow } from "../src/library/repo";

function file(rel_path: string, over: Partial<Omit<FileRow, "id">> = {}): Omit<FileRow, "id"> {
  return {
    rel_path, category: "tv", size: 100, mtime: 1, parsed: null, item_id: null, duration: null,
    audio_tracks: null, subtitle_tracks: null, probe_error: null, added_at: 10, deleted_at: null, ...over,
  };
}

function setup() {
  return new Repo(openDb(":memory:"));
}

test("upsertFile is idempotent on rel_path and returns same id", () => {
  const repo = setup();
  const a = repo.upsertFile(file("a/b.mkv"));
  const b = repo.upsertFile(file("a/b.mkv", { size: 200, mtime: 5, parsed: '{"x":1}' }));
  expect(b).toBe(a);
  const row = repo.getFileByPath("a/b.mkv")!;
  expect(row.size).toBe(200);
  expect(row.mtime).toBe(5);
  expect(row.parsed).toBe('{"x":1}');
  expect(repo.listLiveFiles()).toHaveLength(1);
  expect(repo.getFileByPath("nope")).toBeNull();
});

test("upsertFile clears deleted_at on existing file", () => {
  const repo = setup();
  const id = repo.upsertFile(file("a.mkv"));
  repo.markFilesDeletedExcept([], 50);
  repo.upsertFile(file("a.mkv"));
  expect(repo.getFileByPath("a.mkv")!.deleted_at).toBeNull();
  expect(repo.getFileByPath("a.mkv")!.id).toBe(id);
});

test("markFilesDeletedExcept marks vanished files and undelete clears it", () => {
  const repo = setup();
  const a = repo.upsertFile(file("a.mkv"));
  repo.upsertFile(file("b.mkv"));
  const n = repo.markFilesDeletedExcept(["b.mkv"], 99);
  expect(n).toBe(1);
  expect(repo.getFileByPath("a.mkv")!.deleted_at).toBe(99);
  expect(repo.getFileByPath("b.mkv")!.deleted_at).toBeNull();
  expect(repo.listLiveFiles().map((f) => f.rel_path)).toEqual(["b.mkv"]);
  // already-deleted files are not re-counted or re-stamped
  expect(repo.markFilesDeletedExcept(["b.mkv"], 100)).toBe(0);
  expect(repo.getFileByPath("a.mkv")!.deleted_at).toBe(99);
  repo.undeleteFile(a);
  expect(repo.getFileByPath("a.mkv")!.deleted_at).toBeNull();
});

test("setFileItem, filesForItem and setFileProbe", () => {
  const repo = setup();
  const item = repo.insertItem({ type: "movie", category: "movies", parsed_title: "x", title: "X" }, 1);
  const f = repo.upsertFile(file("m.mkv"));
  repo.setFileItem(f, item);
  expect(repo.filesForItem(item).map((r) => r.id)).toEqual([f]);
  repo.setFileProbe(f, { duration: 12.5, audio: [{ lang: "en" }], subtitles: [] }, null);
  const row = repo.getFileByPath("m.mkv")!;
  expect(row.duration).toBe(12.5);
  expect(JSON.parse(row.audio_tracks!)).toEqual([{ lang: "en" }]);
  expect(row.subtitle_tracks).toBe("[]");
  repo.setFileProbe(f, null, "boom");
  expect(repo.getFileByPath("m.mkv")!.probe_error).toBe("boom");
});

test("hideEmpty hides show whose only item has no live file, and revives it when file returns", () => {
  const repo = setup();
  const show = repo.insertShow({ category: "tv", parsed_key: "tv:foo:", parsed_title: "foo", title: "Foo" }, 1);
  const item = repo.insertItem(
    { type: "episode", category: "tv", show_id: show, season_number: 1, episode_number: 1, parsed_title: "foo", title: "E1" }, 1);
  const f = repo.upsertFile(file("foo/s01e01.mkv"));
  repo.setFileItem(f, item);

  repo.hideEmpty(10);
  expect(repo.getItem(item)!.deleted_at).toBeNull();
  expect(repo.getShow(show)!.deleted_at).toBeNull();

  repo.markFilesDeletedExcept([], 20);
  repo.hideEmpty(30);
  expect(repo.getItem(item)!.deleted_at).toBe(30);
  expect(repo.getShow(show)!.deleted_at).toBe(30);
  expect(repo.listShows("tv")).toHaveLength(0);
  expect(repo.listShows("tv", { includeDeleted: true })).toHaveLength(1);
  expect(repo.listEpisodes(show)).toHaveLength(0);

  repo.hideEmpty(40); // timestamp not bumped
  expect(repo.getItem(item)!.deleted_at).toBe(30);
  expect(repo.getShow(show)!.deleted_at).toBe(30);

  repo.undeleteFile(f);
  repo.hideEmpty(50);
  expect(repo.getItem(item)!.deleted_at).toBeNull();
  expect(repo.getShow(show)!.deleted_at).toBeNull();
  expect(repo.listShows("tv")).toHaveLength(1);
  expect(repo.listEpisodes(show)).toHaveLength(1);
});

test("hideEmpty keeps show visible while another item is live", () => {
  const repo = setup();
  const show = repo.insertShow({ category: "tv", parsed_key: "tv:foo:", parsed_title: "foo", title: "Foo" }, 1);
  const e1 = repo.insertItem({ type: "episode", category: "tv", show_id: show, season_number: 1, episode_number: 1, parsed_title: "foo", title: "E1" }, 1);
  const e2 = repo.insertItem({ type: "episode", category: "tv", show_id: show, season_number: 1, episode_number: 2, parsed_title: "foo", title: "E2" }, 1);
  repo.setFileItem(repo.upsertFile(file("1.mkv")), e1);
  repo.setFileItem(repo.upsertFile(file("2.mkv")), e2);
  repo.markFilesDeletedExcept(["2.mkv"], 5);
  repo.hideEmpty(6);
  expect(repo.getItem(e1)!.deleted_at).toBe(6);
  expect(repo.getItem(e2)!.deleted_at).toBeNull();
  expect(repo.getShow(show)!.deleted_at).toBeNull();
});

test("searchTitles matches case-insensitively on show and movie titles", () => {
  const repo = setup();
  repo.insertShow({ category: "tv", parsed_key: "tv:the office:", parsed_title: "the office", title: "The Office" }, 1);
  repo.insertShow({ category: "tv", parsed_key: "tv:other:", parsed_title: "other", title: "Other" }, 1);
  const m = repo.insertItem({ type: "movie", category: "movies", parsed_key: "movies:office space:1999", parsed_title: "office space", title: "Office Space" }, 1);
  const r = repo.searchTitles("oFFice");
  expect(r.shows.map((s) => s.title)).toEqual(["The Office"]);
  expect(r.movies.map((s) => s.title)).toEqual(["Office Space"]);
  repo.setItemDeleted(m, 5);
  expect(repo.searchTitles("office").movies).toHaveLength(0);
});

test("show/season/item meta updates touch only given keys", () => {
  const repo = setup();
  const show = repo.insertShow({ category: "tv", parsed_key: "k", parsed_title: "k", title: "K", year: 2000 }, 1);
  expect(repo.getShowByKey("k")!.id).toBe(show);
  repo.updateShowMeta(show, { overview: "hi", rating: 7.5 });
  let s = repo.getShow(show)!;
  expect(s.overview).toBe("hi");
  expect(s.rating).toBe(7.5);
  expect(s.year).toBe(2000);
  repo.updateShowMeta(show, {});
  repo.setShowMatchError(show, "nomatch", 77);
  s = repo.getShow(show)!;
  expect([s.match_error, s.match_attempted_at]).toEqual(["nomatch", 77]);

  repo.upsertSeason(show, 1, { name: "S1", episode_count: 3 });
  repo.upsertSeason(show, 1, { name: "Season 1" });
  repo.upsertSeason(show, 2, {});
  const seasons = repo.listSeasons(show);
  expect(seasons.map((x) => x.number)).toEqual([1, 2]);
  expect(seasons[0]!.name).toBe("Season 1");
  expect(seasons[0]!.episode_count).toBe(3);

  const ep = repo.insertItem({ type: "episode", category: "tv", show_id: show, season_number: 1, episode_number: 2, parsed_title: "k", title: "E" }, 1);
  expect(repo.getEpisode(show, 1, 2)!.id).toBe(ep);
  expect(repo.getEpisode(show, 1, 3)).toBeNull();
  repo.updateItemMeta(ep, { title: "New", runtime: 42 });
  expect(repo.getItem(ep)!.title).toBe("New");
  expect(repo.getItem(ep)!.runtime).toBe(42);
  repo.setShowDeleted(show, 9);
  expect(repo.getShow(show)!.deleted_at).toBe(9);
  repo.setShowDeleted(show, null);
  expect(repo.getShow(show)!.deleted_at).toBeNull();
});

test("movies list by category and getMovieByKey", () => {
  const repo = setup();
  const m = repo.insertItem({ type: "movie", category: "movies", parsed_key: "mk", parsed_title: "a", title: "A" }, 1);
  repo.insertItem({ type: "movie", category: "kids", parsed_key: "mk2", parsed_title: "b", title: "B" }, 1);
  expect(repo.getMovieByKey("mk")!.id).toBe(m);
  expect(repo.listMovies("movies").map((x) => x.id)).toEqual([m]);
});

test("settings, overrides and cache", () => {
  const repo = setup();
  expect(repo.getSetting("a")).toBeNull();
  repo.setSetting("a", "1");
  repo.setSetting("a", "2");
  expect(repo.getSetting("a")).toBe("2");

  expect(repo.getOverride("k")).toBeNull();
  repo.setOverride("k", "movie", 5);
  repo.setOverride("k", "tv", 6);
  expect(repo.getOverride("k")).toEqual({ parsed_key: "k", item_type: "tv", tmdb_id: 6 });

  expect(repo.cacheGet("c", 1000)).toBeNull();
  repo.cacheSet("c", '{"a":1}');
  expect(repo.cacheGet("c", 60_000)).toBe('{"a":1}');
  expect(repo.cacheGet("c", -1)).toBeNull();
});

test("setItemMatchError and moveEpisode keep the item id and reset episode meta", () => {
  const repo = setup();
  const show = repo.insertShow({ category: "anime", parsed_key: "k", parsed_title: "k", title: "K" }, 1);
  const ep = repo.insertItem({ type: "episode", category: "anime", show_id: show, season_number: 1, episode_number: 13, parsed_title: "k", title: "Episode 13" }, 1);
  repo.updateItemMeta(ep, { overview: "old", runtime: 24 });
  repo.setItemMatchError(ep, "Not on TMDB", 50);
  expect([repo.getItem(ep)!.match_error, repo.getItem(ep)!.match_attempted_at]).toEqual(["Not on TMDB", 50]);

  repo.moveEpisode(ep, 2, 1, 2, "Episode 1");
  const moved = repo.getItem(ep)!;
  expect([moved.season_number, moved.episode_number, moved.episode_end, moved.title]).toEqual([2, 1, 2, "Episode 1"]);
  expect([moved.overview, moved.runtime, moved.tmdb_id, moved.match_error, moved.match_attempted_at]).toEqual([null, null, null, null, null]);
  expect(repo.getEpisode(show, 2, 1)!.id).toBe(ep);
  expect(repo.getEpisode(show, 1, 13)).toBeNull();
});
