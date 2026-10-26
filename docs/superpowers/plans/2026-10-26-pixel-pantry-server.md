# Pixel Pantry Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A single Windows executable that scans a media folder, matches it against TMDB, serves video/subtitles/artwork over LAN HTTP, tracks playback state, searches OpenSubtitles, and exposes a localhost admin page.

**Architecture:** One Bun process. A scanner turns the folder into SQLite rows (shows → seasons → items; movies are items too; files attach to items). A Hono HTTP app exposes `/api/*` for clients (TV app now, browser later) and `/admin/*` for the localhost-only admin page. External services (TMDB, OpenSubtitles) and side effects (ffprobe, trash, fetch) are injected so everything is testable with `bun test`.

**Tech Stack:** Bun 1.3.x (runtime, `bun:sqlite`, `bun test`, `bun build --compile`), TypeScript, Hono 4, `@ctrl/video-filename-parser` 5, `chokidar` 6, `bonjour-service` 1, `ffprobe-static` 3 (dev) / bundled `ffprobe.exe` (prod).

**Spec:** `docs/superpowers/specs/2026-10-26-pixel-pantry-design.md`

## Global Constraints

- Server code lives in `server/`; the TV app (separate plan) will live in `tv/`. Repo root already has `.gitignore` with `.superpowers/`; add `server/node_modules/`, `server/data/`, `server/dist/`.
- Categories are exactly `tv`, `anime`, `movies` (top-level folders of the media root). `photos` and any other top-level folder are ignored.
- Video extensions: `.mkv .mp4 .m4v .avi .webm .ts .mov .wmv`. Subtitle extensions: `.srt .vtt .ass .ssa`.
- Skipped during walk: hidden entries (name starts with `.`), folders named `extras`, `featurettes`, `samples` (case-insensitive), files whose basename contains the word `sample` (case-insensitive, `/\bsample\b/i`).
- Watched threshold: position ≥ 90 % of duration. "In progress" means position ≥ 30 s and not watched.
- Default settings: `skipBackSeconds: 10`, `skipForwardSeconds: 10`, `autoplayNext: true`.
- Subtitle language: `en` only. Downloaded subtitle filename: `<video basename>.en.srt`, then `.en.2.srt`, `.en.3.srt`… when taken.
- Delete moves files to the Recycle Bin, never `rm`.
- `/admin/*` routes answer only to loopback addresses (`127.0.0.1`, `::1`, `::ffff:127.0.0.1`); otherwise 403.
- Scans are incremental: a file is re-probed and re-parsed only when `size` or `mtime` changed; vanished files get `deleted_at` set, never deleted; items/shows with no live files are hidden from every API list.
- All user-visible strings in English.
- Default port `8765`. mDNS service type `_pixelpantry._tcp`.
- Data directory: `server/data/` in dev; `<directory of the exe>/data/` when compiled. Contains `config.json`, `pantry.db`, `images/`, `logs/server.log`.

## Review Focus

1. A file path with apostrophes, unicode, nested release folders (`tv/Some.Show.S01.1080p/Some.Show.S01E01.mkv`) or no recognisable episode number must still produce a file row and an item (possibly titled from the folder) — never a crash and never "not shown anywhere". Pinned in Task 3 (`parse.test.ts` case "unparseable") and Task 6 (scanner test "unparseable file still becomes an item").
2. `Range: bytes=1000-` (open end) and `Range: bytes=0-999999999` (end past EOF) must return 206 with a clamped range; `Range: bytes=999999999-` must return 416. Pinned in Task 12.
3. Item and show ids must be stable across scans when files did not change, so a playing item is not orphaned by a rescan. Pinned in Task 6 (scanner test "rescan keeps ids").
4. With no TMDB key, or TMDB unreachable, a scan must finish, items must appear unmatched, and the admin status must show the error text. Pinned in Task 6 (scanner test "tmdb failure leaves show unmatched") and Task 14.
5. `POST progress` with `duration: 0`, `position > duration`, or negative values must not mark watched, must not throw, and must clamp. Pinned in Task 9.

---

## File structure

```
server/
  package.json  tsconfig.json  bunfig.toml
  scripts/package-win.sh          # bun build --compile for Windows + copy ffprobe.exe
  src/
    index.ts                      # entry: wire everything, start server
    types.ts                      # API response types, shared with future web UI
    paths.ts                      # dataDir(), isCompiled(), resolveFfprobePath()
    config.ts                     # Config load/save
    log.ts                        # file + ring-buffer logger
    db/schema.ts                  # DDL + migrate(db)
    db/db.ts                      # openDb(path)
    library/parse.ts              # parseVideoPath(relPath) -> ParsedVideo
    library/walk.ts               # listVideoFiles(root) -> FileEntry[]
    library/probe.ts              # probeFile(ffprobePath, absPath) -> ProbeResult
    library/repo.ts               # catalog upserts/queries used by scanner and routes
    library/scanner.ts            # Scanner class
    library/watcher.ts            # watchRoot(root, onChange)
    metadata/tmdb.ts              # TmdbClient
    metadata/matcher.ts           # pickTvMatch / pickMovieMatch
    metadata/anime.ts             # absoluteToSeasonEpisode
    metadata/images.ts            # ImageStore (lazy TMDB image cache)
    playback/progress.ts          # progress, watched, sessions, continue-watching
    playback/history.ts           # history list, stats
    subtitles/hash.ts             # openSubtitlesHash
    subtitles/shift.ts            # shiftSubtitle(text, ms)
    subtitles/sidecar.ts          # listSidecars, nextSidecarName, saveSidecar
    subtitles/opensubtitles.ts    # OsClient
    fs/trash.ts                   # trashPaths(paths) via PowerShell / osascript
    discovery.ts                  # advertise(port)
    tray.ts                       # spawn tray.ps1 on Windows (script embedded as string)
    http/app.ts                   # buildApp(deps) -> Hono
    http/deps.ts                  # Deps interface (db, config, scanner, tmdb, os, images, log, trash)
    http/routes/catalog.ts        # home, categories, shows, movies, items, search
    http/routes/stream.ts         # stream (range), images
    http/routes/playback.ts       # progress, watched, history, stats, delete, settings
    http/routes/subtitles.ts
    http/routes/scan.ts
    http/routes/admin.ts          # loopback guard, status, config, unmatched, match, log, quit
    http/admin/index.html         # admin page (vanilla JS)
  test/                           # mirrors src/, *.test.ts; test/helpers.ts for temp dirs + fake deps
```

---

### Task 1: Project scaffold, config, logger

**Files:**
- Create: `server/package.json`, `server/tsconfig.json`, `server/bunfig.toml`, `server/src/paths.ts`, `server/src/config.ts`, `server/src/log.ts`, `server/test/helpers.ts`, `server/test/config.test.ts`, `server/test/log.test.ts`
- Modify: `.gitignore`

