import type { MaskEntry, MaskKind } from "./types";
import WORDS_TXT from "../../shared/words.txt?raw";

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
    // Policy, member, claim and other ID numbers: a label, then a code with at least 4 digits.
    kind: "ACCOUNT",
    re: /\b(?:policy|member|membership|claim|customer|patient|student|employee|case|reference|ref|license|licence|passport|id)\b(?:\s*(?:no\.?|number|#|id))?[:\s#]{1,3}((?=[A-Z0-9-]*\d[A-Z0-9-]*\d[A-Z0-9-]*\d[A-Z0-9-]*\d)[A-Z0-9][A-Z0-9-]{3,24}[A-Z0-9])\b/gi,
  },
  {
    kind: "PHONE",
    re: /(?:\+\d{1,3}[ .-]?)?(?:\(\d{2,4}\)[ .-]?|\b\d{2,4}[ .-])\d{3,4}[ .-]\d{4}\b/g,
  },
  // Local numbers without an area code: "555-0147".
  { kind: "PHONE", re: /(?<![\d-])\b\d{3}[.-]\d{4}\b(?![\d-])/g },
  // Birth dates after a label: "DOB 1989-04-12", "born 04/12/1989".
  {
    kind: "DOB",
    re: /\b(?:dob|d\.o\.b\.?|date of birth|birth ?date|born)\b[^\d\n]{0,6}(\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}|[A-Z][a-z]{2,8}\.? \d{1,2},? \d{4})/gi,
  },
  // Codes such as policy, member or case IDs: "EG-77120", "GRP-5521".
  { kind: "ACCOUNT", re: /\b[A-Z]{1,4}-\d{4,10}(?:-[A-Z0-9]{1,6})?\b/g },
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
      const value = (rule.kind === "ACCOUNT" || rule.kind === "DOB") && typeof group === "string" ? group : whole;
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
  // Also hide the parts of a full name, so "Andrew" alone is caught when the model said "Andrew Shaw".
  const parts = names.flatMap((n) =>
    /^\d/.test(n.trim()) ? [] : n.trim().split(/[\s,]+/).filter((p) => p.length >= 3 && /^\p{Lu}/u.test(p) && !NOT_NAME.has(p) && (GIVEN.has(p) || unknownWord(p))),
  );
  const clean = [...new Set([...names, ...parts].map((n) => n.trim()))]
    // A model may hand back a placeholder ("NAME_1" or "[NAME_1]") as a name; it is already masked.
    .filter((n) => n.length >= 2 && n.length <= 80 && !/^\[?[A-Z]+_\d+\]?$/.test(n))
    .sort((a, b) => b.length - a.length);
  for (const name of clean) {
    // Not inside a link or an e-mail address: "acrnebank" in acrnebank.com/secure is a site, not a person.
    // Not inside a placeholder either: "[NAME_1]" stays as it is.
    const re = new RegExp(`(?<![\\p{L}\\d./@\\[_-])${escapeRe(name)}(?![\\p{L}\\d_]|\\.\\p{L}|[/@\\]-])`, "gu");
    if (!re.test(text)) continue;
    re.lastIndex = 0;
    const token = nextToken(entries, "NAME", name);
    text = text.replace(re, token);
  }
  return { text, entries };
}

const FAMILY = new Set(["mom", "mum", "dad", "grandma", "grandpa", "honey", "dear", "sir", "madam", "there", "all", "team", "everyone", "customer"]);

