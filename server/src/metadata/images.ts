import { mkdir, rename, rm, stat, writeFile } from "node:fs/promises";
import { basename, join } from "node:path";
import { DEFAULT_TIMEOUT_MS, withTimeout } from "../net";
import type { FetchFn } from "../types";

export type ImageSize = "w342" | "w500" | "w780" | "w1280" | "original";

const SAFE_NAME = /^[\w.-]+\.(jpg|jpeg|png|webp)$/i;

function safeName(tmdbPath: string): string | null {
  const name = basename(tmdbPath);
  return name !== "." && name !== ".." && SAFE_NAME.test(name) ? name : null;
}

export function imageUrl(size: ImageSize, tmdbPath: string | null): string | null {
  const name = tmdbPath ? safeName(tmdbPath) : null;
  return name ? `/api/images/${size}/${name}` : null;
}

export class ImageStore {
  private readonly inflight = new Map<string, Promise<string>>();
  private readonly dir: string;
  private readonly fetchFn: FetchFn;
  private readonly timeoutMs: number;

  constructor(opts: { dir: string; fetchFn: FetchFn; timeoutMs?: number }) {
    this.dir = opts.dir;
    this.fetchFn = opts.fetchFn;
    this.timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  }

  ensure(size: ImageSize, tmdbPath: string): Promise<string> {
    if (!safeName(tmdbPath)) return Promise.reject(new Error("image invalid path"));
    const key = size + tmdbPath;
    let p = this.inflight.get(key);
    if (!p) {
      p = this.load(size, tmdbPath).finally(() => this.inflight.delete(key));
      this.inflight.set(key, p);
    }
    return p;
  }

  private async load(size: ImageSize, tmdbPath: string): Promise<string> {
    const target = join(this.dir, size, basename(tmdbPath));
    if (await stat(target).then((st) => st.isFile(), () => false)) return target;

    const bytes = await withTimeout("image", this.timeoutMs, async (signal) => {
      const res = await this.fetchFn(`https://image.tmdb.org/t/p/${size}${tmdbPath}`, { signal });
      if (!res.ok) {
        await res.body?.cancel();
        throw new Error(`image ${res.status}`);
      }
      return new Uint8Array(await res.arrayBuffer());
    });

    await mkdir(join(this.dir, size), { recursive: true });
    const tmp = `${target}.${process.pid}.${crypto.randomUUID()}.tmp`;
    try {
      await writeFile(tmp, bytes);
      await rename(tmp, target);
    } catch (e) {
      await rm(tmp, { force: true });
      throw e;
    }
    return target;
  }
}
