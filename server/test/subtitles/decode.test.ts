import { expect, test } from "bun:test";
import { decodeSubtitle } from "../../src/subtitles/decode";

const utf16 = (s: string, le: boolean) => {
  const out = new Uint8Array(2 + s.length * 2);
  out.set(le ? [0xff, 0xfe] : [0xfe, 0xff]);
  for (let i = 0; i < s.length; i++) {
    const code = s.charCodeAt(i);
    out[2 + 2 * i + (le ? 0 : 1)] = code & 0xff;
    out[2 + 2 * i + (le ? 1 : 0)] = code >> 8;
  }
  return out;
};

test("UTF-8 with and without BOM", () => {
  const bytes = new TextEncoder().encode("Don’t");
  expect(decodeSubtitle(bytes)).toBe("Don’t");
  expect(decodeSubtitle(new Uint8Array([0xef, 0xbb, 0xbf, ...bytes]))).toBe("Don’t");
});

test("UTF-16 LE and BE by BOM", () => {
  expect(decodeSubtitle(utf16("Ärge’", true))).toBe("Ärge’");
  expect(decodeSubtitle(utf16("Ärge’", false))).toBe("Ärge’");
});

test("invalid UTF-8 falls back to Windows-1252", () => {
  expect(decodeSubtitle(new Uint8Array([0x44, 0x6f, 0x6e, 0x92, 0x74, 0x20, 0xe4]))).toBe("Don’t ä");
});
