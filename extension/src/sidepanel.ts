/// <reference types="chrome" />
import "../../web/src/style.css";
import "./sidepanel.css";
import { mount } from "../../web/src/app";
import { pasteGuardPanel } from "./paste-guard-settings";

const root = document.getElementById("app")!;
const app = await mount(root);
root.append(await pasteGuardPanel());

type Pending = { text: string; at: number };
async function takePending() {
  const { pending } = (await chrome.storage.session.get("pending")) as { pending?: Pending };
  if (!pending) return;
  await chrome.storage.session.remove("pending");
  if (pending.text) await app.setText(pending.text);
}
await takePending();
chrome.storage.session.onChanged.addListener((changes) => {
  if (changes.pending?.newValue) void takePending();
});
