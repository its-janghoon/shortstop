import type { Result, Verdict } from "../../engine/src/types";

export const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

export const ICONS = `<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs>
 <symbol id="i-link" viewBox="0 0 24 24"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/></symbol>
 <symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></symbol>
 <symbol id="i-lock" viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></symbol>
 <symbol id="i-home" viewBox="0 0 24 24"><path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/></symbol>
 <symbol id="i-cloud" viewBox="0 0 24 24"><path d="M7 18h10a4 4 0 0 0 .5-8A6 6 0 0 0 6 9.5 4.3 4.3 0 0 0 7 18z"/></symbol>
 <symbol id="i-alert" viewBox="0 0 24 24"><path d="M12 3l10 18H2z"/><path d="M12 10v5M12 18v.5"/></symbol>
 <symbol id="i-money" viewBox="0 0 24 24"><rect x="3" y="6" width="18" height="12" rx="2"/><circle cx="12" cy="12" r="2.5"/></symbol>
 <symbol id="i-check" viewBox="0 0 24 24"><path d="M5 12l4 4 10-10"/></symbol>
 <symbol id="i-image" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><path d="M21 17l-5-5-9 8"/></symbol>
 <symbol id="i-back" viewBox="0 0 24 24"><path d="M15 6l-6 6 6 6"/></symbol>
</defs></svg>`;

export const icon = (name: string) => `<svg class="i" aria-hidden="true"><use href="#i-${name}"/></svg>`;

const WORD: Record<Verdict, { big: string; stamp: string; label: string }> = {
  scam: { big: "SCAM.", stamp: "CAUGHT", label: "Shortstop says" },
  suspicious: { big: "CAREFUL.", stamp: "CHECK IT", label: "Shortstop says" },
  safe: { big: "SAFE.", stamp: "CLEAR", label: "Shortstop says" },
};

function chipIcon(reason: string): string {
  if (/link|site|look|alphabet|address|http|dash|ends in/i.test(reason)) return "link";
  if (/hurry|rush|urgent|hours/i.test(reason)) return "clock";
  if (/lock|password|login|code|ID|private/i.test(reason)) return "lock";
  if (/money|gift|fee|pay|profit|won/i.test(reason)) return "money";
  return "alert";
}

const DELAY = { msg: 0, tokenStart: 0.85, tokenStep: 0.3 };

/** Rebuild the message from the masked text, so each secret can turn into its placeholder in place. */
export function messageHtml(r: Result): { html: string; maskDone: number } {
  const bad = r.links.filter((l) => l.reasons.length).map((l) => l.url);
  const byToken = new Map(r.mask.map((m) => [m.token, m.value]));
  let i = 0;
  const parts = r.masked.split(/(\[[A-Z]+_\d+\])/g).map((part) => {
    const value = byToken.get(part);
    if (!value) {
      let h = esc(part);
      for (const u of bad) h = h.split(esc(u)).join(`<span class="bad">${esc(u)}</span>`);
      return h;
    }
    const d = DELAY.tokenStart + i++ * DELAY.tokenStep;
    return `<span class="sec" style="--d:${d.toFixed(2)}s"><span class="raw">${esc(value)}</span><span class="tok">${esc(part.slice(1, -1))}</span></span>`;
  });
  return { html: parts.join(""), maskDone: DELAY.tokenStart + i * DELAY.tokenStep };
}

export interface Play {
  maskDone: number;
  cloud: boolean;
  /** No model on device: the first look went to the cloud. */
  skippedShortstop: boolean;
  verdictAt: number;
}

export function plan(r: Result, maskDone: number): Play {
  const cloud = r.receipt.some((s) => s.sent);
  const skippedShortstop = r.firstLook?.where === "in_cloud";
  const throwAt = Math.max(maskDone, 1.2) + 0.2;
  return { maskDone, cloud, skippedShortstop, verdictAt: cloud ? throwAt + 1.3 : throwAt };
}

