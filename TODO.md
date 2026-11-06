# TODO

**Progress: Phase 0: 6/6 done (100%) · Phase 1: 1/16 (6%) · Phase 2: 0/8 · Phase 3: 0/7**
**Current step:** 1.2, GitHub Actions workflow

## Phase 0: Planning and design
- [x] 0.1 Ask clarifying questions (build path, tone UX, apply flow, look, phones, languages, tones)
- [x] 0.2 Write `PLAN.md` (all three platforms)
- [x] 0.3 Write `TODO.md` (this file)
- [x] 0.4 Create `CLAUDE.md` (project memory)
- [x] 0.5 Logo + UI mockup (bubble, preview card, settings), reviewed by you
- [x] 0.6 You approve PLAN.md (and answer the open questions in §9)

## Phase 1: Android (build now)
- [x] 1.1 Gradle project skeleton (`android/`), empty Settings screen, builds locally with the wrapper
- [ ] 1.2 GitHub Actions workflow: unit tests + APK build (unsigned at first) goes green
- [ ] 1.3 `shared/tones.json`, `shared/providers.json`, `shared/API.md`, copied into assets by Gradle
- [ ] 1.4 `Llm.kt`: config loading, request builders, `extra` merge, max_tokens, plus unit tests
- [ ] 1.5 `Llm.kt`: SSE streaming for Gemini + Claude, error mapping, plus unit tests with sample streams
- [ ] 1.6 `Prefs.kt`: settings + Keystore AES-GCM encryption of API keys
- [ ] 1.7 `SettingsActivity`: provider, model (+ custom id), keys, default tone, **Test key** button
- [ ] 1.8 Setup checklist in Settings (restricted settings, accessibility, battery/autostart) with deep links
- [ ] 1.9 `ToneService`: track editable focus, show/hide bubble, skip password fields
- [ ] 1.10 `Overlay`: bubble (drag, remembered position, tone badge), tap = last tone, long-press = menu
- [ ] 1.11 Read text (selection or whole field), preview card with streaming text, ✕ cancel, tone chips rerun
- [ ] 1.12 ✓ Apply: `ACTION_SET_TEXT` + cursor placement + clipboard/paste fallback
- [ ] 1.13 Connection pre-warm on focus (60 s throttle)
- [ ] 1.14 Brand pass: adaptive app icon, font, colours, dark mode
- [ ] 1.15 Signed release builds in CI (keystore secrets; you add them) + README install guide
- [ ] 1.16 On-device test pass by you (checklist below) + fixes

### On-device checklist (1.16)
- [ ] WhatsApp · [ ] Telegram · [ ] Gmail compose · [ ] Google Messages · [ ] Keep · [ ] Chrome textarea
- [ ] Selection-only rewrite · [ ] Farsi → English (Friendly) · [ ] Farsi → English (Formal email)
- [ ] Wrong key shows a clear error · [ ] Airplane mode shows a clear error
- [ ] Service survives a reboot / a day of normal use (Pixel + Xiaomi/OnePlus)

## Phase 2: Browser extension (planned, start after Phase 1)
- [ ] 2.1 `extension/` scaffold: MV3 manifest, TS, esbuild script
- [ ] 2.2 Service worker: streaming fetch for both providers from `shared/*.json`
- [ ] 2.3 Content script: focus detection, button in Shadow DOM
- [ ] 2.4 Preview card + tone menu (same design as Android)
- [ ] 2.5 Apply: textarea/input (value + input event), contenteditable (execCommand insertText)
- [ ] 2.6 Options page (provider, model, keys, default tone)
- [ ] 2.7 Pre-warm on focus
- [ ] 2.8 Test on Gmail, WhatsApp Web, Slack, a plain textarea

## Phase 3: iOS (planned)
- [ ] 3.1 `ios/SHORTCUT.md`: Back Tap shortcut, step by step (interim solution)
- [ ] 3.2 Decide: free Apple ID (7-day re-sign) or paid account, before building the keyboard
- [ ] 3.3 Xcode project: container app (settings, key in shared Keychain group)
- [ ] 3.4 Keyboard extension UI: tone chips + Fix + globe key
- [ ] 3.5 Read/replace via `textDocumentProxy`, streaming via URLSession
- [ ] 3.6 Bundle `shared/*.json`
- [ ] 3.7 Device test
