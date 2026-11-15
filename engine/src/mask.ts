import type { MaskEntry, MaskKind } from "./types";

interface Rule {
  kind: MaskKind;
  re: RegExp;
  check?: (match: string) => boolean;
}

const digits = (s: string) => s.replace(/\D/g, "");

/** Luhn check, so random long numbers are not called cards. */
export function luhn(num: string): boolean {
  const d = digits(num);
  if (d.length < 13 || d.length > 19) return false;
  let sum = 0;
  for (let i = 0; i < d.length; i++) {
    let n = Number(d[d.length - 1 - i]);
    if (i % 2 === 1) {
      n *= 2;
      if (n > 9) n -= 9;
    }
    sum += n;
  }
  return sum % 10 === 0;
}

// Order matters: longer, more specific patterns first.
const RULES: Rule[] = [
  { kind: "EMAIL", re: /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g },
  { kind: "RRN", re: /\b\d{6}-[1-8]\d{6}\b/g },
  { kind: "SSN", re: /\b(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}\b/g },
  { kind: "CARD", re: /\b(?:\d[ -]?){12,18}\d\b/g, check: luhn },
  {
    kind: "ACCOUNT",
    re: /\b(?:acct|account|a\/c|iban|routing)\b[^\d\n]{0,12}([A-Z]{2}\d{2}[A-Z0-9]{8,30}|\d[\d -]{5,20}\d)/gi,
  },
  {
    kind: "PHONE",
    re: /(?:\+\d{1,3}[ .-]?)?(?:\(\d{2,4}\)[ .-]?|\b\d{2,4}[ .-])\d{3,4}[ .-]\d{4}\b/g,
  },
];

export interface Masked {
  text: string;
  entries: MaskEntry[];
}

function nextToken(entries: MaskEntry[], kind: MaskKind, value: string): string {
  const same = entries.find((e) => e.kind === kind && e.value === value);
  if (same) return same.token;
  const n = entries.filter((e) => e.kind === kind).length + 1;
  const token = `[${kind}_${n}]`;
  entries.push({ token, kind, value });
  return token;
}

/** Replace private data found by rules with placeholders like [PHONE_1]. Runs on device, always. */
export function maskByRules(input: string, start: MaskEntry[] = []): Masked {
  const entries = [...start];
  let text = input;
  for (const rule of RULES) {
    text = text.replace(rule.re, (whole: string, group?: unknown) => {
      // ACCOUNT keeps its label ("account") and hides only the number.
      const value = rule.kind === "ACCOUNT" && typeof group === "string" ? group : whole;
      if (rule.check && !rule.check(value)) return whole;
      if (/^\[[A-Z]+_\d+\]$/.test(value)) return whole;
      const token = nextToken(entries, rule.kind, value);
      return whole.replace(value, token);
    });
  }
  return { text, entries };
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** Hide names and addresses the on-device model found. Ignores anything not literally in the text. */
export function maskNames(input: Masked, names: string[] = []): Masked {
  const entries = [...input.entries];
  let text = input.text;
  const clean = [...new Set(names.map((n) => n.trim()))]
    .filter((n) => n.length >= 2 && n.length <= 80 && !/^\[[A-Z]+_\d+\]$/.test(n))
    .sort((a, b) => b.length - a.length);
  for (const name of clean) {
    const re = new RegExp(`(?<![\\p{L}\\d])${escapeRe(name)}(?![\\p{L}\\d])`, "gu");
    if (!re.test(text)) continue;
    re.lastIndex = 0;
    const token = nextToken(entries, "NAME", name);
    text = text.replace(re, token);
  }
  return { text, entries };
}

const FAMILY = new Set(["mom", "mum", "dad", "grandma", "grandpa", "honey", "dear", "sir", "madam", "there", "all", "team", "everyone", "customer"]);

/** Rule-based guess of names and street addresses, for when no model is around (paste guard, no AI). */
export function guessNames(text: string): string[] {
  const out = new Set<string>();
  const NAME = String.raw`[A-Z][a-z]+(?:[ -][A-Z][a-z]+){0,2}`;
  const label = new RegExp(
    String.raw`\b(?:Tenant|Landlord|Name|Patient|Client|Customer|Employee|Buyer|Seller|Signed|Dear|Hi|Hello|Mr\.?|Mrs\.?|Ms\.?|Dr\.?)[:,]?\s+(${NAME})`,
    "g",
  );
  for (const m of text.matchAll(label)) if (!FAMILY.has(m[1]!.toLowerCase())) out.add(m[1]!);
  const street = /\b\d{1,5} [A-Z][a-z]+(?: [A-Z][a-z]+)? (?:St|Street|Ave|Avenue|Rd|Road|Blvd|Dr|Drive|Ln|Lane|Way|Ct|Court)\b\.?/g;
  for (const m of text.matchAll(street)) out.add(m[0].replace(/\.$/, ""));
  return [...out];
}

/** Put the real values back, on device, before showing a cloud answer. */
export function unmask(text: string, entries: MaskEntry[]): string {
  let out = text;
  for (const e of entries) out = out.split(e.token).join(e.value);
  return out;
}

/** True if any masked value still appears in text that is about to leave the device. */
export function leaks(text: string, entries: MaskEntry[]): string[] {
  return entries.filter((e) => text.includes(e.value)).map((e) => e.token);
}