export function phoneResultHtml(r: Result, from: string): { html: string; play: Play } {
  const msg = messageHtml(r);
  const p = plan(r, msg.maskDone);
  const w = WORD[r.verdict];
  const t = (n: number) => `style="--d:${n.toFixed(2)}s"`;
  const html = `
    <div class="from">${esc(from)}</div>
    <div class="msg">${msg.html}</div>
    <section class="verdict v-${r.verdict}" ${t(p.verdictAt)} role="status" aria-live="polite">
      <div class="stamp" ${t(p.verdictAt + 0.7)}>${w.stamp}</div>
      <small>${w.label}</small>
      <h2 ${t(p.verdictAt + 0.5)}>${w.big}</h2>
      <p>${esc(r.action)}</p>
    </section>
    ${
      r.signals.length
        ? `<div class="chips">${r.signals
            .map((s, i) => `<span class="chip v-${r.verdict}" ${t(p.verdictAt + 0.8 + i * 0.1)}>${icon(chipIcon(s))}${esc(s)}</span>`)
            .join("")}</div>`
        : ""
    }
    ${
      r.mask.length
        ? `<div class="kept" ${t(p.maskDone + 0.1)}><h4>${icon("home")}Kept on this device</h4><ul>${r.mask
            .map((m) => `<li><code>${esc(m.token.slice(1, -1))}</code></li><li>${esc(m.value)}</li>`)
            .join("")}</ul></div>`
        : ""
    }`;
  return { html, play: p };
}

/** Demo mode (the contest video) leaves out wall-clock times; the video plays waits sped up. */
const demoMode = () => typeof document !== "undefined" && !!document.documentElement.dataset.demo;

/** The infield: home at the bottom, 1B right, 2B top, 3B left. SS stands between 2B and 3B. */
export const BASES = `
    <path d="M380 440 L618 236 L380 32 L142 236 Z" fill="rgba(201,138,82,.10)" stroke="rgba(255,255,255,.3)" stroke-width="3"/>
    <path d="M380 440 L740 130 M380 440 L20 130" stroke="rgba(255,255,255,.12)" stroke-width="3"/>
    <rect x="370" y="22" width="20" height="20" transform="rotate(45 380 32)" fill="var(--chalk)" opacity=".85"/>
    <text x="406" y="38" class="bl">2B</text>
    <rect x="132" y="226" width="20" height="20" transform="rotate(45 142 236)" fill="var(--chalk)" opacity=".85"/>
    <text x="112" y="242" text-anchor="end" class="bl">3B</text>
    <rect x="371" y="431" width="18" height="18" transform="rotate(45 380 440)" fill="var(--chalk)"/>
    <text x="404" y="446" class="bl">HOME</text>`;

const fmtMs = (ms: number) => (ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : ms < 1 ? "<1 ms" : `${ms} ms`);

