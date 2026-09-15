#!/bin/bash
# README için panel ekran görüntülerini docs/ altına üretir.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p docs
BIN="$(mktemp -d)/render"
swiftc -parse-as-library -D RENDER \
  -target "$(uname -m)-apple-macos13.0" \
  Sources/main.swift Tools/render.swift -o "$BIN"
"$BIN" docs
