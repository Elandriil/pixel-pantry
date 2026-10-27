import { expect, test } from "bun:test";
import type { Category } from "../src/types";
import { normalizeTitle, parseVideoPath } from "../src/library/parse";

const rows: [string, string, number | null, number | null, number[], boolean][] = [
  ["tv/Severance/Season 02/Severance S02E04 Woes Hollow 1080p.mkv", "Severance", null, 2, [4], false],
  ["tv/Severance/Severance - 2x04 - Woes Hollow.mkv", "Severance", null, 2, [4], false],
  ["tv/Season 03/The.Bear.S03E01-E02.1080p.WEB.mkv", "The Bear", null, 3, [1, 2], false],
  ["tv/Dark (2017)/Season 1/Episode 5.mkv", "Dark", 2017, 1, [5], false],
  ["tv/Some.Show.S01.1080p.WEB-DL/Some.Show.S01E01.mkv", "Some Show", null, 1, [1], false],
  ["anime/[SubsPlease] Frieren - 05 (1080p) [ABCD1234].mkv", "Frieren", null, null, [5], true],
  ["anime/Frieren/Frieren - 28.mkv", "Frieren", null, null, [28], true],
  ["anime/Frieren/Frieren S01E05.mkv", "Frieren", null, 1, [5], false],
  ["movies/Dune Part Two (2024)/Dune.Part.Two.2024.2160p.mkv", "Dune Part Two", 2024, null, [], false],
  ["movies/Dune.Part.Two.2024.1080p.BluRay.x264.mkv", "Dune Part Two", 2024, null, [], false],
  ["movies/Heat/Heat.mkv", "Heat", null, null, [], false],
  ["movies/Dune.Part.Two.mkv", "Dune Part Two", null, null, [], false],
  ["anime/Frieren/Frieren - 28v2.mkv", "Frieren", null, null, [28], true],
  ["tv/Weird Folder/clip.mkv", "Weird Folder", null, null, [], false],
  ["tv/Doctor.Who.2005.S01E01.mkv", "Doctor Who", 2005, 1, [1], false],
  ["tv/Doctor Who (2005)/Season 01/Doctor.Who.2005.S01E02.mkv", "Doctor Who", 2005, 1, [2], false],
  ["movies/Blade Runner 2049 (2017)/Blade.Runner.2049.2017.1080p.mkv", "Blade Runner 2049", 2017, null, [], false],
  ["anime/[Grp] Frieren S2 - 03.mkv", "Frieren", null, 2, [3], false],
  ["anime/Kusuriya no Hitorigoto 2nd Season - 03.mkv", "Kusuriya no Hitorigoto", null, 2, [3], false],
  ["anime/Frieren Season 2 - 03.mkv", "Frieren", null, 2, [3], false],
  ["anime/[Grp] Frieren Part 2 - 03 (1080p).mkv", "Frieren", null, 2, [3], false],
  ["anime/Frieren S2/Frieren S2 E03.mkv", "Frieren", null, 2, [3], false],
  ["anime/One Piece - 1100.mkv", "One Piece", null, null, [1100], true],
  ["tv/Ape maga[1970] 01- L'ape magà comincia a volare.mp4", "Ape maga", 1970, null, [1], false],
  ["tv/Ape maga[1970]/Ape maga[1970] 02- Vai,vai Ape magà.mp4", "Ape maga", 1970, null, [2], false],
  ["tv/Lost [2004]/Lost S01E01.mkv", "Lost", 2004, 1, [1], false],
  ["tv/Band Of Brothers/Band Of Brothers Documentry 2001 (1080p x265 Joy).mkv", "Band Of Brothers Documentry", 2001, null, [], false],
  ["tv/Band Of Brothers/Band Of Brothers Part 1 Currahee (1080p x265 Joy).mkv", "Band Of Brothers", null, null, [1], false],
  ["tv/Buffy The Vampire Slayer Complete Series DVDRip 576p x264 (MKV)/Season 2/Buffy.S02E01.mkv", "Buffy", null, 2, [1], false],
  ["tv/Buffy The Vampire Slayer (1992) 720p BRRip x264 AC3 5.1/Buffy.1x05.mkv", "Buffy", 1992, 1, [5], false],
  ["tv/The Beast in Me (2025)/The Beast in Me (2025) - S01E01 - Sick Puppy (1080p NF WEB-DL x265 Ghost).mkv", "The Beast in Me", 2025, 1, [1], false],
  ["tv/McDonald and Dodds/McDonald and Dodds_S04E01_The Rule of 3.mkv", "McDonald and Dodds", null, 4, [1], false],
  ["anime/[SubsPlease] One Piece - 1100 (1080p) [ABCD].mkv", "One Piece", null, null, [1100], true],
];

