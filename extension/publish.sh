#!/usr/bin/env bash
# Publishes to the repo's `updates` branch (the extension checks it every few hours):
#   ./publish.sh config            → only shared/tones.json + providers.json: used by itself, no reinstall
#   ./publish.sh "What's new"      → also a new extension version (bump "version" in manifest.json first):
#                                    the zip + latest.json, shown as "NEW" on the toolbar icon
set -euo pipefail
cd "$(dirname "$0")"
ARG="${1:-}"
[ -n "$ARG" ] || { echo 'Usage: ./publish.sh config   or   ./publish.sh "What is new"' >&2; exit 1; }
REPO_ROOT=$(git rev-parse --show-toplevel)
BRANCH=updates
node --test test/*.test.mjs >/dev/null || { echo "Tests failed." >&2; exit 1; }

WT=$(mktemp -d)
trap 'git -C "$REPO_ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true' EXIT
git -C "$REPO_ROOT" fetch -q origin "$BRANCH"
git -C "$REPO_ROOT" worktree add -q -B "$BRANCH" "$WT" "origin/$BRANCH"
mkdir -p "$WT/shared" "$WT/extension"
cp ../shared/tones.json ../shared/providers.json "$WT/shared/"
MSG="Tones/models update"

if [ "$ARG" != "config" ]; then
  VERSION=$(python3 -c 'import json; print(json.load(open("manifest.json"))["version"])')
  PUBLISHED=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$WT/extension/latest.json" 2>/dev/null || echo 0)
  python3 -c 'import sys; a,b=[list(map(int,v.split("."))) for v in sys.argv[1:]]; sys.exit(0 if a>b else 1)' "$VERSION" "$PUBLISHED" \
    || { echo "manifest.json version $VERSION is not newer than the published $PUBLISHED: bump it first." >&2; exit 1; }
  ./pack.sh >/dev/null
  ZIP="tone-fixer-extension-$VERSION.zip"
  find "$WT/extension" -name 'tone-fixer-extension-*.zip' -delete
  cp "build/$ZIP" "$WT/extension/$ZIP"
  python3 - "$WT/extension/latest.json" "$VERSION" "$ZIP" "$ARG" <<'PY'
import json, sys
path, version, zip_, notes = sys.argv[1:]
json.dump({"version": version, "zip": zip_, "notes": notes}, open(path, "w"), indent=2)
open(path, "a").write("\n")
PY
  MSG="Extension $VERSION: $ARG"
fi

git -C "$WT" add -A -f
if git -C "$WT" diff --cached --quiet; then echo "Nothing new to publish."; exit 0; fi
git -C "$WT" commit -q -m "$MSG"
git -C "$WT" push -q origin "HEAD:$BRANCH"
echo "Published: $MSG"