**Interfaces:**
- Produces:
  - `paths.ts`: `isCompiled(): boolean` (true when `process.execPath` basename is not `bun`/`bun.exe`), `dataDir(): string` (env `PANTRY_DATA_DIR` → else `<dirname(execPath)>/data` when compiled → else `<server>/data`), `resolveFfprobePath(): string` (env `FFPROBE_PATH` → `<dirname(execPath)>/ffprobe.exe` if exists → `ffprobe-static` export → `'ffprobe'`).
  - `config.ts`: `interface Config { mediaRoot: string; port: number; tmdbApiKey: string; opensubtitles: { apiKey: string; username: string; password: string } }`, `DEFAULT_CONFIG` (`mediaRoot: ''`, `port: 8765`, keys empty), `loadConfig(path: string): Config` (missing file → defaults; unknown keys dropped; missing keys filled), `saveConfig(path: string, c: Config): void`.
  - `log.ts`: `class Logger { constructor(filePath: string, opts?: { ringSize?: number; maxBytes?: number }); info(msg: string, data?: object): void; warn(...): void; error(...): void; recent(n?: number): string[] }`. Line format `2026-10-26T10:00:00.000Z INFO message {"k":1}`. When file exceeds `maxBytes` (default 5 MB) rename to `server.log.1` (overwrite) and start fresh. Ring buffer default 500 lines.
  - `test/helpers.ts`: `tmpDir(): string` (fresh dir under `os.tmpdir()`, registered for `afterAll` cleanup), `touch(path: string, bytes?: number): void` (creates parent dirs, writes `bytes` zero bytes, default 0).

- [ ] **Step 1: Scaffold**

`cd server && bun init -y`, then set `package.json` `"name": "pixel-pantry-server"`, `"type": "module"`, scripts `"dev": "bun run src/index.ts"`, `"test": "bun test"`, `"package:win": "bash scripts/package-win.sh"`. `bun add hono@4 @ctrl/video-filename-parser@5 chokidar@6 bonjour-service@1` and `bun add -d ffprobe-static@3 ffmpeg-static@5 @types/bun`. `tsconfig.json`: `strict: true`, `module: ESNext`, `moduleResolution: bundler`, `types: ["bun-types"]`. Append `server/node_modules/`, `server/data/`, `server/dist/` to root `.gitignore`.

- [ ] **Step 2: Write failing tests**

`test/config.test.ts`:
```ts
test("loadConfig returns defaults when file missing", () => {
  expect(loadConfig(join(tmpDir(), "config.json"))).toEqual(DEFAULT_CONFIG);
});
test("saveConfig then loadConfig round-trips and fills missing keys", () => {
  const p = join(tmpDir(), "config.json");
  writeFileSync(p, JSON.stringify({ mediaRoot: "D:\\Media", bogus: 1 }));
  const c = loadConfig(p);
  expect(c.mediaRoot).toBe("D:\\Media"); expect(c.port).toBe(8765); expect((c as any).bogus).toBeUndefined();
  saveConfig(p, { ...c, port: 9000 });
  expect(loadConfig(p).port).toBe(9000);
});
```
`test/log.test.ts`:
```ts
test("writes lines to file and keeps ring buffer", () => {
  const f = join(tmpDir(), "server.log"); const log = new Logger(f, { ringSize: 2 });
  log.info("a"); log.warn("b", { x: 1 }); log.error("c");
  expect(readFileSync(f, "utf8").split("\n").filter(Boolean)).toHaveLength(3);
  expect(log.recent()).toEqual([expect.stringContaining("WARN b {\"x\":1}"), expect.stringContaining("ERROR c")]);
});
test("rotates when file exceeds maxBytes", () => {
  const f = join(tmpDir(), "server.log"); const log = new Logger(f, { maxBytes: 100 });
  for (let i = 0; i < 10; i++) log.info("x".repeat(30));
  expect(existsSync(f + ".1")).toBe(true);
});
```

- [ ] **Step 3: Run tests, verify they fail** — `cd server && bun test` → FAIL (modules not found).

- [ ] **Step 4: Implement `paths.ts`, `config.ts`, `log.ts`, `test/helpers.ts`** per Interfaces.

- [ ] **Step 5: Run tests, verify pass** — `bun test` → 4 pass.

- [ ] **Step 6: Commit** — `git add -A server .gitignore && git commit -m "feat(server): scaffold, config, logger"`

---

### Task 2: SQLite schema and catalog repository

**Files:**
- Create: `server/src/db/schema.ts`, `server/src/db/db.ts`, `server/src/library/repo.ts`, `server/test/db.test.ts`, `server/test/repo.test.ts`

**Interfaces:**
- Produces:
  - `db.ts`: `openDb(path: string | ":memory:"): Database` (bun:sqlite, `PRAGMA journal_mode=WAL; foreign_keys=ON`, runs `migrate`).
  - `schema.ts`: `migrate(db: Database): void` using a `schema_version` table; version 1 creates:
    - `shows(id INTEGER PK, category TEXT, parsed_key TEXT UNIQUE, parsed_title TEXT, title TEXT, year INT, tmdb_id INT, overview TEXT, poster_path TEXT, backdrop_path TEXT, rating REAL, genres TEXT /*json*/, first_air_date TEXT, match_error TEXT, match_attempted_at INT, created_at INT, deleted_at INT)`
    - `seasons(id INTEGER PK, show_id INT REFERENCES shows, number INT, name TEXT, poster_path TEXT, episode_count INT, UNIQUE(show_id, number))`
    - `items(id INTEGER PK, type TEXT /*episode|movie*/, category TEXT, show_id INT NULL, season_number INT NULL, episode_number INT NULL, episode_end INT NULL, parsed_key TEXT NULL /*movies*/, parsed_title TEXT, title TEXT, year INT, tmdb_id INT, overview TEXT, poster_path TEXT, backdrop_path TEXT, still_path TEXT, rating REAL, genres TEXT, air_date TEXT, runtime INT, match_error TEXT, match_attempted_at INT, created_at INT, deleted_at INT, UNIQUE(show_id, season_number, episode_number))`
    - `files(id INTEGER PK, rel_path TEXT UNIQUE, category TEXT, size INT, mtime INT, parsed TEXT /*json ParsedVideo*/, item_id INT NULL, duration REAL, audio_tracks TEXT /*json*/, subtitle_tracks TEXT, probe_error TEXT, added_at INT, deleted_at INT)`
    - `playback(item_id INT PK, position REAL, duration REAL, watched INT, last_played_at INT, subtitle_choice TEXT, audio_choice TEXT)`
    - `sessions(id INTEGER PK, item_id INT, started_at INT, ended_at INT, last_position REAL, seconds_watched REAL)`
    - `settings(key TEXT PK, value TEXT)`
    - `match_overrides(parsed_key TEXT PK, item_type TEXT, tmdb_id INT)`
    - `tmdb_cache(key TEXT PK, json TEXT, fetched_at INT)`
  - `repo.ts`: a `Repo` class wrapping prepared statements. Methods used by later tasks (all synchronous):
    - files: `getFileByPath(relPath): FileRow | null`, `upsertFile(f: Omit<FileRow,'id'>): number`, `markFilesDeletedExcept(livePaths: string[], now: number): number`, `undeleteFile(id)`, `listLiveFiles(): FileRow[]`, `setFileItem(fileId, itemId)`, `setFileProbe(fileId, probe: ProbeResult | null, error: string | null)`, `filesForItem(itemId): FileRow[]`
    - shows: `getShowByKey(parsedKey)`, `insertShow(...)`, `updateShowMeta(id, meta)`, `setShowMatchError(id, err, at)`, `listShows(category?, opts?: { includeDeleted })`, `getShow(id)`, `setShowDeleted(id, deletedAt | null)`
    - seasons: `upsertSeason(showId, number, meta)`, `listSeasons(showId)`
    - items: `getEpisode(showId, season, episode)`, `getMovieByKey(parsedKey)`, `insertItem(...)`, `updateItemMeta(id, meta)`, `getItem(id)`, `listEpisodes(showId)`, `listMovies(category)`, `setItemDeleted(id, at | null)`, `searchTitles(q): { shows: ShowRow[]; movies: ItemRow[] }`
    - housekeeping: `hideEmpty(now)` — sets `deleted_at` on items with no live file and on shows with no live items; clears `deleted_at` where live files exist again.
    - settings: `getSetting(key): string | null`, `setSetting(key, value)`
    - overrides: `getOverride(parsedKey)`, `setOverride(parsedKey, type, tmdbId)`
    - cache: `cacheGet(key, maxAgeMs): string | null`, `cacheSet(key, json)`
  - Row types `FileRow`, `ShowRow`, `SeasonRow`, `ItemRow` exported from `repo.ts`.

