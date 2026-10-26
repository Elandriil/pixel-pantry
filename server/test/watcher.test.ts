import { expect, test } from "bun:test";
import { join } from "node:path";
import { watchRoot } from "../src/library/watcher";
import { tmpDir, touch } from "./helpers";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

test("debounces a burst of video files into one onChange", async () => {
  const root = tmpDir();
  let calls = 0;
  const w = watchRoot(root, () => calls++, { debounceMs: 200, stabilityThresholdMs: 100 });
  await sleep(300); // let the watcher become ready
  touch(join(root, "a.mkv"), 10);
  touch(join(root, "b.mp4"), 10);
  touch(join(root, "c.avi"), 10);
  touch(join(root, "notes.txt"), 10);
  await sleep(1000);
  expect(calls).toBe(1);
  await w.close();
});

test("missing root does not throw and close resolves", async () => {
  const w = watchRoot(join(tmpDir(), "nope"), () => {}, { debounceMs: 50, stabilityThresholdMs: 50 });
  await w.close();
});
