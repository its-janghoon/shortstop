// Voice-over for the demo video: Gemini TTS through Firebase AI Logic, with App Check.
// Driven by video/tts.mjs in headless Chromium: window.shortstopTTS.say(text, voice) returns base64 PCM.
import { initializeApp } from "firebase/app";
import { getAI, getGenerativeModel, GoogleAIBackend, ResponseModality } from "firebase/ai";
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from "firebase/app-check";
import { configFromEnv } from "../../../engine/src/firebase";

const cfg = configFromEnv(import.meta.env)!;
const app = initializeApp(cfg);
if (cfg.recaptchaSiteKey) initializeAppCheck(app, { provider: new ReCaptchaEnterpriseProvider(cfg.recaptchaSiteKey), isTokenAutoRefreshEnabled: true });
const ai = getAI(app, { backend: new GoogleAIBackend() });

async function say(text: string, voice = "Sulafat", model = "gemini-3.1-flash-tts-preview") {
  const m = getGenerativeModel(ai, {
    model,
    generationConfig: {
      responseModalities: [ResponseModality.AUDIO],
      speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } } },
    },
  });
  const r = await m.generateContent(text);
  const part = r.response.candidates?.[0]?.content?.parts?.find((p) => "inlineData" in p && p.inlineData);
  if (!part || !("inlineData" in part)) throw new Error("no audio in response: " + JSON.stringify(r.response).slice(0, 300));
  return { mime: part.inlineData.mimeType, data: part.inlineData.data };
}

Object.assign(window, { shortstopTTS: { say } });
