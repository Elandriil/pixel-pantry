import { expect, test } from "bun:test";
import { absoluteToSeasonEpisode } from "../src/metadata/anime";

test.each([
  [5, [12, 12], { season: 1, episode: 5 }],
  [12, [12, 12], { season: 1, episode: 12 }],
  [13, [12, 12], { season: 2, episode: 1 }],
  [24, [12, 12], { season: 2, episode: 12 }],
  [28, [12, 12], null],
  [3, [], null],
  [0, [12], null],
] as const)("absoluteToSeasonEpisode(%d, %p)", (abs, counts, expected) => {
  expect(absoluteToSeasonEpisode(abs, [...counts])).toEqual(expected);
});
