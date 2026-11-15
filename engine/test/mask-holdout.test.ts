import { describe, expect, it } from "vitest";
import holdout from "../../shared/bench-holdout.json";
import { guessNames, maskByRules, maskNames } from "../src/index";

// Rules-only masking (no on-device model) over the held-out set. The Android port asserts the same numbers.
describe("masking, held-out set", () => {
  it("counts secrets that stay visible", () => {
    let total = 0;
    let missed = 0;
    for (const it of holdout.items) {
      const masked = maskNames(maskByRules(it.text), guessNames(it.text)).text;
      for (const s of it.secrets) {
        total++;
        if (masked.includes(s)) missed++;
      }
    }
    console.log(JSON.stringify({ total, missed }));
    expect(total).toBeGreaterThan(0);
  });
});