- [ ] **Step 1: Write failing tests**

`test/db.test.ts`: `openDb(":memory:")` has tables `shows, seasons, items, files, playback, sessions, settings, match_overrides, tmdb_cache` (query `sqlite_master`), and `migrate` run twice is a no-op.
`test/repo.test.ts` (in-memory db):
```ts
test("upsertFile is idempotent on rel_path and returns same id", ...)
test("markFilesDeletedExcept marks vanished files and undelete clears it", ...)
test("hideEmpty hides show whose only item has no live file, and revives it when file returns", ...)
test("searchTitles matches case-insensitively on show and movie titles", ...)
```

- [ ] **Step 2: Run, verify fail.** `bun test test/db.test.ts test/repo.test.ts`

- [ ] **Step 3: Implement `schema.ts`, `db.ts`, `repo.ts`.** `parsed_key` is built later by the parser (Task 3) as `${category}:${normalizedTitle}:${year ?? ''}`.

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit** — `git commit -m "feat(server): sqlite schema and catalog repo"`

---

### Task 3: Filename and path parser

**Files:**
- Create: `server/src/library/parse.ts`, `server/test/parse.test.ts`

**Interfaces:**
- Produces:
  - `type Category = 'tv' | 'anime' | 'movies'` (in `types.ts`, created here).
  - `interface ParsedVideo { category: Category; kind: 'episode' | 'movie'; title: string; year: number | null; season: number | null; episodes: number[]; absolute: boolean; parsedKey: string }`
  - `parseVideoPath(relPath: string): ParsedVideo | null` — null only when the top-level folder is not a category. `relPath` uses `/` separators (walker normalises).
  - `normalizeTitle(s: string): string` — lowercase, strip diacritics, drop everything but `[a-z0-9 ]`, collapse spaces, trim.

Rules the implementation must follow:
- `kind` is `movie` iff category is `movies`.
- Run `filenameParse(basename, kind === 'episode')` from `@ctrl/video-filename-parser`. Take `title`, `year`, `seasons[0]`, `episodeNumbers`.
- If `episodeNumbers` is empty and kind is episode: strip `[...]` and `(...)` groups from the basename (without extension), then try in order: `/\bS(\d{1,2})E(\d{1,3})(?:-E?(\d{1,3}))?\b/i`, `/\b(\d{1,2})x(\d{1,3})\b/i`, `/\bepisode\s*(\d{1,3})\b/i`, `/\s-\s(\d{1,3})(?:\s|$)/`, `/\bE(\d{1,3})\b/i`, `/^(\d{1,3})\b/`. First match wins.
- Season: library `seasons[0]` → else from any parent folder matching `/^season\s*(\d{1,2})$/i` or `/^S(\d{1,2})$/i` → else null. Anime with a bare `- 05` style number and no season anywhere → `season: null, absolute: true`.
- Title: library `title` if non-empty → else the nearest ancestor folder below the category that is not a season folder, with a trailing `(YYYY)` or `YYYY` removed and `.`/`_` turned into spaces → else the cleaned basename up to the episode marker → else the literal folder name (even "Season 03").
- Year: library `year` → else `/\((\d{4})\)/` or `/\b(19|20)\d{2}\b/` in the title folder name.
- `parsedKey = `${category}:${normalizeTitle(title)}:${year ?? ''}``.

- [ ] **Step 1: Write failing tests** (`test/parse.test.ts`, one `test.each` table):

| relPath | title | year | season | episodes | absolute |
|---|---|---|---|---|---|
| `tv/Severance/Season 02/Severance S02E04 Woes Hollow 1080p.mkv` | Severance | null | 2 | [4] | false |
| `tv/Severance/Severance - 2x04 - Woes Hollow.mkv` | Severance | null | 2 | [4] | false |
| `tv/Season 03/The.Bear.S03E01-E02.1080p.WEB.mkv` | The Bear | null | 3 | [1,2] | false |
| `tv/Dark (2017)/Season 1/Episode 5.mkv` | Dark | 2017 | 1 | [5] | false |
| `tv/Some.Show.S01.1080p.WEB-DL/Some.Show.S01E01.mkv` | Some Show | null | 1 | [1] | false |
| `anime/[SubsPlease] Frieren - 05 (1080p) [ABCD1234].mkv` | Frieren | null | null | [5] | true |
| `anime/Frieren/Frieren - 28.mkv` | Frieren | null | null | [28] | true |
| `anime/Frieren/Frieren S01E05.mkv` | Frieren | null | 1 | [5] | false |
| `movies/Dune Part Two (2024)/Dune.Part.Two.2024.2160p.mkv` | Dune Part Two | 2024 | null | [] | false |
| `movies/Dune.Part.Two.2024.1080p.BluRay.x264.mkv` | Dune Part Two | 2024 | null | [] | false |
| `tv/Weird Folder/clip.mkv` ("unparseable") | Weird Folder | null | null | [] | false |

Plus: `parseVideoPath("photos/x.mkv")` is null; `parsedKey` of the first row is `tv:severance:`; `normalizeTitle("Shōgun: The Series!")` is `shogun the series`.

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement `parse.ts` and `types.ts` (Category).**
- [ ] **Step 4: Run, verify pass.** Adjust the fallback regex order if a row fails, not the expectations.
- [ ] **Step 5: Commit** — `git commit -m "feat(server): video path parser"`

---

### Task 4: Folder walker and ffprobe wrapper

**Files:**
- Create: `server/src/library/walk.ts`, `server/src/library/probe.ts`, `server/test/walk.test.ts`, `server/test/probe.test.ts`

**Interfaces:**
- Produces:
  - `interface FileEntry { relPath: string; category: Category; size: number; mtime: number }` (`mtime` in ms, `relPath` with `/`).
  - `listVideoFiles(root: string): Promise<FileEntry[]>` — applies the Global Constraints skip rules; sorted by `relPath`.
  - `interface Track { index: number; codec: string; language: string | null; title: string | null; channels?: number; default: boolean; forced: boolean }`
  - `interface ProbeResult { duration: number | null; audio: Track[]; subtitles: Track[]; video: { codec: string; width: number; height: number } | null }`
  - `probeFile(ffprobePath: string, absPath: string): Promise<ProbeResult>` — runs `ffprobe -v error -print_format json -show_format -show_streams <path>` via `Bun.spawn`, 30 s timeout, throws `Error("ffprobe failed: <stderr>")` on non-zero exit.

