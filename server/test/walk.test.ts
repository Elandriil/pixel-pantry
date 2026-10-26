import { expect, test } from "bun:test";
import { join } from "node:path";
import { listVideoFiles } from "../src/library/walk";
import { tmpDir, touch } from "./helpers";

test("listVideoFiles walks only category folders and applies skip rules", async () => {
  const root = tmpDir();
  touch(join(root, "tv/A/Season 01/A S01E01.mkv"), 5);
  touch(join(root, "tv/A/Season 01/A S01E01.sample.mkv"));
  touch(join(root, "tv/A/extras/x.mkv"));
  touch(join(root, "tv/.hidden/y.mkv"));
  touch(join(root, "anime/B - 01.mp4"), 3);
  touch(join(root, "movies/C (2020)/C.mkv"), 7);
  touch(join(root, "movies/C (2020)/C.srt"));
  touch(join(root, "photos/d.mkv"));
  touch(join(root, "other/e.mkv"));

  const files = await listVideoFiles(root);
  expect(files.map((f) => f.relPath)).toEqual([
    "anime/B - 01.mp4",
    "movies/C (2020)/C.mkv",
    "tv/A/Season 01/A S01E01.mkv",
  ]);
  expect(files.map((f) => f.category)).toEqual(["anime", "movies", "tv"]);
  expect(files.map((f) => f.size)).toEqual([3, 7, 5]);
  for (const f of files) expect(f.mtime).toBeGreaterThan(0);
});
