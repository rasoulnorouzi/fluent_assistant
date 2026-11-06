// node --test extension/test  (no packages). Mirrors android/src/test/.../LlmTest.kt.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import * as llm from "../llm.js";

const root = new URL("../../", import.meta.url);
const read = (p) => readFileSync(new URL(p, root), "utf8");
const res = (name) => read(`android/src/test/resources/${name}`);
const cfg = { tones: JSON.parse(read("shared/tones.json")), providers: JSON.parse(read("shared/providers.json")) };
const gemini = cfg.providers.providers.gemini;
const claude = cfg.providers.providers.claude;
const tone = (id) => cfg.tones.tones.find((t) => t.id === id);

test("maxTokens scales and clamps", () => {
  assert.equal(llm.maxTokens(cfg, 5), 200);
  assert.equal(llm.maxTokens(cfg, 300), 100 * 3 + 150);
  assert.equal(llm.maxTokens(cfg, 100_000), 4096);
});

test("gemini body", () => {
  const m = gemini.models[0].id;
  const b = llm.buildBody(cfg, gemini, m, tone("formal"), "hello there");
  assert.ok(b.systemInstruction.parts[0].text.includes(tone("formal").style));
  assert.ok(!b.systemInstruction.parts[0].text.includes("{style}"));
  assert.equal(b.contents[0].parts[0].text, "<text>\nhello there\n</text>");
  assert.equal(b.generationConfig.maxOutputTokens, 200);
  assert.equal(b.generationConfig.thinkingConfig.thinkingLevel, "minimal");
  assert.equal(llm.requestUrl(gemini, m), `https://generativelanguage.googleapis.com/v1beta/models/${m}:streamGenerateContent?alt=sse`);
});

test("claude body and browser header", () => {
  const m = claude.models[0].id;
  const b = llm.buildBody(cfg, claude, m, tone("casual"), "yo");
  assert.equal(b.model, m);
  assert.equal(b.stream, true);
  assert.equal(b.messages[0].content, "<text>\nyo\n</text>");
  assert.equal(b.thinking.type, "disabled");
  assert.equal(b.output_config.effort, "low");
  assert.ok(!("temperature" in b));
  const h = llm.requestHeaders(claude, "k");
  assert.equal(h["anthropic-dangerous-direct-browser-access"], "true");
  assert.equal(h["anthropic-version"], "2026-10-21");
  assert.equal(h["x-api-key"], "k");
});

test("text goes inside the markers literally ($ patterns stay as typed)", () => {
  const b = llm.buildBody(cfg, gemini, gemini.models[0].id, tone("daily"), "pay $& now $1");
  assert.equal(b.contents[0].parts[0].text, "<text>\npay $& now $1\n</text>");
  assert.equal(cfg.tones.tones.length, 8);
  assert.ok(!cfg.tones.tones.some((t) => t.id === "friendly"));
});

test("custom model gets no extra; deepMerge", () => {
  assert.ok(!("thinkingConfig" in llm.buildBody(cfg, gemini, "gemini-9-ultra", tone("daily"), "x").generationConfig));
  const base = { a: { x: 1, y: 2 }, b: 1, c: [1] };
  llm.deepMerge(base, { a: { y: 3, z: 4 }, b: { k: true }, c: [2, 3] });
  assert.deepEqual(base, { a: { x: 1, y: 3, z: 4 }, b: { k: true }, c: [2, 3] });
});

test("sse lines", () => {
  assert.equal(llm.sseData('data: {"a":1}\r'), '{"a":1}');
  assert.equal(llm.sseData('data:{"a":1}'), '{"a":1}');
  assert.equal(llm.sseData("event: ping"), null);
  assert.equal(llm.sseData("data: [DONE]"), null);
});

const parseAll = (format, sse) => {
  let text = "", stop = null;
  for (const d of sse.split("\n").map(llm.sseData).filter(Boolean)) {
    const c = llm.parseChunk(format, JSON.parse(d)); text += c.text; if (c.stop) stop = c.stop;
  }
  return [text, stop];
};

