// End-to-end check of the real extension in Chromium (dev only, needs Playwright):
//   node extension/test/e2e.cjs            → screenshots in extension/build/screens/
// A local server plays the Gemini API (streams "Hi! <your text>") and serves a test page with a
// textarea, a one-line input and a Gmail-like rich editor (with a signature that must survive).
const { chromium } = require("playwright");
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const assert = require("assert/strict");
const { execFileSync } = require("child_process");

const EXT = path.resolve(__dirname, "..");
const SHOTS = path.join(EXT, "build", "screens");

const PAGE = `<!doctype html><meta charset="utf-8"><title>Test page</title>
<body style="font:16px system-ui;margin:40px;max-width:720px;background:#fff">
<h3>Plain textarea</h3><textarea id="ta" rows="5" style="width:100%"></textarea>
<h3>One-line input</h3><input id="in" style="width:60%">
<h3>Rich editor (like Gmail)</h3>
<div id="rich" contenteditable="true" style="border:1px solid #ccc;min-height:120px;padding:8px">
<div>hello mr ahmadi</div><div>i will send report friday</div>
<div class="gmail_signature">-- <br>Rasoul · sent from my laptop</div></div>
<div style="height:600px"></div></body>`;

let lastBody = null;
const remote = {}; // the fake `updates` branch: filled in below
function mockServer() {
  return http.createServer(async (req, res) => {
    if (req.url === "/page") { res.writeHead(200, { "content-type": "text/html" }); return res.end(PAGE); }
    const u = req.url.split("?")[0];
    if (u.startsWith("/updates/") && remote[u]) { res.writeHead(200, { "content-type": "application/json" }); return res.end(JSON.stringify(remote[u])); }
    if (req.method === "HEAD") { res.writeHead(200); return res.end(); }
    let raw = ""; for await (const c of req) raw += c;
    if (req.url.startsWith("/v1/messages")) { // Claude-style answers: "Claude: <text>"
      const input = JSON.parse(raw).messages[0].content.replace(/^<text>\n([\s\S]*)\n<\/text>$/, "$1");
      res.writeHead(200, { "content-type": "text/event-stream" });
      const ev = (o) => `event: ${o.type}\ndata: ${JSON.stringify(o)}\n\n`;
      res.write(ev({ type: "message_start", message: {} }));
      res.write(ev({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: `Claude: ${input.trim()}` } }));
      res.write(ev({ type: "message_delta", delta: { stop_reason: "end_turn" } }));
      return res.end(ev({ type: "message_stop" }));
    }
    if (!req.url.includes(":streamGenerateContent")) { res.writeHead(404); return res.end(); }
    if (req.headers["x-goog-api-key"] !== "test-key") {
      res.writeHead(400, { "content-type": "application/json" });
      return res.end(JSON.stringify({ error: { code: 400, message: "API key not valid.", status: "INVALID_ARGUMENT" } }));
    }
    lastBody = JSON.parse(raw);
    const input = lastBody.contents[0].parts[0].text.replace(/^<text>\n([\s\S]*)\n<\/text>$/, "$1");
    const out = `Hi! ${input.trim()}`;
    res.writeHead(200, { "content-type": "text/event-stream" });
    const parts = [out.slice(0, 4), out.slice(4, Math.ceil(out.length / 2)), out.slice(Math.ceil(out.length / 2))];
    for (const p of parts) {
      res.write(`data: ${JSON.stringify({ candidates: [{ content: { parts: [{ text: p }], role: "model" } }] })}\r\n\r\n`);
      await new Promise((r) => setTimeout(r, 250));
    }
    res.end(`data: ${JSON.stringify({ candidates: [{ content: { parts: [{ text: "" }], role: "model" }, finishReason: "STOP" }] })}\r\n\r\n`);
  });
}

