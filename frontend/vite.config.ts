import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";

const v2Target = process.env.VITE_V2_PROXY_TARGET || "http://127.0.0.1:8080";
const proxy = { target: v2Target, secure: process.env.VITE_V2_PROXY_SECURE !== "false" };
const https = process.env.VITE_DEV_HTTPS_PFX ? {
  pfx: readFileSync(process.env.VITE_DEV_HTTPS_PFX),
  passphrase: process.env.VITE_DEV_HTTPS_PFX_PASSWORD
} : process.env.VITE_DEV_HTTPS_KEY && process.env.VITE_DEV_HTTPS_CERT ? {
  key: readFileSync(process.env.VITE_DEV_HTTPS_KEY),
  cert: readFileSync(process.env.VITE_DEV_HTTPS_CERT)
} : undefined;

export default defineConfig({
  base: "/app/",
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    https,
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/v2": proxy,
      "/oauth2": proxy,
      "/login": proxy,
      "/.well-known": proxy
    }
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    include: ["src/**/*.test.{ts,tsx}"],
    css: true
  },
  build: {
    outDir: "dist",
    sourcemap: true
  }
});
