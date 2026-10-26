import { describe, expect, test } from "bun:test";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { buildApp } from "../../src/http/app";
import { ImageStore } from "../../src/metadata/images";
import type { FetchFn } from "../../src/types";
import { tmpDir } from "../helpers";
import { makeDeps, seedMovie } from "./helpers";

const SIZE = 10_000;
const bytes = new Uint8Array(SIZE).map((_, i) => (i * 7 + 3) % 256);

function setup(ext = "mkv", write = true) {
  const deps = makeDeps();
  const id = seedMovie(deps.repo, "Clip");
  if (ext !== "mkv") {
    deps.db.query("UPDATE files SET rel_path = ? WHERE item_id = ?").run(`movies/Clip.${ext}`, id);
  }
  if (write) {
    const path = join(deps.config().mediaRoot, `movies/Clip.${ext}`);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, bytes);
  }
  return { deps, id, app: buildApp(deps) };
}

const get = (app: ReturnType<typeof setup>["app"], id: number, range?: string, method = "GET") =>
  app.request(`/api/items/${id}/stream`, { method, headers: range ? { Range: range } : {} });

describe("GET /api/items/:id/stream", () => {
  test("full file without Range", async () => {
    const { app, id } = setup();
    const res = await get(app, id);
    expect(res.status).toBe(200);
    expect(res.headers.get("Accept-Ranges")).toBe("bytes");
    expect(res.headers.get("Content-Type")).toBe("video/x-matroska");
    expect(res.headers.get("Content-Length")).toBe("10000");
    expect(res.headers.get("Cache-Control")).toBe("no-cache");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(bytes);
  });

  test("Content-Type by extension", async () => {
    const types = [
      ["mp4", "video/mp4"], ["m4v", "video/mp4"], ["webm", "video/webm"], ["ts", "video/mp2t"],
      ["avi", "video/x-msvideo"], ["mov", "video/quicktime"], ["wmv", "application/octet-stream"],
    ];
    for (const [ext, type] of types) {
      const { app, id } = setup(ext);
      expect((await get(app, id)).headers.get("Content-Type")).toBe(type!);
    }
  });

  test("bytes=0-999 -> 206", async () => {
    const { app, id } = setup();
    const res = await get(app, id, "bytes=0-999");
    expect(res.status).toBe(206);
    expect(res.headers.get("Content-Range")).toBe("bytes 0-999/10000");
    expect(res.headers.get("Content-Length")).toBe("1000");
    expect(res.headers.get("Accept-Ranges")).toBe("bytes");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(bytes.slice(0, 1000));
  });

  test("open end clamps to size-1", async () => {
    const { app, id } = setup();
    const res = await get(app, id, "bytes=1000-");
    expect(res.status).toBe(206);
    expect(res.headers.get("Content-Range")).toBe("bytes 1000-9999/10000");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(bytes.slice(1000));
  });

  test("end past EOF clamps", async () => {
    const { app, id } = setup();
    const res = await get(app, id, "bytes=0-999999999");
    expect(res.status).toBe(206);
    expect(res.headers.get("Content-Range")).toBe("bytes 0-9999/10000");
    expect(res.headers.get("Content-Length")).toBe("10000");
  });

  test("start past EOF -> 416", async () => {
    const { app, id } = setup();
    const res = await get(app, id, "bytes=999999999-");
    expect(res.status).toBe(416);
    expect(res.headers.get("Content-Range")).toBe("bytes */10000");
  });

  test("start > end -> 416; zero-length suffix -> 416", async () => {
    const { app, id } = setup();
    for (const r of ["bytes=500-100", "bytes=-0"]) {
      const res = await get(app, id, r);
      expect(res.status).toBe(416);
      expect(res.headers.get("Content-Range")).toBe("bytes */10000");
    }
  });

  test("suffix range serves the last n bytes", async () => {
    const { app, id } = setup();
    const res = await get(app, id, "bytes=-500");
    expect(res.status).toBe(206);
    expect(res.headers.get("Content-Range")).toBe("bytes 9500-9999/10000");
    expect(res.headers.get("Content-Length")).toBe("500");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(bytes.slice(9500));
    const big = await get(app, id, "bytes=-999999");
    expect(big.headers.get("Content-Range")).toBe("bytes 0-9999/10000");
  });

  test("multiple ranges serve the first", async () => {
    const { app, id } = setup();
    const res = await get(app, id, "bytes=0-9, 20-29");
    expect(res.status).toBe(206);
    expect(res.headers.get("Content-Range")).toBe("bytes 0-9/10000");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(bytes.slice(0, 10));
  });

  test("malformed Range is ignored (full 200)", async () => {
    const { app, id } = setup();
    const res = await get(app, id, "items=0-5");
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Length")).toBe("10000");
  });

  test("HEAD returns headers and no body", async () => {
    const { app, id } = setup();
    const res = await get(app, id, undefined, "HEAD");
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Length")).toBe("10000");
    expect(res.headers.get("Accept-Ranges")).toBe("bytes");
    expect((await res.arrayBuffer()).byteLength).toBe(0);

    const ranged = await get(app, id, "bytes=0-99", "HEAD");
    expect(ranged.status).toBe(206);
    expect(ranged.headers.get("Content-Range")).toBe("bytes 0-99/10000");
    expect((await ranged.arrayBuffer()).byteLength).toBe(0);
  });

  test("404 when file missing on disk, item missing, or id invalid", async () => {
    const { app, id } = setup("mkv", false);
    expect((await get(app, id)).status).toBe(404);
    expect((await get(app, 9999)).status).toBe(404);
    expect((await app.request("/api/items/abc/stream")).status).toBe(400);
  });
});

describe("GET /api/images/:size/:name", () => {
  const png = new Uint8Array([1, 2, 3, 4]);
  const imgApp = () => {
    const deps = makeDeps();
    let calls = 0;
    const fetchFn = (async () => {
      calls++;
      return new Response(png);
    }) as unknown as FetchFn;
    deps.images = new ImageStore({ dir: join(tmpDir(), "images"), fetchFn });
    return { app: buildApp(deps), calls: () => calls };
  };

  test("serves image with long cache header", async () => {
    const { app } = imgApp();
    const res = await app.request("/api/images/w500/abc.png");
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("image/png");
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=31536000");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(png);
  });

  test("bad size -> 400", async () => {
    const { app, calls } = imgApp();
    const res = await app.request("/api/images/w999/abc.png");
    expect(res.status).toBe(400);
    expect(calls()).toBe(0);
  });

  test("invalid name or upstream failure -> 404", async () => {
    const { app } = imgApp();
    expect((await app.request("/api/images/w500/evil.txt")).status).toBe(404);
    const deps = makeDeps();
    deps.images = new ImageStore({
      dir: join(tmpDir(), "images"),
      fetchFn: (async () => new Response("no", { status: 404 })) as unknown as FetchFn,
    });
    const res = await buildApp(deps).request("/api/images/w500/gone.jpg");
    expect(res.status).toBe(404);
    expect((await res.json()) as any).toHaveProperty("error");
  });
});
