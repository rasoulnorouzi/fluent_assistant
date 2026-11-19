// Tone Fixer in web pages: a small bubble on the text box you're typing in. Click = rewrite with the
// last tone, right-click or hold = tone menu. The result streams into a preview card; ✓ replaces the
// text (same flow as Android, PLAN.md §5). Everything lives in a Shadow DOM so pages can't
// restyle it, and is built with DOM calls (no innerHTML), so strict sites (Trusted Types) allow it.
(() => {
  if (window.__toneFixer) return;
  window.__toneFixer = true;

  // ---------- small helpers ----------

  const SVG = "http://www.w3.org/2000/svg";
  function h(tag, attrs = {}, ...kids) {
    const svg = ["svg", "path"].includes(tag);
    const el = svg ? document.createElementNS(SVG, tag) : document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
      else if (v !== false && v != null) el.setAttribute(k, v === true ? "" : v);
    }
    for (const k of kids) if (k != null) el.append(k);
    return el;
  }
  const icon = (d, w) => h("svg", { viewBox: "0 0 24 24", width: w, height: w, "aria-hidden": "true" },
    h("path", { d, fill: "none", stroke: "currentColor", "stroke-width": "2.4", "stroke-linecap": "round", "stroke-linejoin": "round" }));
  const WAVE = "M3.5 12.6c2.4-4.2 5.4-4.2 7.8 0s5.4 4.2 7.8 0"; // same wave as the logo
  const CHECK = "M5 12.5l4.5 4.5L19 7.5";
  const CLOSE = "M6.5 6.5l11 11M17.5 6.5l-11 11";
  const AGAIN = "M19.5 12a7.5 7.5 0 1 1-2.2-5.3M18 3v4.2h-4.2";

  const looksFarsi = (s) => /[؀-ۿﭐ-﷿]/.test(s);
  const keepOuterSpace = (input, out) => input.match(/^\s*/)[0] + out.trim() + input.match(/\s*$/)[0];
  const norm = (s) => s.replace(/\s+/g, " ").trim();

  /** The editable element for a focus target: textarea, text input, or the root of a rich editor. */
  function editableFrom(t) {
    if (!t || t.nodeType !== 1) return null;
    if (t.tagName === "TEXTAREA") return t.readOnly || t.disabled ? null : t;
    if (t.tagName === "INPUT") {
      const type = (t.getAttribute("type") || "text").toLowerCase();
      return (type === "text" || type === "search") && !t.readOnly && !t.disabled ? t : null;
    }
    if (t.isContentEditable) {
      let r = t;
      while (r.parentElement && r.parentElement.isContentEditable) r = r.parentElement;
      return r;
    }
    return null;
  }
  function deepActive() {
    let a = document.activeElement;
    while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
    return a;
  }
  const isValueField = (el) => el.tagName === "TEXTAREA" || el.tagName === "INPUT";
  const currentText = (el) => (isValueField(el) ? el.value : el.innerText);

  // ---------- state ----------

  let tones = [];          // [{id, label, letter}]
  let models = [];         // [{provider, model, label, service}]: services with a saved key
  let current = null;      // {provider, model, label}
  let cardTone = null;     // tone shown in the card
  let tone = "daily";      // last used tone
  let field = null;        // the text box the bubble belongs to
  let snap = null;         // what was read at the click: {el, full, input, start, end, range}
  let port = null;         // running request
  let result = "";
  let streaming = false;
  let ui = null;           // built on first use
  let mode = "rewrite";    // what the card shows: "rewrite" (✓ replaces) or "translate" (to Persian, copy only)
  let trModes = [];        // [{id, label}]: روان / رسمی
  let trMode = "fluent";
  let trText = "";         // the text being translated
  let trRange = null;      // where it was selected (the card sits next to it)

  function send(msg) {
    try { return chrome.runtime.sendMessage(msg).catch(() => null); }
    catch { return Promise.resolve(null); } // extension was reloaded: this page still has the old script
  }
  async function loadState() {
    const s = await send({ type: "state" });
    if (s) {
      tones = s.tones; tone = s.tone; models = s.models || []; current = s.current || null;
      trModes = s.translateModes || []; trMode = s.translateMode || trMode; ui && setLetter();
    }
  }
  try {
    chrome.storage.onChanged.addListener((c) => {
      if (c.tone) { tone = c.tone.newValue; ui && setLetter(); }
      if (c.provider || c.models || c.keys || c.modelLists) loadState(); // model list or choice changed
      if (c.hiddenSites) { hiddenHere = (c.hiddenSites.newValue || []).includes(SITE); schedule(); }
      if (c.bubblePos) { positions = c.bubblePos.newValue || {}; schedule(); }
      if (c.enabled) { enabled = c.enabled.newValue !== false; if (!enabled) { cancel(); hideMenu(); } schedule(); }
      if (c.bubbleSpot) { where = c.bubbleSpot.newValue || "corner"; schedule(); }
      if (c.cornerPos) { cornerPos = c.cornerPos.newValue || null; schedule(); }
    });
  } catch { /* old script after a reload */ }

  // ---------- "Hide on this site" ----------

  /** The site the user sees in the address bar (also inside its iframes, e.g. editors). */
  const SITE = (() => {
    try { const a = location.ancestorOrigins; return new URL(a && a.length ? a[a.length - 1] : location.href).hostname; }
    catch { return location.hostname; }
  })();
  let hiddenHere = false;
  let enabled = true;            // the switch in the toolbar popup: off = no bubble anywhere
  let where = "corner";          // bubble: "corner" of the screen (default) or next to the "box"
  let cornerPos = null;          // corner mode, dragged: {r, b} = distance from the right / bottom edge
  try {
    chrome.storage.local.get({ hiddenSites: [], enabled: true, bubbleSpot: "corner", cornerPos: null }).then((s) => {
      hiddenHere = s.hiddenSites.includes(SITE); enabled = s.enabled !== false; where = s.bubbleSpot; cornerPos = s.cornerPos; schedule();
    });
  } catch { /* old script after a reload */ }

  // Where the user dragged the bubble, per site: offset from the box's top-right corner.
  let positions = {};
  try { chrome.storage.local.get({ bubblePos: {} }).then((s) => { positions = s.bubblePos; schedule(); }); } catch { /* old script */ }

  async function savePosition() {
    const b = ui.bubble.getBoundingClientRect();
    if (where === "corner") { // one spot for every site, kept as a distance from the right / bottom edge
      cornerPos = { r: Math.round(document.documentElement.clientWidth - b.right), b: Math.round(window.innerHeight - b.bottom) };
      try { await chrome.storage.local.set({ cornerPos }); } catch { /* old script */ }
      return;
    }
    if (!field) return;
    const r = field.getBoundingClientRect();
    positions = { ...positions, [SITE]: { dx: Math.round(b.left - r.right), dy: Math.round(b.top - r.top) } };
    try { await chrome.storage.local.set({ bubblePos: positions }); } catch { /* old script */ }
  }

  async function resetPosition() {
    if (where === "corner") {
      cornerPos = null;
      try { await chrome.storage.local.set({ cornerPos: null }); } catch { /* old script */ }
      schedule(); return;
    }
    const { [SITE]: _gone, ...rest } = positions;
    positions = rest;
    try { await chrome.storage.local.set({ bubblePos: rest }); } catch { /* old script */ }
    schedule();
  }

  async function hideOnThisSite() {
    hiddenHere = true;
    cancel();
    if (ui) ui.bubble.hidden = true;
    try {
      const { hiddenSites } = await chrome.storage.local.get({ hiddenSites: [] });
      await chrome.storage.local.set({ hiddenSites: [...new Set([...hiddenSites, SITE])] });
    } catch { /* old script after a reload */ }
  }

  // ---------- UI ----------

  const CSS = `
  :host { all: initial; }
  * { box-sizing: border-box; }
  .tf { --bg:#F3F0E8; --card:#FBF8F1; --ink:#2B2A26; --muted:#6B675E; --line:#CFC8B8; --primary:#4F6B55;
        --on-primary:#FBF8F1; --sage:#6F8D75; --clay:#C47A5A; --clay-deep:#A35A3A; --warn:#A35A3A;
        font: 14px/1.4 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: var(--ink); }
  @media (prefers-color-scheme: dark) {
    .tf { --bg:#1F2320; --card:#2A302B; --ink:#ECE7DA; --muted:#A9A397; --line:#4A524B; --primary:#9BBBA2;
          --on-primary:#1F2320; --clay:#D98E6E; --warn:#E7A285; }
  }
  button { font: inherit; color: inherit; border: 0; background: none; cursor: pointer; padding: 0; margin: 0; }
  button:focus-visible { outline: 2px solid var(--clay); outline-offset: 2px; }

  .bubble { position: fixed; width: 34px; height: 34px; border-radius: 50%; background: var(--sage); color: #FBF8F1;
            display: grid; place-items: center; box-shadow: 0 2px 8px rgba(0,0,0,.22); opacity: .85; /* faded, never faint */
            transition: opacity .15s, transform .15s; touch-action: none; }
  .bubble:hover, .bubble.on { opacity: 1; }
  .bubble:active { transform: scale(.94); }
  .bubble.dragging { opacity: 1; cursor: grabbing; transform: scale(1.06); transition: none; }
  .badge { position: absolute; top: -5px; right: -6px; min-width: 17px; height: 17px; border-radius: 9px;
           background: var(--clay-deep); color: #FBF8F1; font: 700 10px/17px system-ui, sans-serif; text-align: center;
           padding: 0 4px; box-shadow: 0 0 0 2px var(--card); }
  .bubble.working { background: #4F6B55; opacity: 1; animation: pulse 1.1s ease-in-out infinite; }
  .bubble.error { box-shadow: 0 0 0 3px var(--clay-deep), 0 2px 8px rgba(0,0,0,.22); opacity: 1; }
  .bubble.shake { animation: shake .35s; }
  @keyframes pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(201,214,203,.9), 0 2px 8px rgba(0,0,0,.22); }
                     50% { box-shadow: 0 0 0 6px rgba(201,214,203,.35), 0 2px 8px rgba(0,0,0,.22); } }
  @keyframes shake { 0%,100% { translate: 0 } 25% { translate: -4px } 75% { translate: 4px } }

  .menu { position: fixed; width: 210px; padding: 6px; border-radius: 18px; background: var(--card);
          box-shadow: 0 10px 30px rgba(0,0,0,.2); }
  .menu .head { font: 700 11px/1 system-ui, sans-serif; letter-spacing: .08em; color: var(--muted); padding: 8px 10px 8px; }
  .row { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 36px; padding: 0 8px; border-radius: 12px; text-align: start; }
  .row:hover { background: color-mix(in srgb, var(--ink) 7%, transparent); }
  .row.sel { background: var(--primary); color: var(--on-primary); }
  .letter { width: 24px; height: 24px; border-radius: 50%; display: grid; place-items: center; font: 700 12px/1 system-ui, sans-serif;
            background: var(--bg); color: var(--primary); flex: none; }
  .row.sel .letter { background: var(--on-primary); color: var(--primary); }
  .sep { height: 1px; background: var(--line); margin: 6px 8px; }
  .menu { max-height: 80vh; overflow-y: auto; scrollbar-width: none; }
  .dot { width: 12px; height: 12px; border-radius: 50%; border: 2px solid var(--muted); margin: 0 6px; flex: none; }
  .row.sel .dot { background: var(--on-primary); border-color: var(--on-primary); }
  .two { display: flex; flex-direction: column; line-height: 1.25; padding: 4px 0; }
  .two small { font-size: 11px; color: var(--muted); }
  .row.sel .two small { color: var(--on-primary); }
  .row.off { color: var(--muted); }
  .row.off .letter { background: transparent; color: var(--muted); }

  .card { position: fixed; border-radius: 22px; background: var(--card); padding: 8px 10px 12px 16px;
          display: flex; flex-direction: column; max-height: calc(100vh - 16px); box-sizing: border-box;
          box-shadow: 0 12px 36px rgba(0,0,0,.22), 0 0 0 1px color-mix(in srgb, var(--line) 50%, transparent); }
  .top { display: flex; align-items: flex-start; gap: 8px; min-height: 36px; }
  .heading { display: flex; flex-direction: column; min-width: 0; padding-top: 6px; }
  .titlerow { display: flex; align-items: center; gap: 8px; }
  .title { font-weight: 700; font-size: 15px; color: var(--primary); }
  .model { font-size: 12px; color: var(--muted); text-align: start; padding: 2px 0; border-radius: 6px; width: fit-content; }
  .model:not([disabled]):hover { color: var(--ink); }
  .model[disabled] { cursor: default; }
  .tag { font-size: 12px; color: var(--muted); background: var(--bg); border-radius: 10px; padding: 2px 8px; }
  .x { margin-inline-start: auto; width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; color: var(--muted); }
  .x:hover { background: color-mix(in srgb, var(--ink) 8%, transparent); color: var(--ink); }
  .body { font-size: 16px; line-height: 1.45; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 40vh; overflow: auto;
          padding: 2px 6px 2px 0; min-height: 24px; flex: 0 1 auto; user-select: text; }
  .caret { color: var(--clay); }
  .status { font-size: 13px; color: var(--muted); margin-top: 6px; padding-inline-end: 6px; }
  .status.err { color: var(--warn); }
  .status a { color: var(--primary); text-decoration: underline; cursor: pointer; margin-inline-start: 6px; }
  .foot { display: flex; align-items: flex-end; gap: 8px; margin-top: 10px; flex: none; }
  .chips { display: flex; flex-wrap: wrap; gap: 6px; flex: 1; min-width: 0; padding: 2px; } /* every tone in view */
  .chip { flex: none; height: 32px; padding: 0 12px; border-radius: 16px; border: 1px solid var(--line); background: var(--card); white-space: nowrap; }
  .chip:hover { border-color: var(--primary); }
  .chip.sel { background: var(--primary); border-color: var(--primary); color: var(--on-primary); font-weight: 700; cursor: default; }
  .ok { flex: none; width: 44px; height: 44px; border-radius: 50%; background: var(--primary); color: var(--on-primary);
        display: grid; place-items: center; box-shadow: 0 2px 6px rgba(0,0,0,.18); }
  .again { flex: none; width: 44px; height: 44px; border-radius: 50%; background: var(--bg); color: var(--primary); display: grid; place-items: center; }
  .again:disabled { opacity: .35; cursor: default; }
  .ok:disabled { opacity: .35; cursor: default; box-shadow: none; }
  .copy { flex: none; height: 44px; padding: 0 18px; border-radius: 22px; background: var(--primary); color: var(--on-primary); font-weight: 700; }
  .copy:disabled { opacity: .35; cursor: default; }
  .card.fa .body { direction: rtl; text-align: right; font-size: 17px; line-height: 1.8; font-family: system-ui, "Vazirmatn", "Noto Naskh Arabic", "Segoe UI", sans-serif; }
  .card.fa .chips { direction: rtl; }

  .pagebar { position: fixed; top: 10px; left: 50%; translate: -50% 0; display: flex; align-items: center; gap: 4px;
             padding: 4px 4px 4px 14px; border-radius: 20px; background: var(--card); direction: rtl;
             box-shadow: 0 6px 20px rgba(0,0,0,.18), 0 0 0 1px color-mix(in srgb, var(--line) 60%, transparent); }
  .pagebar .msg { font-size: 13px; color: var(--muted); padding: 0 6px; white-space: nowrap; }
  .pagebar .msg.err { color: var(--warn); }
  .pagebar .pb { height: 30px; padding: 0 12px; border-radius: 15px; color: var(--primary); font-weight: 600; font-size: 13px; }
  .pagebar .pb:hover, .pagebar .pbx:hover { background: color-mix(in srgb, var(--ink) 8%, transparent); }
  .pagebar .pbx { width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center; color: var(--muted); }
  [hidden] { display: none !important; }
  `;

  function buildUi() {
    const host = h("tone-fixer-ui");
    host.style.cssText = "all:initial;position:fixed;top:0;left:0;width:0;height:0;z-index:2147483647;";
    const root = host.attachShadow({ mode: "open" });
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(CSS);
    root.adoptedStyleSheets = [sheet];

    // Keep the page's focus and selection in the text box when our buttons are pressed.
    const keepFocus = (e) => e.preventDefault();

    const badge = h("span", { class: "badge" });
    const bubble = h("button", { class: "bubble", hidden: true, "aria-label": "Tone Fixer: rewrite (hold for tones)",
      title: "Tone Fixer · click: rewrite · right-click: pick a tone · drag: move it" }, icon(WAVE, 22), badge);
    // Click = rewrite, hold = menu, drag = move (the spot is remembered for this site).
    let holdTimer = 0, held = false, drag = null;
    bubble.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      if (e.button !== 0) return;
      held = false;
      const r = bubble.getBoundingClientRect();
      drag = { x0: e.clientX, y0: e.clientY, left: r.left, top: r.top, moved: false };
      bubble.setPointerCapture(e.pointerId);
      holdTimer = setTimeout(() => { if (!drag?.moved) { held = true; showMenu(); } }, 450);
    });
    bubble.addEventListener("pointermove", (e) => {
      if (!drag) return;
      const dx = e.clientX - drag.x0, dy = e.clientY - drag.y0;
      if (!drag.moved && Math.hypot(dx, dy) < 5) return;
      if (!drag.moved) { drag.moved = true; clearTimeout(holdTimer); hideMenu(); bubble.classList.add("dragging"); }
      const vw = document.documentElement.clientWidth, vh = window.innerHeight;
      bubble.style.left = `${Math.min(Math.max(2, drag.left + dx), vw - 36)}px`;
      bubble.style.top = `${Math.min(Math.max(2, drag.top + dy), vh - 36)}px`;
    });
    bubble.addEventListener("pointerup", (e) => {
      if (e.button !== 0) return;
      clearTimeout(holdTimer);
      const d = drag; drag = null;
      bubble.classList.remove("dragging");
      if (d?.moved) { savePosition(); return; }
      if (!held) { hideMenu(); run(tone, true); }
    });
    bubble.addEventListener("pointercancel", () => { clearTimeout(holdTimer); drag = null; bubble.classList.remove("dragging"); });
    bubble.addEventListener("contextmenu", (e) => { e.preventDefault(); showMenu(); });
    bubble.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); run(tone, true); } });

    const menu = h("div", { class: "menu", hidden: true, role: "menu", onpointerdown: keepFocus });

    const title = h("span", { class: "title" });
    const model = h("button", { class: "model", onclick: nextModel });
    const tag = h("span", { class: "tag", hidden: true }, "from Farsi");
    const body = h("div", { class: "body" });
    const status = h("div", { class: "status", hidden: true });
    const chips = h("div", { class: "chips" });
    const again = h("button", { class: "again", "aria-label": "Another version", title: "Another version (slightly different)",
      onclick: () => { if (!streaming) run(cardTone || tone, false); } }, icon(AGAIN, 22));
    const copyBtn = h("button", { class: "copy", hidden: true, onclick: copyResult }, "کپی");
    const ok = h("button", { class: "ok", "aria-label": "Replace", title: "Replace the text (or press Alt+Shift+F again)",
      onclick: apply }, icon(CHECK, 24));
    const card = h("div", { class: "card", hidden: true, role: "dialog", "aria-label": "Tone Fixer", onpointerdown: (e) => {
      if (!e.target.closest?.(".body")) e.preventDefault(); // allow selecting the result text
    } },
      h("div", { class: "top" },
        h("div", { class: "heading" }, h("div", { class: "titlerow" }, title, tag), model),
        h("button", { class: "x", "aria-label": "Cancel", title: "Cancel (Esc)", onclick: cancel }, icon(CLOSE, 20))),
      body, status,
      h("div", { class: "foot" }, chips, again, copyBtn, ok));

    // Page translation: a small bar at the top (status · original/Persian · ✕).
    const pageMsg = h("span", { class: "msg" });
    const pageSwap = h("button", { class: "pb", onclick: swapPage }, "متن اصلی");
    const pagebar = h("div", { class: "pagebar", hidden: true, role: "status" },
      h("span", { class: "msg" }, "فارسی"), pageMsg, pageSwap,
      h("button", { class: "pbx", "aria-label": "Close translation", title: "Back to the original page", onclick: stopPage }, icon(CLOSE, 16)));

    root.append(h("div", { class: "tf" }, bubble, menu, card, pagebar));
    document.documentElement.append(host);
    return { host, bubble, badge, menu, card, title, model, tag, body, status, chips, again, copyBtn, ok, pagebar, pageMsg, pageSwap };
  }
  const ensureUi = () => (ui && ui.host.isConnected ? ui : (ui = buildUi()));

  function setLetter() {
    ui.badge.textContent = (tones.find((t) => t.id === tone) || {}).letter || "";
  }

  // ---------- bubble placement ----------

  let raf = 0;
  const schedule = () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; place(); }); };

  function shouldShow() {
    return field && field.isConnected && (cardOpen() && mode === "rewrite" || (enabled && !hiddenHere && currentText(field).trim() !== ""));
  }

  function place() {
    if (!ui) return;
    const b = ui.bubble;
    if (!shouldShow()) { b.hidden = true; return; }
    const r = field.getBoundingClientRect();
    const vw = document.documentElement.clientWidth, vh = window.innerHeight;
    if (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw || r.width === 0) { b.hidden = true; return; }
    if (b.classList.contains("dragging")) return; // the user is moving it
    const S = 34, pad = 6;
    const fits = ([x, y]) => x >= 2 && y >= 2 && x + S <= vw - 2 && y + S <= vh - 2;
    const saved = positions[SITE];
    let spot;
    if (spotMode() === "corner") { // fixed in the bottom-right corner (or where it was dragged), out of the way
      const c = cornerPos || { r: 20, b: 20 };
      spot = [Math.min(Math.max(2, vw - c.r - S), vw - S - 2), Math.min(Math.max(2, vh - c.b - S), vh - S - 2)];
    } else if (saved) { // where the user dragged it, relative to the box's top-right corner
      spot = [Math.min(Math.max(2, r.right + saved.dx), vw - S - 2), Math.min(Math.max(2, r.top + saved.dy), vh - S - 2)];
    } else {
      // Never on the text: outside the box first (one-line boxes: to the right; others: above the
      // top-right corner, then below, then beside), and only inside its corner as a last resort.
      const right = [r.right + pad, r.top + (r.height - S) / 2];
      const above = [r.right - S, r.top - S - pad];
      const below = [r.right - S, r.bottom + pad];
      const beside = [r.right + pad, r.top];
      const inside = [Math.min(r.right, vw) - S - pad, Math.min(r.bottom, vh) - S - pad];
      spot = (r.height < 46 ? [right, above, below] : [above, below, beside]).find(fits) || inside;
    }
    b.style.left = `${Math.max(2, spot[0])}px`;
    b.style.top = `${Math.max(2, spot[1])}px`;
    b.hidden = false;
    if (!ui.menu.hidden) placeMenu();
    if (cardOpen()) placeCard();
  }

  const spotMode = () => where;

  function placeMenu() {
    const br = ui.bubble.getBoundingClientRect();
    const m = ui.menu, vw = document.documentElement.clientWidth, vh = window.innerHeight;
    const mh = m.offsetHeight || 300;
    m.style.left = `${Math.max(8, Math.min(br.right - 210, vw - 218))}px`;
    m.style.top = `${br.top - mh - 8 >= 8 ? br.top - mh - 8 : Math.min(br.bottom + 8, vh - mh - 8)}px`;
  }

  let cardSide = "below";
  function placeCard() {
    const c = ui.card, vw = document.documentElement.clientWidth, vh = window.innerHeight;
    const anchor = cardAnchor()?.getBoundingClientRect();
    const w = Math.min(460, vw - 24);
    c.style.width = `${w}px`;
    const left = anchor ? Math.min(Math.max(12, anchor.left), vw - w - 12) : 12;
    c.style.left = `${left}px`;
    if (!anchor) { c.style.top = "12px"; c.style.bottom = ""; return; }
    // The side is chosen when the card opens, so it doesn't jump while text streams in.
    if (cardSide === "below") { c.style.top = `${Math.min(anchor.bottom + 8, vh - 120)}px`; c.style.bottom = ""; }
    else if (cardSide === "above") { c.style.bottom = `${Math.min(vh - anchor.top + 8, vh - 120)}px`; c.style.top = ""; }
    else { c.style.top = "12px"; c.style.bottom = ""; }
    // Never off screen: a card that would run past the bottom (e.g. an email box low on the page,
    // or a long answer) moves up until all of it is in view; max-height + a scrolling body do the rest.
    const r = c.getBoundingClientRect();
    if (r.bottom > vh - 8) { c.style.top = `${Math.max(8, vh - r.height - 8)}px`; c.style.bottom = ""; }
    else if (r.top < 8) { c.style.top = "8px"; c.style.bottom = ""; }
  }
  /** What the card sits next to: the text box, or the selected text being translated. */
  const cardAnchor = () => (mode === "translate" ? trRange : snap?.el || field);
  function chooseCardSide() {
    const a = cardAnchor();
    if (!a) { cardSide = "top"; return; }
    const r = a.getBoundingClientRect(), vh = window.innerHeight, need = 260;
    cardSide = vh - r.bottom >= need ? "below" : r.top >= need ? "above" : "top";
  }

  // ---------- focus tracking ----------

  function onFocus(target) {
    const el = editableFrom(target);
    if (!el) return;
    if (el !== field) {
      field = el;
      if (!tones.length) loadState();
    }
    ensureUi();
    setLetter();
    send({ type: "warm" });
    schedule();
  }

  document.addEventListener("focusin", (e) => onFocus(e.composedPath()[0]), true);
  document.addEventListener("focusout", () => setTimeout(() => {
    if (!field || cardOpen()) return;
    const a = deepActive();
    if (a && editableFrom(a) === field) return;
    if (ui) { ui.bubble.hidden = true; hideMenu(); }
    field = null;
  }, 150), true);
  document.addEventListener("input", (e) => { if (field && field.contains(e.composedPath()[0])) schedule(); }, true);
  window.addEventListener("scroll", schedule, { capture: true, passive: true });
  window.addEventListener("resize", schedule, { passive: true });
  document.addEventListener("pointerdown", (e) => { if (ui && !ui.menu.hidden && !e.composedPath().includes(ui.host)) hideMenu(); }, true);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && ui && (!ui.menu.hidden || cardOpen())) { e.preventDefault(); e.stopPropagation(); hideMenu(); cancel(); }
  }, true);

  // The page may already have focus in a text box when the script loads.
  if (deepActive()) onFocus(deepActive());

  // ---------- tone menu ----------

  function showMenu() {
    ensureUi();
    const m = ui.menu;
    m.replaceChildren(h("div", { class: "head" }, "REWRITE AS"));
    for (const t of tones) {
      m.append(h("button", { class: `row${t.id === tone ? " sel" : ""}`, role: "menuitem", onclick: () => {
        hideMenu();
        tone = t.id; setLetter();
        run(t.id, !(snap && cardOpen()));
      } }, h("span", { class: "letter" }, t.letter), h("span", {}, t.label)));
    }
    if (models.length > 1) {
      m.append(h("div", { class: "sep" }), h("div", { class: "head" }, "MODEL"));
      for (const x of models) {
        const sel = current && x.provider === current.provider && x.model === current.model;
        m.append(h("button", { class: `row model-row${sel ? " sel" : ""}`, role: "menuitemradio", "aria-checked": String(!!sel), onclick: () => {
          hideMenu(); useModel(x, cardOpen() && !!snap);
        } }, h("span", { class: "dot" }), h("span", { class: "two" }, h("span", {}, x.label), h("small", {}, x.service))));
      }
    }
    m.append(h("div", { class: "sep" }));
    if (where === "corner" ? cornerPos : positions[SITE]) m.append(h("button", { class: "row off", role: "menuitem", onclick: () => { hideMenu(); resetPosition(); } },
      h("span", { class: "letter" }, icon(WAVE, 14)), h("span", {}, "Move back to default")));
    m.append(
      h("button", { class: "row off", role: "menuitem", title: "Bring it back in Tone Fixer settings", onclick: () => { hideMenu(); hideOnThisSite(); } },
        h("span", { class: "letter" }, icon(CLOSE, 14)), h("span", {}, "Hide on this site")));
    m.hidden = false;
    placeMenu();
  }
  const hideMenu = () => { if (ui) ui.menu.hidden = true; };

  // ---------- reading the text ----------

  /** Gmail/Outlook keep the signature and quoted mail inside the editor: never rewrite those. */
  const NOT_MINE = ".gmail_signature, .gmail_quote, .gmail_extra, [data-smartmail], blockquote, #divRplyFwdMsg, #Signature, #appendonsend";

  function read() {
    const el = field;
    if (!el || !el.isConnected) return null;
    if (isValueField(el)) {
      const full = el.value;
      let a = el.selectionStart ?? 0, b = el.selectionEnd ?? 0;
      if (!(a < b)) { a = 0; b = full.length; }
      const input = full.slice(a, b);
      return input.trim() ? { el, full, input, start: a, end: b } : null;
    }
    // Rich editor: the selection inside it, or everything up to the signature / quoted mail.
    const sel = el.getRootNode().getSelection?.() || window.getSelection();
    const full = el.innerText;
    const saved = sel.rangeCount ? sel.getRangeAt(0).cloneRange() : null;
    let range;
    if (saved && !saved.collapsed && el.contains(saved.commonAncestorContainer)) range = saved;
    else {
      range = document.createRange();
      range.selectNodeContents(el);
      const stopAt = el.querySelector(NOT_MINE);
      if (stopAt) range.setEndBefore(stopAt);
    }
    // Selection.toString() keeps the line breaks between blocks (Range.toString() doesn't).
    sel.removeAllRanges(); sel.addRange(range);
    const input = sel.toString();
    sel.removeAllRanges(); if (saved) sel.addRange(saved);
    return input.trim() ? { el, full, input, range } : null;
  }

  // ---------- run ----------

  let earlier = { key: null, outs: [] }; // earlier answers for the same text + tone
  let runOuts = null;                     // where the running answer goes when it ends well

  async function run(toneId, fresh) {
    ensureUi();
    if (fresh) {
      const s = read();
      if (!s) { shake(); return; }
      snap = s;
    }
    if (!snap) return;
    if (!tones.length) await loadState();
    stopPort();
    result = ""; streaming = true;
    openCard(toneId, looksFarsi(snap.input));
    let p;
    try { p = chrome.runtime.connect({ name: "rewrite" }); }
    catch { fail("Tone Fixer was updated. Reload this page to use it.", false); return; }
    port = p;
    p.onMessage.addListener((m) => {
      if (port !== p) return;
      if (m.type === "text") { result += m.text; renderBody(); return; }
      if (m.type === "using") { renderModel(m.label); return; }
      if (m.type !== "end") return;
      port = null; p.disconnect();
      if (m.error) fail(m.error, m.settings);
      else if (!result.trim()) fail("No answer from the model. Try again or pick another tone.", false);
      else finish(m.stop === "cut" ? "Cut off: the answer hit the length limit." : null);
    });
    p.onDisconnect.addListener(() => { if (port === p) { port = null; fail("Lost the connection to Tone Fixer. Try again.", false); } });
    // Same text and tone again (bubble, chip or ↻) = a slightly different version.
    if (earlier.key !== `${toneId}\u0000${snap.input}`) earlier = { key: `${toneId}\u0000${snap.input}`, outs: [] };
    const outs = earlier.outs;
    p.postMessage({ text: snap.input, tone: toneId, earlier: outs.slice(-3) });
    runOuts = outs;
    if (toneId !== tone) { tone = toneId; setLetter(); }
  }

  function stopPort() { if (port) { const p = port; port = null; try { p.disconnect(); } catch { /* gone */ } } }

  function shake() {
    const b = ui.bubble;
    b.classList.remove("shake"); void b.offsetWidth; b.classList.add("shake");
  }

  function setBubble(state) {
    ui.bubble.classList.toggle("working", state === "working");
    ui.bubble.classList.toggle("error", state === "error");
    ui.bubble.classList.toggle("on", state !== "idle");
  }

  // ---------- card ----------

  const cardOpen = () => !!ui && !ui.card.hidden;

  function openCard(toneId, fromFarsi) {
    mode = "rewrite";
    ui.card.classList.remove("fa");
    ui.ok.hidden = false; ui.again.hidden = false; ui.copyBtn.hidden = true;
    ui.tag.textContent = "from Farsi";
    const t = tones.find((x) => x.id === toneId);
    ui.title.textContent = t ? t.label : "";
    cardTone = toneId;
    renderModel(current?.label);
    ui.tag.hidden = !fromFarsi;
    ui.status.hidden = true;
    ui.ok.disabled = true; ui.again.disabled = true;
    ui.chips.replaceChildren(...tones.map((x) =>
      h("button", { class: `chip${x.id === toneId ? " sel" : ""}`, onclick: () => { if (!streaming) run(x.id, false); } }, x.label)));
    renderBody();
    setBubble("working");
    if (ui.card.hidden) { ui.card.hidden = false; chooseCardSide(); }
    placeCard();
  }

  /** "Claude Haiku 5.5 ⇄": which model wrote this; click to rerun with the next one. */
  function renderModel(label) {
    ui.model.hidden = !label;
    ui.model.textContent = models.length > 1 ? `${label}  ⇄` : label || "";
    ui.model.disabled = models.length < 2;
    ui.model.title = models.length > 1 ? "Try the next model on the same text" : "";
  }

  async function useModel(x, rerun) {
    await send({ type: "setModel", provider: x.provider, model: x.model });
    earlier = { key: null, outs: [] }; // a new model starts fresh, so models can be compared
    await loadState();
    if (rerun) { if (mode === "translate") translateText(trText, null, trMode); else run(cardTone || tone, false); }
  }

  function nextModel() {
    if (streaming || models.length < 2) return;
    const i = models.findIndex((x) => current && x.provider === current.provider && x.model === current.model);
    useModel(models[(i + 1) % models.length], true);
  }

  function renderBody() {
    const b = ui.body;
    const atEnd = b.scrollTop + b.clientHeight >= b.scrollHeight - 4;
    b.replaceChildren(result);
    if (streaming) b.append(h("span", { class: "caret" }, "▍"));
    b.hidden = !result && !streaming;
    if (atEnd) b.scrollTop = b.scrollHeight; // follow long answers
    if (!ui.card.hidden) placeCard(); // the card grows while text streams in: keep all of it on screen
  }

  function status(msg, err, settingsLink) {
    ui.status.hidden = !msg;
    ui.status.className = `status${err ? " err" : ""}`;
    ui.status.replaceChildren(msg || "");
    if (settingsLink) ui.status.append(h("a", { onclick: () => send({ type: "options" }) }, "Open settings ↗"));
    if (!ui.card.hidden) placeCard();
  }

  function finish(note) {
    streaming = false;
    renderBody();
    status(note, false);
    ui.ok.disabled = !result.trim();
    ui.again.disabled = false;
    ui.copyBtn.disabled = !result.trim();
    if (result.trim() && mode === "rewrite") runOuts?.push(result.trim());
    setBubble("idle");
  }

  function fail(msg, settingsLink) {
    streaming = false;
    renderBody();
    status(msg, true, settingsLink);
    ui.ok.disabled = true;
    ui.again.disabled = false;
    setBubble("error");
  }

  function cancel() {
    stopPort();
    streaming = false; snap = null; result = "";
    if (!ui) return;
    ui.card.hidden = true;
    setBubble("idle");
    schedule();
  }

  // ---------- apply (✓) ----------

  async function copy(text) {
    try { await navigator.clipboard.writeText(text); return true; } catch { return false; }
  }
  async function giveUp(text, why) {
    const copied = await copy(text);
    fail(copied ? `${why} The result is copied: paste it where you want.` : `${why} Select the text above and copy it.`, false);
  }

  async function apply() {
    if (mode !== "rewrite") return;
    const s = snap;
    if (!s || !result.trim() || streaming) return;
    const el = s.el;
    const replacement = keepOuterSpace(s.input, result);
    // The box changed since the click (kept typing, switched chat): never overwrite it.
    if (!el.isConnected || currentText(el) !== s.full) return giveUp(replacement, "The text changed since you clicked, so I didn't replace it.");
    ui.ok.disabled = true;
    el.focus({ preventScroll: true });

    if (isValueField(el)) {
      const newFull = s.full.slice(0, s.start) + replacement + s.full.slice(s.end);
      el.setSelectionRange(s.start, s.end);
      // insertText keeps the box's own undo (Ctrl+Z) and fires the events React/Vue listen to.
      if (!document.execCommand("insertText", false, replacement) || el.value !== newFull) {
        const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value").set;
        setter.call(el, newFull);
        el.dispatchEvent(new Event("input", { bubbles: true }));
      }
      if (el.value !== newFull) return giveUp(replacement, "This box didn't accept the text.");
      const end = s.start + replacement.length;
      el.setSelectionRange(end, end);
      return done();
    }

    // Rich editor (Gmail, WhatsApp Web, Slack, …): select the same range and type over it.
    const sel = el.getRootNode().getSelection?.() || window.getSelection();
    sel.removeAllRanges(); sel.addRange(s.range);
    const took = () => norm(el.innerText).includes(norm(replacement));
    document.execCommand("insertText", false, replacement);
    if (took()) return done();
    await new Promise((r) => setTimeout(r, 250)); // some editors update a moment later
    if (took()) return done();
    if (el.innerText === s.full) {
      // Untouched: try a paste event, which most editors handle themselves.
      sel.removeAllRanges(); sel.addRange(s.range);
      const dt = new DataTransfer(); dt.setData("text/plain", replacement);
      el.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true }));
      await new Promise((r) => setTimeout(r, 250));
      if (took()) return done();
      if (el.innerText === s.full) return giveUp(replacement, "This editor didn't accept the text.");
    }
    return giveUp(replacement, "Not sure the editor took the text: check it.");
  }

  function done() {
    snap = null; result = "";
    ui.card.hidden = true;
    setBubble("idle");
    schedule();
  }

  // ---------- translate selected text to Persian (right-click → «ترجمه به فارسی») ----------

  async function translateText(text, range, modeId) {
    if (!text.trim()) return;
    ensureUi();
    hideMenu(); stopPort();
    if (!trModes.length || !tones.length) await loadState();
    mode = "translate"; trText = text;
    if (range) trRange = range;
    if (modeId) trMode = modeId;
    result = ""; streaming = true;
    ui.card.classList.add("fa");
    ui.title.textContent = "ترجمه به فارسی";
    ui.tag.hidden = true;
    renderModel(current?.label);
    ui.status.hidden = true;
    ui.ok.hidden = true; ui.again.hidden = true; ui.copyBtn.hidden = false; ui.copyBtn.disabled = true;
    ui.copyBtn.textContent = "کپی";
    ui.chips.replaceChildren(...trModes.map((m) =>
      h("button", { class: `chip${m.id === trMode ? " sel" : ""}`, onclick: () => { if (!streaming && m.id !== trMode) translateText(trText, null, m.id); } }, m.label)));
    renderBody();
    if (ui.card.hidden) { ui.card.hidden = false; chooseCardSide(); }
    placeCard();
    let p;
    try { p = chrome.runtime.connect({ name: "rewrite" }); }
    catch { fail("Tone Fixer was updated. Reload this page to use it.", false); return; }
    port = p;
    p.onMessage.addListener((m) => {
      if (port !== p) return;
      if (m.type === "text") { result += m.text; renderBody(); return; }
      if (m.type === "using") { renderModel(m.label); return; }
      if (m.type === "lang") { ui.tag.textContent = m.lang === "فارسی" ? "متن فارسی است" : `از ${m.lang}`; ui.tag.hidden = !m.lang; return; }
      if (m.type !== "end") return;
      port = null; p.disconnect();
      if (m.error) fail(m.error, m.settings);
      else if (!result.trim()) fail("No answer from the model. Try again.", false);
      else finish(m.stop === "cut" ? "Cut off: the answer hit the length limit." : null);
    });
    p.onDisconnect.addListener(() => { if (port === p) { port = null; fail("Lost the connection to Tone Fixer. Try again.", false); } });
    p.postMessage({ text, translate: trMode });
  }

  async function copyResult() {
    if (!result.trim()) return;
    const okCopy = await copy(result.trim());
    ui.copyBtn.textContent = okCopy ? "کپی شد ✓" : "کپی نشد";
    setTimeout(() => { if (ui) ui.copyBtn.textContent = "کپی"; }, 1600);
  }

  // ---------- translate the whole page to Persian (toolbar popup) ----------
  // Like a page translator: each text piece is replaced in place, so links and layout stay. Only
  // what comes into view is sent (plus a margin), in batches; new content (infinite scroll) follows.

  const SKIP = "script,style,noscript,textarea,input,select,option,code,pre,kbd,samp,svg,math,canvas,template,[translate=no],.notranslate,[contenteditable=''],[contenteditable=true]";
  const page = { on: false, full: false, showing: "fa", nodes: new Map(), blocks: new Map(), queue: [], waiting: new Map(), busy: 0, done: 0, sent: 0, error: "", io: null, mo: null, gen: 0 };

  function piecesIn(root) {
    const out = [];
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, { acceptNode(n) {
      const t = n.nodeValue;
      // Punctuation right after a link or bold text (". " in "… the <a>legal rent</a>.") is sent too: the Persian
      // verb comes last, so the end of the sentence often has to move into it.
      const tail = n.previousSibling?.nodeType === 1 && /^\s*[.!?;:,)]+\s*$/.test(t);
      if (!t || (!tail && (t.trim().length < 2 || !/\p{L}/u.test(t)))) return NodeFilter.FILTER_REJECT;
      if (looksFarsi(t) && !/[A-Za-z]/.test(t)) return NodeFilter.FILTER_REJECT; // already Persian
      const el = n.parentElement;
      if (!el || el.closest(SKIP) || page.nodes.has(n)) return NodeFilter.FILTER_REJECT;
      return NodeFilter.FILTER_ACCEPT;
    } });
    for (let n; (n = w.nextNode());) out.push(n);
    return out;
  }

  /** Watch pieces: they're queued when their element comes near the screen. */
  function watch(nodes) {
    for (const n of nodes) {
      const el = n.parentElement;
      if (!page.waiting.has(el)) { page.waiting.set(el, []); page.io.observe(el); }
      page.waiting.get(el).push(n);
    }
  }

  /** Starts translating: [full] = the whole page (as it scrolls into view), else only what is queued (a selection). */
  function ensurePage(full) {
    if (window !== window.top && full) return;
    ensureUi();
    if (!page.on) {
      page.on = true; page.full = false; page.showing = "fa"; page.error = ""; page.done = 0; page.sent = 0; page.gen++;
      faStyle();
      ui.pagebar.hidden = false;
    }
    if (full && !page.full) {
      page.full = true;
      page.io = new IntersectionObserver((entries) => {
        for (const e of entries) {
          if (!e.isIntersecting) continue;
          page.io.unobserve(e.target);
          page.queue.push(...(page.waiting.get(e.target) || []));
          page.waiting.delete(e.target);
        }
        pump();
      }, { rootMargin: "800px 0px" });
      watch(piecesIn(document.body));
      let added = [], timer = 0;
      page.mo = new MutationObserver((muts) => {
        for (const m of muts) for (const n of m.addedNodes) if (n.nodeType === 1) added.push(n);
        clearTimeout(timer);
        timer = setTimeout(() => {
          const roots = added; added = [];
          if (page.on && page.full) for (const r of roots) if (r.isConnected) watch(piecesIn(r));
        }, 400);
      });
      page.mo.observe(document.body, { childList: true, subtree: true });
    }
    renderPagebar();
  }

  /** The text pieces inside a selection; the first and last are cut at the selection's edges. */
  function selectionPieces(range) {
    const sc = range.startContainer, so = range.startOffset, ec = range.endContainer, eo = range.endOffset;
    if (ec.nodeType === 3 && eo > 0 && eo < ec.nodeValue.length) ec.splitText(eo);
    if (sc.nodeType === 3 && so > 0 && so < sc.nodeValue.length) {
      const rest = sc.splitText(so);
      range.setStart(rest, 0);
      if (ec === sc) range.setEnd(rest, rest.nodeValue.length);
    }
    const c = range.commonAncestorContainer;
    const root = c.nodeType === 3 ? c.parentElement : c;
    return root ? piecesIn(root).filter((n) => range.intersectsNode(n)) : [];
  }

  /** Right-click «ترجمه به فارسی»: the selected text itself turns into Persian, in place. */
  function translateInPlace(range) {
    const nodes = selectionPieces(range);
    if (!nodes.length) return false;
    ensurePage(false);
    page.queue.push(...nodes);
    pump();
    return true;
  }

  // Persian text: Vazirmatn (only its Persian letters, so Latin names keep the site's font), right to left.
  let faReady = false;
  function faStyle() {
    if (faReady) return;
    faReady = true;
    try {
      fetch(chrome.runtime.getURL("vazirmatn.woff2")).then((r) => r.arrayBuffer())
        .then((buf) => new FontFace("TF Vazirmatn", buf, { weight: "100 900" }).load())
        .then((f) => document.fonts.add(f)).catch(() => {});
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(`[data-tf-fa] { font-family: "TF Vazirmatn", var(--tf-font, system-ui), sans-serif !important; }
        [data-tf-fa][data-tf-left] { text-align: right !important; }`);
      document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
    } catch { /* very old page setup: Persian still shows, in the site's font */ }
  }

  function pump() {
    while (page.on && page.busy < 3 && page.queue.length) {
      const batch = []; let chars = 0;
      while (page.queue.length && batch.length < 40 && chars < 1800) {
        const n = page.queue.shift();
        if (!n.isConnected || page.nodes.has(n)) continue;
        batch.push(n); chars += n.nodeValue.length;
      }
      if (!batch.length) break;
      page.busy++; page.sent += batch.length;
      translateBatch(batch, page.gen).finally(() => { page.busy--; renderPagebar(); pump(); });
    }
    renderPagebar();
  }

  /** The nearest block (not inline) element around a text piece: one sentence/paragraph is sent as one item. */
  function blockOf(el) {
    for (let e = el, i = 0; e && e !== document.body && i < 6; e = e.parentElement, i++)
      if (!getComputedStyle(e).display.startsWith("inline")) return e;
    return el;
  }

  /**
   * Pieces of one paragraph go as ONE item, with ⟦1⟧ ⟦2⟧ … where a link or bold part starts or ends
   * ("than the ⟦1⟧legal rent⟦2⟧."), so the model sees whole sentences and splits its Persian at the same
   * marks (the verb can move after the last one). [single] = one item per piece (retry without marks).
   */
  async function translateBatch(nodes, gen, single = false) {
    const groups = [];
    for (const n of nodes) {
      const b = single ? n : blockOf(n.parentElement), g = groups.at(-1);
      if (g && g.block === b) g.nodes.push(n); else groups.push({ block: b, nodes: [n] });
    }
    const items = groups.map((g) => g.nodes.map((n, i) => (i ? `⟦${i}⟧` : "") + n.nodeValue.replace(/\s+/g, " ")).join("").trim());
    const r = await send({ type: "translatePieces", pieces: items });
    if (!page.on || gen !== page.gen) return;
    if (r?.items) {
      const again = [];
      groups.forEach((g, gi) => {
        const parts = r.items[gi].split(/⟦\d+⟧/);
        if (parts.length !== g.nodes.length) { again.push(...g.nodes); return; }
        g.nodes.forEach((n, i) => {
          if (!n.isConnected) return;
          const orig = n.nodeValue, tr = parts[i];
          // The original's outer spaces; a space the model wrote at an edge is kept where the original had none.
          const lead = orig.match(/^\s*/)[0] || (/^\s/.test(tr) || (i > 0 && /^\p{L}/u.test(tr.trim()) && /^[.!?;:,)]/.test(orig.trim())) ? " " : "");
          const fa = tr.trim() ? lead + tr.trim() + (orig.match(/\s*$/)[0] || (/\s$/.test(tr) ? " " : "")) : (orig.trim() ? "" : orig);
          page.nodes.set(n, { orig, fa });
          if (page.showing === "fa") n.nodeValue = fa;
          rtl(n.parentElement);
        });
        page.done += g.nodes.length;
      });
      if (again.length && !single) await translateBatch(again, gen, true); // marks got lost: piece by piece
      else if (again.length) { page.sent -= again.length; page.error = "Some parts couldn't be translated."; }
    } else if (r?.retry && nodes.length > 1) { // the answer didn't line up with the items: try in two halves
      const half = Math.ceil(nodes.length / 2);
      await translateBatch(nodes.slice(0, half), gen, single);
      await translateBatch(nodes.slice(half), gen, single);
    } else {
      page.sent -= nodes.length;
      page.error = r?.error || "Some parts couldn't be translated.";
    }
  }

  /** Right-to-left + Vazirmatn for the nearest block around a translated piece (undone by ✕ / "متن اصلی"). */
  function rtl(el) {
    for (let e = el, i = 0; e && e !== document.body && i < 5; e = e.parentElement, i++) {
      const cs = getComputedStyle(e);
      if (cs.display.startsWith("inline")) continue;
      if (!page.blocks.has(e)) page.blocks.set(e, { dir: e.getAttribute("dir"), left: cs.textAlign === "left", font: cs.fontFamily, rtl: false });
      // Right to left only once most of the block is Persian (a few translated words keep an English paragraph LTR).
      const b = page.blocks.get(e);
      const letters = (e.textContent.match(/\p{L}/gu) || []).length, persian = (e.textContent.match(/[\u0600-\u06FF]/g) || []).length;
      b.rtl = persian * 2 >= letters;
      if (page.showing === "fa") styleBlock(e, true);
      return;
    }
  }
  function styleBlock(e, on) {
    const b = page.blocks.get(e);
    if (on) {
      e.setAttribute("data-tf-fa", "");
      e.style.setProperty("--tf-font", b.font);
      if (b.rtl) { e.setAttribute("dir", "rtl"); if (b.left) e.setAttribute("data-tf-left", ""); }
      else { if (b.dir == null) e.removeAttribute("dir"); else e.setAttribute("dir", b.dir); e.removeAttribute("data-tf-left"); }
    } else {
      if (b.dir == null) e.removeAttribute("dir"); else e.setAttribute("dir", b.dir);
      e.removeAttribute("data-tf-fa"); e.removeAttribute("data-tf-left");
      e.style.removeProperty("--tf-font");
      if (!e.getAttribute("style")) e.removeAttribute("style");
    }
  }

  function setShowing(which) {
    page.showing = which;
    for (const [n, v] of page.nodes) if (n.isConnected) n.nodeValue = which === "fa" ? v.fa : v.orig;
    for (const e of page.blocks.keys()) styleBlock(e, which === "fa");
    renderPagebar();
  }
  function swapPage() { setShowing(page.showing === "fa" ? "orig" : "fa"); }

  function stopPage() {
    if (!page.on) return;
    setShowing("orig");
    page.on = false; page.full = false; page.gen++;
    page.io?.disconnect(); page.mo?.disconnect();
    page.nodes.clear(); page.blocks.clear(); page.queue = []; page.waiting.clear();
    if (ui) ui.pagebar.hidden = true;
  }

  const fa = (n) => String(n).replace(/\d/g, (d) => "۰۱۲۳۴۵۶۷۸۹"[d]);
  function renderPagebar() {
    if (!ui || !page.on) return;
    const working = page.busy > 0 || page.queue.length > 0;
    ui.pageMsg.className = `msg${page.error && !working ? " err" : ""}`;
    ui.pageMsg.textContent = page.showing !== "fa" ? "متن اصلی" : working ? `در حال ترجمه… ${fa(page.done)}/${fa(page.sent + page.queue.length)}`
      : page.error ? page.error : page.done ? "ترجمه شد" : "چیزی برای ترجمه نیست";
    ui.pageSwap.textContent = page.showing === "fa" ? "متن اصلی" : "فارسی";
  }

  // ---------- keyboard shortcut (Alt+Shift+F, sent by the service worker) ----------

  try {
    chrome.runtime.onMessage.addListener((m, _sender, reply) => {
      if (m.type === "translateSel") { // right-click menu; the service worker sends it to the frame with the selection
        const sel = window.getSelection();
        const range = sel && sel.rangeCount && !sel.isCollapsed ? sel.getRangeAt(0).cloneRange() : null;
        const text = (range && sel.toString()) || m.text || "";
        const ed = editableFrom(deepActive());
        // Text on the page → translated where it is. Inside a text box (can't be changed in place) → the card.
        if (range && !(ed && ed.contains(range.commonAncestorContainer)) && translateInPlace(range)) { sel.removeAllRanges(); return; }
        trRange = range || ed || null;
        translateText(text, null, null);
        return;
      }
      if (m.type === "pageState" || m.type === "translatePage") { // the popup asks the top page only (frame 0)
        if (window !== window.top) return;
        if (m.type === "translatePage") { if (page.on && (page.full || !m.full)) stopPage(); else ensurePage(true); }
        reply({ on: page.on });
        return;
      }
      if (m.type !== "run" || !document.hasFocus() || !enabled) return;
      if (cardOpen() && !streaming && result.trim()) { apply(); return; } // second press = ✓
      const el = editableFrom(deepActive());
      if (!el) return;
      onFocus(el);
      run(tone, true);
    });
  } catch { /* old script after a reload */ }
})();
