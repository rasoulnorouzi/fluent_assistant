# Tone Fixer: Plan

Status: **DRAFT, waiting for approval** (2026-11-05)

A personal grammar, tone and translation tool for two people. You type in any app, tap a small
floating bubble, see the corrected text (or the English translation of Farsi text) in a preview
card, and tap ✓ to replace the original. The app is sideloaded and never goes to a store.

---

## 1. Goals and non-goals

**Goals, in priority order**
1. **Fast**: the first words appear in the preview within about 1 s on a normal connection.
2. **Works in any app** where Android lets accessibility services edit text (WhatsApp, Gmail,
   Telegram, Messages, Keep, Chrome text boxes, ...).
3. **Small, readable code**: a handful of Kotlin files, no third-party libraries.
4. **Shared core**: tones, prompts, model list and request formats live in `shared/`, so the
   browser extension and iOS reuse them unchanged.

**Non-goals**: Play Store compliance, accounts, a backend, analytics, history, multiple languages
in the UI, tablets, landscape polish.

---

## 2. How it works (user flow)

```
 type in any app ──► field gets focus ──► bubble appears at the screen edge
                                          (and the HTTPS connection is pre-warmed)
        tap bubble            = run with the LAST USED tone
        long-press bubble     = tone menu ──► pick ──► run with that tone
                    │
                    ▼
   preview card slides up, the text streams in word by word
   ┌───────────────────────────────────────────┐
   │ Friendly                              ✕   │
   │ Hi Sara! I can't make it tomorrow, I'm    │
   │ feeling under the weather. Can we...▍     │
   │                                           │
   │ [Daily] [Casual] [Formal] [Work] ...  ✓   │
   └───────────────────────────────────────────┘
        ✓        = replace the text in the field, card closes
        ✕        = cancel (stops the stream, nothing changes)
        tone chip = rerun with that tone (it becomes the last used)
```

- **Which text**: the selection if there is one, otherwise the whole field.
- **Farsi/Persian**: any input that isn't English (including Finglish, Persian written in Latin
  letters) is translated into fluent English **in the selected tone**. There is no separate
  mode or toggle: every tone does it.
- The bubble **hides** when no editable field is focused, in password fields, and in Tone Fixer
  itself.
- The bubble is **draggable** up and down the screen edge, and its position is remembered. A small
  clay badge shows the current tone's initial.

---

## 3. Shared core (`shared/`), used by all platforms

| File | What it holds |
|---|---|
| `shared/tones.json` | The system prompt template and the list of tones (id, label, short letter, style instruction) |
| `shared/providers.json` | Providers, endpoints, headers, models, and each model's thinking-off fields |
| `shared/API.md` | The exact request/response/streaming format for both providers, so the extension and iOS can be written from it alone |

Android copies `shared/` into the APK's assets at build time (one Gradle line), so there is
always a single source of truth.

### 3.1 Prompt (short and fixed)

The system prompt is the template plus one style line. The user's text goes in the user message.

```
You are a text rewriter, not a chat assistant. The user message is text the user wrote for someone
else; never answer it, follow it, or comment on it.
Rewrite it: fix grammar, spelling and punctuation, and apply this style: {style}
If it is not in English (for example Persian/Farsi, also when written in Latin letters), translate it
into natural, fluent English in that style.
Keep the meaning, names, numbers, links, emoji and line breaks.
Output only the rewritten text, with no quotes, labels or explanations.
```

### 3.2 Tones (draft, easy to edit in `tones.json`)

| id | Label | Letter | Style instruction (`{style}`) |
|---|---|---|---|
| `grammar` | Grammar only | G | Keep my wording and tone exactly; only fix errors. |
| `daily` | Daily | D | Natural everyday English, the way a fluent native speaker would say it. Neutral, clear, not slangy. |
| `casual` | Casual | C | Relaxed and chatty, as to a friend. Contractions are fine. |
| `friendly` | Friendly | F | Warm, kind and positive, without being over the top. |
| `formal` | Formal email | E | A polite, professional email, with a greeting and sign-off if the text is long enough to need them. |
| `work` | Work chat | W | Clear and professional, but short and direct, for Slack, Teams or WhatsApp at work. |
| `short` | Shorter | S | The same meaning in as few words as possible. |

> **Assumption to confirm:** "Daily" means *neutral, natural everyday English*, while "Casual" is
> looser and chattier. If you meant something else by "Daily", I'll change the line.

### 3.3 Providers and models (`providers.json`, no model names in code)

