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
  const script = ["Add-Type -AssemblyName Microsoft.VisualBasic", ...calls].join("; ");
  return run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script]);
}
