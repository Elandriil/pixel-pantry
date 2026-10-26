import { expect, test } from "bun:test";
import { join } from "node:path";
import { outsideCategories, watchRoot } from "../src/library/watcher";
import { tmpDir, touch } from "./helpers";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

test("debounces a burst of video files into one onChange", async () => {
  const root = tmpDir();
  let calls = 0;
  const w = watchRoot(root, () => calls++, { debounceMs: 200, stabilityThresholdMs: 100 });
  await sleep(300); // let the watcher become ready
  touch(join(root, "tv", "a.mkv"), 10);
  touch(join(root, "anime", "b.mp4"), 10);
  touch(join(root, "movies", "c.avi"), 10);
  touch(join(root, "tv", "notes.txt"), 10);
  await sleep(1000);
  expect(calls).toBe(1);
  await w.close();
});

test("missing root does not throw and close resolves", async () => {
  const w = watchRoot(join(tmpDir(), "nope"), () => {}, { debounceMs: 50, stabilityThresholdMs: 50 });
  await w.close();
});

test("ignores non-video files", async () => {
  const root = tmpDir();
  let calls = 0;
  const w = watchRoot(root, () => calls++, { debounceMs: 100, stabilityThresholdMs: 100 });
  await sleep(300);
  touch(join(root, "tv", "notes.txt"), 10);
  await sleep(800);
  expect(calls).toBe(0);
  await w.close();
});

test("ignores video files outside tv, anime and movies", async () => {
  const root = tmpDir();
  let calls = 0;
  const w = watchRoot(root, () => calls++, { debounceMs: 100, stabilityThresholdMs: 100 });
  await sleep(300);
  touch(join(root, "a.mkv"), 10);
  touch(join(root, "Downloads", "b.mkv"), 10);
  await sleep(800);
  expect(calls).toBe(0);
  await w.close();
});

test("outsideCategories keeps the root and the category folders", () => {
  const root = join("/m", "media");
  expect(outsideCategories(root, root)).toBe(false);
  expect(outsideCategories(root, join(root, "tv"))).toBe(false);
  expect(outsideCategories(root, join(root, "anime", "Frieren", "x.mkv"))).toBe(false);
  expect(outsideCategories(root, join(root, "Downloads"))).toBe(true);
  expect(outsideCategories(root, join(root, "tvshows", "x.mkv"))).toBe(true);
});
