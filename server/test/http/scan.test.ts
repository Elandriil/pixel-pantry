import { expect, test } from "bun:test";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { buildApp } from "../../src/http/app";
import { makeDeps, request } from "./helpers";

test("scan POST returns 202 and status flips to scanning then idle", async () => {
  const deps = makeDeps();
  // a category folder, so the scan walks instead of stopping at "No tv, anime or movies folder"
  mkdirSync(join(deps.config().mediaRoot, "tv"), { recursive: true });
  const app = buildApp(deps);
  expect((await request(app, "/api/scan/status")).json.state).toBe("idle");

  const r = await request(app, "/api/scan", { method: "POST" });
  expect(r.status).toBe(202);
  expect(r.json.status.state).toBe("scanning");
  expect((await request(app, "/api/scan/status")).json.state).toBe("scanning");
  const again = await request(app, "/api/scan", { method: "POST" });
  expect(again.status).toBe(202);
  expect(again.json.status.state).toBe("scanning");

  await deps.scanner.scan();
  const done = await request(app, "/api/scan/status");
  expect(done.json).toMatchObject({ state: "idle", phase: "done", lastError: null });
});
