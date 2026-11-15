import type { ModelAnswer, Verdict } from "./types";

const VERDICTS: Verdict[] = ["safe", "suspicious", "scam"];

/** Read a model answer. Small models sometimes wrap JSON in prose or code fences, so dig it out. */
export function parseAnswer(raw: string): ModelAnswer {
  const start = raw.indexOf("{");
  const end = raw.lastIndexOf("}");
  if (start < 0 || end <= start) throw new Error("model answer has no JSON object");
  const obj = JSON.parse(raw.slice(start, end + 1)) as Record<string, unknown>;

  const verdict = String(obj.verdict ?? "").toLowerCase() as Verdict;
  if (!VERDICTS.includes(verdict)) throw new Error(`bad verdict: ${String(obj.verdict)}`);
  let confidence = Number(obj.confidence);
  if (!Number.isFinite(confidence)) confidence = 0;
  if (confidence > 1 && confidence <= 100) confidence /= 100;
  confidence = Math.min(1, Math.max(0, confidence));

  const strings = (v: unknown, max: number) =>
    Array.isArray(v) ? v.filter((x): x is string => typeof x === "string" && x.trim() !== "").slice(0, max) : [];

  return {
    verdict,
    confidence,
    signals: strings(obj.signals, 5),
    action: typeof obj.action === "string" ? obj.action.trim() : "",
    names: strings(obj.names, 10),
    ...(typeof obj.text === "string" && obj.text.trim() ? { text: obj.text.trim() } : {}),
  };
}