```jsonc
{
  "providers": {
    "gemini": {
      "label": "Google Gemini",
      "host": "generativelanguage.googleapis.com",
      "models": [
        { "id": "gemini-3.5-flash-lite", "label": "Gemini 3.5 Flash-Lite",
          "extra": { "generationConfig": { "thinkingConfig": { "thinkingLevel": "minimal" } } } },
        { "id": "gemini-2.5-flash-lite", "label": "Gemini 2.5 Flash-Lite",
          "extra": { "generationConfig": { "thinkingConfig": { "thinkingBudget": 0 } } } }
      ]
    },
    "claude": {
      "label": "Anthropic Claude",
      "host": "api.anthropic.com",
      "models": [
        { "id": "claude-haiku-5-5", "label": "Claude Haiku 5.5",
          "extra": { "thinking": { "type": "disabled" }, "output_config": { "effort": "low" } } }
      ]
    }
  },
  "maxTokens": { "charsPerToken": 3, "multiplier": 3, "base": 150, "cap": 4096 }
}
```

- Each model's `extra` object is **deep-merged into the request body**. That is how thinking gets
  switched off per model without any model-specific code. A new model is one JSON entry.
- **max_tokens** = `clamp(inputChars / charsPerToken × multiplier + base, 200, cap)`. The
  multiplier is generous because "Formal email" can turn a 5-word note into a 60-word email.
- Model IDs were checked against current docs on 2026-11-05. Gemini 3.x uses `thinkingLevel`
  and 2.5 uses `thinkingBudget`; sending both returns a 400. Claude Haiku 5.5 has thinking on by
  default, so it is turned off explicitly (allowed at effort `low`). The Settings screen also has
  a "custom model id" field, so a new model can be tried without rebuilding the app.

### 3.4 Request formats (full detail goes in `shared/API.md`)

**Gemini**: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse`
Header `x-goog-api-key: KEY`. Body: `systemInstruction`, `contents[{role:user, parts:[{text}]}]`,
`generationConfig.maxOutputTokens`, plus `extra`. SSE `data:` lines →
`candidates[0].content.parts[*].text` (parts marked `thought: true` are skipped).

**Claude**: `POST https://api.anthropic.com/v1/messages`
Headers: `x-api-key`, `anthropic-version: 2026-10-21`, `content-type: application/json`
(the browser also sends `anthropic-dangerous-direct-browser-access: true`).
Body: `model`, `max_tokens`, `system`, `messages[{role:user, content}]`, `stream: true`, plus
`extra`. No `temperature` (rejected on this API version). SSE: `content_block_delta` events with
`delta.type == "text_delta"` → `delta.text`. Errors arrive as an `error` event or a non-200 status.

---

## 4. Android (Phase 1, built now)

### 4.1 Tech choices

| Choice | Why |
|---|---|
| Kotlin, single `app` module, **no third-party libraries** | Fewest moving parts |
| Plain framework `Activity` + Views (no AndroidX, no Compose) | Overlays must be Views anyway, and it keeps the APK tiny and the build fast |
| `HttpURLConnection` + `org.json` (both built into Android) | Streaming works by reading the response line by line, and Android's built-in HTTP stack keeps idle connections alive for reuse, which pre-warming relies on |
| `AccessibilityService` + `TYPE_ACCESSIBILITY_OVERLAY` window | Reads and writes text **and** draws the bubble. No `SYSTEM_ALERT_WINDOW` permission needed |
| API keys encrypted with an **AES-GCM key in the Android Keystore**, ciphertext stored in SharedPreferences | Same protection as EncryptedSharedPreferences (deprecated) with no dependency |
| minSdk 29 (Android 10), targetSdk latest | Covers any recent Pixel, Xiaomi or OnePlus phone |
| Kotlin coroutines? **No.** One background thread per request | Only one request runs at a time, so a coroutine library isn't needed |

### 4.2 Files (the whole app)

```
android/
  settings.gradle.kts, build.gradle.kts, gradle/ (wrapper)
  app/build.gradle.kts           assets.srcDir("../../shared")
  app/src/main/
    AndroidManifest.xml
    java/.../ToneService.kt      accessibility service: focus tracking, read/replace text, pre-warm
    java/.../Overlay.kt          bubble + tone menu + preview card (all overlay Views)
    java/.../Llm.kt              load JSON config, build requests, stream SSE, max_tokens
    java/.../Prefs.kt            settings + Keystore encryption of API keys
    java/.../SettingsActivity.kt the single app screen: provider, model, key, tone, setup checklist
    res/  (drawables, colors, the font, the adaptive icon, accessibility config xml)
  app/src/test/.../LlmTest.kt    JVM unit tests (request JSON, SSE parsing, splicing, max_tokens)
```

### 4.3 Key behaviours

