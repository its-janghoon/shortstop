/// <reference types="chrome" />
// The user chooses which sites get the paste guard. Each site needs its own host permission,
// asked for at the moment the user turns it on.

const SCRIPT_ID = "shortstop-paste-guard";

export async function sites(): Promise<string[]> {
  const { guardSites } = (await chrome.storage.local.get("guardSites")) as { guardSites?: string[] };
  return guardSites ?? [];
}

async function sync(list: string[]) {
  await chrome.storage.local.set({ guardSites: list });
  const existing = await chrome.scripting.getRegisteredContentScripts({ ids: [SCRIPT_ID] });
  if (existing.length) await chrome.scripting.unregisterContentScripts({ ids: [SCRIPT_ID] });
  if (!list.length) return;
  await chrome.scripting.registerContentScripts([
    {
      id: SCRIPT_ID,
      js: ["paste-guard.js"],
      matches: list.map((h) => `https://${h}/*`),
      runAt: "document_idle",
      allFrames: false,
    },
  ]);
}

async function currentHost(): Promise<string | null> {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  try {
    const u = new URL(tab?.url ?? "");
    return u.protocol === "https:" ? u.hostname : null;
  } catch {
    return null;
  }
}

export async function pasteGuardPanel(): Promise<HTMLElement> {
  const box = document.createElement("section");
  box.className = "guard";
  const render = async () => {
    const list = await sites();
    const host = await currentHost();
    box.innerHTML = `
      <h2>Paste guard</h2>
      <p class="muted">Before you paste into an AI chat, Shortstop offers a masked copy.</p>
      <ul>${list.map((h) => `<li>${h} <button data-rm="${h}">Remove</button></li>`).join("") || "<li class='muted'>No sites yet</li>"}</ul>
      ${host && !list.includes(host) ? `<button id="add">Turn on for ${host}</button>` : ""}`;
    box.querySelector<HTMLButtonElement>("#add")?.addEventListener("click", async () => {
      const ok = await chrome.permissions.request({ origins: [`https://${host}/*`] });
      if (ok) await sync([...list, host!]);
      await render();
    });
    box.querySelectorAll<HTMLButtonElement>("[data-rm]").forEach((b) =>
      b.addEventListener("click", async () => {
        const h = b.dataset.rm!;
        await sync(list.filter((x) => x !== h));
        await chrome.permissions.remove({ origins: [`https://${h}/*`] });
        await render();
      }),
    );
  };
  await render();
  chrome.tabs.onActivated.addListener(() => void render());
  return box;
}
