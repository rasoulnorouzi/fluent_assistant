# CLAUDE.md: project memory for Tone Fixer

Read this at the start of every session, and add a short dated line whenever something is decided,
preferred, broken/fixed, or learned. See PLAN.md for the full plan and TODO.md for progress.

**Working rules (from the user):** plan → approve → build. Tick TODO.md after every step,
update its progress line, and give a short status report (done / next / problems). Never do
several steps silently. Minimum complexity: fewest files and dependencies, no architecture layers.

## Decisions
- 2026-11-05 Personal app for 2 users, sideloaded; no store compliance, no backend.
- 2026-11-05 Android first (native Kotlin); browser extension = Phase 2, iOS = Phase 3 (plan only for now).
- 2026-11-05 APKs are built by GitHub Actions (signed with one fixed keystore from repo secrets); user downloads and installs.
- 2026-11-05 Tap bubble = run with last-used tone; long-press = tone menu.
- 2026-11-05 Result streams into a preview card first; text is replaced only after the user taps ✓ (no auto-apply).
- 2026-11-05 Non-English input (esp. Farsi/Persian, also Finglish) is translated to fluent English in the selected tone, in every tone, with no separate mode.
- 2026-11-05 Tones: Grammar only, Daily, Casual, Friendly, Formal email, Work chat, Shorter.
- 2026-11-05 Visual direction "Soft & personal": paper #F3F0E8, sage #6F8D75 (bubble/icon), sage-deep #4F6B55 (buttons), clay #C47A5A (accent), clay-deep #A35A3A (badge); colours chosen to pass WCAG contrast; mockup https://claude.ai/artifact/HALYw7vAi4PCKBCsP5bqRL; Fraunces for headings; logo = speech bubble with a soft wave. No AI-cliché visuals (sparkles, purple gradients, glass).
- 2026-11-05 Use TYPE_ACCESSIBILITY_OVERLAY from the AccessibilityService instead of SYSTEM_ALERT_WINDOW (one fewer permission). Approved 2026-11-06.
- 2026-11-05 Encrypt API keys with an Android Keystore AES-GCM key (EncryptedSharedPreferences is deprecated). Approved 2026-11-06.
- 2026-11-05 No third-party libs on Android: framework Views, HttpURLConnection, org.json. Raw HTTP for both providers (keeps request format identical across platforms).
- 2026-11-05 Model config in shared/providers.json; each model has an `extra` object deep-merged into the request (used to switch thinking off).
- 2026-11-06 User approved PLAN.md and the mockup (incl. accessibility overlay instead of SYSTEM_ALERT_WINDOW, and Keystore encryption). Open questions left unanswered → going with the plan's defaults: "Daily" as defined, GitHub Release on `v*` tags.
- 2026-11-06 Android build: AGP 9.4.1 (built-in Kotlin, no Kotlin plugin), Gradle wrapper 9.8.2, compileSdk/targetSdk 36, minSdk 29, JDK 17 target. Single module: `android/` itself is the app.
- 2026-11-06 App id / package: `app.tonefixer`.
- 2026-11-06 Gemini models: `gemini-3.5-flash-lite` (default) and `gemini-3.1-flash-lite`, both with `thinkingLevel: minimal`. Dropped `gemini-2.5-flash-lite`.
- 2026-11-06 Added `Ui.kt` (palette light/dark, chip/button helpers, a small wrapping Flow layout) because Settings and the overlay share it, so it lives in one file instead of two copies. Settings is built in code (no XML layouts).
- 2026-11-06 Settings has a "Try it here" text box so the bubble can be tested inside the app itself.
- 2026-11-06 UI is checked without a phone via Robolectric screenshot tests (`ScreenshotTest`, test-only dependency) → PNGs in android/build/screens/.
- 2026-11-06 Overlay = 3 TYPE_ACCESSIBILITY_OVERLAY windows (bubble, long-press menu, preview card), all FLAG_NOT_FOCUSABLE so the app keeps its keyboard/cursor. Card sits at the top of the screen.
- 2026-11-06 Apply = ACTION_SET_TEXT on the whole field (selection spliced in), verified by re-reading the node; fallback = clipboard + select range + ACTION_PASTE; last resort = "copied, paste it" message. The user's leading/trailing whitespace is kept.
- 2026-11-06 Tone chips in the card rerun on the same text read at the tap; picking from the long-press menu reads the field fresh.
- 2026-11-06 Brand: adaptive launcher icon (vector, with monochrome layer for themed icons). Title font = Fraunces SOFT 100 / wght 600, downloaded from Google Fonts with `&text=Tone Fixer`, so it's a 3.8 KB file with only those letters (OFL licence). Body text = system sans.
- 2026-11-06 Release signing: env vars TF_KEYSTORE_FILE/PASSWORD, TF_KEY_ALIAS/PASSWORD (or android/keystore.properties locally). CI decodes secret TF_KEYSTORE_BASE64. versionCode = GITHUB_RUN_NUMBER; v* tags attach the APK to a GitHub Release. Release APK ≈ 106 KB (R8 on).
- 2026-11-06 The repo is PUBLIC: never commit keys or keystores (.gitignore blocks *.jks, *.keystore, *.key, keystore.properties).
- 2026-11-06 Approved: in-app updates from a dedicated `updates` branch (`android/latest.json` + APK, read via raw.githubusercontent.com), not GitHub Releases, because CI is still blocked. Permission given to push to that branch.
- 2026-11-06 Approved: one fixed release key made here (PKCS12, alias `tonefixer`, RSA 4096, SHA-256 cert fingerprint 37:74:E3:B1:…:8B:5F:CB), kept OUTSIDE the repo (~/tonefixer-keys in the cloud box) and sent to the user to keep. A new cloud session needs the user to hand it back (or CI secrets). One-time: uninstall the debug test APK before the first release-signed install.
- 2026-11-06 versionCode = TF_VERSION_CODE (publish script: last published + 1) or the CI run number; versionName = "0.2.<versionCode>".
- 2026-11-06 Update install = framework PackageInstaller session; the status PendingIntent goes to SettingsActivity (singleTop, onNewIntent), so no BroadcastReceiver. Needs REQUEST_INSTALL_PACKAGES + the one-time "Install unknown apps" switch. Auto-check when Settings opens, at most every 20 h.
- 2026-11-06 Approved: Chrome extension in plain JavaScript (no TypeScript/esbuild/npm); `extension/pack.sh` copies shared JSON in and zips. Order: updater first, then extension.