(async () => {
  execFileSync(path.join(EXT, "pack.sh"), { stdio: "ignore" });
  const server = mockServer();
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${server.address().port}`;

  // Test copy of the extension that talks to the mock instead of Google.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tf-ext-"));
  fs.cpSync(EXT, dir, { recursive: true, filter: (p) => !p.includes(`${path.sep}build`) && !p.includes(`${path.sep}test`) });
  const prov = JSON.parse(fs.readFileSync(path.join(dir, "providers.json"), "utf8"));
  prov.providers.gemini.url = `${base}/v1beta/models/{model}:streamGenerateContent?alt=sse`;
  prov.providers.gemini.warmUrl = `${base}/`;
  prov.providers.claude.url = `${base}/v1/messages`;
  prov.providers.claude.warmUrl = `${base}/`;
  fs.writeFileSync(path.join(dir, "providers.json"), JSON.stringify(prov));
  // Fake `updates` branch: a newer Daily style (marked) and a newer extension version.
  const tonesRemote = JSON.parse(fs.readFileSync(path.join(dir, "tones.json"), "utf8"));
  tonesRemote.tones.find((t) => t.id === "daily").style += " REMOTE-MARK";
  remote["/updates/shared/tones.json"] = tonesRemote;
  remote["/updates/shared/providers.json"] = prov;
  remote["/updates/extension/latest.json"] = { version: "9.9.9", zip: "tone-fixer-extension-9.9.9.zip", notes: "Test notes." };
  for (const f of ["background.js"]) {
    const p = path.join(dir, f);
    fs.writeFileSync(p, fs.readFileSync(p, "utf8").replace("https://raw.githubusercontent.com/rasoulnorouzi/fluent_assistant/updates/", `${base}/updates/`));
  }
  const man = JSON.parse(fs.readFileSync(path.join(dir, "manifest.json"), "utf8"));
  man.host_permissions.push("http://127.0.0.1/*");
  fs.writeFileSync(path.join(dir, "manifest.json"), JSON.stringify(man));

  fs.rmSync(SHOTS, { recursive: true, force: true });
  fs.mkdirSync(SHOTS, { recursive: true });
  const ctx = await chromium.launchPersistentContext(fs.mkdtempSync(path.join(os.tmpdir(), "tf-prof-")), {
    channel: "chromium", headless: true, viewport: { width: 900, height: 900 },
    args: [`--disable-extensions-except=${dir}`, `--load-extension=${dir}`],
  });
  let sw = ctx.serviceWorkers()[0] || (await ctx.waitForEvent("serviceworker"));
  const extId = sw.url().split("/")[2];
  await sw.evaluate(() => chrome.storage.local.set({ keys: { gemini: "test-key" }, tone: "daily" }));
  // The install-time update check stores the downloaded tones; wait for it.
  for (let i = 0; i < 50 && !(await sw.evaluate(() => chrome.storage.local.get("remoteCfg").then((s) => !!s.remoteCfg))); i++) await new Promise((r) => setTimeout(r, 100));
  for (const p of ctx.pages()) if (p.url().includes("options.html")) await p.close(); // opened on install

  const page = await ctx.newPage();
  await page.goto(`${base}/page`);
  const ui = page.locator("tone-fixer-ui");
  const bubble = ui.locator(".bubble");
  const card = ui.locator(".card");
  const ok = ui.locator(".ok");
  const shot = (name, loc = page) => loc.screenshot({ path: path.join(SHOTS, `${name}.png`) });
  const results = [];
  const check = async (name, fn) => {
    try { await fn(); results.push(`ok   ${name}`); }
    catch (e) { results.push(`FAIL ${name}: ${e.message.split("\n").slice(0, 6).join(" | ")}`); await shot(`FAIL-${name.replace(/\W+/g, "-")}`); }
  };

  await check("bubble hidden on an empty box, shown after typing", async () => {
    await page.click("#ta");
    await page.waitForTimeout(300);
    assert.equal(await bubble.isVisible(), false);
    await page.keyboard.type("hi sara i cant come tomorow");
    await bubble.waitFor({ state: "visible" });
    assert.equal(await ui.locator(".badge").textContent(), "D");
  });

  await check("click → streams into the card → ✓ replaces (textarea)", async () => {
    await bubble.click();
    await card.waitFor({ state: "visible" });
    await page.waitForTimeout(300);
    await shot("ext-card-streaming");
    await ui.locator(".ok:not([disabled])").waitFor();
    assert.equal(await ui.locator(".title").textContent(), "Daily");
    assert.equal(await ui.locator(".body").textContent(), "Hi! hi sara i cant come tomorow");
    assert.ok(lastBody.systemInstruction.parts[0].text.includes("REMOTE-MARK"), "downloaded tones are used");
    await shot("ext-card-done");
    await ok.click();
    await card.waitFor({ state: "hidden" });
    assert.equal(await page.inputValue("#ta"), "Hi! hi sara i cant come tomorow");
    assert.equal(await page.evaluate(() => document.activeElement.id), "ta"); // focus stayed in the box
  });

  await check("only the selection is rewritten", async () => {
    await page.fill("#ta", "first part. second part.");
    await page.evaluate(() => { const t = document.getElementById("ta"); t.focus(); t.setSelectionRange(12, 24); });
    await bubble.click();
    await ui.locator(".ok:not([disabled])").waitFor();
    await ok.click();
    await card.waitFor({ state: "hidden" });
    assert.equal(await page.inputValue("#ta"), "first part. Hi! second part.");
  });

  await check("right-click → tone menu → Email", async () => {
    await page.fill("#ta", "pls send me the file");
    await page.click("#ta");
    await bubble.click({ button: "right" });
    await ui.locator(".menu").waitFor({ state: "visible" });
    await shot("ext-menu");
    await ui.locator(".row", { hasText: "Email" }).click();
    await ui.locator(".ok:not([disabled])").waitFor();
    assert.equal(await ui.locator(".title").textContent(), "Email");
    assert.equal(lastBody.contents[0].parts[0].text, "<text>\npls send me the file\n</text>"); // text inside the markers
    assert.equal(await ui.locator(".badge").textContent(), "E");
    assert.equal(await sw.evaluate(() => chrome.storage.local.get("tone").then((s) => s.tone)), "formal");
  });

  await check("tone chip reruns on the same text; Esc closes", async () => {
    await ui.locator(".chip", { hasText: "Casual" }).click();
    await ui.locator(".ok:not([disabled])").waitFor();
    assert.equal(await ui.locator(".title").textContent(), "Casual");
    assert.equal(await ui.locator(".body").textContent(), "Hi! pls send me the file");
    await page.keyboard.press("Escape");
    await card.waitFor({ state: "hidden" });
    assert.equal(await page.inputValue("#ta"), "pls send me the file");
  });

  await check("text changed after the click → not replaced", async () => {
    await page.click("#ta");
    await bubble.click();
    await ui.locator(".ok:not([disabled])").waitFor();
    await page.click("#ta"); await page.keyboard.press("End"); await page.keyboard.type(" more");
    await ok.click();
    await ui.locator(".status.err").waitFor();
    assert.match(await ui.locator(".status").textContent(), /changed since you clicked/);
    assert.equal(await page.inputValue("#ta"), "pls send me the file more");
    await ui.locator(".x").click();
  });

  await check("one-line input: bubble beside it, replace works", async () => {
    await page.click("#in");
    await page.keyboard.type("thx for help");
    await bubble.waitFor({ state: "visible" });
    const b = await bubble.boundingBox(), i = await page.locator("#in").boundingBox();
    assert.ok(b.x >= i.x + i.width, "bubble should sit right of a one-line box");
    await bubble.click();
    await ui.locator(".ok:not([disabled])").waitFor();
    await ok.click();
    await card.waitFor({ state: "hidden" });
    assert.equal(await page.inputValue("#in"), "Hi! thx for help");
  });

  await check("rich editor: text replaced, signature kept, Ctrl+Z works", async () => {
    await page.click("#rich div >> nth=0");
    await bubble.waitFor({ state: "visible" });
    await bubble.click();
    await ui.locator(".ok:not([disabled])").waitFor();
    assert.equal(await ui.locator(".body").textContent(), "Hi! hello mr ahmadi\ni will send report friday");
    await shot("ext-rich-card");
    await ok.click();
    await card.waitFor({ state: "hidden" });
    const txt = await page.locator("#rich").innerText();
    assert.match(txt, /^Hi! hello mr ahmadi\ni will send report friday/);
    assert.match(txt, /Rasoul · sent from my laptop/);
    assert.equal(await page.locator("#rich .gmail_signature").count(), 1);
    await page.keyboard.press("Control+z");
    assert.match(await page.locator("#rich").innerText(), /^hello mr ahmadi/);
  });

  await check("keyboard shortcut message: 1st = rewrite, 2nd = replace", async () => {
    await page.fill("#ta", "see u soon");
    await page.click("#ta");
    await page.bringToFront();
    const fire = () => sw.evaluate(async () => { const [t] = await chrome.tabs.query({ active: true }); await chrome.tabs.sendMessage(t.id, { type: "run" }); });
    await fire();
    await ui.locator(".ok:not([disabled])").waitFor();
    await fire();
    await card.waitFor({ state: "hidden" });
    assert.equal(await page.inputValue("#ta"), "Hi! see u soon");
  });

  await check("bad key → clear error with a settings link", async () => {
    await sw.evaluate(() => chrome.storage.local.set({ keys: { gemini: "wrong" } }));
    await page.click("#ta");
    await bubble.click();
    await ui.locator(".status.err").waitFor();
    assert.match(await ui.locator(".status").textContent(), /rejected the request: API key not valid/);
    await shot("ext-card-error");
    await ui.locator(".x").click();
    await sw.evaluate(() => chrome.storage.local.remove("keys"));
    await bubble.click();
    await ui.locator(".status.err").waitFor();
    assert.match(await ui.locator(".status").textContent(), /Add your Gemini API key/);
    assert.equal(await ui.locator(".status a").textContent(), "Open settings ↗");
    await ui.locator(".x").click();
    await sw.evaluate(() => chrome.storage.local.set({ keys: { gemini: "test-key" } }));
  });

  await check("Hide on this site → gone here; settings → Show again → back", async () => {
    await page.click("#ta");
    await bubble.click({ button: "right" });
    await ui.locator(".row.off").click();
    await page.click("#ta"); await page.keyboard.type(" x");
    await page.waitForTimeout(300);
    assert.equal(await bubble.isVisible(), false);
    assert.deepEqual(await sw.evaluate(() => chrome.storage.local.get("hiddenSites").then((s) => s.hiddenSites)), ["127.0.0.1"]);
    const o = await ctx.newPage();
    await o.goto(`chrome-extension://${extId}/options.html`);
    await o.locator("#hiddenList .chip", { hasText: "127.0.0.1" }).click();
    assert.equal(await o.locator("#hiddenList .chip").count(), 0);
    await o.close();
    await page.bringToFront();
    await page.click("#ta"); await page.keyboard.type("y");
    await bubble.waitFor({ state: "visible" });
  });

  await check("pick a model in the menu; card shows it; ⇄ reruns with the next model", async () => {
    await sw.evaluate(() => chrome.storage.local.set({ keys: { gemini: "test-key", claude: "test-key" } }));
    await page.fill("#ta", "see you at 5");
    await page.click("#ta");
    await page.waitForTimeout(300); // state reloads after the key change
    await bubble.click({ button: "right" });
    assert.deepEqual(await ui.locator(".model-row .two span").allTextContents(), ["Gemini 3.5 Flash-Lite", "Gemini 3.1 Flash-Lite", "Claude Haiku 5.5"]);
    await shot("ext-menu-models");
    await ui.locator(".model-row", { hasText: "Claude Haiku 5.5" }).click();
    await page.waitForTimeout(200);
    await bubble.click();
    await ui.locator(".ok:not([disabled])").waitFor();
    assert.equal(await ui.locator(".body").textContent(), "Claude: see you at 5");
    assert.match(await ui.locator(".card .model").textContent(), /^Claude Haiku 5\.5\s+⇄$/);
    await shot("ext-card-claude");
    await ui.locator(".card .model").click(); // next: back to the first Gemini model
    await ui.locator(".body", { hasText: "Hi! see you at 5" }).waitFor();
    assert.match(await ui.locator(".card .model").textContent(), /^Gemini 3\.5 Flash-Lite/);
    await ui.locator(".x").click();
    await sw.evaluate(() => chrome.storage.local.set({ keys: { gemini: "test-key" }, provider: "gemini" }));
  });

  await check("dark mode card", async () => {
    await page.emulateMedia({ colorScheme: "dark" });
    await page.evaluate(() => { document.body.style.background = "#111"; });
    await page.click("#ta");
    await bubble.click();
    await ui.locator(".ok:not([disabled])").waitFor();
    await shot("ext-card-dark");
    await ui.locator(".x").click();
    await page.emulateMedia({ colorScheme: "light" });
  });

  await check("options page renders, Try-it box has the bubble", async () => {
    const o = await ctx.newPage();
    await o.goto(`chrome-extension://${extId}/options.html`);
    await o.locator("#tones .chip").first().waitFor();
    assert.equal(await o.locator("#key").inputValue(), "test-key");
    await o.click("#test");
    await o.locator("#testStatus", { hasText: "Key works" }).waitFor();
    assert.match(await o.locator("#testStatus").textContent(), /Key works/);
    // Models: edit → remove one → add one → restore defaults.
    const chips = () => o.locator("#models .chip").allTextContents();
    assert.deepEqual(await chips(), ["Gemini 3.5 Flash-Lite", "Gemini 3.1 Flash-Lite", "Edit"]);
    await o.click("#models .chip >> text=Edit");
    await o.click("#models .chip >> text=Gemini 3.1 Flash-Lite");
    assert.deepEqual(await chips(), ["Gemini 3.5 Flash-Lite  ✕", "Done"]);
    await o.click("#models .chip >> text=Gemini 3.5 Flash-Lite"); // the last one can't be removed
    assert.equal((await chips()).length, 2);
    await o.fill("#newModel", " gemini-3.5-flash ");
    await o.click("#addModel");
    assert.deepEqual(await chips(), ["Gemini 3.5 Flash-Lite  ✕", "gemini-3.5-flash  ✕", "Done"]);
    await o.screenshot({ path: path.join(SHOTS, "ext-options-models.png"), fullPage: true });
    assert.equal(await sw.evaluate(() => chrome.storage.local.get("models").then((s) => s.models.gemini)), "gemini-3.5-flash");
    await o.click("#restoreModels");
    await o.click("#models .chip >> text=Done");
    assert.deepEqual(await chips(), ["Gemini 3.5 Flash-Lite", "Gemini 3.1 Flash-Lite", "Edit"]);
    assert.equal(await o.locator("#models .chip.sel").textContent(), "Gemini 3.5 Flash-Lite");
    await o.click("#try");
    await o.keyboard.type("salam, man farda nemitoonam biam");
    await o.locator("tone-fixer-ui .bubble").waitFor({ state: "visible" });
    await o.screenshot({ path: path.join(SHOTS, "ext-options.png"), fullPage: true });
    await o.emulateMedia({ colorScheme: "dark" });
    await o.screenshot({ path: path.join(SHOTS, "ext-options-dark.png"), fullPage: true });
    // Update notice: a newer version on the updates branch.
    await o.emulateMedia({ colorScheme: "light" });
    await o.locator("#updateNew").waitFor();
    assert.equal(await o.locator("#newVersion").textContent(), "9.9.9");
    assert.match(await o.locator("#download").getAttribute("href"), /updates\/extension\/tone-fixer-extension-9\.9\.9\.zip$/);
    assert.equal(await sw.evaluate(() => chrome.action.getBadgeText({})), "NEW");
    await o.locator("main > .card", { hasText: "Tone Fixer 0." }).screenshot({ path: path.join(SHOTS, "ext-update-notice.png") });
    await o.close();
  });

  await ctx.close();
  server.close();
  console.log(results.join("\n"));
  const failed = results.filter((r) => r.startsWith("FAIL")).length;
  console.log(`\n${results.length - failed}/${results.length} passed · screenshots in ${path.relative(process.cwd(), SHOTS)}`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