- [ ] **Step 1: Write failing tests**

`walk.test.ts`: build a tmp tree with `touch`: `tv/A/Season 01/A S01E01.mkv`, `tv/A/Season 01/A S01E01.sample.mkv`, `tv/A/extras/x.mkv`, `tv/.hidden/y.mkv`, `anime/B - 01.mp4`, `movies/C (2020)/C.mkv`, `movies/C (2020)/C.srt`, `photos/d.mkv`, `other/e.mkv`. Expect relPaths exactly `["anime/B - 01.mp4", "movies/C (2020)/C.mkv", "tv/A/Season 01/A S01E01.mkv"]` with matching categories and `size`/`mtime` numbers.

`probe.test.ts`: in `beforeAll`, generate `fixture.mkv` with `ffmpeg-static`: `ffmpeg -y -f lavfi -i testsrc=duration=2:size=64x64:rate=5 -f lavfi -i sine=duration=2 -c:v libx264 -c:a aac -metadata:s:a:0 language=eng fixture.mkv`. Expect `duration` within `[1.5, 2.5]`, `video.codec === "h264"`, `audio[0].codec === "aac"`, `audio[0].language === "eng"`, `subtitles` `[]`. Second test: `probeFile(path, "/nonexistent")` rejects with message containing `ffprobe failed`.

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.** Use `fs.promises.readdir(..., { withFileTypes: true })` recursion; probe maps streams by `codec_type`, `tags.language`, `tags.title`, `disposition.default/forced`.
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(server): folder walker and ffprobe wrapper"`

---

### Task 5: TMDB client, matcher, anime episode mapping

**Files:**
- Create: `server/src/metadata/tmdb.ts`, `server/src/metadata/matcher.ts`, `server/src/metadata/anime.ts`, `server/test/tmdb.test.ts`, `server/test/matcher.test.ts`, `server/test/anime.test.ts`

**Interfaces:**
- Produces:
  - `type FetchFn = typeof fetch` (in `types.ts`).
  - `class TmdbClient { constructor(opts: { apiKey: string; fetchFn: FetchFn; cache: { get(key, maxAgeMs): string | null; set(key, json): void }; cacheMaxAgeMs?: number /* default 7 days */ }) }` with:
    - `searchTv(query: string, year?: number | null): Promise<TmdbSearchResult[]>`
    - `searchMovie(query: string, year?: number | null): Promise<TmdbSearchResult[]>`
    - `tvDetails(id: number): Promise<TmdbTvDetails>` (`{ id, name, first_air_date, overview, poster_path, backdrop_path, vote_average, genres: {name}[], seasons: { season_number, name, episode_count, poster_path }[] }`)
    - `tvSeason(id: number, season: number): Promise<TmdbSeason>` (`{ season_number, name, poster_path, episodes: { episode_number, name, overview, still_path, air_date, runtime }[] }`)
    - `movieDetails(id: number): Promise<TmdbMovieDetails>` (`{ id, title, release_date, overview, poster_path, backdrop_path, vote_average, genres, runtime }`)
    - `TmdbSearchResult = { id: number; title: string; year: number | null; popularity: number }` (normalised from `name`/`title`, `first_air_date`/`release_date`).
    - Base URL `https://api.themoviedb.org/3`. If `apiKey` starts with `eyJ` send `Authorization: Bearer`, else `?api_key=`. Throws `Error("TMDB <status>: <body>")` on non-2xx; throws `Error("TMDB API key not set")` when key empty.
  - `pickMatch(results: TmdbSearchResult[], title: string, year: number | null): TmdbSearchResult | null` — score: +3 exact `normalizeTitle` match, +2 normalized title starts with query (or vice versa), +2 year equal, +1 year within ±1, tie-break by `popularity`; return null when no result scores ≥ 2.
  - `absoluteToSeasonEpisode(absolute: number, episodeCounts: number[]): { season: number; episode: number } | null` — `episodeCounts[i]` is the count for season `i+1` (season 0 excluded); null when `episodeCounts` is empty or `absolute` exceeds the sum.

- [ ] **Step 1: Write failing tests**

`tmdb.test.ts` with a fake `fetchFn` that records URLs and returns canned JSON: `searchTv("Severance", 2022)` requests `/3/search/tv?query=Severance&first_air_date_year=2022&api_key=KEY` and normalises `{name:"Severance", first_air_date:"2022-02-18"}` to `{title:"Severance", year:2022}`; second identical call hits cache (fetch called once); bearer header used when key starts with `eyJ`; non-2xx throws with status in message.

`matcher.test.ts`: exact title + year wins over popular partial; year ±1 still matches; `pickMatch([], ...)` null; unrelated titles → null.

`anime.test.ts`: `absoluteToSeasonEpisode(5, [12, 12])` → `{1,5}`; `(13, [12,12])` → `{2,1}`; `(28, [12,12])` → null; `(3, [])` → null.

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement the three modules.**
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(server): tmdb client, matcher, anime mapping"`

---

### Task 6: Scanner

**Files:**
- Create: `server/src/library/scanner.ts`, `server/test/scanner.test.ts`
- Modify: `server/test/helpers.ts` (add `fakeTmdb()` returning a `TmdbClient`-shaped object with programmable responses, and `fakeProbe()`)

**Interfaces:**
- Consumes: Tasks 2–5.
- Produces:
  - `interface ScanStatus { state: 'idle' | 'scanning'; startedAt: number | null; finishedAt: number | null; filesSeen: number; filesProcessed: number; phase: 'walking' | 'probing' | 'matching' | 'done'; lastError: string | null }`
  - `class Scanner { constructor(deps: { repo: Repo; tmdb: TmdbLike; probe: (absPath: string) => Promise<ProbeResult>; mediaRoot: () => string; log: Logger; now?: () => number }); scan(): Promise<ScanStatus>; status(): ScanStatus; onFinished(cb: () => void): void }` where `TmdbLike = Pick<TmdbClient, 'searchTv'|'searchMovie'|'tvDetails'|'tvSeason'|'movieDetails'>`.
  - `scan()` when already scanning returns the in-flight promise (no second concurrent scan). If `mediaRoot()` is empty or missing: status `lastError = "Media folder not set"` and resolve.

Algorithm (the parts tests pin):
1. `listVideoFiles(root)`; `filesSeen = n`.
2. For each entry: existing row with same size+mtime and not deleted → skip. Otherwise `parseVideoPath`, `upsertFile`, then probe (on throw: `setFileProbe(id, null, err.message)`, continue). `filesProcessed++`.
3. `markFilesDeletedExcept(livePaths)`.
4. Group live files by `parsed.parsedKey`:
   - episodes: `getShowByKey` or `insertShow`. If `tmdb_id` null and (`match_attempted_at` null or older than 24 h): `getOverride(key)` → else `searchTv(title, year)` + `pickMatch`; on success `tvDetails` → `updateShowMeta`, `upsertSeason` for each TMDB season; on throw/null `setShowMatchError`. For each file: resolve `(season, episode)`: if `parsed.absolute` and show has TMDB seasons → `absoluteToSeasonEpisode(abs, counts)` else `(parsed.season ?? 1, parsed.episodes[0])`; null mapping → `(1, abs)`. `getEpisode` or `insertItem` (title `Episode N` until TMDB fills it, `episode_end` = last of `parsed.episodes` when >1). When the show is matched and the season's episodes have not been fetched (no item in that season has `tmdb_id`), call `tvSeason` once per season and `updateItemMeta` for items that exist. `setFileItem`.
   - movies: `getMovieByKey` or `insertItem(type:'movie')`; match via `searchMovie` + `pickMatch` + `movieDetails` under the same 24 h retry rule; `setFileItem`.