## Preferences
- 2026-11-05 Speed is the top priority (direct API calls, streaming, pre-warm, thinking off, short fixed prompt).
- 2026-11-05 Wants a hand-made, non-generic look ("no AI slop") for the UI and logo.
- 2026-11-05 Wants step-by-step visibility: always say which TODO step we're on.

## Problems & fixes
- 2026-11-05 Cloud session can't reach ai.google.dev directly (network policy); Gemini facts were taken from web search results of Google's docs instead.
- 2026-11-06 `gemini-2.5-flash-lite` returns 404 "no longer available to new users". Fix: removed from providers.json.
- 2026-11-06 System Gradle 8.14 can't load AGP 9 projects. Fix: generate the wrapper from an empty dir, then always use ./gradlew.
- 2026-11-06 Maven Central sometimes returns HTTP 429 during the first build. Fix: just rerun; it succeeds once deps are cached.
- 2026-11-06 Local builds here: Android SDK installed at ~/android-sdk (platforms;android-36, build-tools;36.1.0); android/local.properties points to it (gitignored).
- 2026-11-06 User pasted a Gemini test key in chat; kept only in the session scratchpad, never in the repo. User should rotate it after testing.
- 2026-11-06 First CI run failed in 1 s with no runner assigned and no logs (runner_id 0). Likely account billing/Actions minutes or Actions disabled; user must check the run page. Local builds keep working meanwhile.
- 2026-11-06 Robolectric downloads its Android runtime jar at test time and Maven Central answered 429. Fix: curl it (with retries) into ~/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7/; Robolectric picks it up from there. In CI (GitHub) it downloads normally.
- 2026-11-06 Password EditTexts default to a monospace font (the hint looked like a typewriter). Fix: set `typeface = Typeface.DEFAULT` after setting inputType.
- 2026-11-06 No /dev/kvm in the cloud box → no emulator; Robolectric screenshots are the way to see the UI here.
- 2026-11-06 Gotcha: inside `LinearLayout(ctx).apply { }` a field named `tag` resolves to View.tag (the layout's own property), so addView(tag, …) didn't compile. Fix: renamed it farsiTag. Watch for View property names (tag, id, alpha, …) inside apply blocks.
- 2026-11-06 Error text used clay-deep, which is unreadable on the dark card. Fix: palette.warn (#E7A285 in dark).
- 2026-11-06 Google Fonts css2 returns EOT for an old-IE user agent; plain curl gets TrueType. `file` calls a TTF "SIMH tape data"; check that the first bytes are 00010000.
- 2026-11-06 Rendering an AdaptiveIconDrawable: its bounds = the visible (masked) area; the layers are drawn 1.5× bigger. Clip to the full bounds, not to the inner third.
- 2026-11-06 In build.gradle.kts, `java.util.Properties()` inside `android { }` fails ("Unresolved reference util") because `java` means the Java extension there. Fix: `import java.util.Properties` at the top.
- 2026-11-06 Android 13+ only shows ⋮ → "Allow restricted settings" AFTER the user has tried to enable the accessibility service once and seen the "Restricted setting" dialog. README and in-app hint explain that order.
- 2026-11-06 CI still never starts (8 runs). Workaround: sent the user a locally built debug APK (signed with this container's debug key, so uninstall before installing a CI release later).
- 2026-11-06 Device-focused code review (fresh agent) found 10 issues; all fixed: (1) any non-IO exception in the stream thread killed the service → catch-all + key cleaning (ASCII only); (2) switching provider saved the old key under the new provider; (3) Chrome/WebView/Flutter/Compose update text late → recheck after 350 ms, paste only if the field still holds the original text; (4) Claude max_tokens/refusal were overwritten by message_stop; (5) empty answer / Gemini promptFeedback block showed a blank card; (6) ✓ could overwrite a field that changed since the tap → now refuses and copies; (7) onInterrupt froze the card → no-op; (8) Settings "Try it" field hidden behind the keyboard (edge-to-edge) → scroll the focused field into view; (9) card under the status bar → real top inset; (10) card follows long streaming text.
- 2026-11-06 Emulator e2e attempt (Android 14 x86_64, software CPU, no KVM): first boot ~18 min, app installed and Settings rendered, accessibility enabled via `settings put secure`; typing via adb caused System UI ANRs, then the worker restarted and the second boot never finished (zygote crash loop after 90 min). Conclusion: no reliable emulator here; the real bubble → card → ✓ flow is still untested on a device.

- 2026-11-06 Extension e2e runs in the pre-installed Chromium (Playwright `channel: "chromium"`, headless; the headless shell can't load extensions) against a local mock of the Gemini API; the live run uses `proxy: {server: $HTTPS_PROXY}` (the proxy CA is already trusted by Chromium here).
- 2026-11-06 Bug caught by the e2e: `Element.replaceChildren(a, null)` inserts the text "null". Never pass null/undefined to append/replaceChildren; add optional children separately.
- 2026-11-06 Extension shadow root is `open` (Playwright can't reach into closed ones); page CSS still can't style it. UI built with DOM calls + constructable stylesheet, no innerHTML (Trusted Types sites like Gmail).
- 2026-11-06 Rich editors: without a selection, rewrite everything up to the first `.gmail_signature/.gmail_quote/blockquote/#Signature…`, so signatures and quoted mail survive. Read text with Selection.toString() (keeps line breaks), not Range.toString().
- 2026-11-06 fetch() treats port 1 as a "bad port" (fails before headers are checked): use a real closed port in "no connection" tests.

## Facts learned (API)
- 2026-11-05 Claude Haiku 5.5 = `claude-haiku-5-5`; thinking is ON by default; `thinking:{type:"disabled"}` is allowed at effort low/medium/high; `temperature/top_p/top_k` are rejected on API version `2026-10-21`. Browser calls need header `anthropic-dangerous-direct-browser-access: true`.
- 2026-11-06 Live test (gemini-3.5-flash-lite, thinkingLevel minimal): first text in ~0.9–1.5 s from the cloud box without pre-warm; Farsi, Finglish, questions and an "ignore your instructions" message were all rewritten correctly by the fixed prompt.
- 2026-11-06 Gemini SSE lines end with \r\n; each `data:` JSON has candidates[0].content.parts[].text; the last chunk has finishReason STOP (or MAX_TOKENS) and an empty text part with a thoughtSignature. HTTP errors (e.g. 404) come back as a plain JSON body `{error:{code,message,status}}`, not SSE.
- 2026-11-05 Gemini 3.x uses `thinkingConfig.thinkingLevel` ("minimal" is the lowest; 3.5 Flash-Lite defaults to minimal); 2.5 uses `thinkingBudget: 0`. Never send both (400).
- 2026-11-06 Llm.kt passes 18 JVM unit tests (fixtures = real recorded Gemini streams + hand-written Claude streams) and a live test (`GEMINI_KEY=... ./gradlew testDebugUnitTest --tests '*liveGemini*'`), which is skipped when no key is set.
- 2026-11-06 Latency measurement (JDK HttpURLConnection): first connection's setup ≈ 470 ms; reused connection ≈ 0 ms (so pre-warm is worth it). Gemini's own time-to-first-token varies a lot per request (0.5–3.3 s), and that's server-side.
- 2026-11-06 Gotcha: writing `shared/*.json` inside a Kotlin KDoc opens a nested comment (`/*`) → "Unclosed comment" compile error. Avoid `/*` in comments.

## Open questions
- 2026-11-05 "Daily" = neutral natural everyday English? (Default taken 2026-11-06; user can still change it.)
- 2026-11-05 iOS: free Apple ID (7-day re-sign) vs paid developer account. Decide before Phase 3b.
