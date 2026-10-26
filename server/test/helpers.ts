import { afterAll } from "bun:test";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

const created: string[] = [];

afterAll(() => {
  for (const d of created.splice(0)) rmSync(d, { recursive: true, force: true });
});

export function tmpDir(): string {
  const d = mkdtempSync(join(tmpdir(), "pantry-test-"));
  created.push(d);
  return d;
}

export function touch(path: string, bytes = 0): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, new Uint8Array(bytes));
}
