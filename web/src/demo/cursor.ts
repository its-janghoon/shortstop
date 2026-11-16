// A drawn cursor for the demo video: eased moves on a gentle arc, a ring on every click.
// The page moves it itself, so screen capture needs no real mouse.

const CSS = `
.dc{position:fixed;left:0;top:0;z-index:2147483646;pointer-events:none;will-change:transform}
.dc .dy{will-change:transform}
.dc svg{display:block;width:44px;height:52px;filter:drop-shadow(0 4px 6px rgba(0,0,0,.28));transform-origin:6px 4px;transition:transform .12s}
.dc.press svg{transform:scale(.86)}
.dring{position:fixed;z-index:2147483645;pointer-events:none;width:20px;height:20px;margin:-10px 0 0 -10px;border-radius:50%;
  border:4px solid #ffd84d;animation:dring .6s cubic-bezier(.2,.8,.2,1) forwards}
@keyframes dring{from{transform:scale(.4);opacity:1}to{transform:scale(3.6);opacity:0}}`;

const SHAPE = `<svg viewBox="0 0 44 52" aria-hidden="true"><path d="M6 4 L6 40 Q6 43 9 41 L17 34 L23 47 Q24.5 50 27.5 48.6 L31 47 Q33.6 45.6 32.4 43 L26.4 30.6 L37 29.6 Q40 29.2 37.8 27 L9.2 2.4 Q6 0 6 4 Z"
  fill="#f4f1e6" stroke="#0b1a12" stroke-width="3" stroke-linejoin="round"/><circle cx="14" cy="18" r="2.2" fill="#0b1a12"/><circle cx="20" cy="23" r="2.2" fill="#0b1a12"/>
  <path d="M13 27 Q17 30 21 27.6" fill="none" stroke="#0b1a12" stroke-width="2" stroke-linecap="round"/></svg>`;

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export class Cursor {
  private el: HTMLDivElement;
  private dy: HTMLDivElement;
  x: number;
  y: number;

  constructor(x = innerWidth * 0.62, y = innerHeight * 1.1) {
    const st = document.createElement("style");
    st.textContent = CSS;
    document.head.append(st);
    this.el = document.createElement("div");
    this.el.className = "dc";
    this.dy = document.createElement("div");
    this.dy.className = "dy";
    this.dy.innerHTML = SHAPE;
    this.el.append(this.dy);
    document.body.append(this.el);
    this.x = x;
    this.y = y;
    this.place();
  }

  private place() {
    // The tip of the arrow is at (6, 4) in the drawing.
    this.el.style.transform = `translateX(${this.x - 6}px)`;
    this.dy.style.transform = `translateY(${this.y - 4}px)`;
  }

  /** Move the tip to (x, y). X and Y use different easings, which bends the path into an arc. */
  async move(x: number, y: number, ms = 900) {
    const ox = this.x - 6;
    const oy = this.y - 4;
    this.x = x;
    this.y = y;
    const a = this.el.animate([{ transform: `translateX(${ox}px)` }, { transform: `translateX(${x - 6}px)` }], {
      duration: ms,
      easing: "cubic-bezier(.65,0,.35,1)",
      fill: "forwards",
    });
    const b = this.dy.animate([{ transform: `translateY(${oy}px)` }, { transform: `translateY(${y - 4}px)` }], {
      duration: ms,
      easing: "cubic-bezier(.45,0,.2,1)",
      fill: "forwards",
    });
    await Promise.all([a.finished, b.finished]);
    this.place();
    a.cancel();
    b.cancel();
  }

  /** Move to the middle of an element (or a point inside it, as fractions of its box). */
  to(el: Element, ms = 900, fx = 0.5, fy = 0.5) {
    const r = el.getBoundingClientRect();
    return this.move(r.left + r.width * fx, r.top + r.height * fy, ms);
  }

  async click() {
    this.el.classList.add("press");
    const ring = document.createElement("div");
    ring.className = "dring";
    ring.style.left = `${this.x}px`;
    ring.style.top = `${this.y}px`;
    document.body.append(ring);
    ring.addEventListener("animationend", () => ring.remove());
    await sleep(140);
    this.el.classList.remove("press");
    await sleep(120);
  }

  hide() {
    this.el.style.display = "none";
  }
}

/** Put text into a field the way a paste does, so the app's own input handler runs. */
export function paste(field: HTMLTextAreaElement | HTMLInputElement, text: string) {
  field.focus();
  field.value = text;
  field.dispatchEvent(new Event("input", { bubbles: true }));
}

/** Type text one character at a time. */
export async function type(field: HTMLTextAreaElement | HTMLInputElement, text: string, cps = 28) {
  field.focus();
  for (const ch of text) {
    field.value += ch;
    field.dispatchEvent(new Event("input", { bubbles: true }));
    await sleep(1000 / cps);
  }
}

export async function waitFor<T>(fn: () => T | null | undefined | false, ms = 60000): Promise<T> {
  const t = performance.now();
  for (;;) {
    const v = fn();
    if (v) return v;
    if (performance.now() - t > ms) throw new Error("demo: timed out waiting");
    await sleep(50);
  }
}
