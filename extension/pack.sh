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
cp -r manifest.json ./*.js ./*.html ./*.css ./*.json ./*.ttf ./*.woff2 ./*.txt icons "$STAGE/tone-fixer/" 2>/dev/null || true
rm -f "$STAGE/tone-fixer/package.json"
(cd "$STAGE" && zip -qr - tone-fixer) > "$ZIP"

# Firefox: the same files with a Firefox manifest (background page instead of a service worker,
# options_ui, an add-on id), zipped with the manifest at the top (an .xpi).
XPI="build/tone-fixer-firefox-$VERSION.xpi"
rm -f "$XPI"
python3 - "$STAGE/tone-fixer/manifest.json" <<'PY'
import json, sys
p = sys.argv[1]; m = json.load(open(p))
m["background"] = {"scripts": ["background.js"], "type": "module"}
m["options_ui"] = {"page": m.pop("options_page"), "open_in_tab": True}
m["browser_specific_settings"] = {"gecko": {
    "id": "tone-fixer@rasoulnorouzi.github.io", "strict_min_version": "140.0",
    # Your text goes to the AI service you picked (Claude or Gemini) and nowhere else.
    "data_collection_permissions": {"required": ["websiteContent"]}}}
json.dump(m, open(p, "w"), ensure_ascii=False, indent=2)
PY
(cd "$STAGE/tone-fixer" && zip -qr - .) > "$XPI"
echo "Ready: load this folder unpacked, or send $ZIP (Chrome) / $XPI (Firefox)"
