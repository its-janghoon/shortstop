import { resolve } from "node:path";
import { defineConfig } from "vite";

export default defineConfig({
  root: "web",
  envDir: "..",
  build: {
    outDir: "../dist/web",
    emptyOutDir: true,
    rollupOptions: { input: { index: resolve(__dirname, "index.html"), bridge: resolve(__dirname, "bridge.html"), bench: resolve(__dirname, "bench.html"), inbox: resolve(__dirname, "inbox.html"), chat: resolve(__dirname, "chat.html"), tts: resolve(__dirname, "tts.html") } },
  },
});
