import { expect, test } from "bun:test";
import { buildApp } from "../../src/http/app";
import { LOCKOUT_MS, MAX_PIN_FAILURES } from "../../src/http/auth";
import { makeDeps, NOW, request, TEST_PIN, TEST_SERVER_ID, TEST_TOKEN } from "./helpers";

const LOCAL = { remoteAddr: "127.0.0.1" };
const bearer = (token: string) => ({ headers: { Authorization: `Bearer ${token}` } });
const json = (body: unknown): RequestInit => ({
  method: "POST",
  headers: { "Content-Type": "application/json", Authorization: "" },
  body: JSON.stringify(body),
});

test("API routes need the paired token; without it or with a wrong one the answer is 401", async () => {
  const app = buildApp(makeDeps());
  for (const path of ["/api/home", "/api/categories/tv", "/api/search?q=a", "/api/scan/status", "/api/history"]) {
    const missing = await request(app, path, { headers: { Authorization: "" } });
    expect(missing.status).toBe(401);
    expect(missing.json).toEqual({ error: "Pairing required" });
    expect(missing.headers.get("WWW-Authenticate")).toBe("Bearer");
    expect((await request(app, path, bearer("c".repeat(64)))).status).toBe(401);
    expect((await request(app, path, bearer(TEST_TOKEN))).status).toBe(200);
  }
  // mutations too
  expect((await request(app, "/api/scan", { method: "POST", headers: { Authorization: "" } })).status).toBe(401);
  expect((await request(app, "/api/shows/1", { method: "DELETE", headers: { Authorization: "Basic x" } })).status).toBe(401);
  // a token that is a prefix of the real one is not enough
  expect((await request(app, "/api/home", bearer(TEST_TOKEN.slice(0, 63)))).status).toBe(401);
});

test("/api/info is public and names the server; /api/pair is public", async () => {
  const app = buildApp(makeDeps());
  const info = await request(app, "/api/info", { headers: { Authorization: "" } });
  expect(info.status).toBe(200);
  expect(info.json).toEqual({ name: "Pixel Pantry", version: "1", serverId: TEST_SERVER_ID });
  const pair = await request(app, "/api/pair", json({ pin: "000000" }));
  expect(pair.status).toBe(403);
});

test("a Host header that is not a LAN address, .local name or localhost is refused on every API route", async () => {
  const app = buildApp(makeDeps());
  for (const host of ["evil.example", "evil.example:8765", "pantry.evil.example:8765", "localhost.evil.example"]) {
    for (const path of ["/api/home", "/api/info"]) {
      const r = await request(app, path, { headers: { Host: host } });
      expect([path, host, r.status]).toEqual([path, host, 403]);
      expect(r.json).toEqual({ error: "Unknown host" });
    }
    const pair = await request(app, "/api/pair", { ...json({ pin: TEST_PIN }), headers: { ...(json({}).headers as object), Host: host } });
    expect(pair.status).toBe(403);
  }
  for (const host of ["192.168.1.20:8765", "10.0.0.5", "desktop-2cmeqhg.local:8765", "DESKTOP.LOCAL", "localhost:8765", "127.0.0.1", "[fe80::1%25eth0]:8765", "[::1]:8765"]) {
    const r = await request(app, "/api/home", { headers: { Host: host } });
    expect([host, r.status]).toEqual([host, 200]);
  }
});

test("pairing with the right PIN hands out the token; a wrong PIN is 403", async () => {
  const app = buildApp(makeDeps());
  const ok = await request(app, "/api/pair", json({ pin: TEST_PIN }));
  expect(ok.status).toBe(200);
  expect(ok.json).toEqual({ token: TEST_TOKEN, serverId: TEST_SERVER_ID });
  const wrong = await request(app, "/api/pair", json({ pin: "654321" }));
  expect(wrong.status).toBe(403);
  expect(wrong.json).toEqual({ error: "Wrong PIN" });
  expect((await request(app, "/api/pair", json({ pin: 123456 }))).status).toBe(400);
  expect((await request(app, "/api/pair", json({}))).status).toBe(400);
  // the pin is matched exactly, not as a prefix or with whitespace
  expect((await request(app, "/api/pair", json({ pin: "12345" }))).status).toBe(403);
  expect((await request(app, "/api/pair", json({ pin: " 123456" }))).status).toBe(403);
});

test("after too many wrong PINs pairing is locked for a while, even for the right PIN", async () => {
  const deps = makeDeps();
  const app = buildApp(deps);
  for (let i = 0; i < MAX_PIN_FAILURES; i++) {
    expect((await request(app, "/api/pair", json({ pin: "000000" }))).status).toBe(403);
  }
  const locked = await request(app, "/api/pair", json({ pin: TEST_PIN }));
  expect(locked.status).toBe(429);
  expect(locked.json).toEqual({ error: "Too many attempts, try again in a minute" });
  deps.setNow(NOW + LOCKOUT_MS - 1);
  expect((await request(app, "/api/pair", json({ pin: TEST_PIN }))).status).toBe(429);
  deps.setNow(NOW + LOCKOUT_MS);
  expect((await request(app, "/api/pair", json({ pin: TEST_PIN }))).status).toBe(200);
  // a success resets the failure count
  for (let i = 0; i < MAX_PIN_FAILURES - 1; i++) await request(app, "/api/pair", json({ pin: "000000" }));
  expect((await request(app, "/api/pair", json({ pin: TEST_PIN }))).status).toBe(200);
});

test("admin status shows the PIN; resetting the pairing changes the PIN and revokes the token", async () => {
  const deps = makeDeps();
  const app = buildApp(deps);
  const status = await request(app, "/admin/api/status", undefined, LOCAL);
  expect(status.json.pairingPin).toBe(TEST_PIN);
  expect(status.json.serverId).toBe(TEST_SERVER_ID);

  const reset = await request(app, "/admin/api/pairing/reset", { method: "POST" }, LOCAL);
  expect(reset.status).toBe(200);
  expect(reset.json.pairingPin).toMatch(/^\d{6}$/);
  expect(reset.json.pairingPin).not.toBe(TEST_PIN);
  expect(deps.config().auth.clientToken).not.toBe(TEST_TOKEN);
  expect(deps.config().auth.serverId).toBe(TEST_SERVER_ID);
  expect((await request(app, "/api/home", bearer(TEST_TOKEN))).status).toBe(401);
  expect((await request(app, "/api/home", bearer(deps.config().auth.clientToken))).status).toBe(200);
  // remote callers cannot reset
  expect((await request(app, "/admin/api/pairing/reset", { method: "POST" }, { remoteAddr: "192.168.1.9" })).status).toBe(403);
});

test("the config API never shows the token and never accepts auth changes", async () => {
  const deps = makeDeps();
  const app = buildApp(deps);
  const cfg = await request(app, "/admin/api/config", undefined, LOCAL);
  expect(cfg.json.auth).toBeUndefined();
  const put = await request(
    app,
    "/admin/api/config",
    { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ auth: { clientToken: "x", pairingPin: "1" }, port: 8766 }) },
    LOCAL,
  );
  expect(put.status).toBe(200);
  expect(deps.config().auth).toEqual({ serverId: TEST_SERVER_ID, pairingPin: TEST_PIN, clientToken: TEST_TOKEN });
  expect(deps.config().port).toBe(8766);
});
