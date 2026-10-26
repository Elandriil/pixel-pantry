import { Hono } from "hono";
import type { Deps } from "./deps";
import { HttpError } from "./errors";
import { adminRoutes } from "./routes/admin";
import { catalogRoutes } from "./routes/catalog";
import { playbackRoutes } from "./routes/playback";
import { scanRoutes } from "./routes/scan";
import { streamRoutes } from "./routes/stream";
import { subtitleRoutes } from "./routes/subtitles";

export { HttpError };

/** Route modules mounted under `/api`. */
const registerRoutes: ((deps: Deps) => Hono)[] = [catalogRoutes, streamRoutes, playbackRoutes, subtitleRoutes, scanRoutes];

export function buildApp(deps: Deps): Hono {
  const app = new Hono();

  // Default for API responses; routes that serve cacheable bytes (images, streams) set their own header.
  app.use("/api/*", async (c, next) => {
    await next();
    if (!c.res.headers.has("Cache-Control")) c.res.headers.set("Cache-Control", "no-store");
  });

  for (const routes of registerRoutes) app.route("/api", routes(deps));
  // `/` and `/admin/*`, loopback only
  app.route("/", adminRoutes(deps));

  app.notFound((c) => c.json({ error: "Not found" }, 404));
  app.onError((err, c) => {
    if (err instanceof HttpError) return c.json({ error: err.message }, err.status);
    deps.log.error("http request failed", { method: c.req.method, path: c.req.path, error: err.message });
    return c.json({ error: err.message }, 500);
  });
  return app;
}
