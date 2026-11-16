import examples from "../../shared/examples.json";
import { analyze, NeedsOnDeviceError } from "../../engine/src/engine";
import { registrable } from "../../engine/src/links";
import { configFromEnv, connect, type Backend } from "../../engine/src/firebase";
import type { Result } from "../../engine/src/types";
import { BASES, esc, fieldHtml, ICONS, icon, phoneResultHtml } from "./ui";

const STATUS: Record<string, { text: string; tone: "ok" | "warn" | "off" }> = {
  available: { text: "Gemini Nano on this device", tone: "ok" },
  downloadable: { text: "On-device model can be downloaded", tone: "warn" },
  downloading: { text: "On-device model downloading", tone: "warn" },
  unavailable: { text: "No on-device model · cloud sees masked text", tone: "warn" },
  rules: { text: "AI not set up · rules only", tone: "off" },
};

const IDLE_FIELD = `
  <div class="play-head"><h3>The play</h3><p class="big">Every check starts on your device.</p></div>
  <div class="diamond idle"><svg viewBox="0 0 760 470" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
    ${BASES}
    <line class="wall" x1="470" y1="40" x2="470" y2="430"/>
    <text x="478" y="58" class="lbl cloudc wall-lbl">MASKED ONLY</text><text x="40" y="58" class="lbl">YOUR DEVICE</text><text x="720" y="58" text-anchor="end" class="lbl cloudc">CLOUD</text>
    <g class="ss"><circle cx="262" cy="196" r="34" fill="var(--chalk)"/><text x="262" y="202" text-anchor="middle" class="base">SS</text></g>
    <g class="glove" transform="translate(296 172)"><g class="spin"><circle r="11" fill="#fff" stroke="var(--scam)" stroke-width="3"/><path d="M-5 -8 Q0 0 -5 8 M5 -8 Q0 0 5 8" fill="none" stroke="var(--scam)" stroke-width="1.6"/></g></g>
    <text x="262" y="254" text-anchor="middle" class="t">Small model on your device</text><text x="262" y="274" text-anchor="middle" class="sub">catches it first, hides your secrets</text>
    <circle cx="618" cy="236" r="30" fill="var(--cloud)"/><text x="618" y="242" text-anchor="middle" class="base dark">1B</text>
    <text x="618" y="290" text-anchor="middle" class="t">Big model in the cloud</text><text x="618" y="310" text-anchor="middle" class="sub">only when unsure, only masked text</text>
  </svg></div>
  <div class="captions" aria-hidden="true">
    <div><b>SS</b><strong>Small model on your device</strong><span>catches it first, hides your secrets</span></div>
    <div class="cl"><b>1B</b><strong>Big model in the cloud</strong><span>only when unsure, only masked text</span></div>
  </div>
  <div class="sent home"><em>${icon("home")}Try an example</em>Pick one on the left, or paste your own text.</div>`;