/** Common English given names (US census top names, both sexes). Used only when no on-device model can find names. */
const GIVEN = new Set(
  (
    "James John Robert Michael Mike William David Richard Joseph Thomas Charles Chris Christopher Daniel Dan Matthew Matt Anthony Donald Steven Steve Paul Andrew Andy Joshua Kenneth Kevin Brian Brandon George Timothy Tim Ronald Edward Jason Jeffrey Ryan Jacob Gary Nicholas Nick Eric Jonathan Stephen Larry Justin Scott Benjamin Ben Gregory Samuel Sam Raymond Patrick Alexander Alex Dennis Jerry Tyler Aaron Jose Henry Adam Douglas Nathan Peter Zachary Kyle Walter Harold Carl Jeremy Keith Roger Gerald Ethan Arthur Terry Sean Lawrence Joe Noah Jesse Albert Bryan Billy Bruce Willie Dylan Alan Ralph Gabriel Roy Juan Wayne Eugene Logan Randy Louis Russell Vincent Philip Bobby Johnny Bradley Leo Lucas Liam Oliver Owen Mason Elijah Luke Isaac Jake Tom Tony Ted Fred Hank Jim Jimmy Bob Ken Rick Ron Greg Doug Phil " +
    "Mary Patricia Jennifer Linda Elizabeth Liz Barbara Susan Jessica Sarah Karen Lisa Nancy Betty Margaret Sandra Ashley Kimberly Kim Emily Donna Michelle Carol Amanda Melissa Deborah Stephanie Rebecca Sharon Laura Cynthia Dorothy Amy Kathleen Angela Shirley Emma Brenda Pamela Nicole Anna Samantha Katherine Kate Christine Debra Rachel Carolyn Janet Maria Olivia Heather Helen Catherine Diane Julie Victoria Joyce Lauren Kelly Christina Ruth Joan Judith Evelyn Hannah Andrea Megan Cheryl Jacqueline Madison Teresa Abigail Sophia Martha Sara Gloria Janice Kathryn Ann Isabella Judy Charlotte Julia Grace Alice Jean Denise Frances Danielle Marilyn Natalie Beverly Diana Brittany Theresa Kayla Alexis Doris Lori Tiffany Mia Ava Chloe Ella Lily Zoe Nora Ellie Ruby Dana Jane Jenny Jen Becky Beth Meg Tina Wendy Holly Paula Monica Erin Tracy Stacy Yuna Mina Jisoo Minji Hana"
  ).split(" "),
);
/** Words that follow a first name but are not surnames. */
const NOT_SURNAME = new Set(["The", "And", "From", "Here", "Is", "Was", "Will", "Can", "Said", "At", "On", "In", "To", "For", "Has", "Had", "Just", "Again", "Please"]);
/** Capitalized words that are rarely part of a person's name. */
const NOT_NAME = new Set(
  (
    "The A An And Or Of To In On At By For From With Your Our My His Her Their This That These Those It We You I He She They Please Thanks Thank Dear Hi Hello " +
    "Monday Tuesday Wednesday Thursday Friday Saturday Sunday January February March April May June July August September October November December " +
    "Bank Account Card Credit Debit Street St Avenue Ave Road Rd Drive Lane Way Court Blvd Suite Apt Unit Floor City County State North South East West New Old " +
    "Inc LLC Ltd Corp Co Company Group Services Service Support Team Department Office Center Centre Hospital Clinic Medical School University College Agency Tax " +
    "Acme Contoso Fabrikam Northwind Globex Initech Umbrella Wingtip Tailspin Adatum Litware Zelle Venmo Wi Fi Gmail Outlook iPhone Android Chrome Google Apple Amazon Microsoft PayPal Bitcoin Ethereum USD EUR KRW Parcel Delivery Order Invoice Payment Customer Client Tenant Landlord Patient Employee Manager Director " +
    "Agreement Contract Lease Policy Terms Section Clause Notice Statement Report Summary Date Total Amount Number Phone Email Address Name Re Subject Ticket Case " +
    "Mr Mrs Ms Dr Prof Sir Madam Mom Dad Grandma Grandpa Security Fraud Alert Prime Gold Premium Plus Pro App Store Online Mobile Web Wallet Coin Crypto Rewards"
  ).split(" "),
);

/** Single capitalized word that is not a common non-name. */
function isPersonish(s: string): boolean {
  const w = s.split(/[ -]/)[0]!.toLowerCase();
  return !FAMILY.has(w) && !["the", "your", "our", "a", "an", "this", "that", "not", "just", "here", "sorry", "back", "calling", "texting", "writing", "contoso", "acme"].includes(w);
}