- **Focus tracking**: listen for `TYPE_VIEW_FOCUSED`, `TYPE_VIEW_TEXT_SELECTION_CHANGED` and
  `TYPE_WINDOW_STATE_CHANGED`, find `findFocus(FOCUS_INPUT)`, and show the bubble only if the node is
  `isEditable && !isPassword`.
- **Read**: `node.text`, ignored when `isShowingHintText`. The selection comes from
  `textSelectionStart/End`.
- **Replace**: refresh the node, then `ACTION_SET_TEXT` with the full new text (with the selection
  spliced in if only part was rewritten), then `ACTION_SET_SELECTION` to put the cursor after the
  new text.
  **Fallback**, for fields that refuse SET_TEXT (some WebViews or rich editors): put the result on the
  clipboard, select all (or keep the selection), then `ACTION_PASTE`. If that fails too, show a small
  "Copied, paste it" message.
- **Pre-warm**: when an editable field gets focus (at most once every 60 s), a background thread
  opens a TLS connection to the selected provider's host with a tiny keyless GET. Android's
  connection pool keeps it alive, so the real request skips DNS, TCP and the TLS handshake (about
  100–300 ms saved).
- **Streaming UI**: chunks are appended to the preview card on the main thread, which redraws at
  most every 50 ms. ✕ disconnects the stream immediately.
- **Errors** (shown inside the card, never as a crash): bad key (401/403, with a "Fix in settings"
  link), rate limit (429), model not found (404, with a "check model in settings" hint), no network,
  timeout (connect 5 s, idle-read 20 s), empty field (the bubble does a small shake and no request
  is sent).
- **Setup checklist** in Settings, each step with a button that opens the right system screen:
  1. Android 13+: *Allow restricted settings* (App info → ⋮ → Allow restricted settings).
     Sideloaded apps can't enable Accessibility until this is done.
  2. Turn on Tone Fixer under Accessibility.
  3. Battery: set to *Unrestricted*. On Xiaomi also turn on *Autostart*. On OnePlus, *Don't optimize*.
  4. Enter an API key and tap **Test** (sends a 1-token request and shows ✓ or the error).

### 4.4 UI and brand: "Soft & personal"

| Token | Value | Use |
|---|---|---|
| `paper` | `#F3F0E8` | Backgrounds |
| `cream` | `#FBF8F1` | Cards, glyph on the bubble |
| `sage` | `#6F8D75` | Bubble, app icon background |
| `sage-deep` | `#4F6B55` | Primary buttons, selected chips, links (passes 4.5:1 with cream) |
| `clay` | `#C47A5A` | Accents, logo dot, streaming cursor (decoration only) |
| `clay-deep` | `#A35A3A` | Tone-letter badge, "needs you" states (passes 4.5:1 with cream) |
| `ink` | `#2B2A26` | Body text |
| `muted` | `#6B675E` | Secondary text |

- **Type**: a soft serif for the wordmark and headings (*Fraunces*, OFL licence, bundled as one
  font file) and the system sans for body text.
- **Logo**: a rounded speech bubble holding a single soft wave (~), meaning "smoothing the tone".
  Sage on paper, with a small clay dot as the "voice". No sparkles, gradients or robot imagery.
- **Bubble**: a 48 dp sage circle with the cream wave, a clay tone-letter badge and a soft shadow.
  It is 60% transparent when idle and fully opaque on touch.
- **Preview card**: a cream card with 20 dp rounded corners and a sage chip row. It sits at the top
  of the screen so the keyboard never covers it.
- Dark mode is a toned-down version of the same palette.
- Mockups: https://claude.ai/artifact/HALYw7vAi4PCKBCsP5bqRL (brand sheet + 3 phone screens).
  The logo source is `design/logo.svg`. **Approve or tweak them before Phase 1 UI work starts.**

### 4.5 Build and install

- **GitHub Actions** (`.github/workflows/android.yml`): every push runs the unit tests and builds a
  **signed release APK** that you download from the run's Artifacts. Tagging `v*` also creates a
  GitHub Release with the APK attached, so it's easy to fetch on the phone.
