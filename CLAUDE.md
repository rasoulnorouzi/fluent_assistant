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
- 2026-11-05 Proposed: use TYPE_ACCESSIBILITY_OVERLAY from the AccessibilityService instead of SYSTEM_ALERT_WINDOW (one fewer permission). Pending approval.
- 2026-11-05 Proposed: encrypt API keys with an Android Keystore AES-GCM key (EncryptedSharedPreferences is deprecated). Pending approval.
- 2026-11-05 Proposed: no third-party libs on Android: framework Views, HttpURLConnection, org.json. Raw HTTP for both providers (keeps request format identical across platforms).
- 2026-11-05 Model config in shared/providers.json; each model has an `extra` object deep-merged into the request (used to switch thinking off).

## Preferences
- 2026-11-05 Speed is the top priority (direct API calls, streaming, pre-warm, thinking off, short fixed prompt).
- 2026-11-05 Wants a hand-made, non-generic look ("no AI slop") for the UI and logo.
- 2026-11-05 Wants step-by-step visibility: always say which TODO step we're on.

## Problems & fixes
- 2026-11-05 Cloud session can't reach ai.google.dev directly (network policy); Gemini facts were taken from web search results of Google's docs instead.

## Facts learned (API)
- 2026-11-05 Claude Haiku 5.5 = `claude-haiku-5-5`; thinking is ON by default; `thinking:{type:"disabled"}` is allowed at effort low/medium/high; `temperature/top_p/top_k` are rejected on API version `2026-10-21`. Browser calls need header `anthropic-dangerous-direct-browser-access: true`.
- 2026-11-05 Gemini 3.x uses `thinkingConfig.thinkingLevel` ("minimal" is the lowest; 3.5 Flash-Lite defaults to minimal); 2.5 uses `thinkingBudget: 0`. Never send both (400).

## Open questions
- 2026-11-05 Does "Daily" tone = neutral natural everyday English (vs looser "Casual")?
- 2026-11-05 Keep repo private? GitHub Release per version, or Actions artifacts only?
- 2026-11-05 iOS: free Apple ID (7-day re-sign) vs paid developer account. Decide before Phase 3b.