/** Rule-based guess of names and street addresses, for when no model is around (paste guard, no AI). */
export function guessNames(text: string): string[] {
  const out = new Set<string>();
  const NAME = String.raw`[A-Z][a-z]+(?:[ -][A-Z][a-z]+){0,2}`;
  const label = new RegExp(
    String.raw`\b(?:Tenant|Landlord|Name|Patient|Client|Customer|Employee|Buyer|Seller|Signed|Dear|Hi|Hello|Mr\.?|Mrs\.?|Ms\.?|Dr\.?)[:,]?\s+(${NAME})`,
    "g",
  );
  for (const m of text.matchAll(label)) if (!FAMILY.has(m[1]!.toLowerCase())) out.add(m[1]!);
  // Titles: "Mrs. Chen", "Dr. Alvarez", "Coach Daniels".
  for (const m of text.matchAll(new RegExp(String.raw`\b(?:Mr|Mrs|Ms|Mx|Dr|Prof|Coach|Officer|Agent|Nurse|Judge)\.?\s+(${NAME})`, "g"))) out.add(m[1]!);
  // An initial and a surname: "R. Kerrigan".
  for (const m of text.matchAll(/\b[A-Z]\. [A-Z][a-z]{2,}\b/g)) out.add(m[0]);
  // A name on its own line (resume, letter header): "Aisha Rahman".
  for (const m of text.matchAll(/^([A-Z][a-z]+(?: [A-Z]\.)? [A-Z][a-z]+(?:-[A-Z][a-z]+)?)$/gm)) {
    if (m[1]!.split(" ").every((w) => !NOT_NAME.has(w.replace(".", "")))) out.add(m[1]!);
  }
  // "Welcome aboard, Marcus!" / "Congrats, Nina?": a single name right after a comma, ending with ! or ?.
  for (const m of text.matchAll(/,\s+([A-Z][a-z]{2,15})[!?]/g)) if (!NOT_NAME.has(m[1]!) && isPersonish(m[1]!)) out.add(m[1]!);
  // "this is Dana", "it's Mike", "I'm Grace", "my name is ...": a person introducing themselves.
  const intro = new RegExp(String.raw`\b(?:[Tt]his is|[Ii]t's|[Ii]t is|I'm|I am|[Mm]y name is|[Nn]ame's)\s+(${NAME})`, "g");
  for (const m of text.matchAll(intro)) if (isPersonish(m[1]!)) out.add(m[1]!);
  // Sign-offs: "- Mike", "Thanks, Dana", "Love,\nGrace".
  const sign = new RegExp(String.raw`(?:^|\n|\s)(?:[-~]\s*|(?:Thanks|Thank you|Cheers|Best|Regards|Best regards|Kind regards|Sincerely|Love|Warmly|XO|Sent by)[,!]?\s+)(${NAME})\b`, "g");
  for (const m of text.matchAll(sign)) if (isPersonish(m[1]!)) out.add(m[1]!);
  // Common given names on their own, with an optional surname.
  const given = new RegExp(String.raw`\b(${[...GIVEN].join("|")})(?:\s+[A-Z][a-z]+(?:-[A-Z][a-z]+)?)?\b`, "g");
  for (const m of text.matchAll(given)) {
    const full = m[0];
    const last = full.split(/\s+/)[1];
    out.add(last && !NOT_SURNAME.has(last) ? full : m[1]!);
  }
  // "Firstname Lastname" mid-sentence: two capitalized words that are not common non-name words.
  // Masking a few extra words is safe; sending a real name is not.
  for (const m of text.matchAll(/(?<=[a-z,;:(]\s|\b(?:and|with|by|for|to|from|of|or)\s)([A-Z][a-z]{1,15}) ([A-Z][a-z]{1,15}(?:-[A-Z][a-z]+)?)\b/g)) {
    if (!NOT_NAME.has(m[1]!) && !NOT_NAME.has(m[2]!) && (GIVEN.has(m[1]!) || unknownWord(m[1]!) || unknownWord(m[2]!))) out.add(`${m[1]} ${m[2]}`);
  }
  const street = /\b\d{1,5} [A-Z][a-z]+(?: [A-Z][a-z]+)? (?:St|Street|Ave|Avenue|Rd|Road|Blvd|Dr|Drive|Ln|Lane|Way|Ct|Court|Terrace|Place|Pl|Circle|Cir|Square|Parkway|Pkwy|Highway|Hwy|Row|Close|Crescent)\b\.?/g;
  for (const m of text.matchAll(street)) out.add(m[0].replace(/\.$/, ""));
  // Any capitalized word that is not an English word is probably a name ("Priya", "Okafor", "Tomás").
  // Runs of them make one name ("Kenji Tanaka", "Rosa M. Fernández"); ALL-CAPS runs too ("FIONA ADEBAYO").
  const W = String.raw`\p{Lu}[\p{Ll}'’]+(?:-\p{Lu}?[\p{Ll}'’]+)?`;
  const run = new RegExp(String.raw`(?<![\p{L}\d@._/-])(${W}(?:[ \t]+(?:\p{Lu}\.[ \t]+)?${W}){0,3})(?![\p{L}\d@_/-]|\.[a-z])`, "gu");
  for (const m of text.matchAll(run)) {
    const parts = m[1]!.split(/\s+/).filter((p) => !/^\p{Lu}\.$/u.test(p));
    if (parts.some((p) => unknownWord(p))) {
      const keep = trimRun(m[1]!);
      if (keep) out.add(keep);
    }
  }
  for (const m of text.matchAll(/(?<![\p{L}\d])(\p{Lu}{2,}(?:\s+\p{Lu}{2,}){1,3})(?![\p{L}\d])/gu)) {
    const parts = m[1]!.split(/\s+/);
    if (parts.every((p) => unknownWord(p[0] + p.slice(1).toLowerCase()))) out.add(m[1]!);
  }
  // Keep the longest form: drop "John" when "John Smith" is there.
  const all = [...out];
  return all.filter((n) => !all.some((o) => o !== n && o.includes(n)));
}

let words: Set<string> | undefined;
/** True for a capitalized word that is not in the English word list and not a known non-name. */
function unknownWord(w: string): boolean {
  words ??= new Set(WORDS_TXT.split("\n"));
  if (/['’](?:m|ll|re|ve|d|t)$/i.test(w)) return false; // I'm, We'll, Don't
  const base = w.replace(/['’]s?$/, "");
  if (base.includes("-")) return base.split("-").some((p) => p.length > 1 && unknownWord(p[0]!.toUpperCase() + p.slice(1)));
  const lower = base.toLowerCase();
  if (MONTHS.has(lower)) return false;
  if (NOT_NAME.has(base) || FAMILY.has(lower) || SLANG.has(lower)) return false;
  if (words.has(lower) || words.has(lower.replace(/s$/, ""))) return GIVEN.has(base);
  return base.length >= 2;
}

/** Drop leading and trailing words of a run that are ordinary words ("Hi Priya" -> "Priya"). */
function trimRun(r: string): string {
  const parts = r.split(/\s+/);
  while (parts.length && !/^\p{Lu}\.$/u.test(parts[0]!) && !unknownWord(parts[0]!)) parts.shift();
  while (parts.length && !/^\p{Lu}\.$/u.test(parts[parts.length - 1]!) && !unknownWord(parts[parts.length - 1]!)) parts.pop();
  return parts.join(" ");
}

const MONTHS = new Set("jan feb mar apr jun jul aug sep sept oct nov dec mon tue tues wed thu thur thurs fri sat sun".split(" "));
/** Casual words that are not in the dictionary. */
const SLANG = new Set("txt msg msgs pic pics info appt dept acct govt intl ok okay hey hiya yo lol omg btw pls plz thx ty ur u gonna wanna gotta yep nope yeah ya hmm ugh wow oops xoxo xo ps fyi asap tbh idk imo brb ttyl np".split(" "));

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
