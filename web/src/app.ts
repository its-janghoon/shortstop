import examples from "../../shared/examples.json";
import { analyze, NeedsOnDeviceError } from "../../engine/src/engine";
import { registrable } from "../../engine/src/links";
import { configFromEnv, connect, type Backend } from "../../engine/src/firebase";
import { renderResult } from "./ui";

const STATUS: Record<string, string> = {
  available: "On-device AI is ready",
  downloadable: "On-device AI can be downloaded",
  downloading: "On-device AI is downloading",
  unavailable: "No on-device AI here, the cloud will be used with masked text",
  rules: "AI not set up. Rules only",
};

/** Mount the checker into a page. Used by the web app and the extension side panel. */
export async function mount(root: HTMLElement, opts: { initialText?: string } = {}) {
  root.innerHTML = `
    <header><h1>Shortstop</h1><p class="tag">Small model first. Your secrets stay home.</p></header>
    <p class="status" id="status">Starting…</p>
    <label for="input" class="sr">Message to check</label>
    <textarea id="input" rows="6" placeholder="Paste a text, e-mail or link. Or drop a screenshot."></textarea>
    <div class="row">
      <button id="check" class="primary">Check</button>
      <button id="photo" type="button">Add screenshot</button>
      <input id="file" type="file" accept="image/*" hidden />
      <span id="photoName" class="muted"></span>
    </div>
    <div class="examples" aria-label="Examples">
      ${examples.examples.map((e) => `<button class="ex ex-${e.kind}" data-id="${e.id}">${e.label}</button>`).join("")}
    </div>
    <div id="out"></div>`;

  const $ = <T extends HTMLElement>(id: string) => root.querySelector<T>(`#${id}`)!;
  const input = $<HTMLTextAreaElement>("input");
  const out = $("out");
  let image: Blob | undefined;
  if (opts.initialText) input.value = opts.initialText;

  let backend: Backend | undefined;
  const cfg = configFromEnv(import.meta.env);
  try {
    backend = cfg ? await connect(cfg) : undefined;
    $("status").textContent = backend ? STATUS[await backend.onDeviceStatus()]! : STATUS.rules!;
  } catch (e) {
    $("status").textContent = `${STATUS.rules} (${(e as Error).message})`;
  }

  const setImage = (b?: Blob, name = "") => {
    image = b;
    $("photoName").textContent = b ? name || "screenshot" : "";
  };
  $("photo").onclick = () => $<HTMLInputElement>("file").click();
  $<HTMLInputElement>("file").onchange = (ev) => {
    const f = (ev.target as HTMLInputElement).files?.[0];
    setImage(f, f?.name);
  };
  root.addEventListener("dragover", (e) => e.preventDefault());
  root.addEventListener("drop", (e) => {
    e.preventDefault();
    const f = e.dataTransfer?.files[0];
    if (f?.type.startsWith("image/")) setImage(f, f.name);
  });
  input.addEventListener("paste", (e) => {
    const f = [...(e.clipboardData?.files ?? [])].find((x) => x.type.startsWith("image/"));
    if (f) setImage(f, "pasted screenshot");
  });

  root.querySelectorAll<HTMLButtonElement>(".ex").forEach((b) => {
    b.onclick = () => {
      input.value = examples.examples.find((e) => e.id === b.dataset.id)!.text;
      setImage();
      void run();
    };
  });

  async function run() {
    const text = input.value;
    if (!text.trim() && !image) return;
    out.innerHTML = `<p class="checking">Checking…</p>`;
    try {
      const r = await analyze(
        { text, ...(image ? { image } : {}) },
        backend ? { models: backend.models, config: backend.config, isReported: backend.isReported } : {},
      );
      out.innerHTML = renderResult(r);
      if (backend && r.verdict === "scam" && r.links.length) {
        const btn = document.createElement("button");
        btn.textContent = "Report this site";
        btn.onclick = async () => {
          for (const l of r.links) if (l.reasons.length) await backend!.report(registrable(l.host));
          btn.textContent = "Reported. Thank you";
          btn.disabled = true;
        };
        out.querySelector(".card")?.append(btn);
      }
    } catch (e) {
      out.innerHTML = `<p class="error">${e instanceof NeedsOnDeviceError ? e.message : `Could not check: ${(e as Error).message}`}</p>`;
    }
  }
  $("check").onclick = () => void run();
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) void run();
  });
  return { run, setText: (t: string) => ((input.value = t), run()) };
}
