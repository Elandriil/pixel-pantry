import { expect, test } from "bun:test";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Logger } from "../src/log";
import { startTray, TRAY_SCRIPT } from "../src/tray";
import { tmpDir } from "./helpers";

test("TRAY_SCRIPT has the four menu items and posts quit", () => {
  for (const label of ["Open admin page", "Rescan now", "Open log folder", "Quit"]) expect(TRAY_SCRIPT).toContain(label);
  expect(TRAY_SCRIPT).toContain("/admin/api/quit");
  expect(TRAY_SCRIPT).toContain("/admin/api/scan");
  expect(TRAY_SCRIPT).toContain("[System.Windows.Forms.Application]::Run()");
  expect(TRAY_SCRIPT).toContain("Pixel Pantry $([char]0x2014) running on port $Port");
});

test("startTray returns null off Windows", () => {
  const log = new Logger(join(tmpDir(), "server.log"));
  expect(startTray(8765, log, { platform: "darwin", dataDir: tmpDir() })).toBeNull();
});

test("startTray on win32 writes the script and spawns hidden powershell; stop kills it", () => {
  const dir = tmpDir();
  const log = new Logger(join(dir, "server.log"));
  const spawned: string[][] = [];
  let killed = 0;
  const spawn = ((cmd: string[]) => {
    spawned.push(cmd);
    return { kill: () => { killed++; } };
  }) as unknown as typeof Bun.spawn;
  const tray = startTray(9000, log, { platform: "win32", dataDir: dir, spawn });
  const script = join(dir, "tray.ps1");
  expect(existsSync(script)).toBe(true);
  expect(readFileSync(script, "utf8")).toBe(TRAY_SCRIPT);
  expect(spawned).toEqual([
    ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script, "-Port", "9000", "-DataDir", dir],
  ]);
  tray!.stop();
  expect(killed).toBe(1);
});
