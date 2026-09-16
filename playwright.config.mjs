import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./src/test/browser",
  outputDir: "./target/playwright",
  reporter: "line",
  use: {
    baseURL: "http://127.0.0.1:18081",
    browserName: "chromium",
    screenshot: "only-on-failure",
    trace: "retain-on-failure"
  },
  webServer: {
    command: "mvn spring-boot:run",
    url: "http://127.0.0.1:18081",
    reuseExistingServer: false,
    timeout: 120000,
    env: {
      CONSULTATION_STORAGE: "memory",
      SERVER_PORT: "18081",
      SPRING_AUTOCONFIGURE_EXCLUDE: "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
    }
  }
});
