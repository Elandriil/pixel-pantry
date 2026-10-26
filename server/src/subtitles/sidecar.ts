import { readdir, writeFile } from "node:fs/promises";
import { basename, dirname, extname, join } from "node:path";

export interface SidecarTrack {
  path: string;
  label: string;
  lang: string | null;
  format: "srt" | "vtt" | "ass";
}

const FORMATS: Record<string, SidecarTrack["format"]> = { ".srt": "srt", ".vtt": "vtt", ".ass": "ass", ".ssa": "ass" };

function baseOf(videoAbsPath: string): string {
  const name = basename(videoAbsPath);
  return name.slice(0, name.length - extname(name).length);
}

export async function listSidecars(videoAbsPath: string): Promise<SidecarTrack[]> {
  const dir = dirname(videoAbsPath);
  const base = baseOf(videoAbsPath);
  const names = (await readdir(dir)).sort();
  const tracks: SidecarTrack[] = [];
  for (const name of names) {
    const ext = extname(name).toLowerCase();
    const format = FORMATS[ext];
    if (!format || !name.startsWith(`${base}.`)) continue;
    // remainder between the basename and the extension, e.g. ".en.2" or ""
    const middle = name.slice(base.length, name.length - ext.length);
    const m = /^(?:\.([A-Za-z]{2,3}))?(?:\.(\d+))?$/.exec(middle);
    const lang = m?.[1]?.toLowerCase() ?? null;
    const num = m?.[2];
    let label: string;
    if (lang) {
      label = lang === "en" || lang === "eng" ? "English" : lang.toUpperCase();
      if (num) label += ` #${num}`;
    } else {
      label = format === "srt" ? "Sidecar" : `Sidecar (${format.toUpperCase()})`;
      if (num) label += ` #${num}`;
    }
    tracks.push({ path: join(dir, name), label, lang, format });
  }
  return tracks;
}

/** First free `<base>.en.srt`, `<base>.en.2.srt`, ... given the existing file names in the directory. */
export function nextSidecarName(videoAbsPath: string, existing: string[]): string {
  const base = baseOf(videoAbsPath);
  const taken = new Set(existing);
  for (let n = 1; ; n++) {
    const name = n === 1 ? `${base}.en.srt` : `${base}.en.${n}.srt`;
    if (!taken.has(name)) return name;
  }
}

export async function saveSidecar(videoAbsPath: string, content: string): Promise<string> {
  const dir = dirname(videoAbsPath);
  const path = join(dir, nextSidecarName(videoAbsPath, await readdir(dir)));
  await writeFile(path, content, { flag: "wx" });
  return path;
}
