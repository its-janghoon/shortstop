import { describe, expect, it } from "vitest";
import { mergeSignals } from "../src/engine";

describe("mergeSignals", () => {
  it("keeps one reason per topic and all link findings", () => {
    const out = mergeSignals(
      ["looks like acmebank.com but is acrnebank.com"],
      ["Claims account is locked", "Fake website URL using typo-squatting", "Urges immediate verification", "says your account is locked", "pushes you to hurry"],
    );
    expect(out).toEqual(["looks like acmebank.com but is acrnebank.com", "Claims account is locked", "Urges immediate verification"]);
  });
  it("drops exact repeats and caps at 5", () => {
    const out = mergeSignals([], ["a", "A", "b", "c", "d", "e", "f"]);
    expect(out).toEqual(["a", "b", "c", "d", "e"]);
  });
});
