import { Hono } from "hono";
import type { ContentfulStatusCode } from "hono/utils/http-status";
import type { Deps } from "./deps";
import { catalogRoutes } from "./routes/catalog";

/** Thrown by route handlers for expected client errors (400, 404, ...). */
export class HttpError extends Error {
  constructor(readonly status: ContentfulStatusCode, message: string) {
    super(message);
  }
}

/**
 * Route modules mounted under `/api`. Later tasks add theirs here:
 * Task 12 stream/images, Task 13 playback/subtitles, Task 14 scan/admin.
 */
const registerRoutes: ((deps: Deps) => Hono)[] = [catalogRoutes];

export function buildApp(deps: Deps): Hono {
  const app = new Hono();

  // Default for API responses; routes that serve cacheable bytes (images, streams) set their own header.
  app.use("/api/*", async (c, next) => {
    await next();
    if (!c.res.headers.has("Cache-Control")) c.res.headers.set("Cache-Control", "no-store");
  });

  for (const routes of registerRoutes) app.route("/api", routes(deps));

  app.notFound((c) => c.json({ error: "Not found" }, 404));
  app.onError((err, c) => {
    if (err instanceof HttpError) return c.json({ error: err.message }, err.status);
    deps.log.error("http request failed", { method: c.req.method, path: c.req.path, error: err.message });
    return c.json({ error: err.message }, 500);
  });
  return app;
}
