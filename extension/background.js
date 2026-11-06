// Service worker: holds the settings, makes the API calls (streaming) and sends the text back to
// the page over a port. Pages never see the API key.
import { buildBody, stream, cleanKey, currentModel, modelList, chooseConfig, isNewerVersion } from "./llm.js";

let cfgPromise = null;
/**
 * {tones, providers}: the copies shipped in the extension (pack.sh), or newer ones downloaded from
 * the repo's `updates` branch (see checkUpdates), whichever chooseConfig picks.
 */
function config() {
  cfgPromise ??= (async () => {
    const [tones, providers] = await Promise.all(["tones.json", "providers.json"].map((f) => fetch(chrome.runtime.getURL(f)).then((r) => r.json())));
    const { remoteCfg } = await chrome.storage.local.get("remoteCfg");
    return chooseConfig({ tones, providers }, remoteCfg);
  })();
  return cfgPromise;
}

// ---------- updates: newer tones/models are used by themselves; a new version shows "NEW" ----------

const UPDATES = "https://raw.githubusercontent.com/rasoulnorouzi/fluent_assistant/updates/";

async function checkUpdates(force = false) {
  const { lastCheck = 0 } = await chrome.storage.local.get("lastCheck");
  if (!force && Date.now() - lastCheck < 6 * 3600_000) return;
  await chrome.storage.local.set({ lastCheck: Date.now() });
  // The query string sidesteps caches between us and GitHub (raw files are cached ~5 min).
  // Kept as text (valid JSON only): chrome.storage would re-sort object keys, e.g. the provider order.
  const get = (path) => fetch(`${UPDATES}${path}?t=${Math.floor(Date.now() / 60_000)}`, { cache: "no-store" })
    .then((r) => (r.ok ? r.text() : null)).then((t) => (t && JSON.parse(t) ? t : null)).catch(() => null);
  const [tones, providers, latest] = await Promise.all([get("shared/tones.json"), get("shared/providers.json"), get("extension/latest.json")]);
  if (tones && providers) { await chrome.storage.local.set({ remoteCfg: { tones, providers, at: Date.now() } }); cfgPromise = null; }
  const info = latest && JSON.parse(latest);
  if (info?.version) await chrome.storage.local.set({ latest: info });
  await showBadge();
}

async function showBadge() {
  const { latest } = await chrome.storage.local.get("latest");
  const fresh = latest && isNewerVersion(latest.version, chrome.runtime.getManifest().version);
  await chrome.action.setBadgeText({ text: fresh ? "NEW" : "" });
  if (fresh) await chrome.action.setBadgeBackgroundColor({ color: "#A35A3A" });
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

/** Models of every service with a saved key (or just the current service if none has one yet). */
function choices(cfg, s) {
  const all = Object.entries(cfg.providers.providers);
  const withKey = all.filter(([id]) => cleanKey(s.keys[id])).length ? all.filter(([id]) => cleanKey(s.keys[id])) : all.filter(([id]) => id === pick(cfg, s).providerId);
  return withKey.flatMap(([id, p]) => modelList(p, id, s).map((m) => ({
    provider: id, model: m, label: p.models.find((x) => x.id === m)?.label || m, service: p.label.split(" ").pop(),
  })));
}
const labelOf = (provider, model) => provider.models.find((x) => x.id === model)?.label || model;

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
    send({ type: "using", label: labelOf(provider, model) }); // the card shows which model writes this
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
        checkUpdates(); // at most every 6 hours
        return reply({
          tones: cfg.tones.tones.map(({ id, label, letter }) => ({ id, label, letter })), tone: cur.tone.id,
          models: choices(cfg, s), current: { provider: cur.providerId, model: cur.model, label: labelOf(cur.provider, cur.model) },
        });
      case "setModel": // picked in the bubble menu or the card
        await chrome.storage.local.set({ provider: msg.provider, models: { ...s.models, [msg.provider]: msg.model } });
        return reply({});
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
      case "checkUpdates": // from the options page
        await checkUpdates(msg.force);
        return reply(await chrome.storage.local.get(["latest", "lastCheck", "remoteCfg"]));
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
chrome.runtime.onInstalled.addListener(({ reason }) => {
  if (reason === "install") chrome.runtime.openOptionsPage();
  checkUpdates(true);
});
chrome.runtime.onStartup.addListener(() => checkUpdates());