test("recorded streams", () => {
  assert.deepEqual(parseAll("gemini", res("gemini_ok.sse")),
    ["Dear Mr. Ahmadi,\n\nPlease accept my apologies for the delay. I will send the report to you by Friday.\n\nBest regards,", "done"]);
  assert.deepEqual(parseAll("gemini", res("gemini_cut.sse")), ["Dear Mr. Ahmadi, I will send", "cut"]);
  assert.deepEqual(parseAll("claude", res("claude_ok.sse")), ["Hi Sara! I'm sorry, I can't make it tomorrow.", "done"]);
  const err = res("claude_overloaded.sse").split("\n").map(llm.sseData).filter(Boolean)
    .map((d) => llm.parseChunk("claude", JSON.parse(d))).find((c) => c.error);
  assert.equal(err.error, "Overloaded");
});

test("gemini thought parts, safety, prompt block", () => {
  assert.equal(llm.parseChunk("gemini", { candidates: [{ content: { parts: [{ text: "hmm", thought: true }, { text: "Hi" }] } }] }).text, "Hi");
  assert.equal(llm.parseChunk("gemini", { candidates: [{ finishReason: "SAFETY" }] }).stop, "blocked");
  assert.equal(llm.parseChunk("gemini", { promptFeedback: { blockReason: "SAFETY" } }).stop, "blocked");
});

test("error messages", () => {
  assert.equal(llm.errorMessage(404, res("gemini_404.json")), "Model not found. Pick another in Settings.");
  assert.equal(llm.errorMessage(401, "{}"), "API key not accepted. Check it in Settings.");
  assert.equal(llm.errorMessage(400, '{"error":{"message":"bad thing"}}'), "The provider rejected the request: bad thing");
  assert.equal(llm.errorMessage(400, "not json"), "The provider rejected the request.");
  assert.equal(llm.errorMessage(529, null), "The provider is having trouble. Try again.");
});

test("text helpers", () => {
  assert.equal(llm.cleanKey(" AIza​ABC-123_x \n"), "AIzaABC-123_x");
  assert.equal(llm.keepOuterSpace("  hi there \n", "Hi there."), "  Hi there. \n");
  assert.ok(llm.looksFarsi("سلام"));
  assert.ok(!llm.looksFarsi("salam"));
});

// ---------- full stream() over real HTTP (local server) ----------

async function serve(status, body, fn, { slowMs = 0 } = {}) {
  let seenKey = null;
  const server = createServer(async (req, rsp) => {
    seenKey = req.headers["x-goog-api-key"] ?? req.headers["x-api-key"];
    for await (const _ of req); // drain
    rsp.writeHead(status, { "content-type": "text/event-stream" });
    if (slowMs) { rsp.write(body.slice(0, 40)); await new Promise((r) => setTimeout(r, slowMs)); }
    rsp.end(slowMs ? body.slice(40) : body);
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  try { await fn(`http://127.0.0.1:${server.address().port}`); } finally { server.close(); }
  return seenKey;
}
const local = (p, base) => ({ ...p, url: `${base}/{model}`, headers: {}, browserHeaders: {} });
const never = new AbortController().signal;

test("stream end to end (gemini)", async () => {
  const key = await serve(200, res("gemini_ok.sse"), async (base) => {
    const pieces = [];
    const out = await llm.stream(local(gemini, base), "m", "test-key", {}, never, (t) => pieces.push(t));
    assert.equal(out.error, null);
    assert.equal(out.stop, "done");
    assert.ok(pieces.length >= 3);
    assert.equal(pieces.join(""), out.text);
  });
  assert.equal(key, "test-key");
});

test("stream: HTTP error, Claude cut survives message_stop, refusal, garbage", async () => {
  await serve(404, res("gemini_404.json"), async (base) => {
    assert.equal((await llm.stream(local(gemini, base), "m", "k", {}, never, () => {})).error, "Model not found. Pick another in Settings.");
  });
  await serve(200, res("claude_ok.sse").replace('"stop_reason":"end_turn"', '"stop_reason":"max_tokens"'), async (base) => {
    const out = await llm.stream(local(claude, base), "m", "k", {}, never, () => {});
    assert.equal(out.stop, "cut"); assert.equal(out.error, null);
  });
  await serve(200, res("claude_ok.sse").replace('"stop_reason":"end_turn"', '"stop_reason":"refusal"'), async (base) => {
    assert.equal((await llm.stream(local(claude, base), "m", "k", {}, never, () => {})).error, "The provider blocked this text.");
  });
  await serve(200, "data: not json\n\n", async (base) => {
    assert.equal((await llm.stream(local(gemini, base), "m", "k", {}, never, () => {})).error, "Unexpected response from the provider.");
  });
});

test("stream: cancel, bad key character, no connection", async () => {
  await serve(200, res("gemini_ok.sse"), async (base) => {
    const ac = new AbortController();
    const p = llm.stream(local(gemini, base), "m", "k", {}, ac.signal, () => {});
    setTimeout(() => ac.abort(), 50);
    assert.equal((await p).stop, "cancelled");
  }, { slowMs: 500 });
  const closed = await new Promise((r) => { const s = createServer().listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => r(`http://127.0.0.1:${p}`)); }); });
  const bad = await llm.stream(local(gemini, closed), "m", "abc\u0001def", {}, never, () => {});
  assert.equal(bad.error, "The API key has an invalid character. Paste it again in Settings.");
  assert.equal((await llm.stream(local(gemini, closed), "m", "k", {}, never, () => {})).error, "No connection.");
});

