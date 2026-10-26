import { expect, test } from "bun:test";
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
  ["tv/Weird Folder/clip.mkv", "Weird Folder", null, null, [], false],
];

test.each(rows)("parseVideoPath %s", (path, title, year, season, episodes, absolute) => {
  const p = parseVideoPath(path)!;
  expect(p.title).toBe(title);
  expect(p.year).toBe(year);
  expect(p.season).toBe(season);
  expect(p.episodes).toEqual(episodes);
  expect(p.absolute).toBe(absolute);
});

test("non-category folder is null", () => {
  expect(parseVideoPath("photos/x.mkv")).toBeNull();
});

test("parsedKey", () => {
  expect(parseVideoPath(rows[0]![0])!.parsedKey).toBe("tv:severance:");
});

test("normalizeTitle strips diacritics and punctuation", () => {
  expect(normalizeTitle("Shōgun: The Series!")).toBe("shogun the series");
});
