import { resolve } from "node:path";
import { defineConfig } from "vite";

// One build for the side panel page and the service worker. The content script has its own config.
export default defineConfig({
  root: "extension",
  envDir: "..",
  publicDir: "public",
  base: "./",
  build: {
    outDir: "../dist/extension",
    emptyOutDir: true,
    target: "chrome138",
    modulePreload: false,
    rollupOptions: {
      input: {
        sidepanel: resolve(__dirname, "sidepanel.html"),
        background: resolve(__dirname, "src/background.ts"),
      },
      output: {
        entryFileNames: (c) => (c.name === "sidepanel" ? "assets/[name]-[hash].js" : "[name].js"),
      },
    },
  },
});
