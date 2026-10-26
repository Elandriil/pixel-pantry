# Notes for the TV client

- **Base URL.** Every URL the server returns (`streamUrl`, subtitle `url`, image paths like `/api/images/w500/x.jpg`) is relative to the server base, e.g. `http://192.168.1.20:8765`. Prefix it; never hard-code a host.
- **Address check.** `GET /api/info` returns `{ "name": "Pixel Pantry", "version": "1" }`. Use it to confirm that an address found via mDNS or typed in by hand really is a Pixel Pantry server.
- **Progress.** `POST /api/items/:id/progress` with `{ position, duration, sessionId }` at most every 30 s while playing (plus once on pause and stop). Always send the `sessionId` from the previous response for the same item; send none for the first report of a new playback. Watch time per report is capped at 30 s, so reporting less often undercounts history and stats.
- **Subtitle ids.** Sidecar ids are `sidecar:<URL-encoded file name>` and embedded ones `embedded:<stream index>`. Both stay valid when other subtitle files are added, so a saved `subtitleChoice` can be matched against `ItemDetail.subtitles` on the next play. `ItemDetail.subtitles` already lists sidecars; `GET /api/items/:id/subtitles` returns the same list.
- **Subtitle bytes.** A sidecar URL serves the file as stored (its own encoding). With `?shift=<ms>` the server re-times it and returns UTF-8.
