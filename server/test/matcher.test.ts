import { expect, test } from "bun:test";
import { pickMatch } from "../src/metadata/matcher";

const r = (id: number, title: string, year: number | null, popularity: number, originalTitle: string | null = null) => ({
  id, title, originalTitle, year, popularity,
});

test("exact title + year beats popular partial", () => {
  const m = pickMatch([r(1, "Dune Part Two Making Of", 2024, 900), r(2, "Dune Part Two", 2024, 10)], "Dune Part Two", 2024);
  expect(m?.result.id).toBe(2);
  expect(m?.confidence).toBe("high");
});

test("year within one still matches", () => {
  expect(pickMatch([r(1, "Dark", 2018, 5)], "Dark", 2017)?.result.id).toBe(1);
});

test("popularity breaks ties", () => {
  expect(pickMatch([r(1, "Heat", 1995, 5), r(2, "Heat", 1995, 50)], "Heat", null)?.result.id).toBe(2);
});

test("empty results is null", () => {
  expect(pickMatch([], "Heat", 1995)).toBeNull();
});

// Was "unrelated titles are null" with year null: the low-confidence fallback now takes TMDB's top
// result when the year does not contradict it, so only a contradicting year gives null.
test("unrelated titles are null when the year does not fit, low confidence when there is no year", () => {
  expect(pickMatch([r(1, "Completely Different", 1999, 99)], "Heat", 1995)).toBeNull();
  expect(pickMatch([r(1, "Completely Different", 1999, 99)], "Heat", null)).toEqual({
    result: r(1, "Completely Different", 1999, 99),
    confidence: "low",
  });
});

test("starts-with needs a word boundary: Heat is not Heated Rivalry", () => {
  expect(pickMatch([r(1, "Heated Rivalry", 2025, 80)], "Heat", 1995)).toBeNull();
  expect(pickMatch([r(1, "Heated Rivalry", 2025, 80)], "Heat", null)?.confidence).toBe("low");
  expect(pickMatch([r(1, "Dune Part Two", 2024, 1)], "Dune", 2024)?.confidence).toBe("high");
});

test("romaji title without an original-title hit falls back to TMDB's first result at low confidence", () => {
  const results = [
    r(209867, "Frieren: Beyond Journey's End", 2023, 30, "葬送のフリーレン"),
    r(1, "Frieren Shorts", 2024, 1, null),
  ];
  expect(pickMatch(results, "Sousou no Frieren", null)).toEqual({ result: results[0]!, confidence: "low" });
});

test("romaji title matches original_title at high confidence", () => {
  const results = [r(5, "Titans", 2018, 90), r(1429, "Attack on Titan", 2013, 60, "Shingeki no Kyojin")];
  expect(pickMatch(results, "Shingeki no Kyojin", null)).toEqual({ result: results[1]!, confidence: "high" });
});

test("low-confidence fallback needs the top result's year within one", () => {
  expect(pickMatch([r(1, "Something Else", 2021, 1)], "Kusuriya", 2023)).toBeNull();
  expect(pickMatch([r(1, "Something Else", 2022, 1)], "Kusuriya", 2023)?.confidence).toBe("low");
  expect(pickMatch([r(1, "Something Else", null, 1)], "Kusuriya", 2023)).toBeNull();
});
