# Pixel Pantry server

Home media server (Bun + TypeScript). Scans a media folder, matches titles via TMDB, serves the library and streams files over HTTP, advertises itself on the LAN (mDNS), and shows a tray icon on Windows.

## Development

```
bun install
bun run dev          # runs src/index.ts, data in server/data/
bun test             # full test suite
bunx tsc --noEmit    # type check
```

Layout: `src/` (server code: `http/`, `library/`, `metadata/`, `subtitles/`, `playback/`, `db/`, `fs/`), `src/http/admin/index.html` (admin page, embedded in the exe), `test/`, `scripts/` (packaging).

## Data directory

`data/` next to the exe (or `server/data/` in dev). Override with `PANTRY_DATA_DIR`.

- database, settings and `images/` (cached artwork)
- `logs/server.log`

`FFPROBE_PATH` overrides the ffprobe location; the exe looks for `ffprobe.exe` beside itself.

## Packaging for Windows

```
bun run package:win
```

Cross-compiles on any OS and writes `dist/win/PixelPantry.exe`, `dist/win/ffprobe.exe` and `dist/win/README.txt` (source: `scripts/README.win.txt`). Copy the `dist/win` folder to the Windows PC. The script fails if any of the three files is missing.

Note: `--windows-hide-console` is only available when building on Windows, so the cross-compiled exe keeps a console window open next to the tray icon.

## First run on Windows

1. Double-click `PixelPantry.exe` (SmartScreen: "More info" then "Run anyway").
2. Allow it through Windows Firewall on **private** networks when prompted.
3. Open `http://localhost:8765/admin`.
4. Set the media folder and a TMDB key (free account at themoviedb.org, Settings, API; paste the "API Read Access Token" or v3 key).
5. Optional: OpenSubtitles account (opensubtitles.com, API consumer key plus username and password).
6. Click "Rescan now". The TV app finds the server automatically.

Update by replacing the exe only; `data\` stays.

## Manual verification on Windows

- [ ] Tray icon appears; menu has "Open admin page", "Rescan now", "Open log folder", "Quit" and each works
- [ ] Deleting a media file from the app sends it to the Recycle Bin
- [ ] Firewall prompt appears on first run; allowing private networks lets the TV connect
- [ ] The server is visible via mDNS from the TV app (no manual address needed)
- [ ] `data\logs\server.log` is written; Quit stops the process
