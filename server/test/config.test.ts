import { expect, test } from "bun:test";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { DEFAULT_CONFIG, loadConfig, saveConfig } from "../src/config";
import { tmpDir } from "./helpers";

test("loadConfig returns defaults when file missing", () => {
  expect(loadConfig(join(tmpDir(), "config.json"))).toEqual(DEFAULT_CONFIG);
});

test("saveConfig then loadConfig round-trips and fills missing keys", () => {
  const p = join(tmpDir(), "config.json");
  writeFileSync(p, JSON.stringify({ mediaRoot: "D:\\Media", bogus: 1 }));
  const c = loadConfig(p);
  expect(c.mediaRoot).toBe("D:\\Media");
  expect(c.port).toBe(8765);
  expect((c as any).bogus).toBeUndefined();
  saveConfig(p, { ...c, port: 9000 });
  expect(loadConfig(p).port).toBe(9000);
});
