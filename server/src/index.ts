import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { advertise } from "./discovery";
import { buildApp } from "./http/app";
import { watchRoot } from "./library/watcher";
import { Logger } from "./log";
import { dataDir } from "./paths";
import { lanAddresses } from "./net";
import { buildDeps } from "./server";
import { startTray } from "./tray";

const dir = dataDir();
for (const d of [dir, join(dir, "images"), join(dir, "logs")]) mkdirSync(d, { recursive: true });
const log = new Logger(join(dir, "logs", "server.log"));

let watcher: { close(): Promise<void> } | null = null;
const startWatcher = (root: string) => {
  if (!root) return;
  watcher = watchRoot(root, () => void deps.scanner.scan(), {
    onError: (err) => log.warn("watcher error", { error: err.message }),
  });
};

const deps = buildDeps({
  dataDir: dir,
  log,
  onConfigSaved: (prev, next) => {
    if (next.mediaRoot === prev.mediaRoot) return;
    const old = watcher;
    watcher = null;
    void old?.close();
    startWatcher(next.mediaRoot);
  },
  quit: () => void quit(),
});
const app = buildApp(deps);
const port = deps.config().port;

let server: ReturnType<typeof Bun.serve>;
try {
  server = Bun.serve({ port, hostname: "0.0.0.0", fetch: (req, srv) => app.fetch(req, { server: srv }) });
} catch (err) {
  if ((err as { code?: string }).code === "EADDRINUSE") {
    log.error(`Port ${port} already in use (is Pixel Pantry already running?)`);
    console.error(`Port ${port} already in use (is Pixel Pantry already running?)`);
    process.exit(1);
  }
  throw err;
}
log.info(`Admin page: http://localhost:${port}/admin`);
for (const ip of lanAddresses()) log.info(`LAN: http://${ip}:${port}`);

const discovery = advertise(port, log);
startWatcher(deps.config().mediaRoot);
const tray = startTray(port, log, { dataDir: dir });
void deps.scanner.scan();

let quitting = false;
async function quit(): Promise<void> {
  if (quitting) return;
  quitting = true;
  log.info("shutting down");
  await watcher?.close();
  await discovery.stop();
  tray?.stop();
  server.stop(true);
  deps.db.close();
  process.exit(0);
}
process.on("SIGINT", () => void quit());
process.on("SIGTERM", () => void quit());
