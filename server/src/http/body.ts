import type { Context } from "hono";
import { HttpError } from "./errors";

/** The request's JSON body as an object, or 400. */
export async function jsonBody(c: Context): Promise<Record<string, unknown>> {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    throw new HttpError(400, "Invalid JSON body");
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) throw new HttpError(400, "Body must be an object");
  return body as Record<string, unknown>;
}
