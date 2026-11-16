// Benchmark page. Runs one test text in one mode with the real Firebase backend, so App Check applies.
// Modes:
//   rules      - no model, rules only
//   cloudraw   - a plain cloud checker: the ORIGINAL text goes to cloud Gemini (the baseline we replace)
//   shortstop  - the real engine: first look (on device if the browser has Gemini Nano, else cloud),
//                masked text only, escalate when unsure
import prompts from "../../shared/prompts.json";
import { analyze } from "../../engine/src/engine";
import { configFromEnv, connect, type Backend } from "../../engine/src/firebase";
import { parseAnswer } from "../../engine/src/parse";

const cfg = configFromEnv(import.meta.env);
const ready: Promise<Backend | undefined> = cfg ? connect(cfg) : Promise.resolve(undefined);

type Mode = "rules" | "cloudraw" | "shortstop";
export interface BenchRow {
  verdict: string;
  ms: number;
  cloudCalls: number;
  onDevice: boolean;
  /** Every string that left the device. */
  sent: string[];
  error?: string;
}

async function runOne(text: string, mode: Mode): Promise<BenchRow> {
  const b = await ready;
  const t = performance.now();
  if (mode === "cloudraw") {
    if (!b) throw new Error("no backend");
    const prompt = prompts.triage.replace("{hints}", "none").replace("{text}", text);
    const call = await b.models.deep(prompt);
    return { verdict: parseAnswer(call.text).verdict, ms: Math.round(performance.now() - t), cloudCalls: 1, onDevice: false, sent: [prompt] };
  }
  const r = await analyze(
    { text },
    mode === "rules" || !b ? {} : { models: b.models, config: b.config, isReported: async () => false },
  );
  const sent = r.receipt.filter((s) => s.sent).map((s) => s.sent!);
  // A failed model call falls back to rules in the app. For measuring, it is an error, not a result.
  const failed = r.receipt.find((s) => /failed|unavailable \(/i.test(s.what));
  if (mode === "shortstop" && failed) throw new Error(failed.what);
  return {
    verdict: r.verdict,
    ms: Math.round(performance.now() - t),
    cloudCalls: r.receipt.filter((s) => s.where === "in_cloud").length,
    onDevice: r.receipt.some((s) => s.where === "on_device" && /First look/.test(s.what)),
    sent,
  };
}

Object.assign(window, {
  shortstopBench: {
    ready: async () => {
      const b = await ready;
      return { backend: !!b, device: b ? await b.onDeviceStatus() : "none", model: b?.config.cloud_model };
    },
    runOne,
  },
});

// Self-running mode, for browsers that must not be automated (Chrome turns built-in AI off under automation):
//   bench.html?run=bench-final2&modes=device,shortstop
// Runs every text, then downloads one JSONL file with the same row format as scripts/bench.mjs.
const SETS: Record<string, () => Promise<{ default: { items: { id: string; kind: string; text: string; secrets: string[] }[] } }>> = {
  bench: () => import("../../shared/bench.json"),
  "bench-final": () => import("../../shared/bench-final.json"),
  "bench-final2": () => import("../../shared/bench-final2.json"),
};
const q = new URLSearchParams(location.search);
const runSet = q.get("run");
if (runSet && SETS[runSet]) void selfRun(runSet, (q.get("modes") ?? "device,shortstop").split(",") as RunMode[]);
if (q.has("timing")) void timing(Number(q.get("timing")) || 12);

/** Where does on-device time go? Compares the Firebase SDK path with direct Prompt API sessions. */
async function timing(n: number) {
  const out = document.getElementById("out")!;
  const b = await ready;
  const items = (await SETS["bench-final2"]!()).default.items.filter((_, i) => i % 12 === 0).slice(0, n);
  type LMS = { prompt(i: string, o?: unknown): Promise<string>; clone(): Promise<LMS>; destroy(): void };
  const lm = (self as unknown as { LanguageModel: { create(o: unknown): Promise<LMS> } }).LanguageModel;
  const opts = { expectedInputs: [{ type: "text", languages: ["en"] }], expectedOutputs: [{ type: "text", languages: ["en"] }] };
  const { $comment: _c, ...schema } = (await import("../../shared/verdict.schema.json")).default as Record<string, unknown>;
  const split = (p: string) => {
    const i = p.indexOf("\nRule checks already found:");
    return [p.slice(0, i), p.slice(i + 1)] as const;
  };
  const res: Record<string, number[]> = { sdk: [], create: [], createPrompt: [], clone: [], clonePrompt: [], cloneNoSchema: [] };
  const t0 = performance.now();
  const base = await lm.create({ ...opts, initialPrompts: [{ role: "system", content: split(prompts.triage)[0] }] });
  const baseMs = performance.now() - t0;
  for (const [k, it] of items.entries()) {
    out.textContent = `timing ${k + 1}/${items.length}`;
    const prompt = prompts.triage.replace("{hints}", "none").replace("{text}", it.text);
    let t = performance.now();
    await b!.models.triage(prompt);
    res.sdk!.push(performance.now() - t);
    t = performance.now();
    const s = await lm.create(opts);
    res.create!.push(performance.now() - t);
    t = performance.now();
    await s.prompt(prompt, { responseConstraint: schema });
    res.createPrompt!.push(performance.now() - t);
    s.destroy();
    t = performance.now();
    const c = await base.clone();
    res.clone!.push(performance.now() - t);
    t = performance.now();
    await c.prompt(split(prompt)[1], { responseConstraint: schema });
    res.clonePrompt!.push(performance.now() - t);
    c.destroy();
    t = performance.now();
    const c2 = await base.clone();
    await c2.prompt(split(prompt)[1] + "\nAnswer with short JSON: verdict, confidence, at most 3 signals, action, names.");
    res.cloneNoSchema!.push(performance.now() - t);
    c2.destroy();
  }
  const med = (x: number[]) => Math.round([...x].sort((a, b) => a - b)[Math.floor(x.length / 2)]!);
  const summary = { n: items.length, baseCreateMs: Math.round(baseMs), ...Object.fromEntries(Object.entries(res).map(([k, v]) => [k, med(v)])) };
  out.textContent = JSON.stringify(summary);
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([JSON.stringify(summary, null, 1)], { type: "application/json" }));
  a.download = "shortstop-timing.json";
  document.body.append(a);
  a.click();
}

