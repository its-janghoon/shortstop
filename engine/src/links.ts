import known from "../../shared/known-domains.json";
import type { LinkFinding } from "./types";

const URL_RE =
  /\b((?:https?:\/\/)?(?:[a-z0-9\u00a1-\uffff](?:[a-z0-9\u00a1-\uffff-]{0,61}[a-z0-9\u00a1-\uffff])?\.)+(?:xn--[a-z0-9-]+|[a-z\u00a1-\uffff]{2,24})(?::\d{2,5})?(?:\/[^\s<>"')\]]*)?)/giu;

/** Swaps scammers use to make a fake host look real. */
const LOOKALIKE: [RegExp, string][] = [
  [/rn/g, "m"],
  [/vv/g, "w"],
  [/0/g, "o"],
  [/1/g, "l"],
  [/3/g, "e"],
  [/5/g, "s"],
  [/\|/g, "l"],
];

export function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  const prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let last = prev[0]!;
    prev[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const tmp = prev[j]!;
      prev[j] = Math.min(prev[j]! + 1, prev[j - 1]! + 1, last + (a[i - 1] === b[j - 1] ? 0 : 1));
      last = tmp;
    }
  }
  return prev[b.length]!;
}

/** Last two labels, or three for things like co.uk. Good enough for lookalike checks. */
export function registrable(host: string): string {
  const parts = host.split(".");
  const second = parts[parts.length - 2] ?? "";
  const n = parts.length > 2 && second.length <= 3 && ["co", "com", "org", "net", "gov", "ac"].includes(second) ? 3 : 2;
  return parts.slice(-n).join(".");
}

function label(domain: string): string {
  return domain.split(".")[0] ?? domain;
}

function normalize(s: string): string {
  let out = s.toLowerCase();
  for (const [re, to] of LOOKALIKE) out = out.replace(re, to);
  return out;
}

const mixedScript = (s: string) => /[a-z]/i.test(s) && /[^\x00-\x7f]/.test(s);

export function extractLinks(text: string): string[] {
  const found = new Set<string>();
  for (const m of text.matchAll(URL_RE)) {
    const raw = m[1]!.replace(/[.,;:!?]+$/, "");
    // Skip placeholders and e-mail domains.
    if (/^\[/.test(raw)) continue;
    const before = text[m.index! - 1];
    if (before === "@") continue;
    found.add(raw);
  }
  return [...found];
}

export function checkLink(raw: string): LinkFinding {
  const withScheme = /^https?:\/\//i.test(raw) ? raw : `http://${raw}`;
  let host = "";
  try {
    host = new URL(withScheme).hostname.toLowerCase();
  } catch {
    host = raw.toLowerCase();
  }
  const reasons: string[] = [];
  const reg = registrable(host);
  const tld = host.split(".").pop() ?? "";

  if (known.domains.includes(reg)) {
    return { url: raw, host, reasons };
  }
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) reasons.push("link goes to a bare IP address");
  if (host.split(".").some((p) => p.startsWith("xn--")) || mixedScript(raw.split("/")[2] ?? raw)) {
    reasons.push("link uses look-alike letters from another alphabet");
  }
  if (known.shorteners.includes(reg)) reasons.push("link is shortened, so you cannot see where it goes");
  if (known.risky_tlds.includes(tld)) reasons.push(`link ends in .${tld}, common in scams`);
  if (/^http:\/\//i.test(raw)) reasons.push("link is not secure (http)");

  const regLabel = label(reg);
  const flat = normalize(regLabel.replace(/-/g, ""));
  for (const real of known.domains) {
    const realLabel = label(real);
    if (realLabel.length < 4) continue;
    const near = levenshtein(flat, realLabel) <= (realLabel.length >= 8 ? 2 : 1);
    const brandInside = flat.includes(realLabel) || host.split(".").slice(0, -2).includes(realLabel);
    if (near || brandInside) {
      reasons.push(`looks like ${real} but is ${reg}`);
      break;
    }
  }
  if ((regLabel.match(/-/g) ?? []).length >= 2) reasons.push("link has many dashes, common in fake sites");
  return { url: raw, host, reasons };
}

/** Rule check for every link in the text. Runs on device, always. */
export function checkLinks(text: string): LinkFinding[] {
  return extractLinks(text).map(checkLink);
}

export const REPORTED_REASON = "other Shortstop users reported this site";
