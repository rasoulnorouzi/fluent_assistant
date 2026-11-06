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

## Facts learned (API)
- 2026-11-05 Claude Haiku 5.5 = `claude-haiku-5-5`; thinking is ON by default; `thinking:{type:"disabled"}` is allowed at effort low/medium/high; `temperature/top_p/top_k` are rejected on API version `2026-10-21`. Browser calls need header `anthropic-dangerous-direct-browser-access: true`.
- 2026-11-06 Live test (gemini-3.5-flash-lite, thinkingLevel minimal): first text in ~0.9–1.5 s from the cloud box without pre-warm; Farsi, Finglish, questions and an "ignore your instructions" message were all rewritten correctly by the fixed prompt.
- 2026-11-06 Gemini SSE lines end with \r\n; each `data:` JSON has candidates[0].content.parts[].text; the last chunk has finishReason STOP (or MAX_TOKENS) and an empty text part with a thoughtSignature. HTTP errors (e.g. 404) come back as a plain JSON body `{error:{code,message,status}}`, not SSE.
- 2026-11-05 Gemini 3.x uses `thinkingConfig.thinkingLevel` ("minimal" is the lowest; 3.5 Flash-Lite defaults to minimal); 2.5 uses `thinkingBudget: 0`. Never send both (400).

## Open questions
- 2026-11-05 "Daily" = neutral natural everyday English? (Default taken 2026-11-06; user can still change it.)
- 2026-11-05 iOS: free Apple ID (7-day re-sign) vs paid developer account. Decide before Phase 3b.
