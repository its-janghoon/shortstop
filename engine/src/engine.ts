import prompts from "../../shared/prompts.json";
import defaults from "../../shared/config.defaults.json";
import { checkLinks, registrable, REPORTED_REASON } from "./links";
import { guessNames, leaks, maskByRules, maskNames, unmask, type Masked } from "./mask";
import { parseAnswer } from "./parse";
import { textSignals, type TextSignals } from "./signals";
import type { EngineConfig, LinkFinding, ModelAnswer, Models, ReceiptStep, Result, Verdict } from "./types";

export interface Input {
  text?: string;
  /** One screenshot. Photos never leave the device: they need an on-device model. */
  image?: Blob;
}

export interface Deps {
  models?: Models;
  config?: Partial<EngineConfig>;
  /** Firestore lookup: has this registrable domain been reported by others? */
  isReported?: (domain: string) => Promise<boolean>;
  now?: () => number;
}

export class NeedsOnDeviceError extends Error {
  constructor() {
    super("Photo checks need on-device AI. Paste the text instead.");
  }
}

const RANK: Record<Verdict, number> = { safe: 0, suspicious: 1, scam: 2 };
const worst = (a: Verdict, b: Verdict): Verdict => (RANK[a] >= RANK[b] ? a : b);

const ACTIONS: Record<Verdict, string> = {
  scam: "Do not click or reply. If it names your bank or a company, call them using the number on their official website or card.",
  suspicious: "Wait before you act. Check with the company using a number you already trust.",
  safe: "No warning signs found. Still never share codes or passwords.",
};

function fill(tpl: string, vars: Record<string, string>): string {
  return tpl.replace(/\{(\w+)\}/g, (_, k: string) => vars[k] ?? "");
}

function hintsFor(links: LinkFinding[], masked: Masked, words: TextSignals): string {
  const out: string[] = [...words.reasons];
  for (const l of links) for (const r of l.reasons) out.push(r);
  // Private data in a message is not a scam sign by itself (forms, contracts, receipts have it), so it is not a hint.
  if (masked.entries.length) out.push(`${masked.entries.length} private values were masked on device (normal, not a warning sign)`);
  return out.length ? out.join("; ") : "none";
}

const strongLink = (reasons: string[]) =>
  reasons.some((r) => r.startsWith("looks like") || r.includes("another alphabet") || r === REPORTED_REASON);

/** Rules-only answer, used when no model can run at all. */
function rulesAnswer(links: LinkFinding[], words: TextSignals): ModelAnswer {
  const linkReasons = links.flatMap((l) => l.reasons);
  const strong = strongLink(linkReasons) || words.score >= 3;
  const verdict: Verdict = strong ? "scam" : linkReasons.length || words.score ? "suspicious" : "safe";
  return {
    verdict,
    confidence: strong ? 0.7 : 0.3,
    signals: [...linkReasons, ...words.reasons].slice(0, 5),
    action: ACTIONS[verdict],
  };
}

function chunks(text: string, max: number, limit = 4): string[] {
  if (text.length <= max) return [text];
  const out: string[] = [];
  for (let i = 0; i < text.length && out.length < limit; i += max) out.push(text.slice(i, i + max));
  return out;
}

