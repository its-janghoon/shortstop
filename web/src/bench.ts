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
