# TODO

**Progress: Phase 0: 6/6 done (100%) · Phase 1: 14/16 (88%) · Phase 1b (updates): 4/5 · Phase 2: 9/10 · Phase 3: 0/7**
**Current step:** waiting on you: U.5 (install Android 0.2.12, then update to 0.2.13 in the app), 2.10 (extension on Gmail / WhatsApp Web / Slack / LinkedIn), 1.16 (phone test).

## Phase 0: Planning and design
- [x] 0.1 Ask clarifying questions (build path, tone UX, apply flow, look, phones, languages, tones)
- [x] 0.2 Write `PLAN.md` (all three platforms)
- [x] 0.3 Write `TODO.md` (this file)
- [x] 0.4 Create `CLAUDE.md` (project memory)
- [x] 0.5 Logo + UI mockup (bubble, preview card, settings), reviewed by you
- [x] 0.6 You approve PLAN.md (and answer the open questions in §9)

## Phase 1: Android (build now)
- [x] 1.1 Gradle project skeleton (`android/`), empty Settings screen, builds locally with the wrapper
- [ ] 1.2 GitHub Actions workflow: unit tests + APK build (unsigned at first) goes green. **Blocked:** 8 runs, each failed in ~3 s without a runner. Repo is public (Actions are free), so it's likely an account billing lock or Actions disabled → check the run page / Settings → Actions
- [x] 1.3 `shared/tones.json`, `shared/providers.json`, `shared/API.md`, copied into assets by Gradle
- [x] 1.4 `Llm.kt`: config loading, request builders, `extra` merge, max_tokens, plus unit tests
- [x] 1.5 `Llm.kt`: SSE streaming for Gemini + Claude, error mapping, plus unit tests with sample streams
- [x] 1.6 `Prefs.kt`: settings + Keystore AES-GCM encryption of API keys (on-device check: Settings → Test)
- [x] 1.7 `SettingsActivity`: provider, model (+ custom id), keys, default tone, **Test key** button
- [x] 1.8 Setup checklist in Settings (restricted settings, accessibility, battery/autostart) with deep links
- [x] 1.9 `ToneService`: track editable focus, show/hide bubble, skip password fields
- [x] 1.10 `Overlay`: bubble (drag, remembered position, tone badge), tap = last tone, long-press = menu
- [x] 1.11 Read text (selection or whole field), preview card with streaming text, ✕ cancel, tone chips rerun
- [x] 1.12 ✓ Apply: `ACTION_SET_TEXT` + cursor placement + clipboard/paste fallback
- [x] 1.13 Connection pre-warm on focus (60 s throttle)
- [x] 1.14 Brand pass: adaptive app icon, font, colours, dark mode
- [x] 1.15 Signed release builds in CI (keystore secrets; you add them) + README install guide (release APK = 106 KB; secrets still to be added by you)
- [ ] 1.16 On-device test pass by you (checklist below) + fixes

### On-device checklist (1.16)
- [ ] WhatsApp · [ ] Telegram · [ ] Gmail compose · [ ] Google Messages · [ ] Keep · [ ] Chrome textarea
- [ ] Selection-only rewrite · [ ] Farsi → English (Friendly) · [ ] Farsi → English (Formal email)
- [ ] Wrong key shows a clear error · [ ] Airplane mode shows a clear error
- [ ] Service survives a reboot / a day of normal use (Pixel + Xiaomi/OnePlus)

## Phase 1b: In-app updates (Android, PLAN.md §4.7, waiting for approval)
- [x] U.1 Fixed release signing key: create it, send it to you, sign builds with it (made and sent to you in chat; keep it safe)
- [x] U.2 `Update.kt`: read `latest.json`, compare versions, download, SHA-256 check, PackageInstaller
- [x] U.3 "About & updates" section in Settings (version, Check button, auto-check once a day)
- [x] U.4 `updates` branch with `latest.json` + APK; `publish-update.sh` to push a new version
- [ ] U.5 Unit tests + screenshot; publish a 2nd version and update to it (you, on the phone)

## Phase 2: Browser extension (Chrome, PLAN.md §5, waiting for approval)
- [x] 2.1 `extension/` scaffold: MV3 manifest, icons, `pack.sh` (copies shared JSON, makes the zip)
- [x] 2.2 `background.js`: streaming fetch + SSE parsing for both providers, pre-warm
- [x] 2.3 Node tests for request bodies and parsing (same recorded streams as Android): `node --test extension/test/*.test.mjs`, 12 pass
- [x] 2.4 `content.js`: focus detection, bubble in Shadow DOM, Alt+Shift+F shortcut
- [x] 2.5 Preview card + tone menu (same design as Android, light/dark)
- [x] 2.6 Apply: textarea/input (native setter + input event), contenteditable (insertText), copy fallback
- [x] 2.7 Options page (provider, model, key + Test, tone, version)
- [x] 2.8 Headless Chromium test with the real extension on a local test page (+ screenshots): `node extension/test/e2e.cjs`, 12/12; plus a live Gemini run (Farsi → Friendly, Formal email: first text in ~0.85 s)
- [x] 2.9 README section + zip sent to you
- [ ] 2.10 Test on Gmail, WhatsApp Web, Slack, LinkedIn by you

## Phase 3: iOS (planned)
- [ ] 3.1 `ios/SHORTCUT.md`: Back Tap shortcut, step by step (interim solution)
- [ ] 3.2 Decide: free Apple ID (7-day re-sign) or paid account, before building the keyboard
- [ ] 3.3 Xcode project: container app (settings, key in shared Keychain group)
- [ ] 3.4 Keyboard extension UI: tone chips + Fix + globe key
- [ ] 3.5 Read/replace via `textDocumentProxy`, streaming via URLSession
- [ ] 3.6 Bundle `shared/*.json`
- [ ] 3.7 Device test
