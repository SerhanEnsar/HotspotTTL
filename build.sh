#!/bin/bash
# HotspotTTL.app derler. Kullanım: ./build.sh [--install]
set -euo pipefail
cd "$(dirname "$0")"

APP="HotspotTTL.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"

swiftc -parse-as-library -O \
  -target "$(uname -m)-apple-macos13.0" \
  Sources/main.swift \
  -o "$APP/Contents/MacOS/HotspotTTL"

cp Info.plist "$APP/Contents/Info.plist"
mkdir -p "$APP/Contents/Resources"
cp diagnose.sh "$APP/Contents/Resources/diagnose.sh"
codesign --force -s - "$APP"

echo "Derlendi: $APP"

if [[ "${1:-}" == "--install" ]]; then
  rm -rf "/Applications/$APP"
  cp -R "$APP" /Applications/
  echo "Kuruldu: /Applications/$APP"
fi
