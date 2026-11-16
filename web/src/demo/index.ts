// Demo mode for the video. Off unless the page has ?demo=<scene>.
// The page draws its own cursor and presses the app's own buttons; the real engine runs.
// A scene starts on the "g" key (sent by the capture script once recording runs), or after a second with &auto=1.
import { Cursor, paste, sleep, waitFor } from "./cursor";
import { wifiSwitch } from "./offline";
import { TEXTS } from "./texts";

type App = { setText: (t: string) => Promise<void> };

function hideMouse() {
  const st = document.createElement("style");
  st.textContent = `*{cursor:none!important}`;
  document.head.append(st);
}

function trigger(): Promise<void> {
  const q = new URLSearchParams(location.search);
  if (q.has("auto")) return sleep(1000).then(() => undefined);
  return new Promise((r) => {
    const on = (e: KeyboardEvent) => {
      if (e.key !== "g") return;
      removeEventListener("keydown", on, true);
      e.preventDefault();
      r();
    };
    addEventListener("keydown", on, true);
  });
}

const q = <T extends Element>(s: string) => document.querySelector<T>(s);

async function checkScene(text: string, opts: { wifi?: boolean } = {}) {
  hideMouse();
  const wifi = opts.wifi ? wifiSwitch(document.body) : undefined;
  await trigger();
  const c = new Cursor();
  await sleep(600);
  if (wifi) {
    await c.to(wifi, 1100);
    await sleep(200);
    await c.click();
    wifi.click();
    await sleep(1100);
  }
  const input = await waitFor(() => q<HTMLTextAreaElement>("#input"));
  await c.to(input, 1000, 0.4, 0.35);
  await c.click();
  await sleep(250);
  paste(input, text);
  await sleep(1100);
  const btn = q<HTMLButtonElement>("#check")!;
  await c.to(btn, 900);
  await sleep(150);
  await c.click();
  btn.click();
  // Get out of the way of the result.
  await sleep(400);
  await c.move(innerWidth * 0.36, innerHeight * 0.93, 1200);
}

/** Runs each candidate text a few times and lists where it was decided. For picking scene texts. */
async function probe(app: App) {
  const out = document.createElement("pre");
  out.style.cssText = "position:fixed;inset:auto 0 0 0;z-index:99999;max-height:60vh;overflow:auto;margin:0;background:#000;color:#0f0;font:14px/1.4 monospace;padding:10px";
  document.body.append(out);
  const n = Number(new URLSearchParams(location.search).get("n") ?? 2);
  for (const [k, t] of Object.entries(TEXTS)) {
    for (let i = 0; i < n; i++) {
      const t0 = performance.now();
      await app.setText(t);
      const v = q(".verdict")?.className.match(/v-(\w+)/)?.[1];
      const cloud = !!q(".sent:not(.home)");
      const ss = q(".captions div")?.textContent?.replace(/\s+/g, " ");
      out.textContent += `${k} #${i}: ${v} cloud=${cloud} ${Math.round(performance.now() - t0)}ms | ${ss}\n`;
      await sleep(500);
    }
  }
  out.textContent += "DONE\n";
}

export async function start(scene: string, ready: Promise<App>) {
  const app = await ready;
  document.documentElement.dataset.demo = scene;
  if (scene === "probe") return probe(app);
  if (scene === "s1") return checkScene(TEXTS.parcel, { wifi: true });
  if (scene === "s2") return checkScene(TEXTS[(new URLSearchParams(location.search).get("t") as keyof typeof TEXTS) ?? "payee"] ?? TEXTS.payee);
  if (scene === "s3") return checkScene(TEXTS.realParcel);
}
