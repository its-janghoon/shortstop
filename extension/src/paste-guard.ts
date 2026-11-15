// Runs only on sites the user turned on. On a large paste with private data, it offers a masked copy.
// Rules only, so it is instant and nothing leaves the page.
import { guessNames, maskByRules, maskNames } from "../../engine/src/mask";

const MIN = 40;

function insert(target: HTMLElement, text: string) {
  target.focus();
  // execCommand keeps the site's own editor state (React, ProseMirror) in sync.
  if (!document.execCommand("insertText", false, text)) {
    if (target instanceof HTMLTextAreaElement || target instanceof HTMLInputElement) {
      const s = target.selectionStart ?? target.value.length;
      target.setRangeText(text, s, target.selectionEnd ?? s, "end");
      target.dispatchEvent(new Event("input", { bubbles: true }));
    }
  }
}

function ask(count: number): Promise<"masked" | "original" | "cancel"> {
  return new Promise((resolve) => {
    const host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    shadow.innerHTML = `
      <style>
        .b{position:fixed;right:20px;bottom:20px;z-index:2147483647;max-width:340px;background:#f6f3ec;color:#23221f;
          border:2px solid #23221f;border-radius:14px;padding:14px 16px;font:15px/1.4 system-ui,sans-serif;box-shadow:0 6px 24px rgba(0,0,0,.18)}
        b{display:block;font-size:16px;margin-bottom:4px}
        .r{display:flex;gap:8px;margin-top:10px;flex-wrap:wrap}
        button{font:inherit;border-radius:999px;padding:6px 12px;border:1.5px solid #23221f;background:transparent;cursor:pointer}
        .p{background:#23221f;color:#f6f3ec;font-weight:600}
      </style>
      <div class="b" role="dialog" aria-label="Shortstop paste guard">
        <b>Shortstop</b>This paste has ${count} private item${count === 1 ? "" : "s"} (names, numbers, e-mails).
        <div class="r"><button class="p" data-v="masked">Paste masked</button><button data-v="original">Paste as is</button><button data-v="cancel">Cancel</button></div>
      </div>`;
    shadow.querySelectorAll("button").forEach((b) =>
      b.addEventListener("click", () => {
        host.remove();
        resolve((b as HTMLButtonElement).dataset.v as "masked" | "original" | "cancel");
      }),
    );
    document.documentElement.append(host);
    shadow.querySelector<HTMLButtonElement>(".p")?.focus();
  });
}

document.addEventListener(
  "paste",
  (e) => {
    const text = e.clipboardData?.getData("text/plain") ?? "";
    const target = e.target as HTMLElement | null;
    if (!target || text.length < MIN) return;
    const m = maskNames(maskByRules(text), guessNames(text));
    if (!m.entries.length) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    void ask(m.entries.length).then((choice) => {
      if (choice === "masked") insert(target, m.text);
      else if (choice === "original") insert(target, text);
    });
  },
  true,
);
