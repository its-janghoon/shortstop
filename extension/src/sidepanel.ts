/// <reference types="chrome" />
import "../../web/src/style.css";
import "./sidepanel.css";
import { mount } from "../../web/src/app";
import { extensionBackend } from "./backend";
import { historyPanel, remember } from "./history";
import { pasteGuardPanel } from "./paste-guard-settings";

const root = document.getElementById("app")!;
const app = await mount(root, { backend: extensionBackend, onResult: (r) => void remember(r) });
const extras = document.createElement("div");
extras.className = "extras";
extras.append(await pasteGuardPanel(), await historyPanel());
root.querySelector(".wrap")!.append(extras);

type Pending = { text: string; at: number; demo?: boolean };
async function takePending() {
  const { pending } = (await chrome.storage.session.get("pending")) as { pending?: Pending };
  if (!pending) return;
  await chrome.storage.session.remove("pending");
  if (pending.demo) document.documentElement.dataset.demo = "ext";
  if (pending.text) await app.setText(pending.text);
}
await takePending();
chrome.storage.session.onChanged.addListener((changes) => {
  if (changes.pending?.newValue) void takePending();
});
