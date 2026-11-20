// Settings page: provider, model, key (+ Test), tone. Same choices as the Android Settings screen.
import { applyPrompts, withStyle, buildBody, stream, cleanKey, modelList, currentModel, chooseConfig, isNewerVersion } from "./llm.js";

const $ = (id) => document.getElementById(id);
// Same choice as the service worker: shipped tones/models, or newer downloaded ones.
const [bundledTones, bundledProviders] = await Promise.all(["tones.json", "providers.json"].map((f) => fetch(f).then((r) => r.json())));
const cfg = chooseConfig({ tones: bundledTones, providers: bundledProviders }, (await chrome.storage.local.get("remoteCfg")).remoteCfg);
const tonesCfg = cfg.tones, providersCfg = cfg.providers;
const providers = providersCfg.providers;

let s = await chrome.storage.local.get({ provider: null, tone: null, models: {}, modelLists: {}, keys: {}, keyOk: {}, hiddenSites: [], prompts: {} });
let editModels = false;
let testMsg = null; // {text, ok}

const save = (patch) => { Object.assign(s, patch); return chrome.storage.local.set(patch); };
const providerId = () => (providers[s.provider] ? s.provider : providersCfg.defaultProvider);
const modelId = () => currentModel(providers[providerId()], providerId(), s);
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
  const ids = modelList(p, pid, s);
  const labelOf = (id) => p.models.find((m) => m.id === id)?.label || id;

  $("nowUsing").textContent = `Now using: ${labelOf(model)}`;
  const withKeys = Object.entries(providers).filter(([id]) => cleanKey(s.keys[id])).map(([, o]) => o.label.split(" ").pop());
  $("nowKeys").textContent = withKeys.length > 1 ? `Keys saved for ${withKeys.join(" and ")}. Right-click the bubble to switch model, or click the model name in the result card.`
    : withKeys.length ? `Key saved for ${withKeys[0]}. Add a key for another service to switch between them from the bubble.` : "No API key yet: add one below.";

  $("providers").replaceChildren(...Object.entries(providers).map(([id, o]) =>
    chip(o.label.split(" ").pop(), id === pid, () => { editModels = false; testMsg = null; save({ provider: id }).then(render); })));

  // Edit mode: click a model to remove it (one always stays). Otherwise: click to use it.
  $("models").replaceChildren(
    ...ids.map((id) => {
      const c = chip(editModels ? `${labelOf(id)}  ✕` : labelOf(id), id === model, () => {
        if (!editModels) save({ models: { ...s.models, [pid]: id } }).then(render);
        else if (ids.length > 1) save({ modelLists: { ...s.modelLists, [pid]: ids.filter((x) => x !== id) } }).then(render);
      });
      if (editModels) c.setAttribute("aria-label", `Remove ${labelOf(id)}`);
      return c;
    }),
    chip(editModels ? "Done" : "Edit", false, () => { editModels = !editModels; render(); if (editModels) $("newModel").focus(); }));
  $("modelEdit").hidden = !editModels;
  $("newModel").placeholder = `Model id, e.g. ${p.models[0].id}`;
  $("modelHint").textContent = `Click ✕ to remove a model, or add any ${p.label.split(" ").pop()} model id. Then press Test to check it.`;
  $("restoreModels").parentElement.hidden = JSON.stringify(ids) === JSON.stringify(p.models.map((m) => m.id));

  $("keyLabel").textContent = `${p.label.split(" ").pop()} API key`;
  if (document.activeElement !== $("key")) $("key").value = s.keys[pid] || "";
  $("keyHelp").href = p.keyHelp;
  $("keyHelp").hidden = !p.keyHelp;
  const st = $("testStatus");
  st.hidden = !testMsg;
  if (testMsg) { st.textContent = testMsg.text; st.className = `status ${testMsg.ok ? "ok" : "err"}`; }

  const hidden = s.hiddenSites || [];
  $("hiddenList").replaceChildren(...hidden.map((site) =>
    chip(`${site}  · Show again`, false, () => save({ hiddenSites: hidden.filter((x) => x !== site) }).then(render))));
  $("hiddenNone").hidden = hidden.length > 0;

  $("tones").replaceChildren(...tonesCfg.tones.map((t) => chip(t.label, t.id === toneId(), () => save({ tone: t.id }).then(render))));
}

function addModel() {
  const pid = providerId(), id = $("newModel").value.replace(/\s+/g, "");
  if (!id) return;
  $("newModel").value = "";
  const ids = [...new Set([...modelList(providers[pid], pid, s), id])];
  save({ modelLists: { ...s.modelLists, [pid]: ids }, models: { ...s.models, [pid]: id } }).then(render);
}
$("addModel").addEventListener("click", addModel);
$("newModel").addEventListener("keydown", (e) => { if (e.key === "Enter") addModel(); });
$("restoreModels").addEventListener("click", (e) => {
  e.preventDefault();
  const pid = providerId();
  const { [pid]: _l, ...lists } = s.modelLists; const { [pid]: _m, ...models } = s.models;
  s.modelLists = lists; s.models = models;
  chrome.storage.local.set({ modelLists: lists, models }).then(render);
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
  const body = buildBody(applyPrompts(cfg, s.prompts), p, model, tonesCfg.tones.find((t) => t.id === "grammar"), "helo, how are you");
  const out = await stream(p, model, key, body, new AbortController().signal, () => { first ||= performance.now(); });
  const ok = !out.error && out.text.trim() !== "";
  await save({ keyOk: { ...s.keyOk, [pid]: ok } });
  testMsg = ok ? { text: `Key works · first word in ${((first - t0) / 1000).toFixed(1)} s`, ok } : { text: out.error || "No answer from the model.", ok };
  $("test").disabled = false;
  render();
});

