import { stat } from "node:fs/promises";

export type RunFn = (cmd: string[]) => Promise<void>;

const TRASH_TIMEOUT_MS = 60_000;

/**
 * Spawns `cmd` and rejects on a non-zero exit with its stderr as the message (or the exit code when stderr is
 * empty). After `timeoutMs` the process is killed and the promise rejects with "trash timeout".
 */
export async function defaultRun(cmd: string[], timeoutMs = TRASH_TIMEOUT_MS): Promise<void> {
  const proc = Bun.spawn(cmd, { stdout: "ignore", stderr: "pipe" });
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    proc.kill(9);
  }, timeoutMs);
  try {
    const [code, stderr] = await Promise.all([proc.exited, new Response(proc.stderr).text()]);
    if (timedOut) throw new Error("trash timeout");
    if (code !== 0) throw new Error(stderr.trim() || `${cmd[0]} exited with ${code}`);
  } finally {
    clearTimeout(timer);
  }
}

const psQuote = (s: string) => `'${s.replaceAll("'", "''")}'`;
const asQuote = (s: string) => `"${s.replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;

/**
 * PowerShell that exits 1, before anything is deleted, when a path is on a drive without a Recycle Bin: there
 * SendToRecycleBin deletes permanently. Network (including UNC paths, which DriveInfo rejects), removable and
 * unknown drives all count.
 */
function driveCheck(paths: string[]): string {
  return (
    `foreach ($p in @(${paths.map(psQuote).join(", ")})) { ` +
    "try { $t = [string]([System.IO.DriveInfo]::new([System.IO.Path]::GetPathRoot($p)).DriveType) } catch { $t = 'Network' }; " +
    "if ($t -in @('Network', 'Removable', 'NoRootDirectory', 'Unknown')) { " +
    '[Console]::Error.WriteLine("Cannot move to Recycle Bin: $p is on a network or removable drive"); exit 1 } }'
  );
}

/** Moves `paths` to the Recycle Bin (Windows) or Trash (macOS) in a single command. */
export async function trashPaths(paths: string[], run: RunFn = defaultRun, platform: string = process.platform): Promise<void> {
  if (platform !== "win32" && platform !== "darwin") throw new Error(`trash not supported on ${platform}`);
  if (paths.length === 0) return;
  if (platform === "darwin") {
    const list = paths.map((p) => `POSIX file ${asQuote(p)}`).join(", ");
    return run(["osascript", "-e", `tell application "Finder" to delete {${list}}`]);
  }
  const calls: string[] = [];
  for (const p of paths) {
    const method = (await stat(p)).isDirectory() ? "DeleteDirectory" : "DeleteFile";
    calls.push(`[Microsoft.VisualBasic.FileIO.FileSystem]::${method}(${psQuote(p)}, 'OnlyErrorDialogs', 'SendToRecycleBin')`);
  }
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "Add-Type -AssemblyName Microsoft.VisualBasic",
    driveCheck(paths),
    ...calls,
  ].join("; ");
  return run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script]);
}
