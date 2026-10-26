import { Hono } from "hono";
import type { Deps } from "../deps";

export function scanRoutes(deps: Deps): Hono {
  const r = new Hono();

  // Mid-scan, scanner.scan() queues at most one rerun instead of starting a parallel scan.
  r.post("/scan", (c) => {
    void deps.scanner.scan();
    return c.json({ status: deps.scanner.status() }, 202);
  });

  r.get("/scan/status", (c) => c.json(deps.scanner.status()));

  return r;
}
