import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import type { Logger } from "./log";
import { isCompiled } from "./paths";

const RUN_KEY = String.raw`HKCU:\Software\Microsoft\Windows\CurrentVersion\Run`;
const VALUE_NAME = "Pixel Pantry";
const LAUNCHER = "Start Pixel Pantry.vbs";

/** The command Windows runs at logon: the .vbs launcher (hidden console) when it sits beside the exe, else the exe. */
export function autostartCommand(exeDir: string, exePath: string, exists: (p: string) => boolean = existsSync): string {
  const vbs = join(exeDir, LAUNCHER);
  return exists(vbs) ? `wscript.exe "${vbs}"` : `"${exePath}"`;
}

/** PowerShell that writes or removes the Run value. Single quotes in paths are doubled, the only escape PowerShell needs. */
export function autostartScript(enabled: boolean, command: string): string {
  const q = (s: string) => `'${s.replace(/'/g, "''")}'`;
  if (!enabled) return `Remove-ItemProperty -Path ${q(RUN_KEY)} -Name ${q(VALUE_NAME)} -ErrorAction SilentlyContinue`;
  return `Set-ItemProperty -Path ${q(RUN_KEY)} -Name ${q(VALUE_NAME)} -Value ${q(command)} -Type String`;
}

/** `-EncodedCommand` takes the script as base64 UTF-16LE, so no path can break the command line's quoting. */
export function encodeCommand(script: string): string {
  return Buffer.from(script, "utf16le").toString("base64");
}

export interface AutostartEnv {
  platform?: NodeJS.Platform;
  compiled?: boolean;
  execPath?: string;
  run?: (args: string[]) => Promise<{ exitCode: number; stderr: string }>;
}

async function runPowershell(args: string[]): Promise<{ exitCode: number; stderr: string }> {
  const proc = Bun.spawn(["powershell.exe", "-NoProfile", "-NonInteractive", ...args], {
    stdout: "ignore",
    stderr: "pipe",
    windowsHide: true,
  });
  const stderr = await new Response(proc.stderr).text();
  return { exitCode: await proc.exited, stderr: stderr.trim() };
}

/**
 * Registers (or unregisters) the server in the current user's Run key so it starts at logon. Only the compiled
 * Windows exe does anything: in development, or on another OS, it is a no-op. Never throws: a failure is logged.
 */
export async function applyAutostart(enabled: boolean, log: Logger, env: AutostartEnv = {}): Promise<boolean> {
  const platform = env.platform ?? process.platform;
  const compiled = env.compiled ?? isCompiled();
  if (platform !== "win32" || !compiled) return false;
  const execPath = env.execPath ?? process.execPath;
  const command = autostartCommand(dirname(execPath), execPath);
  const run = env.run ?? runPowershell;
  try {
    const r = await run(["-EncodedCommand", encodeCommand(autostartScript(enabled, command))]);
    if (r.exitCode !== 0) {
      log.warn("start with Windows: registry update failed", { enabled, error: r.stderr || `exit ${r.exitCode}` });
      return false;
    }
    log.info(enabled ? "start with Windows: enabled" : "start with Windows: disabled", enabled ? { command } : {});
    return true;
  } catch (err) {
    log.warn("start with Windows: registry update failed", { enabled, error: err instanceof Error ? err.message : String(err) });
    return false;
  }
}
