import { expect, test } from "bun:test";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { openSubtitlesHash } from "../../src/subtitles/hash";
import { tmpDir } from "../helpers";

const CHUNK = 64 * 1024;
const MASK = (1n << 64n) - 1n;

function reference(buf: Buffer): string {
  const n = Math.min(buf.length, CHUNK);
  const chunks = [buf.subarray(0, n), buf.subarray(Math.max(0, buf.length - CHUNK), Math.max(0, buf.length - CHUNK) + n)];
  let sum = BigInt(buf.length);
  for (const c of chunks) {
    for (let i = 0; i + 8 <= c.length; i += 8) sum = (sum + c.readBigUInt64LE(i)) & MASK;
  }
  return sum.toString(16).padStart(16, "0");
}

function fileOf(size: number): { path: string; buf: Buffer } {
  const buf = Buffer.alloc(size);
  let x = 123456789;
  for (let i = 0; i < size; i++) {
    x = (x * 1103515245 + 12345) & 0x7fffffff;
    buf[i] = x >> 16;
  }
  const path = join(tmpDir(), `f${size}.bin`);
  writeFileSync(path, buf);
  return { path, buf };
}

test("hash of a 200 KiB file matches an independent BigInt loop", async () => {
  const { path, buf } = fileOf(200 * 1024);
  const h = await openSubtitlesHash(path);
  expect(h).toMatch(/^[0-9a-f]{16}$/);
  expect(h).toBe(reference(buf));
  expect(readFileSync(path).length).toBe(200 * 1024);
});

test("hash handles overlapping chunks, tiny files and a trailing partial word", async () => {
  for (const size of [100 * 1024, 1000, 13, 0, 128 * 1024 + 5]) {
    const { path, buf } = fileOf(size);
    expect(await openSubtitlesHash(path)).toBe(reference(buf));
  }
});
