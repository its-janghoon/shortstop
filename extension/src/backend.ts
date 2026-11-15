// The extension's model backend.
// - First look: Gemini Nano in Chrome (Prompt API), on this device. Photos only here.
// - Deep check: cloud Gemini through the hosted bridge page, which holds App Check.
//   Only the masked prompt crosses; the engine never puts secrets in it.
import schema from "../../shared/verdict.schema.json";
import defaults from "../../shared/config.defaults.json";
import { BRIDGE_ORIGIN, type BridgeAsk, type BridgeReply } from "../../shared/bridge";
import type { Backend } from "../../engine/src/firebase";
import type { EngineConfig, ModelCall } from "../../engine/src/types";

const { $comment: _c, ...responseSchema } = schema as Record<string, unknown>;
type Status = Awaited<ReturnType<Backend["onDeviceStatus"]>>;

interface LMSession {
  prompt(input: unknown, opts?: unknown): Promise<string>;
  destroy(): void;
}
interface LM {
  availability(o?: unknown): Promise<string>;
  create(o?: unknown): Promise<LMSession>;
}
const lm = (globalThis as unknown as { LanguageModel?: LM }).LanguageModel;
const LANG = { expectedInputs: [{ type: "text", languages: ["en"] }, { type: "image" }], expectedOutputs: [{ type: "text", languages: ["en"] }] };

const within = <T,>(p: Promise<T>, ms: number) => Promise.race([p, new Promise<undefined>((r) => setTimeout(() => r(undefined), ms))]);

function bridge() {
  const frame = document.createElement("iframe");
  frame.src = `${BRIDGE_ORIGIN}/bridge.html`;
  frame.hidden = true;
  frame.setAttribute("aria-hidden", "true");
  document.body.append(frame);
  let next = 1;
  const waiting = new Map<number, (r: BridgeReply) => void>();
  const ready = new Promise<boolean>((res) => {
    window.addEventListener("message", (e: MessageEvent<BridgeReply>) => {
      if (e.origin !== BRIDGE_ORIGIN || e.source !== frame.contentWindow) return;
      if (e.data?.id === 0) return res(true);
      waiting.get(e.data?.id)?.(e.data);
      waiting.delete(e.data?.id);
    });
    setTimeout(() => res(false), 10000);
  });
  const ask = async <T,>(req: BridgeAsk, ms = 60000): Promise<T> => {
    if (!(await ready)) throw new Error("cloud bridge did not load");
    const id = next++;
    const r = await within(
      new Promise<BridgeReply>((res) => {
        waiting.set(id, res);
        frame.contentWindow!.postMessage({ id, ...req }, BRIDGE_ORIGIN);
      }),
      ms,
    );
    if (!r) throw new Error("cloud timed out");
    if (r.error) throw new Error(r.error);
    return r.value as T;
  };
  return { ready, ask };
}

export async function extensionBackend(): Promise<Backend> {
  const cloud = bridge();
  const status: Status = lm ? (((await within(lm.availability(LANG).catch(() => "unavailable"), 3000)) ?? "unavailable") as Status) : "unavailable";
  const base = Object.fromEntries(Object.entries(defaults).filter(([k]) => !k.startsWith("$"))) as unknown as EngineConfig & {
    cloud_model: string;
    report_min_count: number;
  };
  const config = (await cloud.ask<typeof base>({ op: "config" }, 8000).catch(() => undefined)) ?? base;

  async function onDevice(prompt: string, image?: Blob): Promise<ModelCall> {
    if (!lm || status !== "available") throw new Error("on-device model not ready");
    const s = await lm.create(LANG);
    try {
      const input = image
        ? [{ role: "user", content: [{ type: "text", value: prompt }, { type: "image", value: image }] }]
        : prompt;
      return { text: await s.prompt(input, { responseConstraint: responseSchema }), source: "on_device" };
    } finally {
      s.destroy();
    }
  }
  const deep = (prompt: string) => cloud.ask<ModelCall>({ op: "deep", prompt });

  return {
    config,
    models: {
      triage: (prompt, image) => (image || status === "available" || config.strict_on_device ? onDevice(prompt, image) : deep(prompt)),
      deep,
    },
    isReported: (domain) => cloud.ask<boolean>({ op: "isReported", domain }, 8000).catch(() => false),
    report: async (domain) => void (await cloud.ask({ op: "report", domain })),
    onDeviceStatus: async () => status,
  };
}
