// Run shared/bench.json through the hosted bench page in Chromium, in three modes.
// Usage: node scripts/bench.mjs <out.jsonl> [chromium path]
// Needs playwright-core installed (any location on NODE_PATH). Resumes: rows already in out.jsonl are skipped.
import fs from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE ?? "playwright-core");
const out = process.argv[2] ?? "bench-results.jsonl";
const exe = process.argv[3];
const PAGE = process.env.BENCH_URL ?? "https://shortstop-b108a.web.app/bench.html";
const MODES = (process.env.BENCH_MODES ?? "rules,shortstop,cloudraw").split(",");
const GAP_MS = Number(process.env.BENCH_GAP_MS ?? 2500);

const items = JSON.parse(fs.readFileSync(new URL(`../shared/${process.env.BENCH_FILE ?? "bench.json"}`, import.meta.url))).items;
const done = new Set(
  fs.existsSync(out)
    ? fs.readFileSync(out, "utf8").split("\n").filter(Boolean).map((l) => {
        const r = JSON.parse(l);
        return r.error ? "" : `${r.id}|${r.mode}`;
      })
    : [],
);

const b = await chromium.launch(exe ? { executablePath: exe } : {});
const p = await b.newPage();
await p.goto(PAGE);
await p.waitForFunction(() => "shortstopBench" in window);
console.log("backend", await p.evaluate(() => window.shortstopBench.ready()));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
for (const mode of MODES) {
  for (const it of items) {
    if (done.has(`${it.id}|${mode}`)) continue;
    let row;
    for (let attempt = 0; attempt < 6; attempt++) {
      row = await p.evaluate(([t, m]) => window.shortstopBench.runOne(t, m).catch((e) => ({ error: String(e?.message ?? e) })), [it.text, mode]);
      if (!row.error || !/429|quota|exhausted|rate/i.test(row.error)) break;
      await sleep(20000 * (attempt + 1));
    }
    const leaked = (row.sent ?? []).length ? it.secrets.filter((s) => row.sent.some((x) => x.includes(s))) : [];
    fs.appendFileSync(out, JSON.stringify({ id: it.id, kind: it.kind, mode, ...row, sent: undefined, sentChars: (row.sent ?? []).join("").length, secrets: it.secrets.length, leaked }) + "\n");
    if (mode !== "rules") await sleep(GAP_MS);
  }
  console.log("mode done", mode);
}
await b.close();
