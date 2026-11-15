import { resolve } from "node:path";
import { defineConfig } from "vite";

// Content scripts registered with chrome.scripting are classic scripts: one self-contained file, no imports.
export default defineConfig({
  build: {
    outDir: "dist/extension",
    emptyOutDir: false,
    target: "chrome138",
    lib: {
      entry: resolve(__dirname, "src/paste-guard.ts"),
      formats: ["iife"],
      name: "ShortstopPasteGuard",
      fileName: () => "paste-guard.js",
    },
  },
});
