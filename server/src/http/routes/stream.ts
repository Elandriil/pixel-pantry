import { Hono } from "hono";
import { createReadStream } from "node:fs";
import { extname } from "node:path";
import { Readable } from "node:stream";
import type { ImageSize } from "../../metadata/images";
import type { Deps } from "../deps";
import { HttpError } from "../errors";
import { filePath, liveFiles } from "./catalog";

const VIDEO_TYPES: Record<string, string> = {
  ".mkv": "video/x-matroska",
  ".mp4": "video/mp4",
  ".m4v": "video/mp4",
  ".webm": "video/webm",
  ".ts": "video/mp2t",
  ".avi": "video/x-msvideo",
  ".mov": "video/quicktime",
};
const IMAGE_TYPES: Record<string, string> = {
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".webp": "image/webp",
};
const IMAGE_SIZES: readonly string[] = ["w342", "w500", "w780", "w1280", "original"] satisfies ImageSize[];

type ByteRange = { start: number; end: number } | "unsatisfiable" | null;

/**
 * Parses a `Range` header against a file of `size` bytes. Only `bytes=a-b` and `bytes=a-` are served;
 * with several ranges the first wins; a suffix range `bytes=-n` is the last n bytes (n = 0 is 416).
 * Returns null when the header should be ignored (absent or not a bytes range) so the full file is served.
 */
function parseRange(header: string | undefined, size: number): ByteRange {
  const m = header?.match(/^\s*bytes\s*=\s*([^,]*)/i);
  if (!m) return null;
  const spec = m[1]!.trim().match(/^(\d*)-(\d*)$/);
  if (!spec || (spec[1] === "" && spec[2] === "")) return null;
  if (spec[1] === "") {
    const n = Number(spec[2]);
    return n === 0 || size === 0 ? "unsatisfiable" : { start: Math.max(0, size - n), end: size - 1 };
  }
  const start = Number(spec[1]);
  const end = Math.min(spec[2] === "" ? size - 1 : Number(spec[2]), size - 1);
  return start >= size || start > end ? "unsatisfiable" : { start, end };
}

export function streamRoutes(deps: Deps): Hono {
  const r = new Hono();

  r.on(["GET", "HEAD"], "/items/:id/stream", async (c) => {
    const id = Number(c.req.param("id"));
    if (!Number.isSafeInteger(id) || id <= 0) throw new HttpError(400, "Invalid id");
    const item = deps.repo.getItem(id);
    if (!item || item.deleted_at !== null) throw new HttpError(404, "Item not found");
    const file = liveFiles(deps.repo.filesForItem(id))[0];
    if (!file) throw new HttpError(404, "Item has no file");

    const path = filePath(deps, file);
    const bun = Bun.file(path);
    if (!(await bun.exists())) throw new HttpError(404, "File missing on disk");
    const size = bun.size;
    // A Node read stream sends the first chunk at once. `Bun.file(...).slice()` as a body reads the whole slice
    // into memory first (on Windows at least): an open-ended `bytes=0-` of a 900 MB file stalled every request
    // for 30 s and the TV app gave up. 1 MB chunks keep the loop free and the socket fed.
    const body = (start: number, end: number) =>
      Readable.toWeb(createReadStream(path, { start, end, highWaterMark: 1 << 20 })) as unknown as ReadableStream;

    const headers: Record<string, string> = {
      "Accept-Ranges": "bytes",
      "Content-Type": VIDEO_TYPES[extname(file.rel_path).toLowerCase()] ?? "application/octet-stream",
      "Cache-Control": "no-cache",
    };
    const head = c.req.method === "HEAD";
    const range = parseRange(c.req.header("Range"), size);

    if (range === "unsatisfiable") {
      return new Response(null, { status: 416, headers: { ...headers, "Content-Range": `bytes */${size}` } });
    }
    if (range) {
      headers["Content-Range"] = `bytes ${range.start}-${range.end}/${size}`;
      headers["Content-Length"] = String(range.end - range.start + 1);
      return new Response(head ? null : body(range.start, range.end), { status: 206, headers });
    }
    headers["Content-Length"] = String(size);
    return new Response(head || size === 0 ? null : body(0, size - 1), { status: 200, headers });
  });

  r.get("/images/:size/:name", async (c) => {
    const size = c.req.param("size");
    const name = c.req.param("name");
    if (!IMAGE_SIZES.includes(size)) throw new HttpError(400, "Invalid image size");
    let path: string;
    try {
      path = await deps.images.ensure(size as ImageSize, "/" + name);
    } catch (err) {
      const error = err instanceof Error ? err.message : String(err);
      deps.log.warn("image unavailable", { size, name, error });
      throw new HttpError(404, error);
    }
    return new Response(Bun.file(path), {
      headers: {
        "Content-Type": IMAGE_TYPES[extname(name).toLowerCase()] ?? "application/octet-stream",
        "Cache-Control": "public, max-age=31536000",
      },
    });
  });

  return r;
}
