// Makes the voice-over: each line of video/lines.json through the hosted tts.html page
// (Gemini TTS via Firebase AI Logic, App Check on), in headless Chromium. Writes <out>/<id>.wav.
// Usage: PLAYWRIGHT_CORE=<path> xvfb-run -a node video/tts.mjs <out dir> [chromium path]
// Headed under Xvfb: App Check (reCAPTCHA Enterprise) rejects headless Chromium with 403.
import fs from "node:fs";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE ?? "playwright-core");
const out = process.argv[2];
const exe = process.argv[3];
const only = process.env.ONLY?.split(",");
const { voice, lines } = JSON.parse(fs.readFileSync(new URL("./lines.json", import.meta.url)));
fs.mkdirSync(out, { recursive: true });

const b = await chromium.launch({
  ...(exe ? { executablePath: exe } : {}),
  headless: false,
  args: ["--disable-blink-features=AutomationControlled"],
  ignoreDefaultArgs: ["--enable-automation"],
});
// reCAPTCHA scores a page that was just opened as a bot; give it a few seconds and some pointer moves first.
// When App Check still says no, start over in a fresh context (the SDK throttles a context after a 403).
async function freshPage() {
  const ctx = await b.newContext();
  const p = await ctx.newPage();
  await p.goto(process.env.TTS_URL ?? "https://shortstop-b108a.web.app/tts.html");
  await p.waitForFunction(() => "shortstopTTS" in window);
  await p.mouse.move(200, 200);
  await p.waitForTimeout(4000);
  await p.mouse.move(600, 400, { steps: 20 });
  await p.waitForTimeout(4000);
  return p;
}
let p = await freshPage();
for (const l of lines) {
  if (only && !only.includes(l.id)) continue;
  let r;
  for (let i = 0; i < 8; i++) {
    r = await p.evaluate(([t, v]) => window.shortstopTTS.say(t, v).catch((e) => ({ error: String(e?.message ?? e) })), [l.text, voice]);
    if (!r.error) break;
    console.error(l.id, "error", r.error);
    if (/App Check/i.test(r.error)) {
      await p.context().close();
      p = await freshPage();
      continue;
    }
    if (!/429|quota|rate|exhausted|503|overloaded/i.test(r.error)) break;
    await new Promise((res) => setTimeout(res, 15000 * (i + 1)));
  }
  if (r.error) process.exit(1);
  const rate = Number(/rate=(\d+)/.exec(r.mime)?.[1] ?? 24000);
  const pcm = `${out}/${l.id}.pcm`;
  fs.writeFileSync(pcm, Buffer.from(r.data, "base64"));
  execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-f", "s16le", "-ar", String(rate), "-ac", "1", "-i", pcm, `${out}/${l.id}.wav`]);
  fs.unlinkSync(pcm);
  console.log(l.id, r.mime, `${(fs.statSync(`${out}/${l.id}.wav`).size / (rate * 2)).toFixed(2)}s`);
}
await b.close();
