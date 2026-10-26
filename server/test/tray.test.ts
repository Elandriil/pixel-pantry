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
  // liveness poll: every 5 s, gone after 3 consecutive failures
  expect(TRAY_SCRIPT).toContain("New-Object System.Windows.Forms.Timer");
  expect(TRAY_SCRIPT).toContain("$timer.Interval = 5000");
  expect(TRAY_SCRIPT).toContain("$base/admin/api/status");
  expect(TRAY_SCRIPT).toContain("$script:failures -ge 3");
});

test("startTray returns null off Windows", () => {
  const log = new Logger(join(tmpDir(), "server.log"));
  expect(startTray(8765, log, { platform: "darwin", dataDir: tmpDir() })).toBeNull();
});

test("startTray on win32 writes the script and spawns hidden powershell", () => {
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
  expect(killed).toBe(0);
});

test("tray stop() leaves the child to exit on its own and kills it only after the grace period", async () => {
  const dir = tmpDir();
  const log = new Logger(join(dir, "server.log"));
  let killed = 0;
  const spawn = (() => ({ kill: () => { killed++; } })) as unknown as typeof Bun.spawn;
  const tray = startTray(9000, log, { platform: "win32", dataDir: dir, spawn, stopGraceMs: 5 });
  tray!.stop();
  expect(killed).toBe(0);
  await Bun.sleep(30);
  expect(killed).toBe(1);
});
