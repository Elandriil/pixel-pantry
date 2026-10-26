import { existsSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export function isCompiled(): boolean {
  const name = basename(process.execPath).toLowerCase();
  return name !== "bun" && name !== "bun.exe";
}

export function dataDir(): string {
  if (process.env.PANTRY_DATA_DIR) return process.env.PANTRY_DATA_DIR;
  if (isCompiled()) return join(dirname(process.execPath), "data");
  return join(dirname(fileURLToPath(import.meta.url)), "..", "data");
}

export function resolveFfprobePath(): string {
  if (process.env.FFPROBE_PATH) return process.env.FFPROBE_PATH;
  const beside = join(dirname(process.execPath), "ffprobe.exe");
  if (existsSync(beside)) return beside;
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const p = require("ffprobe-static")?.path;
    if (typeof p === "string" && p) return p;
  } catch {}
  return "ffprobe";
}
