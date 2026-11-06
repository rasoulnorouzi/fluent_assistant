# Tone Fixer

A tiny personal app: type anywhere on your Android phone, tap the floating bubble, and your text
comes back with the grammar fixed and rewritten in the tone you picked. Farsi (or Finglish) comes
back as fluent English. You see a preview first, and nothing changes until you tap ✓.

It talks straight to Google Gemini or Anthropic Claude with **your own API key**. There's no
server, no account and no tracking. The key is stored encrypted on the phone.

- Plan: [PLAN.md](PLAN.md) · Progress: [TODO.md](TODO.md) · Project memory: [CLAUDE.md](CLAUDE.md)
- Shared config used by all platforms: [`shared/`](shared) (tones, models, [API contract](shared/API.md))

## Install on your phone

1. **Get the APK.** The latest one is always at
   <https://raw.githubusercontent.com/rasoulnorouzi/fluent_assistant/updates/android/latest.json>
   (the `apk` name in it, same folder), or use the file sent to you in chat. After the first
   install, the app updates itself (see "Updates" below).
2. **Install it.** Open the APK on the phone. Android asks you to allow "Install unknown apps" for
   the browser or Files app: allow it, then tap Install.
3. **Open Tone Fixer and follow the checklist at the top:**
   - **Allow restricted settings (Android 13+).** Android blocks Accessibility for apps installed
     outside the Play Store. Tap *Open* → ⋮ (top right) → **Allow restricted settings** → confirm.
     If you don't see the ⋮ option, first try step 2 once (Android only offers it after it has
     blocked the app once), then come back.
   - **Turn on Tone Fixer** under Accessibility → Installed apps / Downloaded apps.
   - **API key:** paste a Gemini key (from <https://aistudio.google.com/apikey>) or a Claude key,
     then tap **Test**. You should see "Key works".
   - **Battery: Unrestricted**, so Android doesn't stop the service. Xiaomi / Redmi / POCO: also
     App info → **Autostart** on. OnePlus: Battery → **Don't optimize**.
4. **Try it** in the "Try it here" box at the bottom of Tone Fixer, then in any app.

**Using it:** tap the bubble to rewrite with the current tone. Hold it to pick another tone. Drag
it up or down to move it. If you select part of the text first, only the selection is rewritten.

**Updating:** Tone Fixer → About & updates (see below). Settings and keys are kept.

## Chrome extension (laptops)

The same bubble in Chrome: click into any text box (Gmail, WhatsApp Web, Slack, LinkedIn, any
form), type, and a small sage bubble appears at its corner.

**Install** (once per laptop):
1. Unzip `tone-fixer-extension-<version>.zip` somewhere you'll keep it (e.g. Documents). It holds
   a `tone-fixer` folder. (From a git checkout instead: run `extension/pack.sh`, then use `extension/`.)
2. Open `chrome://extensions`, turn on **Developer mode** (top right), click **Load unpacked**, and
   pick the `tone-fixer` folder.
3. The settings page opens: paste your Gemini or Claude key, tap **Test**, pick a tone.
4. Optional: pin it (puzzle icon → pin) so the icon opens the settings in one click.

**Using it:** click the bubble = rewrite with the current tone. Right-click or hold it = pick
another tone. The result appears in a card; ✓ replaces your text (Ctrl+Z undoes it), ✕ or Esc
cancels. If you select part of the text first, only that part is rewritten. In Gmail, your
signature and the quoted mail are left alone. Keyboard: **Alt+Shift+F** rewrites, press it again
to replace (change it at `chrome://extensions/shortcuts`).

**Updating:** unzip the new version over the old folder, then click ↻ on Tone Fixer in
`chrome://extensions`. Settings and keys stay.

**Privacy:** the key is stored in Chrome's extension storage on that laptop (not encrypted on disk)
and is only sent to the provider you chose. Pages can't read it.

**Tests (developers):** `node --test extension/test/*.test.mjs` (request/stream logic, no
packages) and `node extension/test/e2e.cjs` (real extension in Chromium against a mock API; needs
Playwright).

## Android updates (inside the app)

Open Tone Fixer → scroll to **About & updates**. The app checks by itself when you open it (at
most once a day), or tap **Check for updates**. When a new version is ready, tap **Update to …**:
it downloads, checks the file, and Android shows its usual "Update this app?" screen. Your
settings and keys stay. The very first time, Android asks you to allow **Install unknown apps**
for Tone Fixer (one switch, then come back: the update continues by itself).

New versions live on this repo's `updates` branch (`android/latest.json` + the APK). To publish
one (needs the release key, below):

```sh
cd android
./publish-update.sh "What's new, in one short line"
```

It builds a signed release APK with the next version number, runs the tests, and pushes both
files to the `updates` branch. Phones see it within a few minutes.

## Signing key (one per app, keep it forever)

Every APK must be signed with the **same** key, or Android refuses to update the app. The key was
made once (`tonefixer-release.jks`, alias `tonefixer`, certificate SHA-256 starting
`37:74:E3:B1`) and you were given the file and its password.

- **Back it up** (password manager, plus a copy off your laptop). If it's lost, the next APK
  can't update the installed one: you'd have to uninstall and set the app up again.
- **Never commit it.** This repo is public, and `.gitignore` blocks `*.jks` and `keystore.properties`.
- Local builds: put a `keystore.properties` next to `android/build.gradle.kts`:
  `TF_KEYSTORE_FILE=/path/to/tonefixer-release.jks`, `TF_KEYSTORE_PASSWORD=…`,
  `TF_KEY_ALIAS=tonefixer`, `TF_KEY_PASSWORD=…` (same password).
- GitHub Actions (once it runs): repo → **Settings → Secrets and variables → Actions**, add
  `TF_KEYSTORE_BASE64` (`base64 -w0 tonefixer-release.jks`; macOS: `base64 -i tonefixer-release.jks`),
  `TF_KEYSTORE_PASSWORD`, `TF_KEY_ALIAS` = `tonefixer`, `TF_KEY_PASSWORD`.

## Build it yourself (optional)

```sh
cd android
./gradlew testDebugUnitTest assembleDebug     # APK in android/build/outputs/apk/debug/
GEMINI_KEY=... ./gradlew testDebugUnitTest --tests '*liveGemini*'   # optional real API check
```

Needs JDK 17+ and the Android SDK (`android/local.properties` → `sdk.dir=...`).
`./gradlew testDebugUnitTest` also renders the screens to `android/build/screens/*.png`.

## Credits

Title font: [Fraunces](https://github.com/undercasetype/Fraunces) by Undercase Type, SIL Open Font
License 1.1 (only the letters "Tone Fixer" are bundled).
