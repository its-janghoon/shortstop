/// <reference types="chrome" />
// Check history stays in this browser only (chrome.storage.local). Masked text only, never the secrets.
import type { Result } from "../../engine/src/types";
import { esc } from "../../web/src/ui";

interface Entry {
  at: number;
  verdict: Result["verdict"];
  masked: string;
}
const KEY = "history";
const MAX = 50;

async function list(): Promise<Entry[]> {
  const { history } = (await chrome.storage.local.get(KEY)) as { history?: Entry[] };
  return history ?? [];
}

export async function remember(r: Result) {
  const items = [{ at: Date.now(), verdict: r.verdict, masked: r.masked.slice(0, 300) }, ...(await list())].slice(0, MAX);
  await chrome.storage.local.set({ [KEY]: items });
}

const WORD = { scam: "Scam", suspicious: "Careful", safe: "Safe" } as const;

export async function historyPanel(): Promise<HTMLElement> {
  const box = document.createElement("section");
  box.className = "guard history";
  const render = async () => {
    const items = await list();
    box.innerHTML = `
      <h2>History</h2>
      <p class="muted">Kept only in this browser. Secrets are stored as placeholders.</p>
      ${
        items.length
          ? `<ol>${items
              .map((e) => `<li><b class="h-${e.verdict}">${WORD[e.verdict]}</b> <span>${esc(e.masked)}</span><time>${new Date(e.at).toLocaleString()}</time></li>`)
              .join("")}</ol><button id="clear" class="ghost">Clear history</button>`
          : `<p class="muted">No checks yet.</p>`
      }`;
    box.querySelector<HTMLButtonElement>("#clear")?.addEventListener("click", async () => {
      await chrome.storage.local.remove(KEY);
      await render();
    });
  };
  await render();
  chrome.storage.local.onChanged.addListener((c) => {
    if (c[KEY]) void render();
  });
  return box;
}