test("model list: defaults, remove, add, fallback, old custom model kept", () => {
  const ids = gemini.models.map((m) => m.id);
  assert.deepEqual(llm.modelList(gemini, "gemini", {}), ids);
  assert.equal(llm.currentModel(gemini, "gemini", {}), ids[0]);
  const s = { models: { gemini: ids[1] }, modelLists: { gemini: [ids[0], "gemini-9-pro"] } };
  assert.equal(llm.currentModel(gemini, "gemini", s), ids[0]); // picked one was removed
  s.models.gemini = "gemini-9-pro";
  assert.equal(llm.currentModel(gemini, "gemini", s), "gemini-9-pro");
  assert.deepEqual(llm.modelList(gemini, "gemini", { modelLists: { gemini: [] } }), ids); // never empty
  assert.deepEqual(llm.modelList(gemini, "gemini", { models: { gemini: "old-custom" } }), [...ids, "old-custom"]);
  // A built-in model added back gets its extra settings again (thinking off).
  assert.equal(llm.buildBody(cfg, gemini, ids[1], tone("daily"), "x").generationConfig.thinkingConfig.thinkingLevel, "minimal");
});

test("updates: downloaded config is used only when valid and not older; version compare", () => {
  const bundled = { tones: cfg.tones, providers: cfg.providers };
  const newTones = { ...cfg.tones, tones: [cfg.tones.tones[0], { ...cfg.tones.tones[1], label: "Everyday" }] };
  assert.equal(llm.chooseConfig(bundled, { tones: newTones, providers: cfg.providers }).tones.tones[1].label, "Everyday");
  assert.equal(llm.chooseConfig(bundled, null).tones, cfg.tones);
  assert.equal(llm.chooseConfig(bundled, { tones: { ...newTones, version: 1 } }).tones, cfg.tones); // older format
  assert.equal(llm.chooseConfig(bundled, { tones: { ...newTones, tones: [] } }).tones, cfg.tones); // broken
  assert.equal(llm.chooseConfig(bundled, { tones: { ...newTones, defaultTone: "gone" } }).tones, cfg.tones);
  assert.equal(llm.chooseConfig(bundled, { providers: { providers: {} } }).providers, cfg.providers);
  assert.ok(llm.isNewerVersion("0.10.0", "0.9.3"));
  assert.ok(llm.isNewerVersion("0.2.1", "0.2.0"));
  assert.ok(!llm.isNewerVersion("0.2.0", "0.2.0"));
  assert.ok(!llm.isNewerVersion("0.1.9", "0.2"));
});
