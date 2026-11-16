// Word rules for common scam tricks (FBI IC3 / FTC patterns). Used as hints for the models,
// and as the whole answer when no model can run.

interface Trick {
  re: RegExp;
  reason: string;
  weight: number;
}

const TRICKS: Trick[] = [
  { re: /\b(gift ?cards?|itunes|google play cards?|steam cards?)\b/i, reason: "asks for gift cards", weight: 2 },
  { re: /\b(wire|western union|moneygram|zelle|bitcoin|crypto|usdt|btc)\b/i, reason: "asks for money in a hard-to-undo way", weight: 2 },
  { re: /\b(verification|security|one[- ]time|6[- ]digit)\s+code\b|\bsend (me )?the codes?\b/i, reason: "asks for a code", weight: 2 },
  { re: /\b(don'?t|do not) tell\b|\bkeep (this|it) (a )?secret\b/i, reason: "asks you to keep it secret", weight: 2 },
  { re: /\b(within|in) (24|48) hours\b|\b(urgent|immediately|right away|act now|final notice|today only)\b/i, reason: "pushes you to hurry", weight: 1 },
  { re: /\b(account|card) (is|has been|was) (locked|suspended|frozen|disabled|blocked)\b|\bunusual (sign-in|activity|login)\b/i, reason: "says your account is locked", weight: 1 },
  { re: /\b(unpaid|outstanding|small|customs|redelivery|shipping) fee\b|\bpackage (is )?on hold\b/i, reason: "asks for a small fee to release a package", weight: 1 },
  { re: /\b(lost|broke|new) (my )?phone\b|\bthis is (a friend'?s|my new) number\b/i, reason: "sender says they have a new number", weight: 1 },
  { re: /\b(you('ve| have)? won|winner|lottery|prize|claim your reward)\b/i, reason: "says you won something", weight: 2 },
  { re: /\b(irs|tax office|police|warrant|arrest)\b.*\b(pay|fine|penalty)\b/i, reason: "threatens you with the law", weight: 2 },
  { re: /\b(guaranteed|double your|risk[- ]free) (returns?|profit|money)\b/i, reason: "promises sure profit", weight: 2 },
  { re: /\b(remote access|anydesk|teamviewer|install this app)\b/i, reason: "asks for remote access to your device", weight: 2 },
  {
    // Only when the message asks for them, not when a form just has an "SSN:" field.
    re: /\b(send|give|enter|confirm|verify|share|provide|reply with|tell (me|us)|update)\b[^.\n]{0,40}\b(password|pin|social security number|ssn|login)\b/i,
    reason: "asks for private login details",
    weight: 1,
  },
];

export interface TextSignals {
  reasons: string[];
  score: number;
}

export function textSignals(text: string): TextSignals {
  const hits = TRICKS.filter((t) => t.re.test(text));
  return { reasons: hits.map((t) => t.reason), score: hits.reduce((n, t) => n + t.weight, 0) };
}