// ---------- Advanced: prompts (hidden behind a link) ----------
// Defaults = the built-in prompts in use (shipped or downloaded); s.prompts holds only the edited ones.

const promptItems = () => [
  { key: "system", title: "Writing (every tone)", def: tonesCfg.system, rows: 14, style: true },
  ...tonesCfg.tones.map((t) => ({ key: `tone:${t.id}`, title: `Tone: ${t.label}`, def: t.style, rows: 4 })),
  { key: "translate", title: "Translation to Persian (selected text)", def: tonesCfg.translate.system, rows: 12, style: true,
    hint: "Keep the rule that the first line names the source language: the card shows it as «از …»." },
  { key: "page", title: "Translation of whole pages", def: tonesCfg.translate.page, rows: 12, style: true,
    hint: "Keep the rules about the JSON array and the ⟦1⟧ marks: the page is put back together from them." },
];
const getOwn = (key) => (key.startsWith("tone:") ? s.prompts?.tones?.[key.slice(5)] : s.prompts?.[key]);
function setOwn(key, value) { // value null = back to the built-in prompt
  const pr = structuredClone(s.prompts || {});
  if (key.startsWith("tone:")) {
    pr.tones = { ...(pr.tones || {}) };
    if (value == null) delete pr.tones[key.slice(5)]; else pr.tones[key.slice(5)] = value;
  } else if (value == null) delete pr[key]; else pr[key] = value;
  return save({ prompts: pr });
}

let savedKey = null; // shows "Saved ✓" next to the prompt just saved
function renderPrompts() {
  $("promptList").replaceChildren(...promptItems().map((it) => {
    const own = getOwn(it.key);
    const area = Object.assign(document.createElement("textarea"), { rows: it.rows, value: own ?? it.def, spellcheck: false });
    area.dir = "auto";
    const status = Object.assign(document.createElement("span"), { className: "status ok", textContent: savedKey === it.key ? "Saved ✓" : "" });
    const saveBtn = Object.assign(document.createElement("button"), { className: "btn", textContent: "Save" });
    const reset = chip("Reset to built-in ↺", false, () => { savedKey = null; setOwn(it.key, null).then(renderPrompts); });
    reset.hidden = own == null;
    saveBtn.addEventListener("click", async () => {
      let v = area.value.trim();
      if (it.style && v && !v.includes("{style}")) { v = withStyle(v); area.value = v; }
      await setOwn(it.key, !v || v === it.def.trim() ? null : v);
      savedKey = it.key;
      renderPrompts();
    });
    const head = document.createElement("div"); head.className = "head";
    head.append(Object.assign(document.createElement("strong"), { textContent: it.title }));
    if (own != null) head.append(Object.assign(document.createElement("span"), { className: "tag", textContent: "edited" }));
    const row = document.createElement("div"); row.className = "row";
    row.append(saveBtn, reset, status);
    const box = document.createElement("div"); box.className = "prompt";
    box.append(head, area, row);
    if (it.hint) box.append(Object.assign(document.createElement("p"), { className: "muted", textContent: it.hint, style: "margin:4px 0 0" }));
    return box;
  }));
}
$("advanced").addEventListener("click", (e) => {
  e.preventDefault();
  $("promptsBox").hidden = !$("promptsBox").hidden;
  if (!$("promptsBox").hidden) { renderPrompts(); $("promptsBox").scrollIntoView({ behavior: "smooth" }); }
});
$("resetAll").addEventListener("click", (e) => {
  e.preventDefault();
  if (confirm("Use the built-in prompts again? Your edited prompts will be removed.")) save({ prompts: {} }).then(renderPrompts);
});

// Keep in step with changes made elsewhere (e.g. a tone picked from the bubble menu).
chrome.storage.onChanged.addListener((changes) => {
  for (const [k, { newValue }] of Object.entries(changes)) s[k] = newValue;
  render();
});

const cmd = (await chrome.commands.getAll()).find((c) => c.name === "rewrite");
$("shortcut").textContent = cmd?.shortcut || "not set";
$("shortcuts").addEventListener("click", (e) => { e.preventDefault(); chrome.tabs.create({ url: "chrome://extensions/shortcuts" }); });
// ---------- About & updates ----------

const VERSION = chrome.runtime.getManifest().version;
function renderUpdate(info) {
  const { latest, lastCheck } = info || {};
  const fresh = latest && isNewerVersion(latest.version, VERSION);
  $("version").textContent = `Tone Fixer ${VERSION}`;
  $("updateNew").hidden = !fresh;
  if (fresh) {
    $("newVersion").textContent = latest.version;
    $("newNotes").textContent = latest.notes || "";
    $("download").href = new URL(latest.zip, "https://raw.githubusercontent.com/rasoulnorouzi/fluent_assistant/updates/extension/").href;
  }
  $("updateState").textContent = fresh ? "" : lastCheck
    ? `Up to date. Tones and models update by themselves (last checked ${new Date(lastCheck).toLocaleString()}).`
    : "Tones and models update by themselves.";
}
$("checkNow").addEventListener("click", async (e) => {
  e.preventDefault();
  $("updateState").textContent = "Checking…";
  const info = await chrome.runtime.sendMessage({ type: "checkUpdates", force: true });
  // Reload only if the downloaded files change what's in use (shows the new tones/models).
  const next = info?.remoteCfg && chooseConfig({ tones: bundledTones, providers: bundledProviders }, info.remoteCfg);
  if (next && JSON.stringify(next) !== JSON.stringify(cfg)) location.reload();
  renderUpdate(info);
});
renderUpdate(await chrome.runtime.sendMessage({ type: "checkUpdates", force: true }).catch(() => null));
render();
