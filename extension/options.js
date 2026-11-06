// Settings page: provider, model, key (+ Test), tone. Same choices as the Android Settings screen.
import { buildBody, stream, cleanKey } from "./llm.js";

const $ = (id) => document.getElementById(id);
const [tonesCfg, providersCfg] = await Promise.all(["tones.json", "providers.json"].map((f) => fetch(f).then((r) => r.json())));
const cfg = { tones: tonesCfg, providers: providersCfg };
const providers = providersCfg.providers;

let s = await chrome.storage.local.get({ provider: null, tone: null, models: {}, keys: {}, keyOk: {} });
let customMode = false;
let testMsg = null; // {text, ok}

const save = (patch) => { Object.assign(s, patch); return chrome.storage.local.set(patch); };
const providerId = () => (providers[s.provider] ? s.provider : providersCfg.defaultProvider);
const modelId = () => s.models[providerId()] || providers[providerId()].models[0].id;
const toneId = () => (tonesCfg.tones.some((t) => t.id === s.tone) ? s.tone : tonesCfg.defaultTone);

function chip(label, selected, onClick) {
  const b = document.createElement("button");
  b.className = `chip${selected ? " sel" : ""}`;
  b.textContent = label;
  b.addEventListener("click", onClick);
  return b;
}

function render() {
  const pid = providerId(), p = providers[pid], model = modelId();
  if (!p.models.some((m) => m.id === model)) customMode = true;

  $("providers").replaceChildren(...Object.entries(providers).map(([id, o]) =>
    chip(o.label.split(" ").pop(), id === pid, () => { customMode = false; testMsg = null; save({ provider: id }).then(render); })));

  $("models").replaceChildren(
    ...p.models.map((m) => chip(m.label, !customMode && m.id === model, () => {
      customMode = false; save({ models: { ...s.models, [pid]: m.id } }).then(render);
    })),
    chip("Custom…", customMode, () => { customMode = true; render(); $("custom").focus(); }));
  $("custom").hidden = !customMode;
  $("custom").placeholder = `Model id, e.g. ${p.models[0].id}`;
  if (customMode && document.activeElement !== $("custom")) $("custom").value = p.models.some((m) => m.id === model) ? "" : model;

  $("keyLabel").textContent = `${p.label.split(" ").pop()} API key`;
  if (document.activeElement !== $("key")) $("key").value = s.keys[pid] || "";
  $("keyHelp").href = p.keyHelp;
  $("keyHelp").hidden = !p.keyHelp;
  const st = $("testStatus");
  st.hidden = !testMsg;
  if (testMsg) { st.textContent = testMsg.text; st.className = `status ${testMsg.ok ? "ok" : "err"}`; }

  $("tones").replaceChildren(...tonesCfg.tones.map((t) => chip(t.label, t.id === toneId(), () => save({ tone: t.id }).then(render))));
}

$("custom").addEventListener("input", (e) => {
  const v = e.target.value.trim();
  if (v) save({ models: { ...s.models, [providerId()]: v } });
});
$("key").addEventListener("input", (e) => {
  const pid = providerId();
  testMsg = null;
  save({ keys: { ...s.keys, [pid]: cleanKey(e.target.value) }, keyOk: { ...s.keyOk, [pid]: false } }).then(render);
});

$("test").addEventListener("click", async () => {
  const pid = providerId(), p = providers[pid], key = cleanKey(s.keys[pid]), model = modelId();
  if (!key) { testMsg = { text: "Enter a key first.", ok: false }; return render(); }
  $("test").disabled = true;
  testMsg = { text: "Testing…", ok: true }; render();
  const t0 = performance.now(); let first = 0;
  const body = buildBody(cfg, p, model, tonesCfg.tones.find((t) => t.id === "grammar"), "helo, how are you");
  const out = await stream(p, model, key, body, new AbortController().signal, () => { first ||= performance.now(); });
  const ok = !out.error && out.text.trim() !== "";
  await save({ keyOk: { ...s.keyOk, [pid]: ok } });
  testMsg = ok ? { text: `Key works · first word in ${((first - t0) / 1000).toFixed(1)} s`, ok } : { text: out.error || "No answer from the model.", ok };
  $("test").disabled = false;
  render();
});

// Keep in step with changes made elsewhere (e.g. a tone picked from the bubble menu).
chrome.storage.onChanged.addListener((changes) => {
  for (const [k, { newValue }] of Object.entries(changes)) s[k] = newValue;
  render();
});

const cmd = (await chrome.commands.getAll()).find((c) => c.name === "rewrite");
$("shortcut").textContent = cmd?.shortcut || "not set";
$("shortcuts").addEventListener("click", (e) => { e.preventDefault(); chrome.tabs.create({ url: "chrome://extensions/shortcuts" }); });
$("version").textContent = `Tone Fixer ${chrome.runtime.getManifest().version}`;
render();
