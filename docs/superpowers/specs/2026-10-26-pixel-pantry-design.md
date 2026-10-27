# Pixel Pantry — design

Date: 2026-10-26
Status: draft, awaiting owner approval

## Intent

A personal, LAN-only replacement for Plex/Jellyfin. One Windows PC holds a media
folder; one Sony Bravia (Google TV / Android TV) plays it through a native app
that looks and feels like Netflix/Plex. Nothing is exposed outside the home
network, there is one user, and there is no transcoding.

The owner is replacing Plex because of specific, concrete annoyances. Those
annoyances are the product requirements; anything Plex does that is not listed
here is not required.

### Why Plex is being replaced (each item is a requirement)

1. Skip forward is 30 s but skip back is 10 s. Both must default to 10 s and be
   configurable independently.
2. No fast way to jump to the next episode. CH+ / CH− on the remote must go to
   next / previous episode instantly, and the player must have an on-screen
   "Next episode" button.
3. Plex hangs and needs its cache cleared. The system must be simple enough
   that it does not get into broken states; when something does go wrong it
   must be visible and diagnosable from the Windows admin page.
4. New files take a long time to appear. The server must watch the folder and
   pick up changes on its own, and both the TV app and the admin page must
   have a "Rescan" button that triggers an immediate scan.
5. Finding subtitles is trial and error. Subtitle search must be one action
   from the player, results must be ranked so the first one usually fits, and
   switching to another result must not interrupt playback. A subtitle delay
   adjustment must exist so slightly-off subtitles can be fixed in place.

## Goals

- Watch files from the PC on the TV with a Netflix-like UI.
- Series and anime grouped into show → season → episode with artwork and
  descriptions from TMDB.
- Remember playback position and watched state across sessions (server side).
- Zero-friction start: double-click the server on Windows, open the app on TV.
- English UI throughout.

## Non-goals (not in phase 1, maybe later)

- Browser UI for watching on the PC (phase 2, see below).
- Transcoding (possible phase 3, only if the browser UI needs it).
- Photos.

## Anti-goals (would make this a failure even if it works)

- Remote/internet access, user accounts, sharing, or anything that needs a
  login. LAN only, one user, no auth beyond "same network".
- A UI that requires typing on the TV remote for everyday use. Subtitle search
  and metadata matching must work without typing.
- Background complexity that can wedge: long-running transcoders, large
  caches that need clearing, or state that lives only in the TV app.

## Phases

- **Phase 1 (this document):** Windows server + Android TV app.
- **Phase 2:** Browser UI (React) on the same server API. Plays what Chrome can
  play natively and says so when it cannot. Shares progress with the TV
  automatically because progress is server side.
- **Phase 3 (undecided):** on-demand transcoding for the browser, only if it
  becomes necessary.

Phase 1 must not design itself into a corner for phase 2: the server API is the
contract, and the TV app is a thin client of it.

## Constraints and environment

- Server host: Windows PC, on whenever the TV is used (same as today with Plex).
- TV: Sony Bravia running Google TV (Android TV). App is installed once via ADB
  sideload; no Play Store publishing.
