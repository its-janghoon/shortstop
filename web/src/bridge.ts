// Runs inside a hidden iframe in the Shortstop extension's side panel.
// The extension keeps the message and the secrets. It sends only the masked prompt here,
// and this page makes the cloud call with App Check, which only works on this site's domain.
import { configFromEnv, connect, type Backend } from "../../engine/src/firebase";
import type { BridgeReply, BridgeRequest } from "../../shared/bridge";

/** Only the Shortstop extension may use this page. */
const ALLOWED = new Set(["chrome-extension://bjalnnabhfhgfbpbabahhaiapjjhpheh", ...(import.meta.env.VITE_EXTRA_EXTENSION_ORIGINS ?? "").split(",").filter(Boolean)]);

const cfg = configFromEnv(import.meta.env);
let backend: Promise<Backend | undefined> | undefined;
const get = () => (backend ??= cfg ? connect(cfg).catch(() => undefined) : Promise.resolve(undefined));

window.addEventListener("message", async (e: MessageEvent<BridgeRequest>) => {
  if (!ALLOWED.has(e.origin) || e.source !== window.parent) return;
  const req = e.data;
  if (!req || typeof req.id !== "number") return;
  const reply = (r: Omit<BridgeReply, "id">) => window.parent.postMessage({ id: req.id, ...r } satisfies BridgeReply, e.origin);
  try {
    const b = await get();
    if (!b) return reply({ error: "cloud not set up" });
    switch (req.op) {
      case "config":
        return reply({ value: b.config });
      case "deep":
        if (typeof req.prompt !== "string" || req.prompt.length > 20000) return reply({ error: "bad prompt" });
        return reply({ value: await b.models.deep(req.prompt) });
      case "isReported":
        return reply({ value: await b.isReported(String(req.domain)) });
      case "report":
        await b.report(String(req.domain));
        return reply({ value: true });
      default:
        return reply({ error: "unknown op" });
    }
  } catch (err) {
    reply({ error: (err as Error).message });
  }
});

window.parent.postMessage({ id: 0, value: "ready" } satisfies BridgeReply, "*");
