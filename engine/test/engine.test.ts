import { describe, expect, it } from "vitest";
import { analyze, NeedsOnDeviceError, type Models } from "../src/index";

const answer = (o: Record<string, unknown>) => JSON.stringify({ signals: [], action: "", ...o });

function fake(opts: {
  triage?: (p: string) => string;
  deep?: (p: string) => string;
  triageSource?: "on_device" | "in_cloud";
  noDevice?: boolean;
}) {
  const sent: string[] = [];
  const models: Models = {
    triage: async (p) => {
      if (opts.noDevice) throw new Error("no model");
      if (opts.triageSource === "in_cloud") sent.push(p);
      return { text: opts.triage?.(p) ?? answer({ verdict: "safe", confidence: 0.95 }), source: opts.triageSource ?? "on_device" };
    },
    deep: async (p) => {
      sent.push(p);
      return { text: opts.deep?.(p) ?? answer({ verdict: "scam", confidence: 0.9 }), source: "in_cloud" };
    },
  };
  return { models, sent };
}

const SCAM = "Hi Jane Doe, your Acme Bank account 123456789012 is locked. Verify at acrnebank.com/verify or call 555-201-3344.";

describe("analyze", () => {
  it("stops on device when sure", async () => {
    const f = fake({ triage: () => answer({ verdict: "scam", confidence: 0.95, signals: ["asks for money"] }) });
    const r = await analyze({ text: "Pay $1.99 now at acme-parcel-redelivery.co" }, { models: f.models });
    expect(r.verdict).toBe("scam");
    expect(r.decidedBy).toBe("on_device");
    expect(f.sent).toEqual([]);
    expect(r.receipt.some((s) => s.sent)).toBe(false);
  });

  it("sends only masked text to the cloud when unsure", async () => {
    const f = fake({
      triage: () => answer({ verdict: "suspicious", confidence: 0.5, names: ["Jane Doe"] }),
      deep: () => answer({ verdict: "scam", confidence: 0.92, action: "Call Acme Bank, not [PHONE_1].", signals: ["fake site"] }),
    });
    const r = await analyze({ text: SCAM }, { models: f.models });
    expect(r.decidedBy).toBe("in_cloud");
    expect(r.verdict).toBe("scam");
    expect(f.sent).toHaveLength(1);
    for (const secret of ["Jane Doe", "123456789012", "555-201-3344"]) expect(f.sent[0]).not.toContain(secret);
    expect(f.sent[0]).toContain("[NAME_1]");
    expect(r.action).toBe("Call Acme Bank, not 555-201-3344.");
    expect(r.signals.join()).toMatch(/looks like acmebank.com/);
  });

  it("strict mode never calls the cloud", async () => {
    const f = fake({ triage: () => answer({ verdict: "suspicious", confidence: 0.4 }) });
    const r = await analyze({ text: SCAM }, { models: f.models, config: { strict_on_device: true } });
    expect(f.sent).toEqual([]);
    expect(r.decidedBy).toBe("on_device");
  });

  it("without on-device model, cloud sees only rule-masked text and the receipt says so", async () => {
    const f = fake({ triageSource: "in_cloud", triage: () => answer({ verdict: "scam", confidence: 0.9 }) });
    const r = await analyze({ text: SCAM }, { models: f.models });
    expect(f.sent[0]).not.toContain("123456789012");
    expect(r.receipt.find((s) => s.where === "in_cloud")?.sent).toBeDefined();
  });

  it("works with rules only when no model exists", async () => {
    const r = await analyze({ text: SCAM });
    expect(r.decidedBy).toBe("rules");
    expect(r.verdict).toBe("scam");
  });

  it("never lets a safe model answer hide a look-alike link", async () => {
    const f = fake({ triage: () => answer({ verdict: "safe", confidence: 0.99 }) });
    const r = await analyze({ text: "Login: paypa1.com" }, { models: f.models });
    expect(r.verdict).toBe("suspicious");
  });

  it("uses community reports", async () => {
    const f = fake({ triage: () => answer({ verdict: "safe", confidence: 0.99 }) });
    const r = await analyze({ text: "Track it at shipnow-help.com" }, { models: f.models, isReported: async (d) => d === "shipnow-help.com" });
    expect(r.signals).toContain("other Shortstop users reported this site");
    expect(r.verdict).toBe("suspicious");
  });

  it("photos need on-device AI", async () => {
    const f = fake({ triageSource: "in_cloud" });
    await expect(analyze({ image: new Blob(["x"]) }, { models: f.models })).rejects.toBeInstanceOf(NeedsOnDeviceError);
  });

  it("photo text read on device is masked before the cloud", async () => {
    const f = fake({
      triage: () => answer({ verdict: "suspicious", confidence: 0.4, text: "Jane Doe call 555-201-3344 now", names: ["Jane Doe"] }),
    });
    const r = await analyze({ image: new Blob(["x"]) }, { models: f.models });
    expect(f.sent[0]).toContain("[NAME_1] call [PHONE_1]");
    expect(r.decidedBy).toBe("in_cloud");
  });

  it("falls back to triage when the cloud fails", async () => {
    const models: Models = {
      triage: async () => ({ text: answer({ verdict: "suspicious", confidence: 0.5 }), source: "on_device" }),
      deep: async () => {
        throw new Error("quota");
      },
    };
    const r = await analyze({ text: SCAM }, { models });
    expect(r.decidedBy).toBe("on_device");
    expect(r.receipt.at(-1)?.what).toMatch(/failed/);
  });
});
