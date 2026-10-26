const pad = (n: number, w: number) => String(n).padStart(w, "0");

function shiftMs(total: number, ms: number): number {
  return Math.max(0, total + ms);
}

/** Shifts every cue timestamp by `ms` (clamped at 0); all other text is preserved byte-for-byte. */
export function shiftSubtitle(text: string, ms: number, format: "srt" | "vtt" | "ass"): string {
  if (format === "ass") return shiftAss(text, ms);
  const sep = format === "srt" ? "," : ".";
  const t = `(?:(\\d+):)?(\\d{2}):(\\d{2})\\${sep}(\\d{3})`;
  const re = new RegExp(`^${t}([ \\t]*-->[ \\t]*)${t}`, "gm");
  const fmt = (h: string | undefined, m: string, s: string, f: string, hasHours: boolean): string => {
    const total = shiftMs(((Number(h ?? 0) * 60 + Number(m)) * 60 + Number(s)) * 1000 + Number(f), ms);
    const hh = Math.floor(total / 3_600_000);
    const mm = Math.floor(total / 60_000) % 60;
    const ss = Math.floor(total / 1000) % 60;
    const head = hasHours || hh > 0 ? `${pad(hh, 2)}:` : "";
    return `${head}${pad(mm, 2)}:${pad(ss, 2)}${sep}${pad(total % 1000, 3)}`;
  };
  return text.replace(re, (_all, h1, m1, s1, f1, arrow, h2, m2, s2, f2) =>
    `${fmt(h1, m1, s1, f1, h1 !== undefined)}${arrow}${fmt(h2, m2, s2, f2, h2 !== undefined)}`);
}

function shiftAss(text: string, ms: number): string {
  const re = /^(Dialogue:[^,\r\n]*,)(\d+):(\d{2}):(\d{2})\.(\d{2}),(\d+):(\d{2}):(\d{2})\.(\d{2})(?=,)/gm;
  const fmt = (h: string, m: string, s: string, c: string): string => {
    const total = shiftMs(((Number(h) * 60 + Number(m)) * 60 + Number(s)) * 100 + Number(c), Math.round(ms / 10));
    return `${Math.floor(total / 360_000)}:${pad(Math.floor(total / 6000) % 60, 2)}:${pad(Math.floor(total / 100) % 60, 2)}.${pad(total % 100, 2)}`;
  };
  return text.replace(re, (_all, head, h1, m1, s1, c1, h2, m2, s2, c2) =>
    `${head}${fmt(h1, m1, s1, c1)},${fmt(h2, m2, s2, c2)}`);
}
