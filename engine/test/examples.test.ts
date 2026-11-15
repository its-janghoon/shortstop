import { describe, expect, it } from "vitest";
import examples from "../../shared/examples.json";
import { analyze } from "../src/index";

// Rules alone (no AI) must already get the demo examples right.
describe("demo examples, rules only", () => {
  for (const ex of examples.examples) {
    it(ex.id, async () => {
      const r = await analyze({ text: ex.text });
      if (ex.kind === "scam") expect(r.verdict).toBe("scam");
      if (ex.kind === "safe") expect(r.verdict).toBe("safe");
      if (ex.kind === "mask") {
        for (const secret of ["John Smith", "48 Maple Ave", "(555) 410-7781", "john.smith@acmemail.com", "7720 1938 4410"]) {
          expect(r.masked).not.toContain(secret);
        }
      }
    });
  }
});
