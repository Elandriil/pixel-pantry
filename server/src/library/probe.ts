export interface Track {
  index: number;
  codec: string;
  language: string | null;
  title: string | null;
  channels?: number;
  default: boolean;
  forced: boolean;
}

export interface ProbeResult {
  duration: number | null;
  audio: Track[];
  subtitles: Track[];
  video: { codec: string; width: number; height: number } | null;
}

const TIMEOUT_MS = 30_000;

interface RawStream {
  index: number;
  codec_type?: string;
  codec_name?: string;
  width?: number;
  height?: number;
  channels?: number;
  tags?: { language?: string; title?: string };
  disposition?: { default?: number; forced?: number };
}

function toTrack(s: RawStream): Track {
  const t: Track = {
    index: s.index,
    codec: s.codec_name ?? "unknown",
    language: s.tags?.language ?? null,
    title: s.tags?.title ?? null,
    default: s.disposition?.default === 1,
    forced: s.disposition?.forced === 1,
  };
  if (s.channels !== undefined) t.channels = s.channels;
  return t;
}

export async function probeFile(ffprobePath: string, absPath: string): Promise<ProbeResult> {
  let timedOut = false;
  let proc: ReturnType<typeof Bun.spawn>;
  try {
    proc = Bun.spawn(
      [ffprobePath, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", absPath],
      { stdout: "pipe", stderr: "pipe" },
    );
  } catch (err) {
    throw new Error(`ffprobe failed: ${(err as Error).message}`);
  }
  const timer = setTimeout(() => {
    timedOut = true;
    proc.kill();
  }, TIMEOUT_MS);
  try {
    const [stdout, stderr, code] = await Promise.all([
      new Response(proc.stdout as ReadableStream).text(),
      new Response(proc.stderr as ReadableStream).text(),
      proc.exited,
    ]);
    if (timedOut) throw new Error("ffprobe failed: timeout");
    if (code !== 0) throw new Error(`ffprobe failed: ${stderr.trim()}`);
    const json = JSON.parse(stdout) as { streams?: RawStream[]; format?: { duration?: string } };
    const streams = json.streams ?? [];
    const v = streams.find((s) => s.codec_type === "video");
    const d = json.format?.duration !== undefined ? Number(json.format.duration) : NaN;
    return {
      duration: Number.isFinite(d) ? d : null,
      audio: streams.filter((s) => s.codec_type === "audio").map(toTrack),
      subtitles: streams.filter((s) => s.codec_type === "subtitle").map(toTrack),
      video: v ? { codec: v.codec_name ?? "unknown", width: v.width ?? 0, height: v.height ?? 0 } : null,
    };
  } finally {
    clearTimeout(timer);
  }
}
