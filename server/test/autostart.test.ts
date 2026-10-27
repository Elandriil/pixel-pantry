import { expect, test } from "bun:test";
import { join } from "node:path";
import { applyAutostart, autostartCommand, autostartScript, encodeCommand } from "../src/autostart";
import { Logger } from "../src/log";
import { tmpDir } from "./helpers";

const RUN_KEY = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

test("autostartCommand prefers the hidden-console launcher beside the exe", () => {
  const dir = "C:\\Pixel Pantry";
  const exe = join(dir, "PixelPantry.exe");
  expect(autostartCommand(dir, exe, () => true)).toBe(`wscript.exe "${join(dir, "Start Pixel Pantry.vbs")}"`);
  expect(autostartCommand(dir, exe, () => false)).toBe(`"${exe}"`);
});

test("autostartScript writes or removes the Run value with single-quoted, escaped arguments", () => {
  expect(autostartScript(true, `wscript.exe "C:\\Tim's PC\\Start Pixel Pantry.vbs"`)).toBe(
    `Set-ItemProperty -Path '${RUN_KEY}' -Name 'Pixel Pantry' -Value 'wscript.exe "C:\\Tim''s PC\\Start Pixel Pantry.vbs"' -Type String`,
  );
  expect(autostartScript(false, "x")).toBe(
    `Remove-ItemProperty -Path '${RUN_KEY}' -Name 'Pixel Pantry' -ErrorAction SilentlyContinue`,
  );
});

test("encodeCommand is base64 of UTF-16LE, what powershell -EncodedCommand expects", () => {
  expect(encodeCommand("dir")).toBe(Buffer.from("d\0i\0r\0", "latin1").toString("base64"));
});

test("applyAutostart runs powershell only for the compiled Windows exe", async () => {
  const log = new Logger(join(tmpDir(), "server.log"));
  const calls: string[][] = [];
  const run = async (args: string[]) => {
    calls.push(args);
    return { exitCode: 0, stderr: "" };
  };
  const execPath = "C:\\Pixel Pantry\\PixelPantry.exe";
  expect(await applyAutostart(true, log, { platform: "darwin", compiled: true, execPath, run })).toBe(false);
  expect(await applyAutostart(true, log, { platform: "win32", compiled: false, execPath, run })).toBe(false);
  expect(calls).toEqual([]);

  expect(await applyAutostart(true, log, { platform: "win32", compiled: true, execPath, run })).toBe(true);
  expect(calls.length).toBe(1);
  expect(calls[0]![0]).toBe("-EncodedCommand");
  const script = Buffer.from(calls[0]![1]!, "base64").toString("utf16le");
  expect(script).toStartWith(`Set-ItemProperty -Path '${RUN_KEY}' -Name 'Pixel Pantry' -Value '`);
  expect(script).toContain("PixelPantry.exe");
  expect(log.recent(1)[0]).toContain("start with Windows: enabled");

  expect(await applyAutostart(false, log, { platform: "win32", compiled: true, execPath, run })).toBe(true);
  expect(Buffer.from(calls[1]![1]!, "base64").toString("utf16le")).toStartWith("Remove-ItemProperty");
});

test("applyAutostart logs a failed or crashing powershell and returns false", async () => {
  const log = new Logger(join(tmpDir(), "server.log"));
  const env = { platform: "win32" as const, compiled: true, execPath: "C:\\P\\PixelPantry.exe" };
  expect(await applyAutostart(true, log, { ...env, run: async () => ({ exitCode: 1, stderr: "Access denied" }) })).toBe(false);
  expect(log.recent(1)[0]).toContain("Access denied");
  expect(await applyAutostart(true, log, { ...env, run: async () => { throw new Error("ENOENT powershell.exe"); } })).toBe(false);
  expect(log.recent(1)[0]).toContain("ENOENT powershell.exe");
});
