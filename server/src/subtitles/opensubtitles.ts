import type { FetchFn } from "../types";

const BASE = "https://api.opensubtitles.com/api/v1";

export type OsResult = {
  fileId: number;
  release: string;
  hashMatch: boolean;
  downloads: number;
  language: string;
  fps: number | null;
};

export interface OsSearch {
  hash?: string;
  query?: string;
  season?: number | null;
  episode?: number | null;
  tmdbId?: number | null;
  type: "episode" | "movie";
}

interface OsAttributes {
  files?: { file_id?: number }[];
  release?: string;
  moviehash_match?: boolean;
  download_count?: number;
  language?: string;
  fps?: number | null;
}

export class OsClient {
  private token: string | null = null;
  private readonly userAgent: string;

  constructor(
    private readonly opts: { apiKey: string; username: string; password: string; fetchFn: FetchFn; userAgent?: string },
  ) {
    this.userAgent = opts.userAgent ?? "PixelPantry v1";
  }

  private headers(post: boolean, token?: string): Record<string, string> {
    return {
      "Api-Key": this.opts.apiKey,
      "User-Agent": this.userAgent,
      Accept: "application/json",
      ...(post ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    };
  }

  private requireConfigured(): void {
    if (!this.opts.apiKey || !this.opts.username) throw new Error("OpenSubtitles not configured");
  }

  async search(q: OsSearch): Promise<OsResult[]> {
    this.requireConfigured();
    const params = new URLSearchParams();
    const add = (k: string, v: string | number | null | undefined) => {
      if (v !== undefined && v !== null && v !== "") params.set(k, String(v));
    };
    add("moviehash", q.hash);
    add("query", q.query);
    add("season_number", q.season);
    add("episode_number", q.episode);
    add("tmdb_id", q.tmdbId);
    params.set("languages", "en");
    params.set("type", q.type);
    const res = await this.opts.fetchFn(`${BASE}/subtitles?${params}`, { headers: this.headers(false) });
    if (!res.ok) throw new Error(`OpenSubtitles ${res.status}: ${await res.text()}`);
    const body = (await res.json()) as { data?: { attributes?: OsAttributes }[] };
    const out: OsResult[] = [];
    for (const d of body.data ?? []) {
      const a = d.attributes;
      const fileId = a?.files?.[0]?.file_id;
      if (!a || fileId === undefined) continue;
      out.push({
        fileId,
        release: a.release ?? "",
        hashMatch: a.moviehash_match === true,
        downloads: a.download_count ?? 0,
        language: a.language ?? "en",
        fps: a.fps ?? null,
      });
    }
    return out.sort((x, y) => Number(y.hashMatch) - Number(x.hashMatch) || y.downloads - x.downloads);
  }

  private async login(): Promise<string> {
    const res = await this.opts.fetchFn(`${BASE}/login`, {
      method: "POST",
      headers: this.headers(true),
      body: JSON.stringify({ username: this.opts.username, password: this.opts.password }),
    });
    if (!res.ok) throw new Error(`OpenSubtitles ${res.status}: ${await res.text()}`);
    const { token } = (await res.json()) as { token: string };
    this.token = token;
    return token;
  }

  private requestDownload(fileId: number, token: string): Promise<Response> {
    return this.opts.fetchFn(`${BASE}/download`, {
      method: "POST",
      headers: this.headers(true, token),
      body: JSON.stringify({ file_id: fileId }),
    });
  }

  async download(fileId: number): Promise<string> {
    this.requireConfigured();
    let res = await this.requestDownload(fileId, this.token ?? (await this.login()));
    if (res.status === 401) {
      this.token = null;
      res = await this.requestDownload(fileId, await this.login());
    }
    if (!res.ok) throw new Error(`OpenSubtitles ${res.status}: ${await res.text()}`);
    const { link } = (await res.json()) as { link: string };
    const file = await this.opts.fetchFn(link, { headers: { "User-Agent": this.userAgent } });
    if (!file.ok) throw new Error(`OpenSubtitles ${file.status}: ${await file.text()}`);
    return file.text();
  }
}