5. `repo.hideEmpty(now)`.
6. Set `finishedAt`, `state='idle'`, call finished callbacks. Any unexpected throw → `lastError = message`, logged, state back to idle.

- [ ] **Step 1: Write failing tests** (temp media tree built with `touch`, `fakeProbe` returns `{duration: 1200, ...}`, `fakeTmdb` canned):

```ts
test("first scan creates show, seasons, episodes, attaches files", ...)        // tv/Severance/Season 02/Severance S02E04.mkv + E05 → show.tmdb_id set, 2 items, files.item_id set
test("rescan keeps ids and does not re-probe unchanged files", ...)              // probe call count unchanged on second scan; item ids equal
test("changed mtime re-probes that file only", ...)
test("vanished file hides item and show; restored file revives them", ...)
test("anime absolute numbering maps through TMDB season counts", ...)           // Frieren - 13 with counts [12,16] → season 2 episode 1
test("anime absolute without TMDB falls back to season 1", ...)
test("tmdb failure leaves show unmatched with match_error and scan finishes", ...)  // fakeTmdb.searchTv throws "TMDB 401"
test("unparseable file still becomes an item under folder title", ...)          // tv/Weird Folder/clip.mkv → show "Weird Folder", item episode 1 season 1
test("multi-episode file sets episode_end", ...)                                 // S03E01-E02 → one item, episode_number 1, episode_end 2
test("movie with override uses override tmdb id", ...)
test("concurrent scan() calls share one run", ...)
```

- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement `scanner.ts`.**
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(server): incremental library scanner"`

---

### Task 7: Folder watcher

**Files:**
- Create: `server/src/library/watcher.ts`, `server/test/watcher.test.ts`

**Interfaces:**
- Produces: `watchRoot(root: string, onChange: () => void, opts?: { debounceMs?: number /* default 5000 */ }): { close(): Promise<void> }` using chokidar (`ignoreInitial: true`, `awaitWriteFinish: { stabilityThreshold: 2000 }`), firing `onChange` once per quiet period after any add/unlink/change of a video file.

- [ ] **Step 1: Write failing test:** with `debounceMs: 200`, create three files in quick succession under a tmp root, wait 1 s, expect `onChange` called exactly once; `close()` resolves.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit** — `git commit -m "feat(server): media folder watcher"`

---

### Task 8: Image store

**Files:**
- Create: `server/src/metadata/images.ts`, `server/test/images.test.ts`

**Interfaces:**
- Produces: `class ImageStore { constructor(opts: { dir: string; fetchFn: FetchFn }); ensure(size: 'w342' | 'w500' | 'w780' | 'w1280' | 'original', tmdbPath: string): Promise<string /* abs local path */> }` — local file `<dir>/<size>/<basename of tmdbPath>`; downloads from `https://image.tmdb.org/t/p/<size><tmdbPath>` only when missing; concurrent `ensure` for the same key share one download; non-2xx throws `Error("image <status>")`. Also `imageUrl(size, tmdbPath | null): string | null` → `/api/images/<size>/<basename>` (used by routes to build API responses).

- [ ] **Step 1: Write failing tests:** downloads once for two sequential calls; two concurrent calls → one fetch; 404 throws; `imageUrl('w500', '/abc.jpg')` → `/api/images/w500/abc.jpg`, null → null.
- [ ] **Step 2–5:** fail → implement → pass → `git commit -m "feat(server): lazy tmdb image cache"`

---

### Task 9: Playback state, continue watching, history, stats

**Files:**
- Create: `server/src/playback/progress.ts`, `server/src/playback/history.ts`, `server/test/progress.test.ts`, `server/test/history.test.ts`

**Interfaces:**
- Consumes: `Repo` (Task 2) for inserting fixture shows/items.
- Produces (`progress.ts`, all take `db: Database` as first arg, `now` in ms):
  - `reportProgress(db, args: { itemId: number; position: number; duration: number; sessionId?: number | null; now: number }): { sessionId: number; watched: boolean }` — clamps `position` to `[0, duration]` and `duration` to `≥ 0`; `watched = duration > 0 && position / duration >= 0.9` (once set, stays set until `setWatched(false)`); creates a session when `sessionId` missing or not found; adds `clamp(position - last_position, 0, 30)` to `seconds_watched`; sets `last_position`, `ended_at = now`, `playback.last_played_at = now`.
  - `setWatched(db, itemId, watched: boolean, now)`; `setSeasonWatched(db, showId, season, watched, now)`; `setShowWatched(db, showId, watched, now)` — watched sets `position = duration (or 0 if unknown)`; unwatched sets `position = 0`.
  - `setTrackChoice(db, itemId, { subtitle?: string | null; audio?: string | null })`.
  - `getPlayback(db, itemId): PlaybackRow | null`.
  - `continueWatching(db, category: Category | null, limit = 30): { itemId: number; reason: 'resume' | 'next' ; lastPlayedAt: number }[]` — for every live item in progress (position ≥ 30 s, not watched) → `resume`; for every live show whose most recently played item is watched → the next live episode in `(season_number, episode_number)` order that is not watched → `next`, with `lastPlayedAt` of that show; movies never produce `next`; sorted by `lastPlayedAt` desc; one entry per show (resume beats next).
- Produces (`history.ts`):
  - `interface HistoryEntry { sessionId: number; itemId: number; startedAt: number; endedAt: number; secondsWatched: number }`; `listHistory(db, limit = 200): HistoryEntry[]` ordered by `started_at` desc, only sessions with `seconds_watched ≥ 60`.
  - `stats(db, now): { hoursWeek: number; hoursMonth: number; hoursAll: number; episodesAll: number; moviesAll: number; topShowMonth: { showId: number; title: string; hours: number } | null }` — hours from `sessions.seconds_watched` (week = last 7 days, month = last 30 days), episodes/movies = count of `playback.watched = 1` by item type, top show = show with most session seconds in last 30 days.

- [ ] **Step 1: Write failing tests**

`progress.test.ts`: reaching 90 % sets watched; `duration: 0` never sets watched and does not throw; `position > duration` clamps to duration and sets watched; negative position clamps to 0; same session accumulates `seconds_watched` with a 30 s cap per report; `continueWatching` returns `resume` for a half-watched episode, `next` for S1E2 after S1E1 watched, nothing after the last episode is watched, `next` for a show never surfaces when a resume exists for it, movies get `resume` only, hidden (deleted) items excluded, ordered by last played desc.
`history.test.ts`: sessions under 60 s excluded; `stats` hours sum correctly across week/month/all boundaries; `topShowMonth` picks the show with most seconds; null when no sessions.

- [ ] **Step 2–5:** fail → implement → pass → `git commit -m "feat(server): playback progress, continue watching, history, stats"`

