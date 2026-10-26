import { mkdir, rename, rm, stat, writeFile } from "node:fs/promises";
import { basename, join } from "node:path";
import type { FetchFn } from "../types";

export type ImageSize = "w342" | "w500" | "w780" | "w1280" | "original";

export function imageUrl(size: ImageSize, tmdbPath: string | null): string | null {
  return tmdbPath ? `/api/images/${size}/${basename(tmdbPath)}` : null;
}

export class ImageStore {
  private readonly inflight = new Map<string, Promise<string>>();
  private readonly dir: string;
  private readonly fetchFn: FetchFn;

  constructor(opts: { dir: string; fetchFn: FetchFn }) {
    this.dir = opts.dir;
    this.fetchFn = opts.fetchFn;
  }

  ensure(size: ImageSize, tmdbPath: string): Promise<string> {
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
    if (await stat(target).then(() => true, () => false)) return target;

    const res = await this.fetchFn(`https://image.tmdb.org/t/p/${size}${tmdbPath}`);
    if (!res.ok) throw new Error(`image ${res.status}`);
    const bytes = new Uint8Array(await res.arrayBuffer());

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
