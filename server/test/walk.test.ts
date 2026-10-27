import { expect, test } from "bun:test";
import { statSync } from "node:fs";
import { join } from "node:path";
import { listVideoFiles } from "../src/library/walk";
import { legacyLibraries, tmpDir, touch } from "./helpers";

test("listVideoFiles walks only the library folders and applies skip rules", async () => {
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

  const files = await listVideoFiles(legacyLibraries(root));
  expect(files.map((f) => f.relPath)).toEqual([
    "anime/B - 01.mp4",
    "movies/C (2020)/C.mkv",
    "tv/A/Season 01/A S01E01.mkv",
  ]);
  expect(files.map((f) => f.absPath)).toEqual([
    join(root, "anime/B - 01.mp4"),
    join(root, "movies/C (2020)/C.mkv"),
    join(root, "tv/A/Season 01/A S01E01.mkv"),
  ]);
  expect(files.map((f) => f.category)).toEqual(["anime", "movies", "tv"]);
  expect(files.map((f) => f.size)).toEqual([3, 7, 5]);
  for (const f of files) expect(f.mtime).toBeGreaterThan(0);
});

test("listVideoFiles prefixes each file with its library id and takes the library's type", async () => {
  const base = tmpDir();
  touch(join(base, "October Movies/Heat (1995)/Heat.mkv"), 2);
  touch(join(base, "October Movies/notes.txt"));
  touch(join(base, "stray.mkv"));
  const files = await listVideoFiles([
    { id: "lib-0a1b2c3d", path: join(base, "October Movies"), category: "movies" },
    { id: "lib-missing", path: join(base, "nope"), category: "tv" },
  ]);
  expect(files).toEqual([
    {
      relPath: "lib-0a1b2c3d/Heat (1995)/Heat.mkv",
      absPath: join(base, "October Movies/Heat (1995)/Heat.mkv"),
      category: "movies",
      size: 2,
      mtime: files[0]!.mtime,
    },
  ]);
});

test("listVideoFiles skips entries that vanish mid-scan and keeps siblings", async () => {
  const root = tmpDir();
  touch(join(root, "tv/A/a.mkv"));
  touch(join(root, "tv/A/gone.mkv"));
  touch(join(root, "tv/A/z.mkv"));
  touch(join(root, "movies/M/m.mkv"));
  const racing = async (p: string) => {
    if (p.endsWith("gone.mkv")) throw Object.assign(new Error("gone"), { code: "ENOENT" });
    return statSync(p);
  };
  const files = await listVideoFiles(legacyLibraries(root), racing);
  expect(files.map((f) => f.relPath)).toEqual(["movies/M/m.mkv", "tv/A/a.mkv", "tv/A/z.mkv"]);
});

test("listVideoFiles propagates non-ENOENT errors", async () => {
  const root = tmpDir();
  touch(join(root, "tv/a.mkv"));
  const failing = async () => {
    throw Object.assign(new Error("denied"), { code: "EACCES" });
  };
  await expect(listVideoFiles(legacyLibraries(root), failing)).rejects.toThrow("denied");
});
