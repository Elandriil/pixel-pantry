import { open } from "node:fs/promises";

const CHUNK = 64 * 1024;
const MASK = (1n << 64n) - 1n;

/** OpenSubtitles movie hash: file size + uint64le sums of the first and last 64 KiB, mod 2^64, as 16 hex digits. */
export async function openSubtitlesHash(absPath: string): Promise<string> {
  const fh = await open(absPath, "r");
  try {
    const { size } = await fh.stat();
    const len = Math.min(size, CHUNK);
    let sum = BigInt(size);
    for (const offset of [0, Math.max(0, size - CHUNK)]) {
      const buf = Buffer.alloc(len);
      const { bytesRead } = await fh.read(buf, 0, len, offset);
      for (let i = 0; i + 8 <= bytesRead; i += 8) sum = (sum + buf.readBigUInt64LE(i)) & MASK;
    }
    return (sum & MASK).toString(16).padStart(16, "0");
  } finally {
    await fh.close();
  }
}
