/**
 * Subtitle bytes as text: a UTF-8 or UTF-16 (LE/BE) byte order mark decides the encoding; without one the bytes
 * are UTF-8 when they are valid UTF-8, else Windows-1252 (the usual encoding of older Western .srt files).
 */
export function decodeSubtitle(bytes: Uint8Array): string {
  const [a, b, c] = bytes;
  if (a === 0xef && b === 0xbb && c === 0xbf) return new TextDecoder("utf-8").decode(bytes);
  if (a === 0xff && b === 0xfe) return new TextDecoder("utf-16le").decode(bytes);
  if (a === 0xfe && b === 0xff) return new TextDecoder("utf-16be").decode(bytes);
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    return new TextDecoder("windows-1252").decode(bytes);
  }
}
