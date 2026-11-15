export type Verdict = "safe" | "suspicious" | "scam";

/** What a model returns, after parsing and checking. */
export interface ModelAnswer {
  verdict: Verdict;
  confidence: number;
  signals: string[];
  action: string;
  /** Names and addresses the triage model found. Never sent anywhere. */
  names?: string[];
  /** Words read from a screenshot (image triage only). Stays on device. */
  text?: string;
}

export type Where = "on_device" | "in_cloud" | "rules";

/** One model call as seen by the engine. Real apps wrap Firebase AI Logic; tests use a fake. */
export interface ModelCall {
  text: string;
  source: Exclude<Where, "rules">;
}

export interface Models {
  /**
   * Triage pass. Should prefer on-device (Firebase InferenceMode.PREFER_ON_DEVICE,
   * or ONLY_ON_DEVICE in strict mode). Throws when no model can run.
   */
  triage(prompt: string, image?: Blob): Promise<ModelCall>;
  /** Deep pass. Always in the cloud (InferenceMode.ONLY_IN_CLOUD). Only ever receives masked text. */
  deep(prompt: string): Promise<ModelCall>;
}

export interface EngineConfig {
  escalate_below_confidence: number;
  on_device_max_chars: number;
  strict_on_device: boolean;
}

export interface MaskEntry {
  token: string;
  kind: MaskKind;
  value: string;
}

export type MaskKind = "EMAIL" | "PHONE" | "CARD" | "ACCOUNT" | "SSN" | "RRN" | "DOB" | "NAME";

export interface LinkFinding {
  url: string;
  host: string;
  reasons: string[];
}

/** One step the engine took, shown to the user as the "receipt". */
export interface ReceiptStep {
  where: Where;
  what: string;
  ms: number;
  /** Exact text that left the device in this step, if any. */
  sent?: string;
}

export interface Result {
  verdict: Verdict;
  confidence: number;
  signals: string[];
  action: string;
  decidedBy: Where;
  /** The triage (first look) answer, if a model ran. */
  firstLook?: { where: Exclude<Where, "rules">; verdict: Verdict; confidence: number };
  links: LinkFinding[];
  masked: string;
  mask: MaskEntry[];
  receipt: ReceiptStep[];
}
