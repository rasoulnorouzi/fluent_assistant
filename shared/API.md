# Shared API contract

Every platform (Android, browser extension, iOS) does the same thing with the two JSON files in
this folder. If you write a new client, this page is all you need.

## 1. Build the prompt

```
system  = tones.system with "{style}" replaced by tone.style
user    = the text from the field (selection if any, else the whole field)
```

The text is sent as-is (not trimmed, so line breaks are kept). An empty or whitespace-only field
is not sent.

## 2. max_tokens

```
m = providers.maxTokens
maxTokens = clamp(ceil(len(text) / m.charsPerToken) * m.multiplier + m.base, m.min, m.cap)
```

`len` counts UTF-16 chars (Kotlin/JS `length`; Swift `utf16.count`).

## 3. Request

URL: `provider.url` with `{model}` replaced by the model id.
Headers: `content-type: application/json`, `provider.keyHeader: <API key>`, every entry of
`provider.headers`, and in a browser also every entry of `provider.browserHeaders`.
Body: the format's base body below, then `model.extra` **deep-merged** on top (objects merge
key by key; anything else replaces).

**format `gemini`**
```json
{
  "systemInstruction": { "parts": [ { "text": "<system>" } ] },
  "contents": [ { "role": "user", "parts": [ { "text": "<user>" } ] } ],
  "generationConfig": { "maxOutputTokens": 512 }
}
```

**format `claude`**
```json
{
  "model": "<model id>",
  "max_tokens": 512,
  "stream": true,
  "system": "<system>",
  "messages": [ { "role": "user", "content": "<user>" } ]
}
```

Don't send `temperature`, `top_p` or `top_k` to Claude: they are rejected on API version `2026-10-21`.

## 4. Streaming response (Server-Sent Events)

Read the body line by line. Strip a trailing `\r`. Only lines starting with `data:` matter; the
rest (`event:`, blank lines, `: comments`) are ignored. Parse what follows `data:` as JSON.

**format `gemini`**: every data object:
- text: concatenate `candidates[0].content.parts[i].text`, skipping parts with `"thought": true`
- end: `candidates[0].finishReason` is set. `"STOP"` = done; `"MAX_TOKENS"` = cut off; anything
  else (`SAFETY`, `RECITATION`, …) = blocked.
- error: an object with `error` → `error.code`, `error.message`.

**format `claude`**: switch on `type`:
- `content_block_delta` with `delta.type == "text_delta"` → append `delta.text`
- `message_delta` → `delta.stop_reason`: `"end_turn"` done, `"max_tokens"` cut off,
  `"refusal"` blocked
- `message_stop` → done
- `error` → `error.type`, `error.message`
- everything else (`message_start`, `content_block_start/stop`, `ping`, `thinking` blocks) is ignored.

## 5. Errors before streaming

If the HTTP status isn't 200, the body is plain JSON (not SSE):
Gemini `{"error":{"code":404,"message":"…","status":"NOT_FOUND"}}`,
Claude `{"type":"error","error":{"type":"authentication_error","message":"…"}}`.

Show the user one short message for each:

| Status | Message |
|---|---|
| 400 | "The provider rejected the request" + provider message (often a bad model setting) |
| 401 / 403 | "API key not accepted. Check it in Settings." |
| 404 | "Model not found. Pick another in Settings." |
| 429 | "Too many requests or quota used up. Try again in a moment." |
| 5xx / 529 | "The provider is having trouble. Try again." |
| network | "No connection." |

## 6. Pre-warm

When a text field gets focus (at most once every 60 s), send a keyless `GET provider.warmUrl` and
throw the response away. It only opens DNS + TCP + TLS so the real request can reuse the
connection.
