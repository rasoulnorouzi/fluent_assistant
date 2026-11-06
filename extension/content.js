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

  function send(msg) {
    try { return chrome.runtime.sendMessage(msg).catch(() => null); }
    catch { return Promise.resolve(null); } // extension was reloaded: this page still has the old script
  }
  async function loadState() {
    const s = await send({ type: "state" });
    if (s) { tones = s.tones; tone = s.tone; models = s.models || []; current = s.current || null; ui && setLetter(); }
  }
  try {
    chrome.storage.onChanged.addListener((c) => {
      if (c.tone) { tone = c.tone.newValue; ui && setLetter(); }
      if (c.provider || c.models || c.keys || c.modelLists) loadState(); // model list or choice changed
      if (c.hiddenSites) { hiddenHere = (c.hiddenSites.newValue || []).includes(SITE); schedule(); }
    });
  } catch { /* old script after a reload */ }

  // ---------- "Hide on this site" ----------

  /** The site the user sees in the address bar (also inside its iframes, e.g. editors). */
  const SITE = (() => {
    try { const a = location.ancestorOrigins; return new URL(a && a.length ? a[a.length - 1] : location.href).hostname; }
    catch { return location.hostname; }
  })();
  let hiddenHere = false;
  try { chrome.storage.local.get({ hiddenSites: [] }).then((s) => { hiddenHere = s.hiddenSites.includes(SITE); schedule(); }); }
  catch { /* old script after a reload */ }

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
          padding: 2px 6px 2px 0; min-height: 24px; user-select: text; }
  .caret { color: var(--clay); }
  .status { font-size: 13px; color: var(--muted); margin-top: 6px; padding-inline-end: 6px; }
  .status.err { color: var(--warn); }
  .status a { color: var(--primary); text-decoration: underline; cursor: pointer; margin-inline-start: 6px; }
  .foot { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
  .chips { display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none; flex: 1; min-width: 0; padding: 2px;
           mask-image: linear-gradient(to right, #000 calc(100% - 28px), transparent); } /* hint: more tones → */
  .chip { flex: none; height: 32px; padding: 0 12px; border-radius: 16px; border: 1px solid var(--line); background: var(--card); white-space: nowrap; }
  .chip:hover { border-color: var(--primary); }
  .chip.sel { background: var(--primary); border-color: var(--primary); color: var(--on-primary); font-weight: 700; cursor: default; }
  .ok { flex: none; width: 44px; height: 44px; border-radius: 50%; background: var(--primary); color: var(--on-primary);
        display: grid; place-items: center; box-shadow: 0 2px 6px rgba(0,0,0,.18); }
  .ok:disabled { opacity: .35; cursor: default; box-shadow: none; }
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
      title: "Tone Fixer · click: rewrite · right-click: pick a tone" }, icon(WAVE, 22), badge);
    let holdTimer = 0, held = false;
    bubble.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      if (e.button !== 0) return;
      held = false;
      holdTimer = setTimeout(() => { held = true; showMenu(); }, 450);
    });
    bubble.addEventListener("pointerup", (e) => {
      if (e.button !== 0) return;
      clearTimeout(holdTimer);
      if (!held) { hideMenu(); run(tone, true); }
    });
    bubble.addEventListener("pointerleave", () => clearTimeout(holdTimer));
    bubble.addEventListener("contextmenu", (e) => { e.preventDefault(); showMenu(); });
    bubble.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); run(tone, true); } });

    const menu = h("div", { class: "menu", hidden: true, role: "menu", onpointerdown: keepFocus });

    const title = h("span", { class: "title" });
    const model = h("button", { class: "model", onclick: nextModel });
    const tag = h("span", { class: "tag", hidden: true }, "from Farsi");
    const body = h("div", { class: "body" });
    const status = h("div", { class: "status", hidden: true });
    const chips = h("div", { class: "chips" });
    const ok = h("button", { class: "ok", "aria-label": "Replace", title: "Replace the text (or press Alt+Shift+F again)",
      onclick: apply }, icon(CHECK, 24));
    const card = h("div", { class: "card", hidden: true, role: "dialog", "aria-label": "Tone Fixer", onpointerdown: (e) => {
      if (!e.target.closest?.(".body")) e.preventDefault(); // allow selecting the result text
    } },
      h("div", { class: "top" },
        h("div", { class: "heading" }, h("div", { class: "titlerow" }, title, tag), model),
        h("button", { class: "x", "aria-label": "Cancel", title: "Cancel (Esc)", onclick: cancel }, icon(CLOSE, 20))),
      body, status,
      h("div", { class: "foot" }, chips, ok));

    root.append(h("div", { class: "tf" }, bubble, menu, card));
    document.documentElement.append(host);
    return { host, bubble, badge, menu, card, title, model, tag, body, status, chips, ok };
  }
  const ensureUi = () => (ui && ui.host.isConnected ? ui : (ui = buildUi()));

  function setLetter() {
    ui.badge.textContent = (tones.find((t) => t.id === tone) || {}).letter || "";
  }

  // ---------- bubble placement ----------

  let raf = 0;
  const schedule = () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; place(); }); };

  function shouldShow() {
    return field && field.isConnected && (cardOpen() || (!hiddenHere && currentText(field).trim() !== ""));
  }

  function place() {
    if (!ui) return;
    const b = ui.bubble;
    if (!shouldShow()) { b.hidden = true; return; }
    const r = field.getBoundingClientRect();
    const vw = document.documentElement.clientWidth, vh = window.innerHeight;
    if (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw || r.width === 0) { b.hidden = true; return; }
    const S = 34, pad = 6;
    let x, y;
    if (r.height < 46 && r.right + pad + S <= vw) { // one-line box: sit just outside its right edge
      x = r.right + pad; y = r.top + (r.height - S) / 2;
    } else { // inside the bottom-right corner of the visible part
      x = Math.min(r.right, vw) - S - pad; y = Math.min(r.bottom, vh) - S - pad;
    }
    b.style.left = `${Math.max(4, x)}px`;
    b.style.top = `${Math.max(4, y)}px`;
    b.hidden = false;
    if (!ui.menu.hidden) placeMenu();
    if (cardOpen()) placeCard();
  }

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
    const anchor = (snap?.el || field)?.getBoundingClientRect();
    const w = Math.min(460, vw - 24);
    c.style.width = `${w}px`;
    const left = anchor ? Math.min(Math.max(12, anchor.left), vw - w - 12) : 12;
    c.style.left = `${left}px`;
    if (!anchor) { c.style.top = "12px"; c.style.bottom = ""; return; }
    // The side is chosen when the card opens, so it doesn't jump while text streams in.
    if (cardSide === "below") { c.style.top = `${Math.min(anchor.bottom + 8, vh - 120)}px`; c.style.bottom = ""; }
    else if (cardSide === "above") { c.style.bottom = `${Math.min(vh - anchor.top + 8, vh - 120)}px`; c.style.top = ""; }
    else { c.style.top = "12px"; c.style.bottom = ""; }
  }
  function chooseCardSide() {
    const r = (snap?.el || field).getBoundingClientRect(), vh = window.innerHeight, need = 260;
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
    m.append(h("div", { class: "sep" }),
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
    p.postMessage({ text: snap.input, tone: toneId });
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
    const t = tones.find((x) => x.id === toneId);
    ui.title.textContent = t ? t.label : "";
    cardTone = toneId;
    renderModel(current?.label);
    ui.tag.hidden = !fromFarsi;
    ui.status.hidden = true;
    ui.ok.disabled = true;
    ui.chips.replaceChildren(...[...tones].sort((a, b) => (a.id === toneId ? -1 : b.id === toneId ? 1 : 0)).map((x) =>
      h("button", { class: `chip${x.id === toneId ? " sel" : ""}`, onclick: () => { if (x.id !== toneId) run(x.id, false); } }, x.label)));
    ui.chips.scrollLeft = 0;
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
    await loadState();
    if (rerun) run(cardTone || tone, false);
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
  }

  function status(msg, err, settingsLink) {
    ui.status.hidden = !msg;
    ui.status.className = `status${err ? " err" : ""}`;
    ui.status.replaceChildren(msg || "");
    if (settingsLink) ui.status.append(h("a", { onclick: () => send({ type: "options" }) }, "Open settings ↗"));
  }

  function finish(note) {
    streaming = false;
    renderBody();
    status(note, false);
    ui.ok.disabled = !result.trim();
    setBubble("idle");
  }

  function fail(msg, settingsLink) {
    streaming = false;
    renderBody();
    status(msg, true, settingsLink);
    ui.ok.disabled = true;
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

  // ---------- keyboard shortcut (Alt+Shift+F, sent by the service worker) ----------

  try {
    chrome.runtime.onMessage.addListener((m) => {
      if (m.type !== "run" || !document.hasFocus()) return;
      if (cardOpen() && !streaming && result.trim()) { apply(); return; } // second press = ✓
      const el = editableFrom(deepActive());
      if (!el) return;
      onFocus(el);
      run(tone, true);
    });
  } catch { /* old script after a reload */ }
})();
