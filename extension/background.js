// Service worker: holds the settings, makes the API calls (streaming) and sends the text back to
// the page over a port. Pages never see the API key.
import { buildBody, stream, cleanKey, currentModel } from "./llm.js";

let cfgPromise = null;
/** {tones: shared/tones.json, providers: shared/providers.json}, copied in by pack.sh. */
export function config() {
  cfgPromise ??= Promise.all(["tones.json", "providers.json"].map((f) => fetch(chrome.runtime.getURL(f)).then((r) => r.json())))
    .then(([tones, providers]) => ({ tones, providers }));
  return cfgPromise;
}

/** Stored settings: {provider, tone, models: {gemini: picked id}, modelLists: {gemini: [ids]}, keys: {gemini: key}}. */
const settings = () => chrome.storage.local.get({ provider: null, tone: null, models: {}, modelLists: {}, keys: {} });

function pick(cfg, s) {
  const providers = cfg.providers.providers;
  const providerId = providers[s.provider] ? s.provider : cfg.providers.defaultProvider;
  const provider = providers[providerId];
  const tones = cfg.tones.tones;
  const tone = tones.find((t) => t.id === s.tone) || tones.find((t) => t.id === cfg.tones.defaultTone);
  const model = currentModel(provider, providerId, s);
  return { providerId, provider, tone, model, key: cleanKey(s.keys[providerId]) };
}

// ---------- rewrite (port "rewrite"): {text, tone} in; {type:"text"|"end", …} out ----------

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== "rewrite") return;
  const abort = new AbortController();
  port.onDisconnect.addListener(() => abort.abort());
  port.onMessage.addListener(async ({ text, tone: toneId }) => {
    const send = (m) => { try { port.postMessage(m); } catch { /* page closed */ } };
    const cfg = await config();
    const s = await settings();
    if (toneId) { s.tone = toneId; chrome.storage.local.set({ tone: toneId }); }
    const { provider, tone, model, key } = pick(cfg, s);
    if (!key) return send({ type: "end", error: `Add your ${provider.label.split(" ").pop()} API key in Tone Fixer settings.`, settings: true });
    const out = await stream(provider, model, key, buildBody(cfg, provider, model, tone, text), abort.signal,
      (piece) => send({ type: "text", text: piece }));
    if (out.stop !== "cancelled") send({ type: "end", ...out, settings: !!out.error?.includes("Settings") });
  });
});

// ---------- small requests from the page and the options page ----------

let lastWarm = { url: "", at: 0 };

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  (async () => {
    const cfg = await config();
    const s = await settings();
    const cur = pick(cfg, s);
    switch (msg.type) {
      case "state": // what the page needs to draw the bubble, menu and card
        return reply({ tones: cfg.tones.tones.map(({ id, label, letter }) => ({ id, label, letter })), tone: cur.tone.id });
      case "warm": { // open DNS + TLS early, so the real request reuses the connection
        const url = cur.provider.warmUrl;
        if (url !== lastWarm.url || Date.now() - lastWarm.at > 60_000) {
          lastWarm = { url, at: Date.now() };
          fetch(url, { method: "HEAD" }).catch(() => {});
        }
        return reply({});
      }
      case "options":
        chrome.runtime.openOptionsPage();
        return reply({});
      default:
        return reply({});
    }
  })();
  return true; // reply comes asynchronously
});

// Toolbar icon → settings. Keyboard shortcut → rewrite in the focused text box of the active tab.
chrome.action.onClicked.addListener(() => chrome.runtime.openOptionsPage());
chrome.commands.onCommand.addListener((command, tab) => {
  if (command === "rewrite" && tab?.id != null) chrome.tabs.sendMessage(tab.id, { type: "run" }).catch(() => {});
});
chrome.runtime.onInstalled.addListener(({ reason }) => { if (reason === "install") chrome.runtime.openOptionsPage(); });
