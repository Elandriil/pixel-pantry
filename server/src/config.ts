import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export interface Config {
  mediaRoot: string;
  port: number;
  tmdbApiKey: string;
  opensubtitles: { apiKey: string; username: string; password: string };
}

export const DEFAULT_CONFIG: Config = {
  mediaRoot: "",
  port: 8765,
  tmdbApiKey: "",
  opensubtitles: { apiKey: "", username: "", password: "" },
};

export function loadConfig(path: string): Config {
  let raw: any = {};
  try {
    raw = JSON.parse(readFileSync(path, "utf8")) ?? {};
  } catch {}
  const os = raw.opensubtitles ?? {};
  const str = (v: unknown, d: string) => (typeof v === "string" ? v : d);
  return {
    mediaRoot: str(raw.mediaRoot, DEFAULT_CONFIG.mediaRoot),
    port: typeof raw.port === "number" ? raw.port : DEFAULT_CONFIG.port,
    tmdbApiKey: str(raw.tmdbApiKey, DEFAULT_CONFIG.tmdbApiKey),
    opensubtitles: {
      apiKey: str(os.apiKey, ""),
      username: str(os.username, ""),
      password: str(os.password, ""),
    },
  };
}

export function saveConfig(path: string, c: Config): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(c, null, 2));
}
