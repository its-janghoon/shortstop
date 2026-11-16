import "./style.css";
import { mount } from "./app";

const ready = mount(document.getElementById("app")!);
// Demo mode for the video, only with ?demo=<scene>. Loaded on demand, so normal visits never fetch it.
const demo = new URLSearchParams(location.search).get("demo");
if (demo) void import("./demo").then((m) => m.start(demo, ready));
