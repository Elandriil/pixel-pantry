import { DEFAULT_TIMEOUT_MS, withTimeout } from "../net";
import { resolve, type FetchFn, type Lazy } from "../types";

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
  /** The movie's TMDB id, or for an episode its show's (sent as `parent_tmdb_id`). */
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
  /** Login token plus the credentials it was obtained with, so a credential change forces a new login. */
  private session: { token: string; apiKey: string; username: string } | null = null;
  private readonly userAgent: string;

  constructor(
    private readonly opts: {
      apiKey: Lazy<string>;
      username: Lazy<string>;
      password: Lazy<string>;
      fetchFn: FetchFn;
      userAgent?: string;
      timeoutMs?: number;
    },
  ) {
    this.userAgent = opts.userAgent ?? "PixelPantry v1";
  }

  /** One request, its body read as text, within the timeout. */
  private request(url: string, init: RequestInit): Promise<{ status: number; ok: boolean; text: string }> {
    return withTimeout("OpenSubtitles", this.opts.timeoutMs ?? DEFAULT_TIMEOUT_MS, async (signal) => {
      const res = await this.opts.fetchFn(url, { ...init, signal });
      return { status: res.status, ok: res.ok, text: await res.text() };
    });
  }

  private headers(post: boolean, token?: string): Record<string, string> {
    return {
      "Api-Key": resolve(this.opts.apiKey),
      "User-Agent": this.userAgent,
      Accept: "application/json",
      ...(post ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    };
  }

  private requireConfigured(): void {
    if (!resolve(this.opts.apiKey) || !resolve(this.opts.username)) throw new Error("OpenSubtitles not configured");
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
    add(q.type === "episode" ? "parent_tmdb_id" : "tmdb_id", q.tmdbId);
    params.set("languages", "en");
    params.set("type", q.type);
    const res = await this.request(`${BASE}/subtitles?${params}`, { headers: this.headers(false) });
    if (!res.ok) throw new Error(`OpenSubtitles ${res.status}: ${res.text}`);
    const body = JSON.parse(res.text) as { data?: { attributes?: OsAttributes }[] };
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
    const apiKey = resolve(this.opts.apiKey);
    const username = resolve(this.opts.username);
    const res = await this.request(`${BASE}/login`, {
      method: "POST",
      headers: this.headers(true),
      body: JSON.stringify({ username, password: resolve(this.opts.password) }),
    });
    if (!res.ok) throw new Error(`OpenSubtitles ${res.status}: ${res.text}`);
    const { token } = JSON.parse(res.text) as { token: string };
    this.session = { token, apiKey, username };
    return token;
  }

  /** The cached token, unless the credentials changed since it was obtained. */
  private cachedToken(): string | null {
    const s = this.session;
    if (!s || s.apiKey !== resolve(this.opts.apiKey) || s.username !== resolve(this.opts.username)) return null;
    return s.token;
  }

  private requestDownload(fileId: number, token: string) {
    return this.request(`${BASE}/download`, {
      method: "POST",
      headers: this.headers(true, token),
      body: JSON.stringify({ file_id: fileId }),
    });
  }

  async download(fileId: number): Promise<string> {
    this.requireConfigured();
    let res = await this.requestDownload(fileId, this.cachedToken() ?? (await this.login()));
    if (res.status === 401) {
      this.session = null;
      res = await this.requestDownload(fileId, await this.login());
    }
    if (!res.ok) throw new Error(`OpenSubtitles ${res.status}: ${res.text}`);
    const { link } = JSON.parse(res.text) as { link: string };
    const file = await this.request(link, { headers: { "User-Agent": this.userAgent } });
    if (!file.ok) throw new Error(`OpenSubtitles ${file.status}: ${file.text}`);
    return file.text;
  }
}
