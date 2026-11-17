# Pixel Pantry

A personal, LAN-only media server and Android TV app: a small replacement for Plex or Jellyfin for one household. A Windows PC holds the media folders, a Google TV / Android TV plays them through a native app. Nothing is exposed outside the home network, there are no accounts, and files are played as they are (no transcoding).

## What it does

- **Library.** Scans the folders you name (TV shows, anime, movies), groups series into show → season → episode and fetches artwork and descriptions from TMDB. New files are picked up by a folder watcher; "Rescan" on the TV or the admin page forces it.
- **Player.** Skip back and forward 10 s each (both configurable), next / previous episode on CH+ / CH− and on screen, autoplay of the next episode, resume position and watched state kept on the server.
- **Subtitles.** Embedded tracks and sidecar files (`.srt`, `.vtt`, `.ass`), English on by default, a delay adjustment, and a one-action OpenSubtitles search whose results can be switched without interrupting playback.
- **Audio.** Pick an audio track once and the rest of the show follows: the language is remembered per show, so a series with English and Japanese audio keeps playing in the one you chose.
- **Housekeeping.** Continue watching, history and stats, delete to the Recycle Bin from the TV, an admin page on the PC for settings, status and logs.
- **Pairing.** The TV finds the server on the network by itself (mDNS) and is paired once with a 6-digit PIN from the admin page.

## Download the server (Windows)

**[PixelPantry-win.zip](https://github.com/Elandriil/pixel-pantry/raw/dist/PixelPantry-win.zip)** is the current build, kept on the [`dist` branch](https://github.com/Elandriil/pixel-pantry/tree/dist).

1. Extract the whole zip and copy the `win` folder anywhere, for example `C:\PixelPantry`.
2. Double-click `Start Pixel Pantry.vbs`. If SmartScreen warns, choose "More info", then "Run anyway". Allow it through Windows Firewall on **private** networks.
3. Open `http://localhost:8765/admin`, add your library folders and a free [TMDB](https://www.themoviedb.org) API key, then click "Rescan now".

To update, replace `PixelPantry.exe` with the one from a newer zip; the `data` folder next to it (database, settings, artwork) stays. The full guide is `README.txt` inside the zip and [server/README.md](server/README.md).

## The TV app

The app is built from source and sideloaded over ADB; there is no store build. [tv/README.md](tv/README.md) has the build commands and the steps for a Sony Bravia (they apply to any Android TV / Google TV device).

## Repository layout

| Path | What |
|------|------|
| [`server/`](server/) | The server: Bun + TypeScript, SQLite, packaged as a single Windows exe |
| [`tv/`](tv/) | The Android TV app: Kotlin, Compose for TV, Media3 |
| [`docs/`](docs/) | Design spec, implementation plans and notes |

## Development

```sh
# server
cd server
bun install
bun run dev        # data in server/data/
bun test

# TV app
cd tv
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

`bun run publish:dist` in `server/` builds the Windows exe and publishes the zip to the `dist` branch.

## Security

The admin page answers only on the PC itself. The media API needs the paired TV's token on every request. Traffic is plain HTTP, so this is meant for a home network you trust, not for the internet. Details: [server/README.md](server/README.md#security-model).
