import { expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Logger } from "../src/log";
import { tmpDir } from "./helpers";

test("writes lines to file and keeps ring buffer", () => {
  const f = join(tmpDir(), "server.log");
  const log = new Logger(f, { ringSize: 2 });
  log.info("a");
  log.warn("b", { x: 1 });
  log.error("c");
  expect(readFileSync(f, "utf8").split("\n").filter(Boolean)).toHaveLength(3);
  expect(log.recent()).toEqual([expect.stringContaining('WARN b {"x":1}'), expect.stringContaining("ERROR c")]);
});

test("rotates when file exceeds maxBytes", () => {
  const f = join(tmpDir(), "server.log");
  const log = new Logger(f, { maxBytes: 100 });
  for (let i = 0; i < 10; i++) log.info("x".repeat(30));
  expect(existsSync(f + ".1")).toBe(true);
});