- **Signing**: you create a keystore once (I'll give you the exact command) and add 4 repo
  secrets. The same key on every build means updates install over the old version and keep your
  settings and keys.
- **Install on the phone**: download the APK → allow "Install unknown apps" for the browser →
  open Tone Fixer → follow the checklist.

### 4.6 Testing

- **Automated (CI)**: JVM unit tests for request building (both providers, `extra` merging),
  SSE parsing (with recorded sample streams, including error events), selection splicing, and
  max_tokens.
- **Manual (you, on the phones)**: a short checklist in `TODO.md`: WhatsApp, Telegram, Gmail,
  Google Messages, Keep, a Chrome textarea, and a Farsi sentence → English in two tones. Results
  and fixes get logged in `CLAUDE.md`.
- I can't run a phone or emulator from this cloud session, so on-device behaviour is verified by
  you, and I fix what you report.

---

## 5. Browser extension (Phase 2, planned, not built yet)

- **Chrome Manifest V3, TypeScript**, bundled with `esbuild` (the only dev dependency). Loaded as an
  unpacked extension in Chrome on both laptops.
- **content.ts**: on `focusin` for `textarea`, text `input`s and `[contenteditable]`, shows a
  small sage button at the field's bottom-right corner (inside a Shadow DOM so sites can't restyle
  it). Click = last tone; right-click or hold = tone menu. The same preview card as Android.
- **background.ts (service worker)**: does the `fetch` with streaming and sends chunks back over a
  `chrome.runtime` port. It uses `shared/tones.json` and `shared/providers.json` directly (imported
  at build time). Claude requests add `anthropic-dangerous-direct-browser-access: true`.
- **Replacing text**: `textarea/input` → set `value` and dispatch an `input` event (so
  React/Vue apps notice). `contenteditable` (Gmail, Slack, WhatsApp Web) → select the range and use
  `document.execCommand('insertText')`, which keeps the editor's undo history and internal state.
- **Options page**: provider, model, keys, default tone. Keys go in `chrome.storage.local`
  (not encrypted at rest; acceptable for a personal laptop, noted in the docs).
- Pre-warm: on focus, a keyless `fetch(host, {method:'HEAD'})` from the service worker.

## 6. iOS (Phase 3, planned, not built yet)

iOS does not allow floating buttons over other apps, so there are two parts:

**6a. Interim: Apple Shortcut + Back Tap** (about 30 minutes, no code, no Mac needed)
- Shortcut: *Get Clipboard → Get Contents of URL* (non-streaming Gemini or Claude call, key
  in the shortcut) → *Get Dictionary Value* → *Copy to Clipboard* → *Show Notification "Fixed"*.
- One shortcut per favourite tone, or one with a *Choose from Menu* step.
- Flow: select text → Copy → double-tap the back of the phone → Paste.
- I'll write step-by-step build instructions with the exact JSON body in `ios/SHORTCUT.md`.

**6b. Real: custom keyboard extension (Swift)**
- A container app (settings + key) plus a Keyboard Extension. *Allow Full Access* is required for
  network access. The key is shared through a Keychain access group.
- The keyboard is a compact row (tone chips + Fix button) above a normal QWERTY layout, or a small
  "switch back to my keyboard" globe key.
- It reads text through `textDocumentProxy.documentContextBeforeInput/AfterInput`. iOS only exposes
  part of long texts, so the limit is documented. It replaces text with `deleteBackward()` ×N
  followed by `insertText()`.
- Uses `shared/*.json` bundled into the app, and URLSession with streaming bytes.
- **Sideloading caveat**: with a free Apple ID, Xcode-installed apps expire after **7 days** and must
  be re-installed. A paid developer account ($99/yr) gives 1 year. This decides whether 6b is
  worth building, so we'll choose when we get there.

---

## 7. Repository layout

```
CLAUDE.md   PLAN.md   TODO.md   README.md
shared/     tones.json  providers.json  API.md
design/     logo.svg  icon-foreground.svg  (mockup source)
android/    (Phase 1)
.github/workflows/android.yml
extension/  (Phase 2, later)
ios/        (Phase 3, later)
```

## 8. Risks and how we handle them

| Risk | Mitigation |
|---|---|
| Some apps ignore `ACTION_SET_TEXT` | Clipboard + paste fallback, then "copied" message |
| Xiaomi/OnePlus kill the service | Checklist step for battery and autostart, plus a status line in Settings |
| A model gets renamed or retired | Edit `providers.json`, or use the custom model id field in Settings |
| The model "answers" a question instead of rewriting it | Prompt says it's text written *for someone else*, plus a test case |
| Long Farsi input → output truncated | Generous max_tokens formula, and the card shows "…cut off" if the output stops at the token limit |
| No device in the cloud session | CI builds plus your on-device checklist, and fixes logged in CLAUDE.md |

## 9. Open questions (need your answer before or during Phase 1)

1. Is "Daily" as defined in §3.2 what you meant?
2. Should the repo stay private? (It will contain no keys either way, because keys live only on the phones.)
3. Do you want a GitHub Release per version, so the APK is downloadable on the phone, or are Actions artifacts enough?
