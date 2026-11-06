#!/usr/bin/env bash
# Copies the shared tone/provider files (and the title font) into this folder, so Chrome can load
# it as an unpacked extension, and makes build/tone-fixer-extension-<version>.zip to send around.
set -euo pipefail
cd "$(dirname "$0")"
cp ../shared/tones.json ../shared/providers.json .
cp ../android/src/main/res/font/fraunces_title.ttf fraunces_title.ttf
VERSION=$(python3 -c 'import json; print(json.load(open("manifest.json"))["version"])')
mkdir -p build
ZIP="build/tone-fixer-extension-$VERSION.zip"
rm -f "$ZIP"
# The zip holds a "tone-fixer" folder: unzip it, then chrome://extensions → Load unpacked → that folder.
STAGE=$(mktemp -d); trap 'rm -rf "$STAGE"' EXIT
mkdir "$STAGE/tone-fixer"
cp -r manifest.json ./*.js ./*.html ./*.css ./*.json ./*.ttf icons "$STAGE/tone-fixer/" 2>/dev/null || true
rm -f "$STAGE/tone-fixer/package.json"
(cd "$STAGE" && zip -qr - tone-fixer) > "$ZIP"
echo "Ready: load this folder unpacked, or send $ZIP"
