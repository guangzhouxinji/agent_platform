import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./frontend/e2e",
  outputDir: "./target/playwright-frontend",
  reporter: "line",
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:5173/app/",
    browserName: "chromium",
    screenshot: "only-on-failure",
    // Windows file locking can make retained trace archives fail after an otherwise successful browser scenario.
    trace: "off"
  },
  webServer: {
    command: "npm --prefix frontend run dev -- --host 127.0.0.1 --port 5173",
    url: "http://127.0.0.1:5173/app/",
    reuseExistingServer: true,
    timeout: 120000
  }
});
