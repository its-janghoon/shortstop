import { describe, expect, it } from "vitest";
import { leaks, luhn } from "../src/mask";
import { maskByRules, maskNames, unmask } from "../src/index";

describe("maskByRules", () => {
  it("hides email, phone, card and account", () => {
    const raw =
      "Hi, write to jane.doe@acmemail.com or call (555) 201-3344. Card 4111 1111 1111 1111, account no. 123456789012.";
    const m = maskByRules(raw);
    expect(m.text).toContain("[EMAIL_1]");
    expect(m.text).toContain("[PHONE_1]");
    expect(m.text).toContain("[CARD_1]");
    expect(m.text).toContain("[ACCOUNT_1]");
    expect(m.text).toContain("account no.");
    expect(leaks(m.text, m.entries)).toEqual([]);
    expect(unmask(m.text, m.entries)).toBe(raw);
  });

  it("does not call random numbers cards", () => {
    expect(luhn("1234 5678 9012 3456")).toBe(false);
    const m = maskByRules("Order 1234 5678 9012 3456 shipped");
    expect(m.text).not.toContain("[CARD_");
  });

  it("hides US SSN and Korean RRN", () => {
    const m = maskByRules("SSN 123-45-6789 and 900101-1234567");
    expect(m.text).toBe("SSN [SSN_1] and [RRN_1]");
  });

  it("reuses the same token for the same value", () => {
    const m = maskByRules("Call 555-201-3344. Again: 555-201-3344.");
    expect(m.text).toBe("Call [PHONE_1]. Again: [PHONE_1].");
  });
});

describe("maskNames", () => {
  it("hides names the model found, only when present, longest first", () => {
    const m = maskNames(maskByRules("Jane Doe lives at 12 Oak St. Jane will pay."), ["Jane Doe", "Jane", "Bob", "12 Oak St"]);
    expect(m.text).toBe("[NAME_2] lives at [NAME_1]. [NAME_3] will pay.");
    expect(m.entries.map((e) => e.value)).toEqual(["12 Oak St", "Jane Doe", "Jane"]);
  });

  it("does not hide part of a longer word", () => {
    const m = maskNames(maskByRules("Annabel met Ann."), ["Ann"]);
    expect(m.text).toBe("Annabel met [NAME_1].");
  });

  it("does not hide a word that is part of a link", () => {
    const m = maskNames(maskByRules("Ask acrnebank at https://acrnebank.com/secure"), ["acrnebank"]);
    expect(m.text).toBe("Ask [NAME_1] at https://acrnebank.com/secure");
  });
});

import { guessNames } from "../src/index";

describe("guessNames", () => {
  it("finds labelled names and street addresses", () => {
    const t = "Tenant: John Smith, 48 Maple Ave, Springfield. Dear Jane Doe, thanks.";
    // Unknown capitalized words such as the town name are masked too: extra masking is safe.
    expect(guessNames(t)).toEqual(expect.arrayContaining(["John Smith", "Jane Doe", "48 Maple Ave"]));
  });
});
