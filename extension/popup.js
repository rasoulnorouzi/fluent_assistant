// Toolbar popup: switch Tone Fixer off (everywhere or on this site), where the bubble sits, and
// "translate this page to Persian" (sent to the page's top frame, which does the work).
import { isNewerVersion } from "./llm.js";

const $ = (id) => document.getElementById(id);
const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
let host = "";
try { const u = new URL(tab?.url || ""); if (/^https?:$/.test(u.protocol)) host = u.hostname; } catch { /* no url */ }
let s = await chrome.storage.local.get({ enabled: true, hiddenSites: [], bubbleSpot: "corner", latest: null });

// Can the page run Tone Fixer at all (not chrome:// pages, the Web Store, …)? It answers if so.
const pageState = () => chrome.tabs.sendMessage(tab.id, { type: "pageState" }, { frameId: 0 }).catch(() => null);
let state = tab?.id != null ? await pageState() : null;

function render() {
  const on = s.enabled !== false;
  $("enabled").setAttribute("aria-checked", String(on));
  $("site").textContent = host || "not available on this page";
  $("here").setAttribute("aria-checked", String(on && !!host && !s.hiddenSites.includes(host)));
  $("here").disabled = !on || !host;
  for (const b of $("spot").children) b.classList.toggle("sel", b.dataset.v === s.bubbleSpot);
  $("page").disabled = !on || !state;
  $("page").textContent = state?.on ? "برگشت به متن اصلی" : "ترجمه‌ی این صفحه به فارسی";
  $("note").className = "note";
  $("note").textContent = !on ? "Tone Fixer is off." : !state ? "Tone Fixer can't run on this page (reload it if it was open before)."
    : "ترجمه‌ی متن انتخاب‌شده: کلیک راست ← «ترجمه به فارسی»";
  $("note").dir = on && state ? "rtl" : "ltr";
  $("update").hidden = !(s.latest?.version && isNewerVersion(s.latest.version, chrome.runtime.getManifest().version));
}

async function save(patch) { Object.assign(s, patch); await chrome.storage.local.set(patch); render(); }

$("enabled").onclick = () => save({ enabled: s.enabled === false });
$("here").onclick = () => {
  if (!host) return;
  const hidden = s.hiddenSites.includes(host) ? s.hiddenSites.filter((x) => x !== host) : [...s.hiddenSites, host];
  save({ hiddenSites: hidden });
};
for (const b of $("spot").children) b.onclick = () => save({ bubbleSpot: b.dataset.v });
$("page").onclick = async () => {
  const r = await chrome.tabs.sendMessage(tab.id, { type: "translatePage" }, { frameId: 0 }).catch(() => null);
  if (r) window.close();
};
$("settings").onclick = () => { chrome.runtime.openOptionsPage(); window.close(); };
$("update").onclick = () => { chrome.runtime.openOptionsPage(); window.close(); };
render();
