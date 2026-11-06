// Everything about talking to the LLM, the same as android/…/Llm.kt and shared/API.md:
// request bodies, streaming (SSE) parsing and error messages. Pure ES module, so the service
// worker uses it and `node --test` checks it against the same recorded streams as Android.

/** Max output tokens, relative to the input length (providers.json → maxTokens). */
export function maxTokens(cfg, chars) {
  const m = cfg.providers.maxTokens;
  const n = Math.ceil(chars / m.charsPerToken) * m.multiplier + m.base;
  return Math.min(Math.max(n, m.min), m.cap);
}

/** Request body. A model id that isn't in providers.json (custom) gets no `extra`. */
export function buildBody(cfg, provider, modelId, tone, text) {
  const system = cfg.tones.system.replace("{style}", () => tone.style);
  const max = maxTokens(cfg, text.length);
  // The text goes inside markers (tones.json → userTemplate), so the model treats it as text to
  // rewrite, never as a question or an instruction to follow. A function replacement keeps "$&" literal.
  text = cfg.tones.userTemplate ? cfg.tones.userTemplate.replace("{text}", () => text) : text;
  let body;
  if (provider.format === "gemini") {
    body = {
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: "user", parts: [{ text }] }],
      generationConfig: { maxOutputTokens: max },
    };
  } else if (provider.format === "claude") {
    body = { model: modelId, max_tokens: max, stream: true, system, messages: [{ role: "user", content: text }] };
  } else throw new Error(`Unknown format ${provider.format}`);
  const model = provider.models.find((m) => m.id === modelId);
  if (model?.extra) deepMerge(body, model.extra);
  return body;
}

const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

/** Objects merge key by key; anything else replaces. Mutates and returns `base`. */
export function deepMerge(base, extra) {
  for (const [k, v] of Object.entries(extra)) {
    if (isObj(v) && isObj(base[k])) deepMerge(base[k], v);
    else base[k] = structuredClone(v);
  }
  return base;
}

export const requestUrl = (provider, modelId) => provider.url.replace("{model}", encodeURIComponent(modelId));

/** Request headers: key + fixed headers + the ones only browsers need (Claude's direct-access flag). */
export const requestHeaders = (provider, key) => ({
  "content-type": "application/json",
  [provider.keyHeader]: key,
  ...(provider.headers || {}),
  ...(provider.browserHeaders || {}),
});

/** The JSON after `data:`, or null for any other SSE line. */
export function sseData(line) {
  const l = line.replace(/\r$/, "");
  if (!l.startsWith("data:")) return null;
  const d = l.slice(5).trim();
  return d === "" || d === "[DONE]" ? null : d;
}

/** One parsed `data:` object → {text, stop, error}. stop = "done" | "cut" | "blocked". */
export function parseChunk(format, data) {
  if (format === "gemini") {
    if (data.error) return { text: "", error: data.error.message || `Error ${data.error.code}` };
    if (data.promptFeedback?.blockReason) return { text: "", stop: "blocked" };
    const c = data.candidates?.[0];
    const text = (c?.content?.parts || []).filter((p) => !p.thought).map((p) => p.text || "").join("");
    const r = c?.finishReason || "";
    const stop = r === "" || r === "FINISH_REASON_UNSPECIFIED" ? null : r === "STOP" ? "done" : r === "MAX_TOKENS" ? "cut" : "blocked";
    return { text, stop };
  }
  if (format === "claude") {
    switch (data.type) {
      case "content_block_delta":
        return { text: data.delta?.type === "text_delta" ? data.delta.text : "" };
      case "message_delta": {
        const r = data.delta?.stop_reason;
        return { text: "", stop: r === "max_tokens" ? "cut" : r === "refusal" ? "blocked" : null };
      }
      case "message_stop":
        return { text: "", stop: "done" };
      case "error":
        return { text: "", error: data.error?.message || "Unknown error" };
      default:
        return { text: "" };
    }
  }
  throw new Error(`Unknown format ${format}`);
}

