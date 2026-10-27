#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=dist/win
rm -rf "$OUT"
mkdir -p "$OUT"

# --windows-hide-console is rejected when cross-compiling from non-Windows, so the console is hidden via the .vbs launcher instead.
bun build --compile --target=bun-windows-x64 --minify \
  ./src/index.ts --outfile "$OUT/PixelPantry.exe"

cp node_modules/ffprobe-static/bin/win32/x64/ffprobe.exe "$OUT/ffprobe.exe"
cp scripts/README.win.txt "$OUT/README.txt"
printf '%s\r\n' \
  'Set sh = CreateObject("WScript.Shell")' \
  'Set fso = CreateObject("Scripting.FileSystemObject")' \
  'dir = fso.GetParentFolderName(WScript.ScriptFullName)' \
  'If Not fso.FileExists(dir & "\PixelPantry.exe") Then' \
  '  MsgBox "PixelPantry.exe was not found next to this script." & vbCrLf & vbCrLf & "If you opened this from inside the zip file, extract the whole zip first (right-click the zip, Extract All), then run Start Pixel Pantry.vbs from the extracted folder.", vbExclamation, "Pixel Pantry"' \
  '  WScript.Quit 1' \
  'End If' \
  'sh.CurrentDirectory = dir' \
  'sh.Run """" & dir & "\PixelPantry.exe""", 0, False' > "$OUT/Start Pixel Pantry.vbs"

for f in PixelPantry.exe ffprobe.exe README.txt "Start Pixel Pantry.vbs"; do
  [ -f "$OUT/$f" ] || { echo "missing $OUT/$f" >&2; exit 1; }
done
ls -la "$OUT"
