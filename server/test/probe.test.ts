import { beforeAll, expect, test } from "bun:test";
import { join } from "node:path";
import ffmpegPath from "ffmpeg-static";
import ffprobe from "ffprobe-static";
import { probeFile } from "../src/library/probe";
import { tmpDir } from "./helpers";

let fixture = "";

beforeAll(async () => {
  fixture = join(tmpDir(), "fixture.mkv");
  const proc = Bun.spawn(
    [
      ffmpegPath as string, "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x64:rate=5",
      "-f", "lavfi", "-i", "sine=duration=2", "-c:v", "libx264", "-c:a", "aac",
      "-metadata:s:a:0", "language=eng", fixture,
    ],
    { stdout: "ignore", stderr: "ignore" },
  );
  expect(await proc.exited).toBe(0);
});

test("probeFile reads duration, video and audio tracks", async () => {
  const r = await probeFile(ffprobe.path, fixture);
  expect(r.duration).toBeGreaterThanOrEqual(1.5);
  expect(r.duration).toBeLessThanOrEqual(2.5);
  expect(r.video?.codec).toBe("h264");
  expect(r.audio[0].codec).toBe("aac");
  expect(r.audio[0].language).toBe("eng");
  expect(r.subtitles).toEqual([]);
});

test("probeFile rejects when ffprobe fails", async () => {
  await expect(probeFile(ffprobe.path, "/nonexistent")).rejects.toThrow("ffprobe failed");
});
