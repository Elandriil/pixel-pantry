#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=dist/win
rm -rf "$OUT"
mkdir -p "$OUT"

# --windows-hide-console is rejected when cross-compiling from non-Windows, so a console window shows.
bun build --compile --target=bun-windows-x64 --minify \
  ./src/index.ts --outfile "$OUT/PixelPantry.exe"

cp node_modules/ffprobe-static/bin/win32/x64/ffprobe.exe "$OUT/ffprobe.exe"
cp scripts/README.win.txt "$OUT/README.txt"

for f in PixelPantry.exe ffprobe.exe README.txt; do
  [ -f "$OUT/$f" ] || { echo "missing $OUT/$f" >&2; exit 1; }
done
ls -la "$OUT"
