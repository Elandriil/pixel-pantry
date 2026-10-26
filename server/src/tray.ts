import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { Logger } from "./log";
import { dataDir as defaultDataDir } from "./paths";

/** Windows tray icon. Runs as a hidden PowerShell child and talks back to the server over HTTP. */
export const TRAY_SCRIPT = String.raw`param(
  [Parameter(Mandatory = $true)][int]$Port,
  [Parameter(Mandatory = $true)][string]$DataDir
)
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$base = "http://localhost:$Port"
$icon = New-Object System.Windows.Forms.NotifyIcon
$icon.Icon = [System.Drawing.SystemIcons]::Application
# Em dash from its code point: Windows PowerShell reads a BOM-less .ps1 as ANSI.
$icon.Text = "Pixel Pantry $([char]0x2014) running on port $Port"

$menu = New-Object System.Windows.Forms.ContextMenuStrip

$open = $menu.Items.Add("Open admin page")
$open.add_Click({ Start-Process "$base/admin" })

$rescan = $menu.Items.Add("Rescan now")
$rescan.add_Click({ try { Invoke-RestMethod -Method Post "$base/admin/api/scan" | Out-Null } catch {} })

$logs = $menu.Items.Add("Open log folder")
$logs.add_Click({ Start-Process (Join-Path $DataDir "logs") })

$quit = $menu.Items.Add("Quit")
$quit.add_Click({
  try { Invoke-RestMethod -Method Post "$base/admin/api/quit" | Out-Null } catch {}
  $icon.Visible = $false
  $icon.Dispose()
  [System.Windows.Forms.Application]::Exit()
})

$icon.ContextMenuStrip = $menu
$icon.Visible = $true
[System.Windows.Forms.Application]::Run()
`;

export interface TrayOptions {
  platform?: string;
  dataDir?: string;
  spawn?: typeof Bun.spawn;
}

/** Starts the tray icon on Windows; returns null elsewhere or if it cannot start. */
export function startTray(port: number, log: Logger, opts: TrayOptions = {}): { stop(): void } | null {
  if ((opts.platform ?? process.platform) !== "win32") return null;
  const dir = opts.dataDir ?? defaultDataDir();
  const spawn = opts.spawn ?? Bun.spawn;
  try {
    mkdirSync(dir, { recursive: true });
    const script = join(dir, "tray.ps1");
    writeFileSync(script, TRAY_SCRIPT);
    const child = spawn(
      ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script, "-Port", String(port), "-DataDir", dir],
      { stdout: "ignore", stderr: "ignore" },
    );
    log.info("tray started");
    return {
      stop() {
        try {
          child.kill();
        } catch {}
      },
    };
  } catch (err) {
    log.error("tray failed to start", { error: err instanceof Error ? err.message : String(err) });
    return null;
  }
}
