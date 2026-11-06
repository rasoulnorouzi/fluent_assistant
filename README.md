# Tone Fixer

A tiny personal app: type anywhere on your Android phone, tap the floating bubble, and your text
comes back with the grammar fixed and rewritten in the tone you picked. Farsi (or Finglish) comes
back as fluent English. You see a preview first, and nothing changes until you tap ✓.

It talks straight to Google Gemini or Anthropic Claude with **your own API key**. There's no
server, no account and no tracking. The key is stored encrypted on the phone.

- Plan: [PLAN.md](PLAN.md) · Progress: [TODO.md](TODO.md) · Project memory: [CLAUDE.md](CLAUDE.md)
- Shared config used by all platforms: [`shared/`](shared) (tones, models, [API contract](shared/API.md))

## Install on your phone

1. **Get the APK.** Open the repo's **Actions** tab → the latest green "Android" run →
   **Artifacts** → `tone-fixer-apk` (a zip; inside, use `ToneFixer-release.apk`, or
   `ToneFixer-debug.apk` if there is no release one yet). Once a version is tagged (`v0.1.0`, …),
   the APK is also attached to the **Releases** page, which is easier to open on the phone.
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

**Updating:** install the newer APK over the old one; settings and keys are kept. (This works only
with release APKs signed by the same key, see below. Debug APKs from different CI runs need an
uninstall first.)

## Signing (one-time, so updates install over each other)

Every release APK must be signed with the **same** key. Make it once on your computer (you need
Java's `keytool`, which ships with Android Studio or any JDK):

```sh
keytool -genkeypair -keystore tonefixer.jks -alias tonefixer -keyalg RSA -keysize 2048 -validity 10000
# answer the questions; remember the password

base64 -w0 tonefixer.jks > tonefixer.jks.b64   # macOS: base64 -i tonefixer.jks -o tonefixer.jks.b64
```

Then on GitHub: repo → **Settings → Secrets and variables → Actions → New repository secret**, four times:

| Name | Value |
|---|---|
| `TF_KEYSTORE_BASE64` | the contents of `tonefixer.jks.b64` |
| `TF_KEYSTORE_PASSWORD` | the keystore password |
| `TF_KEY_ALIAS` | `tonefixer` |
| `TF_KEY_PASSWORD` | the key password (same as above if you pressed Enter) |

**Back up `tonefixer.jks` and its password** (e.g. in your password manager). If it's lost, the
next APK can't update the installed one: you'd have to uninstall and set the app up again.
**Never commit it.** This repo is public, and `.gitignore` already blocks `*.jks`.

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
