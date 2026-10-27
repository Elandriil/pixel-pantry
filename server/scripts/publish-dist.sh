#!/usr/bin/env bash
# Zips dist/win and publishes it as the single commit of the `dist` branch on origin.
# The branch is rewritten every time (one commit, no history), so rebuilds never bloat the repo.
# Usage: bash scripts/publish-dist.sh            (zips the existing dist/win)
#        bash scripts/publish-dist.sh --build    (runs package-win.sh first)
set -euo pipefail
cd "$(dirname "$0")/.."

[ "${1:-}" = "--build" ] && bash scripts/package-win.sh
for f in PixelPantry.exe ffprobe.exe README.txt "Start Pixel Pantry.vbs"; do
  [ -f "dist/win/$f" ] || { echo "missing dist/win/$f (run scripts/package-win.sh)" >&2; exit 1; }
done

ZIP=dist/PixelPantry-win.zip
rm -f "$ZIP"
(cd dist && zip -q -r -9 PixelPantry-win.zip win)
SIZE=$(du -h "$ZIP" | cut -f1)
SRC=$(git rev-parse --short HEAD)

# Build the commit from plumbing so the working tree and the current branch are untouched.
BLOB=$(git hash-object -w "$ZIP")
NOTE=$(printf 'Pixel Pantry Windows build %s (%s)\n\nUnzip PixelPantry-win.zip, then read win/README.txt.\nBuilt from main commit %s.\n' "$(date +%Y-%m-%d)" "$SIZE" "$SRC" | git hash-object -w --stdin)
TREE=$(printf '100644 blob %s\tPixelPantry-win.zip\n100644 blob %s\tREADME.txt\n' "$BLOB" "$NOTE" | git mktree)
COMMIT=$(git commit-tree "$TREE" -m "Windows build from $SRC ($(date +%Y-%m-%d))")
git update-ref refs/heads/dist "$COMMIT"
git push --force origin dist
echo "published $ZIP ($SIZE) as branch dist (commit $COMMIT)"
