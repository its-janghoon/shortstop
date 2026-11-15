import { initializeApp, type FirebaseApp } from "firebase/app";
import { getAI, getGenerativeModel, GoogleAIBackend, InferenceMode, InferenceSource, type Part } from "firebase/ai";
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from "firebase/app-check";
import { getAuth, signInAnonymously } from "firebase/auth";
import { fetchAndActivate, getRemoteConfig, getValue } from "firebase/remote-config";
import { doc, getDoc, getFirestore, increment, serverTimestamp, setDoc } from "firebase/firestore";
import schema from "../../shared/verdict.schema.json";
import defaults from "../../shared/config.defaults.json";
import type { EngineConfig, ModelCall, Models } from "./types";

export interface WebConfig {
  apiKey: string;
  authDomain: string;
  projectId: string;
  appId: string;
  recaptchaSiteKey?: string;
}

export function configFromEnv(env: Record<string, string | undefined>): WebConfig | null {
  const c = {
    apiKey: env.VITE_FIREBASE_API_KEY ?? "",
    authDomain: env.VITE_FIREBASE_AUTH_DOMAIN ?? "",
    projectId: env.VITE_FIREBASE_PROJECT_ID ?? "",
    appId: env.VITE_FIREBASE_APP_ID ?? "",
    recaptchaSiteKey: env.VITE_RECAPTCHA_SITE_KEY,
  };
  return c.apiKey && c.projectId && c.appId ? c : null;
}

const { $comment: _c, ...responseSchema } = schema as Record<string, unknown>;

async function blobPart(b: Blob): Promise<Part> {
  const buf = new Uint8Array(await b.arrayBuffer());
  let bin = "";
  for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode(...buf.subarray(i, i + 0x8000));
  return { type: "inlineData", inlineData: { data: btoa(bin), mimeType: b.type || "image/png" } };
}

export interface Backend {
  models: Models;
  config: EngineConfig & { cloud_model: string; report_min_count: number };
  isReported: (domain: string) => Promise<boolean>;
  report: (domain: string) => Promise<void>;
  onDeviceStatus: () => Promise<"available" | "downloadable" | "downloading" | "unavailable">;
}

export async function sha256(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s.toLowerCase()));
  return [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export async function connect(cfg: WebConfig): Promise<Backend> {
  const app: FirebaseApp = initializeApp(cfg);

  if (!cfg.recaptchaSiteKey) {
    // Judges may run with a debug token; see TESTING.md.
    (self as unknown as { FIREBASE_APPCHECK_DEBUG_TOKEN?: boolean | string }).FIREBASE_APPCHECK_DEBUG_TOKEN ??= true;
  }
  initializeAppCheck(app, {
    provider: new ReCaptchaEnterpriseProvider(cfg.recaptchaSiteKey ?? "debug"),
    isTokenAutoRefreshEnabled: true,
  });

  await signInAnonymously(getAuth(app)).catch(() => undefined);

  const rc = getRemoteConfig(app);
  rc.settings.minimumFetchIntervalMillis = 60 * 60 * 1000;
  rc.defaultConfig = Object.fromEntries(Object.entries(defaults).filter(([k]) => !k.startsWith("$"))) as Record<string, string | number | boolean>;
  await fetchAndActivate(rc).catch(() => undefined);
  const config = {
    escalate_below_confidence: getValue(rc, "escalate_below_confidence").asNumber(),
    on_device_max_chars: getValue(rc, "on_device_max_chars").asNumber(),
    strict_on_device: getValue(rc, "strict_on_device").asBoolean(),
    cloud_model: getValue(rc, "cloud_model").asString(),
    report_min_count: getValue(rc, "report_min_count").asNumber(),
  };

  const ai = getAI(app, { backend: new GoogleAIBackend() });
  const generationConfig = { responseMimeType: "application/json", responseJsonSchema: responseSchema };
  const triageModel = getGenerativeModel(ai, {
    mode: config.strict_on_device ? InferenceMode.ONLY_ON_DEVICE : InferenceMode.PREFER_ON_DEVICE,
    onDeviceParams: {
      createOptions: { expectedInputs: [{ type: "text" }, { type: "image" }] } as never,
      promptOptions: { responseConstraint: responseSchema },
    },
    inCloudParams: { model: config.cloud_model, generationConfig },
  });
  const photoModel = getGenerativeModel(ai, {
    mode: InferenceMode.ONLY_ON_DEVICE,
    onDeviceParams: {
      createOptions: { expectedInputs: [{ type: "text" }, { type: "image" }] } as never,
      promptOptions: { responseConstraint: responseSchema },
    },
  });
  const deepModel = getGenerativeModel(ai, {
    mode: InferenceMode.ONLY_IN_CLOUD,
    inCloudParams: { model: config.cloud_model, generationConfig },
  });

  const call = async (m: typeof deepModel, parts: (string | Part)[]): Promise<ModelCall> => {
    const r = await m.generateContent(parts);
    return {
      text: r.response.text(),
      source: r.response.inferenceSource === InferenceSource.ON_DEVICE ? "on_device" : "in_cloud",
    };
  };

  const models: Models = {
    triage: async (prompt, image) => (image ? call(photoModel, [prompt, await blobPart(image)]) : call(triageModel, [prompt])),
    deep: (prompt) => call(deepModel, [prompt]),
  };

  const db = getFirestore(app);
  return {
    models,
    config,
    isReported: async (domain) => {
      const snap = await getDoc(doc(db, "reports", await sha256(domain))).catch(() => null);
      return (snap?.data()?.count ?? 0) >= config.report_min_count;
    },
    report: async (domain) => {
      // Only the hash of the site name is stored. No message text, no user data.
      await setDoc(doc(db, "reports", await sha256(domain)), { count: increment(1), last: serverTimestamp() }, { merge: true });
    },
    onDeviceStatus: async () => {
      const lm = (self as unknown as { LanguageModel?: { availability: (o?: unknown) => Promise<string> } }).LanguageModel;
      if (!lm) return "unavailable";
      return (await lm.availability().catch(() => "unavailable")) as Awaited<ReturnType<Backend["onDeviceStatus"]>>;
    },
  };
}
