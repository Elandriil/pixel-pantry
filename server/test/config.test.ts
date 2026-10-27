import { expect, test } from "bun:test";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  DEFAULT_CONFIG,
  librariesFromMediaRoot,
  libraryConflict,
  libraryFilePath,
  loadConfig,
  newLibraryId,
  saveConfig,
} from "../src/config";
import { tmpDir } from "./helpers";

test("loadConfig returns defaults when file missing", () => {
  expect(loadConfig(join(tmpDir(), "config.json"))).toEqual(DEFAULT_CONFIG);
  expect(DEFAULT_CONFIG.startWithWindows).toBe(true);
});

test("saveConfig then loadConfig round-trips and fills missing keys", () => {
  const p = join(tmpDir(), "config.json");
  const libraries = [{ id: "lib-0a1b2c3d", path: "D:\\October Movies", category: "movies" as const }];
  writeFileSync(p, JSON.stringify({ libraries, startWithWindows: false, bogus: 1 }));
  const c = loadConfig(p);
  expect(c.libraries).toEqual(libraries);
  expect(c.startWithWindows).toBe(false);
  expect(c.port).toBe(8765);
  expect((c as any).bogus).toBeUndefined();
  saveConfig(p, { ...c, port: 9000 });
  expect(loadConfig(p)).toEqual({ ...c, port: 9000 });
});

test("loadConfig drops malformed library entries and duplicate ids", () => {
  const p = join(tmpDir(), "config.json");
  writeFileSync(
    p,
    JSON.stringify({
      libraries: [
        { id: "tv", path: "D:\\TV", category: "tv" },
        { id: "tv", path: "D:\\TV2", category: "tv" },
        { id: "a/b", path: "D:\\X", category: "tv" },
        { id: "lib-1", path: "", category: "tv" },
        { id: "lib-2", path: "D:\\P", category: "photos" },
        "junk",
      ],
    }),
  );
  expect(loadConfig(p).libraries).toEqual([{ id: "tv", path: "D:\\TV", category: "tv" }]);
});

test("a pre-library config's mediaRoot becomes one library per existing category subfolder", () => {
  const p = join(tmpDir(), "config.json");
  writeFileSync(p, JSON.stringify({ mediaRoot: "D:\\Media", port: 9000 }));
  const exists = (path: string) => path.endsWith("tv") || path.endsWith("movies");
  const c = loadConfig(p, exists);
  expect(c.libraries).toEqual([
    { id: "tv", path: join("D:\\Media", "tv"), category: "tv" },
    { id: "movies", path: join("D:\\Media", "movies"), category: "movies" },
  ]);
  expect(c.port).toBe(9000);
  // the drive is unplugged right now: all three, so nothing is lost
  expect(librariesFromMediaRoot("D:\\Media", () => false).map((l) => l.id)).toEqual(["tv", "anime", "movies"]);
  expect(librariesFromMediaRoot("", () => true)).toEqual([]);
});

test("newLibraryId is lib- plus 8 hex chars", () => {
  const ids = new Set(Array.from({ length: 50 }, newLibraryId));
  expect(ids.size).toBe(50);
  for (const id of ids) expect(id).toMatch(/^lib-[0-9a-f]{8}$/);
});

test("libraryFilePath joins the library folder with the path inside it", () => {
  const libraries = [
    { id: "tv", path: "/m/tv", category: "tv" as const },
    { id: "lib-0a1b2c3d", path: "/m/October Movies", category: "movies" as const },
  ];
  expect(libraryFilePath(libraries, "tv/Severance/S1E1.mkv")).toBe(join("/m/tv", "Severance/S1E1.mkv"));
  expect(libraryFilePath(libraries, "lib-0a1b2c3d/Heat.mkv")).toBe(join("/m/October Movies", "Heat.mkv"));
  expect(libraryFilePath(libraries, "lib-0a1b2c3d")).toBe("/m/October Movies");
  expect(libraryFilePath(libraries, "lib-gone/Heat.mkv")).toBeNull();
  expect(libraryFilePath(libraries, "tv2/x.mkv")).toBeNull();
});

test("libraryConflict rejects the same, a nested and an enclosing folder", () => {
  const tv = { id: "tv", path: "/m/tv", category: "tv" as const };
  const lib = (path: string) => ({ id: "x", path, category: "movies" as const });
  expect(libraryConflict(lib("/m/tv/"), [tv])).toBe("/m/tv/ is already a library folder");
  expect(libraryConflict(lib("/m/tv/Sub"), [tv])).toBe("/m/tv/Sub is inside the library folder /m/tv");
  expect(libraryConflict(lib("/m"), [tv])).toBe("/m contains the library folder /m/tv");
  expect(libraryConflict(lib("/m/tv2"), [tv])).toBeNull();
  expect(libraryConflict(lib("/m/movies"), [tv])).toBeNull();
});