/** Mount the checker into a page. Used by the web app and the extension side panel. */
export async function mount(root: HTMLElement, opts: { initialText?: string; backend?: () => Promise<Backend>; onResult?: (r: Result) => void } = {}) {
  root.innerHTML = `${ICONS}
  <div class="stripes"></div><div class="lights"></div>
  <div class="wrap">
    <header class="brand"><div class="logo"><i></i></div><div><h1>Shortstop</h1><p>Small model first. Your secrets stay home.</p></div>
      <div class="pill off" id="status"><b></b><span>Starting…</span></div></header>
    <div class="phone"><div class="screen" id="screen"></div></div>
    <section class="field" id="field">${IDLE_FIELD}</section>
  </div>`;

  const $ = <T extends HTMLElement>(sel: string) => root.querySelector<T>(sel)!;
  const screen = $("#screen");
  const field = $("#field");
  let image: Blob | undefined;
  let draft = opts.initialText ?? "";

  let backend: Backend | undefined;
  const setStatus = (key: string, extra = "") => {
    const s = STATUS[key]!;
    $("#status").className = `pill ${s.tone}`;
    $("#status span").textContent = s.text + extra;
  };
  const cfg = configFromEnv(import.meta.env);
  compose();
  try {
    backend = opts.backend ? await opts.backend() : cfg ? await connect(cfg) : undefined;
    const st = backend ? await Promise.race([backend.onDeviceStatus(), new Promise<"unavailable">((r) => setTimeout(() => r("unavailable"), 3000))]) : "rules";
    setStatus(st);
    if (st === "downloadable" || st === "downloading") offerDownload();
  } catch {
    setStatus("rules");
  }

  /** Chrome only downloads Gemini Nano after a click, so the status pill becomes a download button. */
  function offerDownload() {
    const lm = (self as unknown as { LanguageModel?: { create: (o: unknown) => Promise<{ destroy(): void }> } }).LanguageModel;
    if (!lm) return;
    const pill = $("#status");
    pill.setAttribute("role", "button");
    pill.tabIndex = 0;
    pill.classList.add("dl");
    $("#status span").textContent = "Download on-device model (about 4 GB)";
    const start = () => {
      pill.onclick = null;
      $("#status span").textContent = "Downloading on-device model… 0%";
      lm.create({
        expectedInputs: [{ type: "text", languages: ["en"] }],
        expectedOutputs: [{ type: "text", languages: ["en"] }],
        monitor(m: EventTarget) {
          m.addEventListener("downloadprogress", (e) => {
            $("#status span").textContent = `Downloading on-device model… ${Math.round(((e as unknown as { loaded: number }).loaded ?? 0) * 100)}%`;
          });
        },
      })
        .then((s) => {
          s.destroy();
          location.reload();
        })
        .catch((e: Error) => {
          setStatus("unavailable");
          $("#status span").textContent = `On-device model failed: ${e.message}`;
        });
    };
    pill.onclick = start;
    pill.onkeydown = (e) => {
      if (e.key === "Enter" || e.key === " ") start();
    };
  }

  function compose() {
    screen.className = "screen compose";
    screen.innerHTML = `
      <div class="from">Check a message</div>
      <label for="input" class="sr">Message to check</label>
      <textarea id="input" placeholder="Paste a text, e-mail or link.">${esc(draft)}</textarea>
      <div class="row">
        <button id="photo" type="button" class="ghost">${icon("image")}<span id="photoName">${image ? "Screenshot added" : "Add screenshot"}</span></button>
        <input id="file" type="file" accept="image/*" hidden />
      </div>
      <h4 class="ex-h">Or try one</h4>
      <div class="examples">${examples.examples
        .map((e) => `<button class="ex ex-${e.kind}" data-id="${e.id}">${icon(e.kind === "safe" ? "check" : e.kind === "mask" ? "lock" : "alert")}${e.label}</button>`)
        .join("")}</div>
      <button id="check" class="btn">Check it</button>`;
    const input = $<HTMLTextAreaElement>("#input");
    input.oninput = () => (draft = input.value);
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void run();
    });
    input.addEventListener("paste", (e) => {
      const f = [...(e.clipboardData?.files ?? [])].find((x) => x.type.startsWith("image/"));
      if (f) setImage(f);
    });
    $("#photo").onclick = () => $<HTMLInputElement>("#file").click();
    $<HTMLInputElement>("#file").onchange = (ev) => setImage((ev.target as HTMLInputElement).files?.[0]);
    screen.querySelectorAll<HTMLButtonElement>(".ex").forEach((b) => {
      b.onclick = () => {
        draft = examples.examples.find((e) => e.id === b.dataset.id)!.text;
        image = undefined;
        void run(b.textContent ?? "");
      };
    });
    $("#check").onclick = () => void run();
  }

  function setImage(b?: Blob) {
    image = b;
    const n = root.querySelector("#photoName");
    if (n) n.textContent = b ? "Screenshot added" : "Add screenshot";
  }

  root.addEventListener("dragover", (e) => e.preventDefault());
  root.addEventListener("drop", (e) => {
    e.preventDefault();
    const f = e.dataTransfer?.files[0];
    if (f?.type.startsWith("image/")) {
      setImage(f);
      void run();
    }
  });

  async function run(label = "") {
    if (!draft.trim() && !image) return;
    screen.className = "screen checking";
    field.classList.add("working");
    screen.innerHTML = `<div class="from">Checking…</div><div class="msg pending">${esc(draft.slice(0, 400))}</div><div class="spinner" aria-label="Checking"></div>`;
    try {
      const r = await analyze(
        { text: draft, ...(image ? { image } : {}) },
        backend ? { models: backend.models, config: backend.config, isReported: backend.isReported } : {},
      );
      const from = label ? `Example · ${label.trim()}` : image ? "Your screenshot" : "Your message";
      opts.onResult?.(r);
      const { html, play } = phoneResultHtml(r, from);
      screen.className = "screen result";
      screen.innerHTML = `${html}
        <div class="actions">
          ${backend && r.verdict === "scam" && r.links.some((l) => l.reasons.length) ? `<button id="report" class="ghost">${icon("alert")}Report this site</button>` : ""}
          <button id="again" class="btn">${icon("back")}Check another</button>
        </div>`;
      field.classList.remove("working");
      field.innerHTML = fieldHtml(r, play);
      // Restart CSS animations on every check.
      for (const el of [screen, field]) {
        el.classList.remove("go");
        void el.offsetWidth;
        el.classList.add("go");
      }
      screen.querySelectorAll<HTMLElement>(".sec .raw").forEach((raw) =>
        raw.addEventListener("animationend", () => raw.parentElement!.classList.add("done"), { once: true }),
      );
      $("#again").onclick = () => {
        image = undefined;
        compose();
        field.innerHTML = IDLE_FIELD;
      };
      const rep = root.querySelector<HTMLButtonElement>("#report");
      if (rep)
        rep.onclick = async () => {
          for (const l of r.links) if (l.reasons.length) await backend!.report(registrable(l.host));
          rep.innerHTML = `${icon("check")}Reported. Thank you`;
          rep.disabled = true;
        };
    } catch (e) {
      field.classList.remove("working");
      compose();
      screen.insertAdjacentHTML(
        "afterbegin",
        `<p class="error">${esc(e instanceof NeedsOnDeviceError ? e.message : `Could not check: ${(e as Error).message}`)}</p>`,
      );
    }
  }

  if (draft) void run();
  return {
    run,
    setText: (t: string) => {
      draft = t;
      image = undefined;
      return run();
    },
  };
}
