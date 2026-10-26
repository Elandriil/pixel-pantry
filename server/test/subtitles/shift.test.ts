import { expect, test } from "bun:test";
import { shiftSubtitle } from "../../src/subtitles/shift";

test("srt shifts forward and clamps at zero", () => {
  const src = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello 00:00:01,000\r\n";
  expect(shiftSubtitle(src, 500, "srt")).toBe("1\r\n00:00:01,500 --> 00:00:03,000\r\nHello 00:00:01,000\r\n");
  expect(shiftSubtitle("00:00:01,000 --> 00:00:02,500", -2000, "srt")).toBe("00:00:00,000 --> 00:00:00,500");
});

test("srt carries into minutes and hours", () => {
  expect(shiftSubtitle("00:59:59,900 --> 01:00:00,000", 200, "srt")).toBe("01:00:00,100 --> 01:00:00,200");
});

test("vtt keeps the MM:SS.mmm form and the HH form", () => {
  const src = "WEBVTT\n\n00:01.000 --> 00:02.500 align:start\ncue\n\n01:00:01.000 --> 01:00:02.000\nx\n";
  expect(shiftSubtitle(src, 1000, "vtt")).toBe(
    "WEBVTT\n\n00:02.000 --> 00:03.500 align:start\ncue\n\n01:00:02.000 --> 01:00:03.000\nx\n",
  );
});

test("ass shifts Dialogue times only", () => {
  const src = [
    "[Events]",
    "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi, 0:00:01.00",
    "Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,note",
  ].join("\n");
  const out = shiftSubtitle(src, 1500, "ass").split("\n");
  expect(out[1]).toBe("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text");
  expect(out[2]).toBe("Dialogue: 0,0:00:02.50,0:00:03.50,Default,,0,0,0,,Hi, 0:00:01.00");
  expect(out[3]).toBe("Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,note");
  expect(shiftSubtitle("Dialogue: 0,0:00:01.00,0:00:02.00,x", -5000, "ass")).toBe("Dialogue: 0,0:00:00.00,0:00:00.00,x");
});
