import { defineConfig, loadEnv } from "vite";
import { inspectAdjudicationAssets } from "../scripts/lib/adjudication-product-assets.mjs";
import { requireAdjudicationClientId } from "../scripts/lib/adjudication-product-build.mjs";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";
import { mkdirSync, writeFileSync } from "node:fs";
export default defineConfig(({ mode }) => {
  const clientId = requireAdjudicationClientId({...loadEnv(mode, import.meta.dirname, "VITE_"), ...process.env});
  return {
  base: "/app/", cacheDir: "target/vite-adjudication-cache",
  plugins: [react(), {name: "adjudication-build-evidence", writeBundle() {
    const modules = [...this.getModuleIds()].filter(id => id.includes("/src/") && !id.includes("/node_modules/"))
      .map(id => id.replaceAll("\\", "/").replace(import.meta.dirname.replaceAll("\\", "/") + "/", "")).sort();
    mkdirSync(resolve(import.meta.dirname, "target"), {recursive: true});
    writeFileSync(resolve(import.meta.dirname, "target/adjudication-build-modules.json"), JSON.stringify(modules, null, 2));
    const manifest = inspectAdjudicationAssets(resolve(import.meta.dirname, "dist-adjudication"));
    writeFileSync(resolve(import.meta.dirname, "target/adjudication-build-binding.json"), JSON.stringify({clientId, ...manifest}, null, 2));
  }}],
  build: {outDir: "dist-adjudication", emptyOutDir: true, sourcemap: true,
    rollupOptions: {input: resolve(import.meta.dirname, "adjudication.html")}}
};
});
