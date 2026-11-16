// Renders the flat scenes in video/scenes.html to MP4, frame by frame (every animation is seeked, so no dropped frames).
// Usage: PLAYWRIGHT_CORE=<path> node video/render-scenes.mjs <out dir> [chromium path]
import fs from "node:fs";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_CORE ?? "playwright-core");
const [out, exe] = process.argv.slice(2);
const FPS = 30;
const SCENES = { open: 6, problem: 7, name: 8, s7: 14, end: 6 };
const only = process.env.ONLY?.split(",");
fs.mkdirSync(out, { recursive: true });

const b = await chromium.launch(exe ? { executablePath: exe } : {});
const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
for (const [s, secs] of Object.entries(SCENES)) {
  if (only && !only.includes(s)) continue;
  await p.goto(new URL(`./scenes.html?s=${s}${process.env.REPO ? `&repo=${encodeURIComponent(process.env.REPO)}` : ""}`, import.meta.url).href);
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(500);
  const ff = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(FPS), "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "14", "-pix_fmt", "yuv420p", `${out}/${s}.mp4`]);
  for (let i = 0; i < secs * FPS; i++) {
    await p.evaluate((t) => window.seek(t), i / FPS);
    const png = await p.screenshot({ type: "png" });
    if (!ff.stdin.write(png)) await new Promise((r) => ff.stdin.once("drain", r));
  }
  ff.stdin.end();
  await new Promise((r) => ff.on("close", r));
  console.log(s, "done");
}
await b.close();
