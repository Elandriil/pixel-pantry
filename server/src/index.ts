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

const logFatal = (kind: string) => (err: unknown) =>
  log.error(kind, err instanceof Error ? { error: err.message, stack: err.stack } : { error: String(err) });

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
    old?.close().catch((err) => log.warn("watcher close failed", { error: err instanceof Error ? err.message : String(err) }));
    startWatcher(next.mediaRoot);
  },
  quit: () => void quit(),
});
const app = buildApp(deps);
const port = deps.config().port;

let server: ReturnType<typeof Bun.serve>;
try {
  // idleTimeout 0: a paused TV player keeps its stream connection open without sending anything
  server = Bun.serve({ port, hostname: "0.0.0.0", idleTimeout: 0, fetch: (req, srv) => app.fetch(req, { server: srv }) });
} catch (err) {
  if ((err as { code?: string }).code === "EADDRINUSE") {
    log.error(`Port ${port} already in use (is Pixel Pantry already running?)`);
    console.error(`Port ${port} already in use (is Pixel Pantry already running?)`);
    process.exit(1);
  }
  throw err;
}
// Belt and braces once serving: a stray async failure (e.g. a socket error) is logged, never fatal.
// Installed after Bun.serve so a startup failure other than a busy port still exits.
process.on("uncaughtException", logFatal("uncaught exception"));
process.on("unhandledRejection", logFatal("unhandled rejection"));
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
  try {
    await watcher?.close().catch(() => {});
    await Promise.race([discovery.stop(), Bun.sleep(1000)]);
    tray?.stop();
    server.stop(true);
    deps.db.close();
  } catch (err) {
    logFatal("shutdown failed")(err);
  } finally {
    process.exit(0);
  }
}
/** First signal shuts down cleanly; a second one while shutting down exits immediately. */
const onSignal = () => (quitting ? process.exit(0) : void quit());
process.on("SIGINT", onSignal);
process.on("SIGTERM", onSignal);
