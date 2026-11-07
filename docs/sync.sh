#!/usr/bin/env bash
# Copies the shared request code and tone/provider files into the web app (GitHub Pages serves
# only this folder). Run after changing extension/llm.js or shared/*.json; a test fails if you forget.
set -euo pipefail
cd "$(dirname "$0")"
cp ../extension/llm.js ../shared/tones.json ../shared/providers.json .
echo "docs/ is in sync"