export async function analyze(input: Input, deps: Deps = {}): Promise<Result> {
  const cfg: EngineConfig = { ...defaults, ...deps.config };
  const now = deps.now ?? (() => performance.now());
  const receipt: ReceiptStep[] = [];
  const step = async <T>(where: ReceiptStep["where"], what: string, fn: () => Promise<T> | T, sent?: string) => {
    const t = now();
    const out = await fn();
    receipt.push({ where, what, ms: Math.round(now() - t), ...(sent !== undefined ? { sent } : {}) });
    return out;
  };

  let raw = input.text?.trim() ?? "";
  if (!raw && !input.image) throw new Error("nothing to check");
  if (input.image && !deps.models) throw new NeedsOnDeviceError();

  // 1. Rules, on device, always.
  let masked = await step("rules", "Hid private data with rules", () => maskNames(maskByRules(raw), guessNames(raw)));
  let links = await step("rules", "Checked links", () => checkLinks(raw));
  let words = textSignals(raw);
  if (deps.isReported) {
    for (const l of links) {
      if (await deps.isReported(registrable(l.host))) l.reasons.push(REPORTED_REASON);
    }
  }

  let triage: ModelAnswer | undefined;
  let triageWhere: "on_device" | "in_cloud" = "on_device";
  if (!deps.models) {
    const a = rulesAnswer(links, words);
    return finish(a, "rules");
  }
  const models = deps.models;

  // 2. Triage. Prefer on device. Photos must stay on device.
  const parts = input.image ? [masked.text] : chunks(masked.text, cfg.on_device_max_chars);
  const names: string[] = [];
  for (const part of parts) {
    const tpl = input.image ? prompts.image : prompts.triage;
    const prompt = fill(tpl, { text: part || "(no text, read the picture)", hints: hintsFor(links, masked, words) });
    let call;
    const t = now();
    try {
      call = await models.triage(prompt, input.image);
    } catch (e) {
      if (input.image) throw new NeedsOnDeviceError();
      receipt.push({ where: "on_device", what: `On-device model unavailable (${(e as Error).message})`, ms: 0 });
      break;
    }
    if (input.image && call.source !== "on_device") throw new NeedsOnDeviceError();
    triageWhere = call.source;
    receipt.push({
      where: call.source,
      what: call.source === "on_device" ? "First look on device" : "First look in the cloud (no on-device model)",
      ms: Math.round(now() - t),
      ...(call.source === "in_cloud" ? { sent: prompt } : {}),
    });
    let a: ModelAnswer;
    try {
      a = parseAnswer(call.text);
    } catch {
      a = { verdict: "suspicious", confidence: 0, signals: [], action: "" };
    }
    names.push(...(a.names ?? []));
    if (input.image && a.text) {
      raw = a.text;
      masked = maskByRules(raw, masked.entries);
      links = checkLinks(raw);
      words = textSignals(raw);
    }
    triage = triage
      ? {
          ...triage,
          verdict: worst(triage.verdict, a.verdict),
          confidence: Math.min(triage.confidence, a.confidence),
          signals: [...triage.signals, ...a.signals],
        }
      : a;
  }
  // Time for the triage steps is folded into the last receipt row.
  masked = maskNames(masked, names);

  const long = masked.text.length > cfg.on_device_max_chars;
  const unsure = !triage || triage.confidence < cfg.escalate_below_confidence || triage.verdict === "suspicious";
  if ((!unsure && !long) || cfg.strict_on_device) {
    const a = triage ?? rulesAnswer(links, words);
    return finish(a, triage ? triageWhere : "rules");
  }

  // 3. Deep look in the cloud. Only masked text leaves the device.
  const triageNote = triage ? `${triage.verdict} (${triage.confidence.toFixed(2)}): ${triage.signals.join("; ")}` : "not available";
  let prompt = fill(prompts.deep, { text: masked.text, hints: hintsFor(links, masked, words), triage: unmaskFree(triageNote, masked) });
  const leaked = leaks(prompt, masked.entries);
  if (leaked.length) {
    // Should never happen: the triage note could quote private data. Mask it again.
    for (const e of masked.entries) prompt = prompt.split(e.value).join(e.token);
  }
  try {
    const t = now();
    const call = await models.deep(prompt);
    receipt.push({ where: "in_cloud", what: "Deep check in the cloud (masked text only)", ms: Math.round(now() - t), sent: prompt });
    const a = parseAnswer(call.text);
    return finish(a, "in_cloud");
  } catch (e) {
    receipt.push({ where: "in_cloud", what: `Cloud check failed (${(e as Error).message})`, ms: 0 });
    return finish(triage ?? rulesAnswer(links, words), triage ? triageWhere : "rules");
  }

  function finish(a: ModelAnswer, decidedBy: Result["decidedBy"]): Result {
    let verdict = a.verdict;
    const linkReasons = links.flatMap((l) => l.reasons);
    // Safety net: a model "safe" never hides a fake link or a pile of scam tricks.
    if ((strongLink(linkReasons) || words.score >= 3) && verdict === "safe") verdict = "suspicious";
    const extra = verdict === "safe" ? [] : words.reasons;
    const signals = mergeSignals(linkReasons, [...a.signals, ...extra].map((s) => unmask(s, masked.entries)));
    const action = a.action && a.verdict === verdict ? unmask(a.action, masked.entries) : ACTIONS[verdict];
    return {
      verdict,
      confidence: a.confidence,
      signals,
      action,
      decidedBy,
      ...(triage ? { firstLook: { where: triageWhere, verdict: triage.verdict, confidence: triage.confidence } } : {}),
      links,
      masked: masked.text,
      mask: masked.entries,
      receipt,
    };
  }
}

/** Make sure a note built on device carries placeholders, not values. */
function unmaskFree(note: string, masked: Masked): string {
  let out = note;
  for (const e of masked.entries) out = out.split(e.value).join(e.token);
  return out;
}

/** What a reason is about, so "says your account is locked" and "Claims account is locked" count once. */
const TOPICS: [string, RegExp][] = [
  ["link", /\b(link|url|domain|site|website|address|look-?alike|typo|spoof|mimic|impersonat\w* (a )?(site|domain))/i],
  ["hurry", /\b(hurry|urgen\w*|immediate\w*|right away|within \d+|deadline|act now|pressure|rush)/i],
  ["locked", /\b(locked|suspend\w*|frozen|blocked|disabled|unusual (sign|log))/i],
  ["fee", /\b(fee|small payment|redeliver\w*|customs|package on hold)/i],
  ["code", /\b(code|otp|one-time|verification number)/i],
  ["gift", /\bgift ?cards?\b/i],
  ["secret", /\b(secret|don'?t tell|keep (it|this) (quiet|between))/i],
  ["won", /\b(won|winner|prize|lottery|reward)/i],
  ["law", /\b(arrest|warrant|police|legal action|lawsuit|fine)\b/i],
  ["profit", /\b(profit|return|invest\w*|double)/i],
  ["remote", /\b(remote access|anydesk|teamviewer|install)/i],
  ["login", /\b(password|pin|login|log in|credential|ssn|social security)/i],
  ["money", /\b(wire|crypto|bitcoin|zelle|transfer money|send money)/i],
];
const topicOf = (s: string) => TOPICS.find(([, re]) => re.test(s))?.[0];

/** Link findings first (they are exact), then model and rule reasons, one per topic, at most 5. */
export function mergeSignals(linkReasons: string[], others: string[]): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  const add = (s: string) => {
    const key = s.trim().toLowerCase();
    if (!key || seen.has(key)) return;
    seen.add(key);
    out.push(s.trim());
  };
  linkReasons.forEach(add);
  const topics = new Set<string>(linkReasons.length ? ["link"] : []);
  for (const s of others) {
    const t = topicOf(s);
    if (t && topics.has(t)) continue;
    if (t) topics.add(t);
    add(s);
  }
  return out.slice(0, 5);
}
