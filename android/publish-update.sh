#!/usr/bin/env bash
# Publishes a new Android version for the in-app updater (PLAN.md §4.7):
# builds a release APK with the next versionCode, then pushes it + latest.json to the `updates` branch.
#
#   ./publish-update.sh "What's new, one short line"
#
# Needs the release key (android/keystore.properties or TF_* env vars). Run from android/.
set -euo pipefail
cd "$(dirname "$0")"
NOTES="${1:-}"
BRANCH=updates
REPO_ROOT=$(git rev-parse --show-toplevel)
[ -f keystore.properties ] || [ -n "${TF_KEYSTORE_FILE:-}" ] || { echo "No release key (keystore.properties)." >&2; exit 1; }

# The updates branch lives in its own worktree, so the work branch is never touched.
WT=$(mktemp -d)
trap 'git -C "$REPO_ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true' EXIT
if git -C "$REPO_ROOT" fetch -q origin "$BRANCH" 2>/dev/null; then
  git -C "$REPO_ROOT" worktree add -q -B "$BRANCH" "$WT" "origin/$BRANCH"
else
  git -C "$REPO_ROOT" worktree add -q --detach "$WT"
  git -C "$WT" checkout -q --orphan "$BRANCH"
  git -C "$WT" rm -rq --cached . && find "$WT" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  printf 'Published Android builds for the in-app updater. See android/latest.json.\n' > "$WT/README.md"
fi
mkdir -p "$WT/android"

# Next versionCode = last published + 1 (at least 12, above the CI run numbers used so far).
LAST=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionCode"])' "$WT/android/latest.json" 2>/dev/null || echo 11)
CODE=$(( LAST + 1 > 12 ? LAST + 1 : 12 ))
NAME="0.2.$CODE"
echo "Building $NAME (versionCode $CODE)…"
TF_VERSION_CODE=$CODE ./gradlew -q testDebugUnitTest assembleRelease
APK=build/outputs/apk/release/ToneFixer-release.apk
SDK=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || echo "$ANDROID_HOME")
"$(ls -d "$SDK"/build-tools/* | tail -1)/apksigner" verify "$APK"

# Keep the new APK and the previous one (a phone may still hold a cached latest.json for a few minutes).
PREV=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apk"])' "$WT/android/latest.json" 2>/dev/null || true)
find "$WT/android" -name 'ToneFixer-*.apk' ! -name "${PREV:-none}" -delete
cp "$APK" "$WT/android/ToneFixer-$NAME.apk"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
python3 - "$WT/android/latest.json" "$CODE" "$NAME" "ToneFixer-$NAME.apk" "$SHA" "$NOTES" <<'PY'
import json, sys
path, code, name, apk, sha, notes = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "apk": apk, "sha256": sha, "notes": notes},
          open(path, "w"), indent=2)
open(path, "a").write("\n")
PY
git -C "$WT" add -A -f
git -C "$WT" commit -q -m "Android $NAME${NOTES:+: $NOTES}"
git -C "$WT" push -q origin "HEAD:$BRANCH"
echo "Published $NAME → $(cat "$WT/android/latest.json" | tr -d '\n')"
cp "$APK" "build/ToneFixer-$NAME.apk"
echo "APK also at android/build/ToneFixer-$NAME.apk"