- Library folders (owner's existing Plex library, not to be renamed). Each
  folder the owner adds in the admin page is scanned as one type: TV shows,
  Anime or Movies (change of 2026-10-27: originally one media root with fixed
  `tv`, `anime` and `movies` subfolders; the root also holds unrelated files
  and downloads in progress, and extra folders such as `October movies` must
  be addable). Several folders may share a type; their shows merge. Nothing
  outside the listed folders is read. Typical contents:
  - `tv/` — series. Usually `Show Name/Season 01/Show Name S01E01.mkv`, but
    season folders of different shows may sit directly under `tv/` and
    episode naming varies (`S01E05`, `1x05`, `Episode 5`, …).
  - `anime/` — series, loosely named, often `[Group] Show Name - 05 [1080p].mkv`
    with absolute episode numbers and no season folders.
  - `movies/` — `Movie Name (2019).mkv` or `Movie Name (2019)/Movie Name.mkv`.
  - `photos/` — ignored.
- The owner does not curate these folders: whatever was downloaded gets
  dropped into `tv/`, `anime/` or `movies/` as-is (a show folder, a bare
  season folder, a single file, a release folder with extras and samples).
  The parser must be lenient and the result must never be "the file is not
  shown anywhere". No listing of the real library is available; the builder
  writes parser fixtures from the common release naming conventions.
- Sidecar subtitles (`.srt`, `.ass`, `.vtt`) next to video files; many files
  also have embedded subtitle tracks.
- No import of watched history from Plex. Everything starts unwatched.
- ASS/SSA subtitles are rendered as plain text by ExoPlayer (no fonts,
  positioning or typesetting). Owner accepts this for phase 1.
- Subtitle language of interest: English.
- External services: TMDB (metadata, artwork) and OpenSubtitles (subtitle
  search/download). Both need API keys that the owner enters in the admin page.
  OpenSubtitles free tier has a daily download limit; that is acceptable.

## Technology (my calls, owner delegated these)

- **Server:** TypeScript on Node, SQLite for the catalog and playback state,
  packaged into a single Windows `.exe` with a system-tray icon. Chosen so that
  the phase 2 React browser UI can share types with the server. Builder picks
  the HTTP framework, SQLite binding and packaging tool.
- **TV app:** Kotlin, Android TV (Leanback or Compose for TV, builder's choice),
  Media3/ExoPlayer for playback. Direct play only; the Bravia decodes
  HEVC/H.264, AC3/DTS/AAC natively.
- **Discovery:** the TV app finds the server on the LAN automatically (mDNS or
  UDP broadcast, builder's choice). Manual IP:port entry exists as a fallback in
  the TV app settings.
- **Naming:** project and app name is "Pixel Pantry" (from the project folder).
  Owner has not confirmed the name; it is trivial to change.

## The server

A single process that does five things.

### 1. Library scan

- Scans the configured library folders, each as its type (TV shows, Anime or
  Movies). A folder that is missing right now (unplugged drive) keeps its
  files; a folder removed from the settings drops them at the next scan.
  A pre-2026-10-27 config's media root migrates to one library per existing
  `tv`, `anime`, `movies` subfolder.
- Watches the folder for changes (filesystem watcher) and debounces into an
  incremental scan. Also runs a scan on startup, and on demand from the
  TV app or admin page ("Rescan"). **My call:** every scan is incremental:
  a file is re-probed and re-matched only when its path, size or mtime
  changed; files that disappeared are soft-deleted (playback state kept) so a
  temporarily unplugged drive does not wipe history. Shows, seasons and
  movies with no remaining files disappear from every list in the TV app
  (confirmed by the owner); they come back if the files do.
- Identifies video files by extension (mkv, mp4, avi, m4v, webm, ts, …).
- Parses show name, season, episode (or absolute episode) and year from folder
  and file names. The parser must handle the formats listed under Constraints.
  Anime with absolute numbering is mapped to TMDB seasons/episodes using TMDB's
  episode lists (sum of episodes per season); when that fails, episodes stay
  under a single "Season 1" rather than disappearing.
- Groups files into show → season → episode. Season folders sitting directly
  under `tv/` are attached to the show named in the folder or file name.
- Matches shows and movies against TMDB by name and year. Caches TMDB
  responses and artwork locally so a rescan does not re-download everything.
- Items that cannot be matched still appear in the TV app, with the parsed
  name and no artwork, and are listed on the admin page under "Unmatched" where
  the owner can search TMDB and pick the right match by hand. A manual match is
  remembered across rescans.
- Scan progress and the last scan time are visible on the admin page and
  exposed through the API.
- A scan never blocks playback or the API.

### 2. Metadata and artwork

- TMDB for shows, seasons, episodes, movies: titles, overviews, air dates,
  ratings, genres, posters, backdrops, episode stills.
- Artwork is downloaded once and served from the server to the TV; the TV never
  talks to TMDB directly.
- Embedded metadata in the files (duration, audio and subtitle tracks, codecs)
  is read by the server (ffprobe is acceptable as a bundled tool) so the TV app
  can show track names before playback starts.

### 3. Serving media

- Video files are served over HTTP with range requests, as-is.
- Sidecar and downloaded subtitle files are served as separate URLs.
- Embedded subtitle tracks are selected inside ExoPlayer; the server does not
  extract them (ExoPlayer handles SRT/ASS/PGS tracks in MKV).

### 4. Playback state

- Per episode/movie: position in seconds, duration, watched flag, last played
  timestamp. Single global user.
- Watched is set automatically when position passes ~90 % of duration; the
  builder may choose the exact threshold.
- The TV app reports position periodically during playback (every few seconds)
  and on pause/stop. If the TV app is killed, the last report stands.
- "Continue Watching" is derived server side: half-watched items plus
  "next up" (the episode after the most recently watched one of each show,
  if it exists and is unwatched). Movies only have resume, never "next up".
- "Mark watched / unwatched" exists for an episode, a whole season, and a
  whole show.
- Every playback session is logged (item, start time, end time, seconds
  actually watched) so a history can be shown. Logging a session does not
  depend on the watched flag.

### 5. Subtitles

- Search on OpenSubtitles by file hash first, then by show/movie name + season
  + episode, English only (language configurable later if ever needed).
- Results are returned ranked: hash matches first, then by OpenSubtitles'
  own download count / trust signals. Each result shows release name and
  whether it is a hash match.
- Download saves the file next to the video (`<video name>.en.srt`, with a
  numeric suffix if one already exists) so it is picked up as a sidecar on the
  next scan and is available forever after.
- The API exposes: list tracks for an item (embedded + sidecar), search online,
  download result N.

### Admin page (served by the server at `http://localhost:<port>`)

- Status: server running, LAN address and port the TV should see, last scan
  time, scan in progress + progress, number of items per category.
- Settings: library folders (path + type, add/remove), port, TMDB API key,
  OpenSubtitles credentials, "Start with Windows" (default on: the server
  registers itself in the user's Run key at every start, so the owner never
  has to set up autostart; unticking removes the entry). Saved to a config
  file next to the exe.
- Unmatched: list of unmatched shows/movies with a TMDB search box and
  "use this" per result.
- Log: last N lines inline; full log in a file; "Open log folder" in the tray
  menu.
- Tray menu: Open admin page, Rescan now, Open log folder, Quit.
- No auth on the admin page; it binds to localhost only. The media API binds
  to all interfaces.

### API shape (for phase 2 compatibility)

JSON over HTTP. Rough resource list; the builder designs the details:

- `GET /api/home`, `GET /api/categories/{tv|anime|movies}` → rows (continue
  watching, recently added, all) of item summaries.
- `GET /api/shows/{id}`, `/seasons/{id}`, `/episodes/{id}`, `/movies/{id}`.
- `GET /api/items/{id}/stream` (range requests), `/subtitles`, `/subtitles/search`,
  `POST /api/items/{id}/subtitles/download`.
- `POST /api/items/{id}/progress`, `POST /api/items/{id}/watched`.
- `DELETE /api/shows/{id}`, `DELETE /api/movies/{id}` → move the files to
  the Windows Recycle Bin and remove the item from the catalog.
- `GET /api/history`, `GET /api/stats`.
- `POST /api/scan`, `GET /api/scan/status`.
- `GET /api/search?q=`.
- `GET /api/images/{id}`.

## The TV app

### Navigation and home

- Left sidebar, Plex-style: Home, TV, Anime, Movies, Search; at the bottom
  Rescan and Settings. Collapsed to icons whenever focus is in the content
  area; expands with labels when focus moves onto it (press LEFT from the
  first column). Selecting an entry moves focus back into the content.
- Content area per category: a "hero" strip at the top showing the focused
  item's artwork, title, episode info and a Resume/Play button; below it rows:
  **Continue Watching**, **Recently Added**, then **All (A–Z)**. **My call:**
  "All" is a poster grid (several rows, scrolls vertically, sorted A–Z) rather
  than a single horizontal row, so it stays usable at hundreds of titles. The
  mockup shows it as a row; the grid supersedes that.
- Home shows Continue Watching and Recently Added across all three categories.
- OK on a Continue Watching card plays immediately. OK on any other card opens
  the show/movie page.
- Long-press OK (or the menu key) on a show or movie card, and a "⋯" button on
  the show/movie page, opens a menu: Mark watched / unwatched, Delete from
  PC. Delete asks for confirmation naming the show and the number of files,
  then the server moves the files to the Windows Recycle Bin and the show
  vanishes from the app. **My call:** Recycle Bin rather than permanent
  deletion, so one wrong click on the remote cannot destroy a season; the
  owner empties the bin on the PC when they want the space back.
- Rescan in the sidebar triggers a server scan and shows a brief toast; rows
  refresh when the scan finishes.
- Search: on-screen keyboard, searches titles in the local catalog only.

### Show page

- Backdrop, poster, title, year, season/episode counts, rating, genres,
  overview.
- Primary button: "Resume S2 E4 · 23 min left" (or "Play S1 E1" when nothing
  is started). Secondary: "Mark season watched / unwatched".
- Season tabs (LEFT/RIGHT), then a horizontal row of episodes with still,
  number, title, runtime, watched checkmark or progress bar.
- OK plays an episode. Long-press OK (or the menu key) on an episode: Mark
  watched / unwatched, Play from start.
- Movie page: same minus seasons and episodes.

### Player

- Media3/ExoPlayer, direct play.
- Controls overlay appears on any key press and hides after ~4 s: title line
  (show · SxEy · episode name), "Ends at HH:MM", seek bar with elapsed and
  remaining, buttons Prev / Pause / Next episode, and on the right Audio,
  Subtitles, Sub delay.
- Remote mapping:
  - OK / Play-Pause: toggle pause, show controls.
  - LEFT / RIGHT: skip back / forward. Default 10 s each; two independent
    settings. Holding repeats.
  - UP / DOWN: show controls, move focus between buttons.
  - CH+ / CH−: next / previous episode immediately (saves position of the
    current one first). A toast confirms which episode started.
  - BACK: hide controls if visible, otherwise stop, save position, return to
    the show page.
- End of episode: auto-play next episode after a countdown toast ("Next
  episode in 10 s… BACK to cancel"). Toggle in Settings.
- Subtitles menu: embedded tracks, sidecar files, Off, then "Search
  online…". Search results appear in the same menu; choosing one downloads
  and applies it without stopping playback. Switching again is the same path.
- Sub delay: adjust in ±0.5 s steps (builder may choose finer steps);
  shown on the button; resets per item. **My call:** delay applies to
  sidecar and downloaded subtitle files only (the server or app re-serves the
  file with shifted timestamps). Embedded tracks ship with the video and are
  in sync by definition, so they get no delay control; this avoids custom
  Media3 subtitle-renderer plumbing.
- Audio menu: embedded audio tracks.
- The last chosen subtitle/audio track per item is remembered on the server
  so resuming later restores it.

### History page

- Sidebar entry "History" between Search and the bottom group.
- Top: a few stat tiles. **My call** on which: hours watched this week, this
  month, all time; episodes and movies watched all time; most-watched show
  this month. Builder may pick the exact set, keep it to one row.
- Below: a reverse-chronological list of playback sessions grouped by day:
  thumbnail, show · SxEy · title (or movie title), how much of it was watched,
  time of day. OK on an entry opens the show/movie page.

### Settings (TV app)

- Skip back seconds, skip forward seconds (default 10 / 10).
- Auto-play next episode (default on).
- Server address: auto-discovered, with manual override.
- Settings live on the server so the phase 2 browser UI can share them
  (skip lengths apply there too); the TV app caches them.

### Robustness

- The TV app keeps no state of its own besides the server address and a cache
  of images. Clearing it loses nothing.
- If the server is unreachable, the app shows a clear message with the address
  it tried and a Retry button, never a blank screen or a spinner that never
  ends.
- Playback errors show the ExoPlayer error text in plain words plus the file
  name, so the owner can look at the file on the PC.

## Left to the builder

- Exact HTTP framework, SQLite library, packaging and tray-icon library on
  Windows; project layout for server and TV app (monorepo vs two repos).
- Leanback vs Compose for TV.
- Discovery protocol choice.
- Filename parser implementation (a well-known library is fine).
- Watched threshold, progress report interval, image sizes and caching.
- Visual design details (colors, type) within the dark, Plex/Netflix-like
  direction shown in the brainstorm mockups
  (`.superpowers/brainstorm/*/content/home-layout-v2.html`,
  `show-and-player.html`).
- Logging format and rotation.

## Decisions made during the builder check

Owner answered: no Plex import; plain-text ASS is fine for phase 1; no file
listing available, design for an uncurated drop folder; shows with no files
vanish from lists; add a history/stats page; add long-press delete of a show
including its files.

My calls (owner can override by editing this file):

- Subtitle delay works on sidecar and downloaded files only, not embedded
  tracks.
- "All (A–Z)" is a vertically scrolling poster grid, not a single row.
- Scans are always incremental; disappeared files are soft-deleted and
  hidden rather than purged, so history survives an unplugged drive.
- "Delete from PC" moves files to the Recycle Bin instead of deleting them
  permanently.
- History page stat tiles: hours this week / month / all time, episodes and
  movies all time, top show this month.