export function fieldHtml(r: Result, p: Play): string {
  const sent = r.receipt.filter((s) => s.sent);
  const leaked = r.mask.filter((m) => sent.some((s) => s.sent!.includes(m.value))).length;
  const total = r.receipt.reduce((n, s) => n + s.ms, 0);
  const device = r.receipt.find((s) => s.where === "on_device");
  const cloudSteps = r.receipt.filter((s) => s.where === "in_cloud");
  const cloudMs = cloudSteps.reduce((n, s) => n + s.ms, 0);
  const fl = r.firstLook;
  const ssTitle = !fl ? "Rules on this device" : p.skippedShortstop ? "No on-device model" : "Gemini Nano";
  const ssSub = !fl
    ? `hid ${r.mask.length} secret${r.mask.length === 1 ? "" : "s"} · checked links`
    : p.skippedShortstop
      ? "rules hid the secrets instead"
      : `${demoMode() ? "" : `${fmtMs(device?.ms ?? 0)} · `}hid ${r.mask.length} secret${r.mask.length === 1 ? "" : "s"} · ${fl.verdict} (${fl.confidence.toFixed(2)})`;
  const fbTitle = p.cloud ? "Gemini in the cloud" : "Cloud not needed";
  const fbSub = p.cloud ? `${demoMode() ? "" : `${fmtMs(cloudMs)} · `}${r.verdict} (${r.confidence.toFixed(2)}) · saw placeholders only` : "stayed on the bench";
  const firstSent = sent[sent.length - 1]?.sent ?? "";
  const shown = firstSent.match(/<msg>\n([\s\S]*?)\n<\/msg>/)?.[1] ?? firstSent;
  const tokens = (s: string) => esc(s).replace(/\[([A-Z]+_\d+)\]/g, "<code>$1</code>");
  const t = (n: number) => `style="--d:${n.toFixed(2)}s"`;
  const throwAt = p.verdictAt - 1.3;
  // Ball path: home -> shortstop, then (if cloud) shortstop -> first base across the wall.
  const first = p.skippedShortstop ? "M 380 440 Q 520 360 618 236" : "M 380 440 Q 300 300 262 196";
  return `
  <div class="play-head"><h3>The play</h3><p class="big">${
    p.cloud ? "Fielded on your device. Thrown to the cloud, masked." : "Fielded on your device. Nothing left it."
  }</p></div>
  <div class="diamond"><svg viewBox="0 0 760 470" preserveAspectRatio="xMidYMid meet" role="img" aria-label="Where each step ran">
    ${BASES}
    <line class="wall" x1="470" y1="40" x2="470" y2="430"/>
    <text x="478" y="58" class="lbl cloudc wall-lbl">MASKED ONLY</text>
    <text x="40" y="58" class="lbl">YOUR DEVICE</text>
    <text x="720" y="58" text-anchor="end" class="lbl cloudc">CLOUD</text>
    <text x="380" y="418" text-anchor="middle" class="sm">Message in</text>
    <g class="${p.skippedShortstop ? "dim" : ""}">
      <circle class="glow" ${t(1.1)} cx="262" cy="196" r="0" fill="var(--chalk)"/>
      <circle cx="262" cy="196" r="34" fill="var(--chalk)"/><text x="262" y="202" text-anchor="middle" class="base">SS</text>
      <text x="262" y="254" text-anchor="middle" class="t">${esc(ssTitle)}</text>
      <text x="262" y="274" text-anchor="middle" class="sub">${esc(ssSub)}</text>
    </g>
    <g class="${p.cloud ? "" : "dim"}">
      <circle class="glow cl" ${t(p.verdictAt - 0.1)} cx="618" cy="236" r="0" fill="var(--cloud)"/>
      <circle cx="618" cy="236" r="30" fill="var(--cloud)"/><text x="618" y="242" text-anchor="middle" class="base dark">1B</text>
      <text x="618" y="290" text-anchor="middle" class="t">${esc(fbTitle)}</text>
      <text x="618" y="310" text-anchor="middle" class="sub">${esc(fbSub)}</text>
    </g>
    <circle class="ball" style="offset-path:path('${first}');--d:.2s" r="10"/>
    ${p.cloud && !p.skippedShortstop ? `<circle class="ball b2" style="offset-path:path('M 262 196 Q 450 110 618 236');--d:${throwAt.toFixed(2)}s" r="10"/>` : ""}
  </svg></div>
  <div class="captions" aria-hidden="true">
    <div class="${p.skippedShortstop ? "dim" : ""}"><b>SS</b><strong>${esc(ssTitle)}</strong><span>${esc(ssSub)}</span></div>
    <div class="cl ${p.cloud ? "" : "dim"}"><b>1B</b><strong>${esc(fbTitle)}</strong><span>${esc(fbSub)}</span></div>
  </div>
  ${
    p.cloud
      ? `<div class="sent" ${t(throwAt + 0.6)}><em>${icon("cloud")}Exactly what left your device</em>${tokens(shown)}</div>`
      : `<div class="sent home" ${t(p.verdictAt)}><em>${icon("home")}Nothing left your device</em>The whole check ran here.</div>`
  }
  <div class="score${demoMode() ? " three" : ""}">
    <div class="cell"><b>${r.mask.length}</b><span>secrets kept home</span></div>
    <div class="cell ${leaked ? "bad" : ""}"><b>${leaked}</b><span>secrets sent</span></div>
    <div class="cell cloudc"><b>${sent.length}</b><span>cloud assist${sent.length === 1 ? "" : "s"}</span></div>
    ${demoMode() ? "" : `<div class="cell"><b>${fmtMs(total)}</b><span>total</span></div>`}
  </div>`;
}