type RunMode = Mode | "device";
async function selfRun(set: string, modes: RunMode[]) {
  const out = document.getElementById("out")!;
  const b = await ready;
  const device = b ? await b.onDeviceStatus() : "none";
  const items = (await SETS[set]!()).default.items;
  const lines: string[] = [];
  const say = (s: string) => {
    out.textContent = `${set} · device ${device}\n${s}`;
    document.title = s;
  };
  for (const mode of modes) {
    let n = 0;
    for (const it of items) {
      n++;
      say(`${mode} ${n}/${items.length}`);
      let row: Partial<BenchRow> = {};
      for (let attempt = 0; attempt < 4; attempt++) {
        try {
          row = await runMode(it.text, mode, b);
          break;
        } catch (e) {
          row = { error: String((e as Error).message ?? e) };
          if (!/429|quota|rate|exhausted/i.test(row.error!)) break;
          await new Promise((r) => setTimeout(r, 15000 * (attempt + 1)));
        }
      }
      const leaked = row.sent ? it.secrets.filter((s) => row.sent!.some((x) => x.includes(s))) : [];
      lines.push(JSON.stringify({ id: it.id, kind: it.kind, mode, ...row, sent: undefined, sentChars: (row.sent ?? []).join("").length, secrets: it.secrets.length, leaked, device }));
    }
  }
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([lines.join("\n") + "\n"], { type: "application/x-ndjson" }));
  a.download = `shortstop-${set}-${modes.join("+")}.jsonl`;
  document.body.append(a);
  a.click();
  say(`done ${lines.length} rows`);
}

/** "device" is on-device only: the engine in strict mode, nothing leaves the device. */
async function runMode(text: string, mode: RunMode, b: Backend | undefined): Promise<BenchRow> {
  if (mode !== "device") return runOne(text, mode);
  if (!b) throw new Error("no backend");
  const t = performance.now();
  const r = await analyze({ text }, { models: b.models, config: { ...b.config, strict_on_device: true }, isReported: async () => false });
  const failed = r.receipt.find((s) => /failed|unavailable \(/i.test(s.what));
  if (failed) throw new Error(failed.what);
  return {
    verdict: r.verdict,
    ms: Math.round(performance.now() - t),
    cloudCalls: r.receipt.filter((s) => s.where === "in_cloud").length,
    onDevice: r.receipt.some((s) => s.where === "on_device" && /First look/.test(s.what)),
    sent: r.receipt.filter((s) => s.sent).map((s) => s.sent!),
  };
}