---

### Task 10: Subtitles: hash, shift, sidecars, OpenSubtitles client

**Files:**
- Create: `server/src/subtitles/hash.ts`, `server/src/subtitles/shift.ts`, `server/src/subtitles/sidecar.ts`, `server/src/subtitles/opensubtitles.ts`, tests `server/test/subtitles/{hash,shift,sidecar,opensubtitles}.test.ts`

**Interfaces:**
- Produces:
  - `openSubtitlesHash(absPath: string): Promise<string>` — 16 lowercase hex chars: `(filesize + Σ uint64le of first 64 KiB + Σ uint64le of last 64 KiB) mod 2^64`; files smaller than 128 KiB hash whatever exists (still read start and end chunks, which may overlap).
  - `shiftSubtitle(text: string, ms: number, format: 'srt' | 'vtt' | 'ass'): string` — shifts every timestamp, clamps at 0, preserves everything else byte-for-byte (`srt` `HH:MM:SS,mmm`, `vtt` `HH:MM:SS.mmm` or `MM:SS.mmm`, `ass` `H:MM:SS.cc` on `Dialogue:` lines only).
  - `interface SidecarTrack { path: string; label: string; lang: string | null; format: 'srt' | 'vtt' | 'ass' }`; `listSidecars(videoAbsPath: string): Promise<SidecarTrack[]>` — same directory, filename starts with the video basename (without extension), subtitle extension; `lang` from a `.xx.` or `.xxx.` segment before the extension; `label` = remainder after basename (e.g. `en.2.srt` → `English #2`, bare `.srt` → `Sidecar`); `.ssa` reported as `ass`.
  - `nextSidecarName(videoAbsPath: string, existing: string[]): string` → `<base>.en.srt`, `<base>.en.2.srt`, …
  - `saveSidecar(videoAbsPath: string, content: string): Promise<string /* abs path */>`.
  - `class OsClient { constructor(opts: { apiKey: string; username: string; password: string; fetchFn: FetchFn; userAgent?: string /* default "PixelPantry v1" */ }); search(q: { hash?: string; query?: string; season?: number | null; episode?: number | null; tmdbId?: number | null; type: 'episode' | 'movie' }): Promise<OsResult[]>; download(fileId: number): Promise<string /* subtitle text */> }` with `OsResult = { fileId: number; release: string; hashMatch: boolean; downloads: number; language: string; fps: number | null }`, results sorted `hashMatch` desc then `downloads` desc. Base `https://api.opensubtitles.com/api/v1`; headers `Api-Key`, `User-Agent`, `Content-Type: application/json`; `download` first POSTs `/login` (token cached in memory, re-login on 401) then `POST /download { file_id }` → `{ link }` → GET link → text. Throws `Error("OpenSubtitles not configured")` when apiKey or username empty; `Error("OpenSubtitles <status>: <body>")` otherwise.

- [ ] **Step 1: Write failing tests:** hash of a generated 200 KiB file equals a value computed by an independent BigInt loop in the test; shift srt `00:00:01,000 --> 00:00:02,500` by 500 → `00:00:01,500 --> 00:00:03,000`, by −2000 clamps to `00:00:00,000`; ass `Dialogue: 0,0:00:01.00,0:00:02.00,` shifts and `Format:` lines untouched; vtt `MM:SS.mmm` preserved form; sidecar listing from a tmp dir with `Show.mkv`, `Show.srt`, `Show.en.srt`, `Show.en.2.srt`, `Show.ass`, `Other.srt` → 4 tracks with labels/langs as specified; `nextSidecarName` with both `.en.srt` and `.en.2.srt` existing → `.en.3.srt`; OsClient search sends `moviehash`, `query`, `season_number`, `episode_number`, `languages=en`, `type` params and ranks hash matches first; download logs in once for two downloads and re-logs in after a 401.
- [ ] **Step 2–5:** fail → implement → pass → `git commit -m "feat(server): subtitle hash, shift, sidecars, opensubtitles client"`

---

### Task 11: HTTP app and catalog routes

**Files:**
- Create: `server/src/http/deps.ts`, `server/src/http/app.ts`, `server/src/http/routes/catalog.ts`, `server/src/types.ts` (extend), `server/test/http/helpers.ts`, `server/test/http/catalog.test.ts`

**Interfaces:**
- Produces (`deps.ts`): `interface Deps { db: Database; repo: Repo; config: () => Config; saveConfig: (c: Config) => void; scanner: Scanner; tmdb: TmdbClient; os: OsClient; images: ImageStore; log: Logger; trash: (paths: string[]) => Promise<void>; now: () => number; quit: () => void }`.
- `buildApp(deps: Deps): Hono` — registers all route modules; JSON errors `{ error: string }` with 404/400/500; every response has `Cache-Control: no-store` except images and streams.
- `types.ts` additions (API contract, used by phase 2):
```ts
export interface Progress { position: number; duration: number; watched: boolean }
export interface ItemSummary { id: number; type: 'episode' | 'movie'; category: Category; title: string; showId: number | null; showTitle: string | null; season: number | null; episode: number | null; episodeEnd: number | null; year: number | null; runtime: number | null; overview: string | null; poster: string | null; backdrop: string | null; still: string | null; airDate: string | null; progress: Progress | null; addedAt: number }
export interface ShowSummary { id: number; category: Category; title: string; year: number | null; overview: string | null; poster: string | null; backdrop: string | null; rating: number | null; genres: string[]; episodeCount: number; unwatchedCount: number; matched: boolean; addedAt: number }
export type Card = { kind: 'show'; show: ShowSummary; nextUp?: ItemSummary } | { kind: 'item'; item: ItemSummary; reason?: 'resume' | 'next' }
export interface Row { key: 'continue' | 'recent' | 'all'; title: string; cards: Card[] }
export interface CategoryPage { category: Category | 'home'; rows: Row[] }
export interface SeasonDetail { number: number; name: string; poster: string | null; episodes: ItemSummary[] }
export interface ShowDetail { show: ShowSummary; seasons: SeasonDetail[]; resume: ItemSummary | null }
export interface ItemDetail { item: ItemSummary; streamUrl: string; fileName: string; fileSize: number; audio: Track[]; subtitles: SubtitleTrack[]; next: ItemSummary | null; prev: ItemSummary | null; subtitleChoice: string | null; audioChoice: string | null }
export interface SubtitleTrack { id: string /* "embedded:3" | "sidecar:<n>" */; label: string; lang: string | null; url: string | null /* null for embedded */; format: 'srt' | 'vtt' | 'ass' | 'embedded' }
export interface Settings { skipBackSeconds: number; skipForwardSeconds: number; autoplayNext: boolean }
```
- Routes (`catalog.ts`):
  - `GET /api/home` → `CategoryPage` with rows `continue` (all categories, items with `reason`) and `recent` (shows and movies across categories by `addedAt` desc, 30).
  - `GET /api/categories/:category` → rows `continue`, `recent`, `all` (shows A–Z for tv/anime; movie items A–Z for movies). 404 for unknown category.
  - `GET /api/shows/:id` → `ShowDetail` (seasons ascending, season 0 last and named "Specials"; `resume` = continue-watching entry for this show or first unwatched episode).
  - `GET /api/items/:id` → `ItemDetail` (`streamUrl` `/api/items/:id/stream`, `next`/`prev` by season/episode order within the show, null for movies; file = newest live file for the item).
  - `GET /api/search?q=` → `{ shows: ShowSummary[]; movies: ItemSummary[] }`, empty `q` → empty lists.
  - Image fields are built with `images.imageUrl`; `poster` for episodes is the show poster, `still` the episode still.
