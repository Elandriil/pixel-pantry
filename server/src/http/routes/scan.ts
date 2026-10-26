import { Hono } from "hono";
import type { Deps } from "../deps";

export function scanRoutes(deps: Deps): Hono {
  const r = new Hono();

  // scanner.scan() joins a running scan instead of starting a second one.
  r.post("/scan", (c) => {
    void deps.scanner.scan();
    return c.json({ status: deps.scanner.status() }, 202);
  });

  r.get("/scan/status", (c) => c.json(deps.scanner.status()));

  return r;
}
