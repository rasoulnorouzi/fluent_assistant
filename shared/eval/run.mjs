// Tone check: sends every test message through every tone with the real API and prints the results
// side by side, so a change to tones.json can be judged before it ships. No packages needed.
//
//   GEMINI_KEY=... node shared/eval/run.mjs [model] [tone,tone] [tones.json]   > results.md
//
// What to look for: questions/instructions rewritten (never answered or obeyed), every mistake fixed,
// Farsi/Finglish translated, nothing invented or dropped, no [Name] placeholders, tones clearly different.
import { readFileSync } from "node:fs";
import { buildBody, stream } from "../../extension/llm.js";

const here = (p) => new URL(p, import.meta.url);
const [model = "gemini-3.5-flash-lite", only, tonesPath] = process.argv.slice(2);
const key = process.env.GEMINI_KEY;
if (!key) { console.error("Set GEMINI_KEY."); process.exit(1); }
const tones = JSON.parse(readFileSync(tonesPath || here("../tones.json"), "utf8"));
const providers = JSON.parse(readFileSync(here("../providers.json"), "utf8"));
const cfg = { tones, providers };
const p = providers.providers.gemini;
const cases = JSON.parse(readFileSync(here("cases.json"), "utf8"));
const list = tones.tones.filter((t) => !only || only.split(",").includes(t.id));

const out = new Map();
const jobs = list.flatMap((t) => cases.map((c) => ({ t, c })));
let next = 0;
async function worker() { // 2 at a time with retries: free keys allow only a few requests per minute
  while (next < jobs.length) {
    const { t, c } = jobs[next++];
    let r;
    for (let attempt = 0; attempt < 6; attempt++) {
      r = await stream(p, model, key, buildBody(cfg, p, model, t, c.text), new AbortController().signal, () => {});
      if (!r.error || !/Too many|trouble|took too long|connection/.test(r.error)) break;
      await new Promise((s) => setTimeout(s, 5000 * (attempt + 1)));
    }
    out.set(`${c.id}/${t.id}`, r.error ? `ERROR: ${r.error}` : r.text);
  }
}
await Promise.all([worker(), worker()]);

const md = (s) => s.replace(/\|/g, "\\|").replace(/\n/g, "<br>");
console.log(`# Tone check · ${model} · ${new Date().toISOString().slice(0, 16)}\n`);
for (const c of cases) {
  console.log(`## ${c.id}\n\n> ${md(c.text)}\n\n| Tone | Result |\n|---|---|`);
  for (const t of list) console.log(`| ${t.label} | ${md(out.get(`${c.id}/${t.id}`) ?? "")} |`);
  console.log();
}