- `test/http/helpers.ts`: `makeDeps(overrides?)` — in-memory db, fake tmdb/os/trash, temp dirs; `seedShow(repo, { title, category, seasons: number[][] /* episode numbers per season */ })` and `seedMovie(repo, title)` helpers returning ids. Requests via `app.request(path, init)`.

- [ ] **Step 1: Write failing tests:** home continue row ordering and `reason`; category `all` sorted A–Z, hidden shows excluded; unknown category 404; show detail seasons order with Specials last and `resume`; item detail `next`/`prev` across a season boundary (S1E12 → S2E1) and null at the end; search case-insensitive; `Cache-Control: no-store` present.
- [ ] **Step 2–5:** fail → implement → pass → `git commit -m "feat(server): http app and catalog routes"`

---

### Task 12: Streaming and image routes

**Files:**
- Create: `server/src/http/routes/stream.ts`, `server/test/http/stream.test.ts`

**Interfaces:**
- Routes:
  - `GET /api/items/:id/stream` — serves the newest live file: `Accept-Ranges: bytes`, `Content-Type` by extension (`video/x-matroska`, `video/mp4`, `video/webm`, else `application/octet-stream`), full 200 without `Range`; with `Range: bytes=a-b` → 206, `Content-Range: bytes a-b/size`, `Content-Length`, body `Bun.file(path).slice(a, b+1)`; open end clamps to `size-1`; end past EOF clamps; `a ≥ size` → 416 with `Content-Range: bytes */size`; `HEAD` supported; 404 when item or file missing on disk.
  - `GET /api/images/:size/:name` — `images.ensure(size, '/' + name)` then serve file with `Cache-Control: public, max-age=31536000`; 404 on failure; `size` must be one of the allowed sizes else 400.

- [ ] **Step 1: Write failing tests** (temp file of 10 000 bytes with known content): no range → 200, length 10000; `bytes=0-999` → 206, `Content-Range: bytes 0-999/10000`, body equals bytes 0..999; `bytes=1000-` → 206, `bytes 1000-9999/10000`; `bytes=0-999999999` → 206 clamped to `0-9999`; `bytes=999999999-` → 416; HEAD returns headers, empty body; missing file → 404; image route serves cached file with long cache header, bad size → 400.
- [ ] **Step 2–5:** fail → implement → pass → `git commit -m "feat(server): range streaming and image routes"`

---

### Task 13: Playback, subtitle, scan, settings, delete routes

**Files:**
- Create: `server/src/http/routes/playback.ts`, `server/src/http/routes/subtitles.ts`, `server/src/http/routes/scan.ts`, `server/src/fs/trash.ts`, tests `server/test/http/{playback,subtitles,scan}.test.ts`, `server/test/trash.test.ts`

**Interfaces:**
- `trash.ts`: `trashPaths(paths: string[]): Promise<void>` — `win32`: one PowerShell invocation `Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile/DeleteDirectory(<path>, 'OnlyErrorDialogs', 'SendToRecycleBin')` per path; `darwin`: `osascript -e 'tell application "Finder" to delete POSIX file "<path>"'`; other → `Error("trash not supported on <platform>")`. Rejects on non-zero exit. (Test only covers argument building via an injected `run(cmd: string[]) => Promise<void>`; the real spawn is a thin wrapper.)
- Routes (`playback.ts`):
  - `POST /api/items/:id/progress` body `{ position, duration, sessionId? }` → `{ sessionId, watched }` (Task 9 `reportProgress`); 400 when body lacks numbers.
  - `POST /api/items/:id/watched` body `{ watched: boolean }`; `POST /api/shows/:id/watched` body `{ watched, season? }` (season omitted → whole show).
  - `POST /api/items/:id/tracks` body `{ subtitle?: string | null; audio?: string | null }`.
  - `GET /api/history` → `{ entries: (HistoryEntry & { item: ItemSummary })[] }`; `GET /api/stats` → stats object from Task 9.
  - `GET /api/settings` / `PUT /api/settings` (partial body merged, validated: skip seconds integers 1–300) → `Settings`, stored in `settings` table as JSON under key `client`.
  - `DELETE /api/shows/:id`, `DELETE /api/movies/:id` → collects abs paths of live files (plus their sidecar subtitle files), and for a show the show folder itself when every video file inside it belongs to that show; calls `deps.trash`; then `scanner.scan()` (not awaited); returns `{ trashed: string[] }`. 404 if not found.
- Routes (`subtitles.ts`):
  - `GET /api/items/:id/subtitles` → `{ tracks: SubtitleTrack[] }` — embedded from `files.subtitle_tracks` (`id: "embedded:<index>"`), then sidecars (`id: "sidecar:<n>"` with `url: /api/items/:id/subtitles/sidecar/<n>`).
  - `GET /api/items/:id/subtitles/sidecar/:n?shift=<ms>` → text with `Content-Type` `application/x-subrip` / `text/vtt` / `text/x-ssa`; shifted via `shiftSubtitle` when `shift` ≠ 0.
  - `GET /api/items/:id/subtitles/search` → `{ results: OsResult[] }` using hash of the file + show title/season/episode or movie title + tmdbId; 503 with `{ error }` when OpenSubtitles not configured or upstream fails.
  - `POST /api/items/:id/subtitles/download` body `{ fileId }` → downloads, `saveSidecar`, returns the new `SubtitleTrack`.
- Routes (`scan.ts`): `POST /api/scan` → `202 { status }` (starts scan if idle); `GET /api/scan/status` → `ScanStatus`.

- [ ] **Step 1: Write failing tests:** progress happy path and 400; watched toggles reflected in `GET /api/items/:id`; season watched marks only that season; settings default/merge/validation (`skipForwardSeconds: 0` → 400); delete show calls `trash` with the show folder and returns 404 after rescan hides it; subtitles list order embedded→sidecar; sidecar served shifted when `?shift=500`; search returns 503 when not configured; download creates `<base>.en.srt` and the list then includes it; scan POST returns 202 and status flips to `scanning` then `idle`; `trash.test.ts` builds the win32 PowerShell command string and darwin osascript for a sample path with a quote in it.
- [ ] **Step 2–5:** fail → implement → pass → `git commit -m "feat(server): playback, subtitle, scan, settings and delete routes"`

---

### Task 14: Admin page and admin routes

**Files:**
- Create: `server/src/http/routes/admin.ts`, `server/src/http/admin/index.html`, `server/test/http/admin.test.ts`