/** User-facing message for a non-200 response (shared/API.md §5). */
export function errorMessage(status, body) {
  let detail = null;
  try { detail = JSON.parse(body).error.message || null; } catch { /* not JSON */ }
  const tail = detail ? `: ${detail}` : ".";
  if (status === 400) return "The provider rejected the request" + tail;
  if (status === 401 || status === 403) return "API key not accepted. Check it in Settings.";
  if (status === 404) return "Model not found. Pick another in Settings.";
  if (status === 429) return "Too many requests or quota used up. Try again in a moment.";
  if (status >= 500) return "The provider is having trouble. Try again.";
  return `Request failed (${status})` + tail;
}

/**
 * Streams one rewrite. Calls onText(piece) as text arrives. Resolves to {text, stop, error};
 * stop = "cancelled" when `signal` aborts. Never throws.
 */
export async function stream(provider, modelId, key, body, signal, onText) {
  let text = "";
  let stop = null;
  if (/[^\x21-\x7e]/.test(key)) return { text, stop, error: "The API key has an invalid character. Paste it again in Settings." };
  // Up to 25 s for the answer to start, then 20 s of silence ends it (Android: 5 s connect + 20 s read).
  const timeout = new AbortController();
  let timer = setTimeout(() => timeout.abort(), 25_000);
  const both = AbortSignal.any([signal, timeout.signal]);
  try {
    const res = await fetch(requestUrl(provider, modelId), {
      method: "POST", headers: requestHeaders(provider, key), body: JSON.stringify(body), signal: both,
    });
    if (res.status !== 200) return { text, stop: null, error: errorMessage(res.status, await res.text().catch(() => "")) };
    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let buf = "";
    read: while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      clearTimeout(timer); timer = setTimeout(() => timeout.abort(), 20_000);
      buf += value;
      let nl;
      while ((nl = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, nl); buf = buf.slice(nl + 1);
        const data = sseData(line);
        if (data == null) continue;
        const c = parseChunk(provider.format, JSON.parse(data));
        if (c.error) return { text, stop: null, error: c.error };
        if (c.text) { text += c.text; onText(c.text); }
        // Claude sends max_tokens/refusal in message_delta, then message_stop ("done"): keep the first.
        if (c.stop && stop !== "cut" && stop !== "blocked") stop = c.stop;
        if (c.stop === "done") { reader.cancel().catch(() => {}); break read; }
      }
    }
    if (stop === "blocked") return { text, stop, error: "The provider blocked this text." };
    return { text, stop: stop || "done", error: null };
  } catch (e) {
    if (signal.aborted) return { text, stop: "cancelled", error: null };
    if (timeout.signal.aborted) return { text, stop: null, error: "The provider took too long. Try again." };
    if (e instanceof SyntaxError) return { text, stop: null, error: "Unexpected response from the provider." };
    return { text, stop: null, error: "No connection." };
  } finally {
    clearTimeout(timer);
  }
}

/** Keys are plain ASCII; drop spaces, zero-width and other invisible characters picked up when pasting. */
export const cleanKey = (key) => [...(key || "")].filter((ch) => ch.charCodeAt(0) >= 0x21 && ch.charCodeAt(0) <= 0x7e).join("");

/** Does the text contain Persian/Arabic script? (Shows the "from Farsi" tag.) */
export const looksFarsi = (s) => /[؀-ۿﭐ-﷿]/.test(s);

/** Models often drop or add outer whitespace; keep the user's original spacing. */
export function keepOuterSpace(input, output) {
  const lead = input.match(/^\s*/)[0];
  const trail = input.match(/\s*$/)[0];
  return lead + output.trim() + trail; // input is never blank here, so lead and trail don't overlap
}

/**
 * The models shown for a provider: the built-in ones until the user adds or removes some. Never
 * empty. A model picked with the old "Custom…" field stays in the list. `s` = stored settings.
 */
export function modelList(provider, providerId, s) {
  const saved = s.modelLists?.[providerId];
  if (Array.isArray(saved) && saved.length) return saved;
  const picked = s.models?.[providerId];
  return [...new Set([...provider.models.map((m) => m.id), ...(picked ? [picked] : [])])];
}

/** The model a rewrite uses: the picked one if it's still in the list, else the first. */
export function currentModel(provider, providerId, s) {
  const list = modelList(provider, providerId, s);
  return list.includes(s.models?.[providerId]) ? s.models[providerId] : list[0];
}
