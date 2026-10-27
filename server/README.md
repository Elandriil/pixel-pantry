# Pixel Pantry server

Home media server (Bun + TypeScript). Scans the configured library folders, matches titles via TMDB, serves the library and streams files over HTTP, advertises itself on the LAN (mDNS), and shows a tray icon on Windows.

## Development

```
bun install
bun run dev          # runs src/index.ts, data in server/data/
bun test             # full test suite
bunx tsc --noEmit    # type check
```

Layout: `src/` (server code: `http/`, `library/`, `metadata/`, `subtitles/`, `playback/`, `db/`, `fs/`), `src/http/admin/index.html` (admin page, embedded in the exe), `test/`, `scripts/` (packaging).

Notes for the TV client (progress reporting, URLs, subtitle ids, address check): `docs/tv-client-notes.md`.

## Data directory

`data/` next to the exe (or `server/data/` in dev). Override with `PANTRY_DATA_DIR`.

- database, settings and `images/` (cached artwork)
- `logs/server.log`

`FFPROBE_PATH` overrides the ffprobe location; the exe looks for `ffprobe.exe` beside itself.

## Packaging for Windows

```
bun run package:win
```

Cross-compiles on any OS and writes `dist/win/PixelPantry.exe`, `dist/win/ffprobe.exe`, `dist/win/Start Pixel Pantry.vbs` and `dist/win/README.txt` (source: `scripts/README.win.txt`). Copy the `dist/win` folder to the Windows PC. The script fails if any of the four files is missing.

Note: `--windows-hide-console` is only available when building on Windows, so the cross-compiled exe shows a console window when started directly. `Start Pixel Pantry.vbs` launches it hidden; building on Windows itself with `--windows-hide-console` removes the console entirely.

## Getting the build onto the Windows PC

`dist/` is git-ignored (the exe alone is 116 MB, over GitHub's 100 MB file limit). `bun run publish:dist` builds, zips `dist/win` (about 60 MB) and force-pushes it as the only commit of the `dist` branch, so rebuilds never grow the repo. On the Windows PC:

```
git clone --branch dist --depth 1 https://github.com/Elandriil/pixel-pantry.git pixel-pantry-dist
```

then unzip `PixelPantry-win.zip`. To update later: `git pull` in that folder (the branch is rewritten, so use `git fetch origin dist && git reset --hard origin/dist` if pull complains), unzip again and replace `PixelPantry.exe` only.

## First run on Windows

1. Double-click `Start Pixel Pantry.vbs` (hides the console window; double-clicking `PixelPantry.exe` directly also works but shows a console window). SmartScreen: "More info" then "Run anyway". From then on it starts with Windows by itself ("Start with Windows" in the admin settings, on by default: a `Pixel Pantry` value in `HKCU\...\Run` pointing at the .vbs, refreshed at every start, so moving the folder is fine).
2. Allow it through Windows Firewall on **private** networks when prompted.
3. Open `http://localhost:8765/admin`.
4. Add the library folders (each with its type: TV shows, Anime or Movies; e.g. `D:\Media\tv`, `D:\Media\movies`, `D:\October movies`) and a TMDB key (free account at themoviedb.org, Settings, API; paste the "API Read Access Token" or v3 key).
5. Optional: OpenSubtitles account (opensubtitles.com, API consumer key plus username and password).
6. Click "Rescan now". The TV app finds the server automatically.

Update by replacing the exe only; `data\` stays.

## Limitations

- Deleting from the app uses the Recycle Bin, which network shares and removable drives (USB disks, SD cards) do not have. For media on those drives the delete is refused with "Cannot move to Recycle Bin: <path> is on a network or removable drive" and nothing is deleted; remove the files in Explorer instead.

## Manual verification on Windows

- [ ] Tray icon appears; menu has "Open admin page", "Rescan now", "Open log folder", "Quit" and each works
- [ ] Deleting a media file from the app sends it to the Recycle Bin
- [ ] Deleting a file on a USB drive or network share is refused with the "Cannot move to Recycle Bin" message and the file stays
- [ ] Firewall prompt appears on first run; allowing private networks lets the TV connect
- [ ] The server is visible via mDNS from the TV app (no manual address needed)
- [ ] `data\logs\server.log` is written; Quit stops the process