**Interfaces:**
- Routes (all under `/admin`; a middleware returns 403 `{ error: "admin is localhost only" }` unless the remote address is loopback. Remote address resolution: `c.env?.remoteAddr` when present (tests pass it as the third argument of `app.request(path, init, { remoteAddr: "127.0.0.1" })`), otherwise `server.requestIP(c.req.raw)?.address` from the Bun server passed in `Bun.serve`'s fetch as the second argument and forwarded as env. Never trust `X-Forwarded-For`):
  - `GET /admin` → `index.html`; `GET /` → 302 to `/admin`.
  - `GET /admin/api/status` → `{ version, port, lanAddresses: string[] /* non-internal IPv4 */, mediaRoot, scan: ScanStatus, counts: { tv: number; anime: number; movies: number }, lastScanError, tmdbConfigured: boolean, opensubtitlesConfigured: boolean }`.
  - `GET /admin/api/config` → `Config` with secrets masked (`"••••"` when set); `PUT /admin/api/config` → merges non-masked fields, `saveConfig`, if `mediaRoot` changed triggers scan; returns `{ restartRequired: boolean }` (true when port changed).
  - `GET /admin/api/unmatched` → `{ shows: { id, parsedTitle, year, category, error, fileCount }[], movies: { id, parsedTitle, year, error }[] }` (tmdb_id null, not hidden).
  - `GET /admin/api/tmdb/search?type=tv|movie&q=&year=` → `TmdbSearchResult[]`.
  - `POST /admin/api/match` body `{ type: 'show' | 'movie'; id; tmdbId }` → sets override for the entity's `parsed_key`, clears `tmdb_id`/`match_attempted_at`, triggers scan → `202`.
  - `POST /admin/api/scan` → same as `/api/scan`. `GET /admin/api/log?n=200` → `{ lines: string[] }`. `POST /admin/api/quit` → `{ ok: true }` then `deps.quit()`.
- `index.html`: single page, vanilla JS, dark theme, sections in this order: Status (with LAN address shown as `http://<ip>:<port>`), Settings form (media root, port, TMDB key, OpenSubtitles key/username/password, Save button, "Restart required" note when returned), Unmatched (each row has a search box prefilled with the parsed title, results list with "Use this" buttons), Log (auto-refresh every 5 s, `<pre>`). Polls `/admin/api/status` every 3 s while a scan runs. Buttons: "Rescan now", "Quit server".

- [ ] **Step 1: Write failing tests:** non-loopback → 403; status fields present with counts from seeded data; config GET masks secrets and PUT with masked value keeps the old secret; PUT with new mediaRoot triggers `scanner.scan` (spy); unmatched lists a seeded show with `tmdb_id` null; match sets override and clears `tmdb_id`; `GET /` redirects to `/admin`; `GET /admin` serves HTML containing `Pixel Pantry`.
- [ ] **Step 2–5:** fail → implement → pass → `git commit -m "feat(server): admin page and routes"`

---

### Task 15: Discovery, tray, entry point

**Files:**
- Create: `server/src/discovery.ts`, `server/src/tray.ts`, `server/src/index.ts`, `server/test/discovery.test.ts`, `server/test/tray.test.ts`

**Interfaces:**
- `discovery.ts`: `advertise(port: number, log: Logger): { stop(): Promise<void> }` — `bonjour-service` publish `{ name: "Pixel Pantry", type: "pixelpantry", port, txt: { version: "1" } }`; errors are logged, never thrown.
- `tray.ts`: `startTray(port: number, log: Logger): { stop(): void } | null` — on `win32` writes the embedded PowerShell script (`TRAY_SCRIPT` constant) to `<dataDir>/tray.ps1` and spawns `powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File tray.ps1 -Port <port>`; other platforms return null. The script builds a `System.Windows.Forms.NotifyIcon` with menu items: **Open admin page** (`Start-Process http://localhost:<port>/admin`), **Rescan now** (`Invoke-RestMethod -Method Post http://localhost:<port>/admin/api/scan`), **Open log folder** (`Start-Process <dataDir>\logs`), **Quit** (`Invoke-RestMethod -Method Post .../admin/api/quit`, then exit). Tooltip `Pixel Pantry — running on port <port>`.
- `index.ts` startup order: `dataDir()` mkdir → `Logger` → `loadConfig` → `openDb` → `Repo` → `TmdbClient` (cache backed by `repo.cacheGet/cacheSet`) → `OsClient` → `ImageStore` → `Scanner` (probe bound to `resolveFfprobePath()`) → `buildApp` → `Bun.serve({ port, hostname: "0.0.0.0", fetch })` → `advertise` → `watchRoot(mediaRoot, () => scanner.scan())` (re-created when config mediaRoot changes) → `startTray` → `scanner.scan()`. `quit` stops watcher, discovery, tray, server, then `process.exit(0)`. Log the admin URL and LAN URLs on startup. If the port is busy, log `Port <n> already in use (is Pixel Pantry already running?)` and exit 1.

- [ ] **Step 1: Write failing tests:** `discovery.test.ts` — `advertise` with an injected bonjour factory publishes with type `pixelpantry` and port; `tray.test.ts` — `TRAY_SCRIPT` contains the four menu labels and `/admin/api/quit`; `startTray` returns null on non-win32.
- [ ] **Step 2: Run, verify fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass; then smoke-run** `PANTRY_DATA_DIR=/tmp/pantry bun run src/index.ts` with a `config.json` pointing at a small test folder: expect log lines for admin URL, a completed scan, and `curl localhost:8765/api/home` returning JSON.
- [ ] **Step 5: Commit** — `git commit -m "feat(server): discovery, tray, entry point"`

---

### Task 16: Windows packaging and run instructions

**Files:**
- Create: `server/scripts/package-win.sh`, `server/README.md`

**Interfaces:**
- `package-win.sh`: `bun build --compile --target=bun-windows-x64 --windows-hide-console --minify ./src/index.ts --outfile dist/win/PixelPantry.exe`, then copies `node_modules/ffprobe-static/bin/win32/x64/ffprobe.exe` to `dist/win/ffprobe.exe`, and writes `dist/win/README.txt` (first-run steps). Exits non-zero if either file is missing afterwards.
- `README.md`: dev commands (`bun install`, `bun test`, `bun run dev`), data dir layout, first-run steps on Windows: double-click `PixelPantry.exe`, open `http://localhost:8765/admin`, set media folder and TMDB key (where to get one), optional OpenSubtitles account, allow through Windows Firewall when prompted (private network), note that the TV app finds the server automatically.

- [ ] **Step 1: Write the script and README.**
- [ ] **Step 2: Run `bun run package:win` on this Mac** — expect `dist/win/PixelPantry.exe` and `dist/win/ffprobe.exe` to exist (`ls -la dist/win`). Cross-compiling produces the exe; running it is verified on the Windows PC later (manual step, documented in README).
- [ ] **Step 3: Commit** — `git commit -m "build(server): windows packaging script and readme"`

---

## Self-review notes

- Spec coverage: scan/watch/rescan (6, 7, 13), parsing rules incl. bare season folders and anime (3, 5, 6), unmatched + manual match (14), artwork (8, 12), embedded track metadata (4, 11), range streaming (12), playback state + continue watching + mark watched (9, 13), sessions/history/stats (9, 13), subtitles search/download/sidecar/shift (10, 13), delete to Recycle Bin (13), settings on server (13), admin page/tray/discovery/log (14, 15), packaging (16). Phase-2 API contract lives in `types.ts` (11).
- Not in this plan on purpose: the TV app (separate plan), transcoding, auth.
- Known manual verification left for the Windows PC: tray icon, Recycle Bin behaviour, firewall prompt, mDNS visibility from the TV. The README lists them.
