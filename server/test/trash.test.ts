import { expect, test } from "bun:test";
import { join } from "node:path";
import { defaultRun, trashPaths } from "../src/fs/trash";
import { tmpDir, touch } from "./helpers";

function recorder() {
  const cmds: string[][] = [];
  return { cmds, run: async (cmd: string[]) => void cmds.push(cmd) };
}

test("win32 builds one PowerShell command, quotes doubled, DeleteDirectory for folders", async () => {
  const dir = tmpDir();
  const file = join(dir, "Bob's Show", "S01E01.mkv");
  touch(file);
  const folder = join(dir, "Bob's Show");
  const { cmds, run } = recorder();
  await trashPaths([file, folder], run, "win32");
  expect(cmds).toHaveLength(1);
  const [exe, ...args] = cmds[0]!;
  expect(exe).toBe("powershell.exe");
  expect(args.slice(0, -1)).toEqual(["-NoProfile", "-NonInteractive", "-Command"]);
  const esc = (p: string) => p.replaceAll("'", "''");
  expect(args.at(-1)).toBe(
    "$ErrorActionPreference = 'Stop'; " +
      "Add-Type -AssemblyName Microsoft.VisualBasic; " +
      `foreach ($p in @('${esc(file)}', '${esc(folder)}')) { ` +
      "try { $t = [string]([System.IO.DriveInfo]::new([System.IO.Path]::GetPathRoot($p)).DriveType) } catch { $t = 'Network' }; " +
      "if ($t -in @('Network', 'Removable', 'NoRootDirectory', 'Unknown')) { " +
      '[Console]::Error.WriteLine("Cannot move to Recycle Bin: $p is on a network or removable drive"); exit 1 } }; ' +
      `[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile('${esc(file)}', 'OnlyErrorDialogs', 'SendToRecycleBin'); ` +
      `[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory('${esc(folder)}', 'OnlyErrorDialogs', 'SendToRecycleBin')`,
  );
});

test("win32 checks every path's drive before the first delete", async () => {
  const dir = tmpDir();
  const file = join(dir, "a.mkv");
  touch(file);
  const { cmds, run } = recorder();
  await trashPaths([file], run, "win32");
  const script = cmds[0]!.at(-1)!;
  const check = script.indexOf("[System.IO.DriveInfo]::new([System.IO.Path]::GetPathRoot($p))");
  expect(check).toBeGreaterThan(0);
  expect(script).toContain("'Network', 'Removable'");
  expect(script).toContain("Cannot move to Recycle Bin: $p is on a network or removable drive");
  expect(check).toBeLessThan(script.indexOf("::DeleteFile("));
});

test("darwin builds one osascript Finder delete with quotes and backslashes escaped", async () => {
  const { cmds, run } = recorder();
  await trashPaths(['/m/tv/The "Best" Show', "/m/movies/a\\b.mkv"], run, "darwin");
  expect(cmds).toEqual([
    [
      "osascript",
      "-e",
      'tell application "Finder" to delete {POSIX file "/m/tv/The \\"Best\\" Show", POSIX file "/m/movies/a\\\\b.mkv"}',
    ],
  ]);
});

test("other platforms reject; empty list does nothing", async () => {
  const { cmds, run } = recorder();
  await expect(trashPaths(["/x"], run, "linux")).rejects.toThrow("trash not supported on linux");
  await trashPaths([], run, "darwin");
  expect(cmds).toEqual([]);
});

test("defaultRun kills a command that outlives the timeout and rejects with trash timeout", async () => {
  const started = Date.now();
  await expect(defaultRun(["sleep", "5"], 50)).rejects.toThrow("trash timeout");
  expect(Date.now() - started).toBeLessThan(2000);
});

test("defaultRun rejects with stderr on a non-zero exit", async () => {
  await expect(defaultRun(["sh", "-c", "echo 'no can do' >&2; exit 3"])).rejects.toThrow(/^no can do$/);
  await expect(defaultRun(["sh", "-c", "exit 4"])).rejects.toThrow("sh exited with 4");
  await defaultRun(["true"]);
});