test.each(rows)("parseVideoPath %s", (path, title, year, season, episodes, absolute) => {
  const p = parseVideoPath(path, path.split("/")[0] as Category)!;
  expect(p.title).toBe(title);
  expect(p.year).toBe(year);
  expect(p.season).toBe(season);
  expect(p.episodes).toEqual(episodes);
  expect(p.absolute).toBe(absolute);
});

test("extras are never episodes: S01EX1, Extra.*, -trailer, -featurette", () => {
  for (const p of [
    "tv/Buffy/Buffy.S01EX1.Interview.With.Joss.Whedon.&.David.Boreanaz.mkv",
    "tv/Buffy/Buffy.S06EXA.Outtakes.mkv",
    "tv/Buffy The Vampire Slayer (1992) 720p BRRip x264 AC3 5.1/Extra.Theatrical.Trailer.mkv",
    "movies/Heat (1995)/Heat (1995)-trailer.mkv",
    "movies/Heat (1995)/Heat.1995.featurette.mkv",
  ]) expect(parseVideoPath(p, p.split("/")[0] as Category)).toBeNull();
  // titles that merely contain such a word are fine
  expect(parseVideoPath("movies/The Interview (2014)/The Interview (2014).mkv", "movies")!.title).toBe("The Interview");
  expect(parseVideoPath("tv/Extras/Extras S01E01.mkv", "tv")!.title).toBe("Extras");
});

test("release-tag folder names are cut at the first tag", () => {
  // the folder names the show when the file name does not
  expect(parseVideoPath("tv/Shetland.S01.720p.DSNP.WEBRip.x264-GalaxyTV/S01E01.mkv", "tv")!.title).toBe("Shetland");
  expect(parseVideoPath("tv/Buffy The Vampire Slayer Complete Series DVDRip 576p x264 (MKV)/Season 2/S02E01.mkv", "tv")).toMatchObject({
    title: "Buffy The Vampire Slayer",
    season: 2,
    episodes: [1],
  });
  expect(parseVideoPath("tv/Buffy The Vampire Slayer (1992) 720p BRRip x264 AC3 5.1/1x05.mkv", "tv")).toMatchObject({
    title: "Buffy The Vampire Slayer",
    year: 1992,
  });
});

test("a file directly in the library folder is null", () => {
  expect(parseVideoPath("tv", "tv")).toBeNull();
});

test("the library id is not part of the title and the type comes from the library", () => {
  const p = parseVideoPath("lib-0a1b2c3d/Heat (1995)/Heat.mkv", "movies")!;
  expect(p).toMatchObject({ category: "movies", kind: "movie", title: "Heat", year: 1995, parsedKey: "movies:heat:1995" });
  expect(parseVideoPath("lib-0a1b2c3d/[Grp] Frieren - 03.mkv", "anime")).toMatchObject({ absolute: true, episodes: [3] });
});

test("parsedKey", () => {
  expect(parseVideoPath(rows[0]![0], "tv")!.parsedKey).toBe("tv:severance:");
});

test("normalizeTitle strips diacritics and punctuation", () => {
  expect(normalizeTitle("Shōgun: The Series!")).toBe("shogun the series");
});
