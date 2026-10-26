import { expect, test } from "bun:test";
import { pickMatch } from "../src/metadata/matcher";

const r = (id: number, title: string, year: number | null, popularity: number) => ({ id, title, year, popularity });

test("exact title + year beats popular partial", () => {
  const m = pickMatch([r(1, "Dune Part Two Making Of", 2024, 900), r(2, "Dune Part Two", 2024, 10)], "Dune Part Two", 2024);
  expect(m?.id).toBe(2);
});

test("year within one still matches", () => {
  expect(pickMatch([r(1, "Dark", 2018, 5)], "Dark", 2017)?.id).toBe(1);
});

test("popularity breaks ties", () => {
  expect(pickMatch([r(1, "Heat", 1995, 5), r(2, "Heat", 1995, 50)], "Heat", null)?.id).toBe(2);
});

test("empty results is null", () => {
  expect(pickMatch([], "Heat", 1995)).toBeNull();
});

test("unrelated titles are null", () => {
  expect(pickMatch([r(1, "Completely Different", 1999, 99)], "Heat", null)).toBeNull();
});
