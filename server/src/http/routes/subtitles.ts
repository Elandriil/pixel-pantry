import { Hono } from "hono";
import { readFile } from "node:fs/promises";
import { basename } from "node:path";
import type { ItemRow } from "../../library/repo";
import type { OsSearch } from "../../subtitles/opensubtitles";
import { openSubtitlesHash } from "../../subtitles/hash";
import { listSidecars, saveSidecar, type SidecarTrack } from "../../subtitles/sidecar";
import { decodeSubtitle } from "../../subtitles/decode";
import { shiftSubtitle } from "../../subtitles/shift";
import type { SubtitleListResponse, SubtitleSearchResponse } from "../../types";
import { jsonBody } from "../body";
import type { Deps } from "../deps";
import { HttpError } from "../errors";
import { filePath, liveFiles, liveItem, parseId, sidecarsOf, sidecarTrack, subtitleTracks } from "./catalog";

const CONTENT_TYPES: Record<SidecarTrack["format"], string> = {
  srt: "application/x-subrip",
  vtt: "text/vtt",
  ass: "text/x-ssa",
};

const message = (err: unknown) => (err instanceof Error ? err.message : String(err));

/** OpenSubtitles calls fail with 503: not configured, or upstream trouble. */
async function upstream<T>(deps: Deps, what: string, fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (err) {
    deps.log.warn(`opensubtitles ${what} failed`, { error: message(err) });
    throw new HttpError(503, message(err));
  }
}

export function subtitleRoutes(deps: Deps): Hono {
  const r = new Hono();

  /** The live item and the absolute path of its newest live file, or 404. */
  const video = (raw: string) => {
    const item = liveItem(deps, parseId(raw));
    const file = liveFiles(deps.repo.filesForItem(item.id))[0];
    if (!file) throw new HttpError(404, "Item has no file");
    return { item, file, abs: filePath(deps, file) };
  };

  const searchQuery = (item: ItemRow): Omit<OsSearch, "hash"> => {
    if (item.type === "movie") return { type: "movie", query: item.title, tmdbId: item.tmdb_id };
    const show = item.show_id !== null ? deps.repo.getShow(item.show_id) : null;
    return {
      type: "episode",
      query: show?.title ?? item.parsed_title,
      season: item.season_number,
      episode: item.episode_number,
      tmdbId: show?.tmdb_id ?? null,
    };
  };

  r.get("/items/:id/subtitles", async (c) => {
    const { item, file } = video(c.req.param("id"));
    return c.json({ tracks: await subtitleTracks(deps, item.id, file) } satisfies SubtitleListResponse);
  });

  r.get("/items/:id/subtitles/sidecar/:name", async (c) => {
    const { abs } = video(c.req.param("id"));
    const name = c.req.param("name"); // decoded by Hono
    // looked up among the listed sidecars, never joined into a path
    const track = (await sidecarsOf(abs)).find((t) => basename(t.path) === name);
    if (!track) throw new HttpError(404, "Subtitle not found");
    const shift = Number(c.req.query("shift") ?? 0);
    if (!Number.isSafeInteger(shift)) throw new HttpError(400, "shift must be an integer (ms)");
    // unshifted: the file's own bytes, whatever their encoding (so no charset is claimed)
    if (shift === 0) return new Response(Bun.file(track.path), { headers: { "Content-Type": CONTENT_TYPES[track.format] } });
    const text = decodeSubtitle(new Uint8Array(await readFile(track.path)));
    return c.body(shiftSubtitle(text, shift, track.format), 200, {
      "Content-Type": `${CONTENT_TYPES[track.format]}; charset=utf-8`,
    });
  });

  r.get("/items/:id/subtitles/search", async (c) => {
    const { item, abs } = video(c.req.param("id"));
    let hash: string | undefined;
    try {
      hash = await openSubtitlesHash(abs);
    } catch (err) {
      deps.log.warn("subtitle hash failed", { path: abs, error: message(err) });
    }
    const results = await upstream(deps, "search", () => deps.os.search({ ...searchQuery(item), hash }));
    return c.json({ results } satisfies SubtitleSearchResponse);
  });

  r.post("/items/:id/subtitles/download", async (c) => {
    const { item, abs } = video(c.req.param("id"));
    const b = await jsonBody(c);
    if (typeof b.fileId !== "number" || !Number.isSafeInteger(b.fileId)) throw new HttpError(400, "fileId must be a number");
    const fileId = b.fileId;
    const text = await upstream(deps, "download", () => deps.os.download(fileId));
    const saved = await saveSidecar(abs, text);
    const track = (await listSidecars(abs)).find((t) => t.path === saved);
    if (!track) throw new Error(`saved subtitle not listed: ${saved}`);
    deps.log.info("subtitle downloaded", { itemId: item.id, path: saved });
    return c.json(sidecarTrack(item.id, track));
  });

  return r;
}
