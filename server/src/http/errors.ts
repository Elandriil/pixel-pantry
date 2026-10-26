import type { ContentfulStatusCode } from "hono/utils/http-status";

/** Thrown by route handlers for expected client errors (400, 404, ...). */
export class HttpError extends Error {
  constructor(readonly status: ContentfulStatusCode, message: string) {
    super(message);
  }
}
