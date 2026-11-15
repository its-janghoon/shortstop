import type { Result } from "../../engine/src/types";

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

const TITLE: Record<Result["verdict"], string> = {
  scam: "This looks like a scam",
  suspicious: "Be careful",
  safe: "Looks safe",
};

const WHERE: Record<string, string> = {
  rules: "Rules on this device",
  on_device: "AI on this device",
  in_cloud: "Cloud AI",
};

/** Show masked text with placeholders highlighted. */
export function highlight(masked: string): string {
  return esc(masked).replace(/\[([A-Z]+)_(\d+)\]/g, '<mark class="tok">[$1_$2]</mark>');
}

export function renderResult(r: Result): string {
  const sentSteps = r.receipt.filter((s) => s.sent);
  const total = r.receipt.reduce((n, s) => n + s.ms, 0);
  return `
  <section class="card v-${r.verdict}" role="status" aria-live="polite">
    <p class="verdict">${TITLE[r.verdict]}</p>
    <p class="action">${esc(r.action)}</p>
    ${r.signals.length ? `<ul class="signals">${r.signals.map((s) => `<li>${esc(s)}</li>`).join("")}</ul>` : ""}
    <p class="decided">Decided by <b>${WHERE[r.decidedBy]}</b> · ${total} ms</p>
  </section>
  <details class="receipt" ${sentSteps.length ? "open" : ""}>
    <summary>Receipt: ${sentSteps.length ? "what left this device" : "nothing left this device"}</summary>
    <ol>
      ${r.receipt
        .map(
          (s) => `<li class="w-${s.where}"><span class="where">${WHERE[s.where]}</span> ${esc(s.what)} <span class="ms">${s.ms} ms</span>
          ${s.sent ? `<pre class="sent">${highlight(s.sent.replace(/^[\s\S]*<msg>\n?|\n?<\/msg>[\s\S]*$/g, ""))}</pre>` : ""}</li>`,
        )
        .join("")}
    </ol>
    ${r.mask.length ? `<p class="kept">Kept on this device: ${r.mask.map((m) => `<code>${m.token}</code>`).join(" ")}</p>` : ""}
  </details>`;
}
