#!/usr/bin/env bash
# Fetches a current Windows ffprobe.exe for the distribution. ffprobe parses every media file in the library,
# including files that came from anywhere, so it must not be the 2021 build that ffprobe-static ships.
# Source: BtbN's FFmpeg builds (https://github.com/BtbN/FFmpeg-Builds), the "latest" release of the 9.0 line (static LGPL build: ffprobe.exe runs alone).
# Usage: bash scripts/fetch-ffprobe-win.sh <output path, e.g. dist/win/ffprobe.exe>
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=${1:?output path required}
ASSET=${FFPROBE_WIN_ASSET:-ffmpeg-n9.0-latest-win64-lgpl-9.0.zip}
URL=${FFPROBE_WIN_URL:-https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/$ASSET}
CACHE=dist/cache
ZIP="$CACHE/$ASSET"

mkdir -p "$CACHE" "$(dirname "$OUT")"
# The "latest" asset is rebuilt upstream; -z re-downloads only when the server has a newer file.
if [ -f "$ZIP" ]; then
  curl -fsSL -z "$ZIP" -o "$ZIP" "$URL"
else
  curl -fsSL -o "$ZIP" "$URL"
fi

INNER=$(unzip -Z1 "$ZIP" | grep -E '/bin/ffprobe\.exe$' | head -1)
[ -n "$INNER" ] || { echo "ffprobe.exe not found inside $ZIP" >&2; exit 1; }
unzip -p "$ZIP" "$INNER" > "$OUT"
[ -s "$OUT" ] || { echo "extracted ffprobe.exe is empty" >&2; exit 1; }
echo "ffprobe.exe from $ASSET -> $OUT ($(du -h "$OUT" | cut -f1))"
