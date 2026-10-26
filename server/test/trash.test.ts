import { expect, test } from "bun:test";
import { join } from "node:path";
import { trashPaths } from "../src/fs/trash";
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
    "Add-Type -AssemblyName Microsoft.VisualBasic; " +
      `[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile('${esc(file)}', 'OnlyErrorDialogs', 'SendToRecycleBin'); ` +
      `[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory('${esc(folder)}', 'OnlyErrorDialogs', 'SendToRecycleBin')`,
  );
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
