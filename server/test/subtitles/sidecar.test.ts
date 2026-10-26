import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { listSidecars, nextSidecarName, saveSidecar } from "../../src/subtitles/sidecar";
import { tmpDir, touch } from "../helpers";

test("listSidecars finds tracks for the video only", async () => {
  const d = tmpDir();
  for (const f of ["Show.mkv", "Show.srt", "Show.en.srt", "Show.en.2.srt", "Show.ass", "Other.srt", "Show.nfo"]) {
    touch(join(d, f));
  }
  const tracks = await listSidecars(join(d, "Show.mkv"));
  const byName = Object.fromEntries(tracks.map((t) => [t.path.split("/").pop(), t]));
  expect(tracks).toHaveLength(4);
  expect(byName["Show.en.srt"]).toMatchObject({ label: "English", lang: "en", format: "srt" });
  expect(byName["Show.en.2.srt"]).toMatchObject({ label: "English #2", lang: "en", format: "srt" });
  expect(byName["Show.srt"]).toMatchObject({ label: "Sidecar", lang: null, format: "srt" });
  expect(byName["Show.ass"]).toMatchObject({ label: "Sidecar (ASS)", lang: null, format: "ass" });
});

test("listSidecars maps ssa to ass and upper-cases unknown language codes", async () => {
  const d = tmpDir();
  for (const f of ["A.mkv", "A.fr.ssa", "A.eng.vtt"]) touch(join(d, f));
  const tracks = await listSidecars(join(d, "A.mkv"));
  const byName = Object.fromEntries(tracks.map((t) => [t.path.split("/").pop(), t]));
  expect(byName["A.fr.ssa"]).toMatchObject({ label: "FR", lang: "fr", format: "ass" });
  expect(byName["A.eng.vtt"]).toMatchObject({ label: "English", lang: "eng", format: "vtt" });
});

test("nextSidecarName picks the first free slot", () => {
  const v = "Show.mkv";
  expect(nextSidecarName(v, [])).toBe("Show.en.srt");
  expect(nextSidecarName(v, ["Show.en.srt"])).toBe("Show.en.2.srt");
  expect(nextSidecarName(v, ["Show.en.srt", "Show.en.2.srt"])).toBe("Show.en.3.srt");
});

test("saveSidecar writes the next free file and returns its path", async () => {
  const d = tmpDir();
  touch(join(d, "Show.mkv"));
  const p1 = await saveSidecar(join(d, "Show.mkv"), "one");
  const p2 = await saveSidecar(join(d, "Show.mkv"), "two");
  expect(p1).toBe(join(d, "Show.en.srt"));
  expect(p2).toBe(join(d, "Show.en.2.srt"));
  expect(readFileSync(p2, "utf8")).toBe("two");
});
