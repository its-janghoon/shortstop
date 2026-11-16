// Demo "Wi-Fi off": from the moment it is switched on, this page cannot reach the network at all.
// fetch, XHR, WebSocket, EventSource and beacons all fail, so anything that still works ran on this device.

let off = false;
export const isOffline = () => off;

export function goOffline() {
  if (off) return;
  off = true;
  const fail = () => Promise.reject(new TypeError("Failed to fetch (offline)"));
  window.fetch = fail as typeof fetch;
  XMLHttpRequest.prototype.send = function () {
    setTimeout(() => this.dispatchEvent(new ProgressEvent("error")), 0);
  };
  window.WebSocket = class {
    constructor() {
      throw new DOMException("offline", "NetworkError");
    }
  } as unknown as typeof WebSocket;
  window.EventSource = class {
    constructor() {
      throw new DOMException("offline", "NetworkError");
    }
  } as unknown as typeof EventSource;
  navigator.sendBeacon = () => false;
  Object.defineProperty(navigator, "onLine", { get: () => false });
  window.dispatchEvent(new Event("offline"));
}

const WIFI = `<svg viewBox="0 0 24 24" class="i" aria-hidden="true"><path d="M2 8.5a15 15 0 0 1 20 0"/><path d="M5.5 12a10 10 0 0 1 13 0"/><path d="M9 15.5a5 5 0 0 1 6 0"/><circle cx="12" cy="19" r="1" fill="currentColor"/><path class="slash" d="M3 3l18 18"/></svg>`;

/** A Wi-Fi switch in the header, next to the model status. */
export function wifiSwitch(root: HTMLElement): HTMLButtonElement {
  const st = document.createElement("style");
  st.textContent = `
  .wifi{margin-left:auto;display:flex;gap:8px;align-items:center;padding:8px 14px;border-radius:999px;border:1px solid;font-weight:700;font-size:14px;
    color:#f4f1e6;background:rgba(255,255,255,.08);transition:background .25s,color .25s}
  .wifi svg.i{width:18px;height:18px}
  .wifi .slash{opacity:0;stroke-dasharray:26;stroke-dashoffset:26;transition:opacity .1s,stroke-dashoffset .35s}
  .wifi.off{color:#ffd84d;background:rgba(255,216,77,.14)}
  .wifi.off .slash{opacity:1;stroke-dashoffset:0}
  .wifi + .pill{margin-left:10px}`;
  document.head.append(st);
  const b = document.createElement("button");
  b.type = "button";
  b.className = "wifi";
  b.innerHTML = `${WIFI}<span>Wi-Fi on</span>`;
  b.onclick = () => {
    goOffline();
    b.classList.add("off");
    b.querySelector("span")!.textContent = "Wi-Fi off";
  };
  root.querySelector("#status")!.before(b);
  return b;
}
