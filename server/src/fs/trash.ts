import { stat } from "node:fs/promises";

export type RunFn = (cmd: string[]) => Promise<void>;

/** Spawns `cmd` and rejects on a non-zero exit, with stderr in the message. */
export async function defaultRun(cmd: string[]): Promise<void> {
  const proc = Bun.spawn(cmd, { stdout: "ignore", stderr: "pipe" });
  const [code, stderr] = await Promise.all([proc.exited, new Response(proc.stderr).text()]);
  if (code !== 0) throw new Error(`${cmd[0]} exited with ${code}: ${stderr.trim()}`);
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
