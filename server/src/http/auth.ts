import { timingSafeEqual } from "node:crypto";
import { Hono, type MiddlewareHandler } from "hono";
import { jsonBody } from "./body";
import type { Deps } from "./deps";
import { HttpError } from "./errors";

export const MAX_PIN_FAILURES = 5;
export const LOCKOUT_MS = 60_000;

/**
 * Hosts a request to this server may carry: an IPv4 or bracketed IPv6 literal, a `.local` mDNS name or
 * localhost, each with an optional port. A browser on a rebinding domain (`pantry.evil.example` resolving to a
 * LAN address) sends that domain as Host and is refused, so a web page cannot drive the API through DNS tricks.
 */
const ALLOWED_HOST = /^(?:localhost|\d{1,3}(?:\.\d{1,3}){3}|\[[0-9a-z:.%]+\]|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.local)(?::\d+)?$/i;

export const isAllowedHost = (host: string): boolean => ALLOWED_HOST.test(host);

/** Constant-time equality of two secrets of any length. */
export function secretEquals(a: string, b: string): boolean {
  const x = Buffer.from(a, "utf8");
  const y = Buffer.from(b, "utf8");
  return x.length === y.length && timingSafeEqual(x, y);
}

/** Unauthenticated routes: the server check used by discovery, and pairing itself. */
const isPublic = (method: string, path: string): boolean =>
  (method === "GET" && path === "/api/info") || (method === "POST" && path === "/api/pair");

/**
 * Every `/api` request must name this server in its Host header and, except for the public routes, carry the
 * paired TV's token as `Authorization: Bearer <token>`. The header also keeps browsers out: a cross-site page
 * cannot add it without a CORS preflight, which this server never grants.
 */
export function apiGuard(deps: Deps): MiddlewareHandler {
  return async (c, next) => {
    const host = c.req.header("Host") ?? new URL(c.req.url).host;
    if (!isAllowedHost(host)) return c.json({ error: "Unknown host" }, 403);
    if (isPublic(c.req.method, c.req.path)) return next();
    const token = deps.config().auth.clientToken;
    const header = c.req.header("Authorization") ?? "";
    const presented = header.startsWith("Bearer ") ? header.slice("Bearer ".length) : "";
    if (!token || !presented || !secretEquals(presented, token)) {
      return c.json({ error: "Pairing required" }, 401, { "WWW-Authenticate": "Bearer" });
    }
    await next();
  };
}

/** Wrong-PIN counter: after `MAX_PIN_FAILURES` in a row pairing is refused for `LOCKOUT_MS`. One TV at a time pairs, so one counter is enough. */
export class PairingGate {
  private failures = 0;
  private lockedUntil = 0;

  constructor(private readonly now: () => number) {}

  locked(): boolean {
    return this.now() < this.lockedUntil;
  }

  succeeded(): void {
    this.failures = 0;
    this.lockedUntil = 0;
  }

  failed(): void {
    this.failures++;
    if (this.failures >= MAX_PIN_FAILURES) {
      this.failures = 0;
      this.lockedUntil = this.now() + LOCKOUT_MS;
    }
  }
}

export function pairRoutes(deps: Deps): Hono {
  const r = new Hono();
  const gate = new PairingGate(deps.now);

  r.post("/pair", async (c) => {
    const { pin } = await jsonBody(c);
    if (typeof pin !== "string" || !pin) throw new HttpError(400, "pin must be a string");
    if (gate.locked()) throw new HttpError(429, "Too many attempts, try again in a minute");
    const auth = deps.config().auth;
    if (!auth.pairingPin || !secretEquals(pin, auth.pairingPin)) {
      gate.failed();
      deps.log.warn("pairing refused: wrong PIN");
      throw new HttpError(403, "Wrong PIN");
    }
    gate.succeeded();
    deps.log.info("TV paired");
    return c.json({ token: auth.clientToken, serverId: auth.serverId });
  });

  return r;
}
