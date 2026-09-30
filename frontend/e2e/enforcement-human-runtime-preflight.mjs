import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { chromium } from "playwright";

const [base = "https://127.0.0.1:5174", output = "frontend/target/enforcement-human-runtime"] = process.argv.slice(2);
const evidence = resolve(output);
await mkdir(evidence, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const events = [];
page.on("response", response => {
  const url = new URL(response.url());
  if (["/v2", "/oauth2", "/login", "/.well-known"].some(prefix => url.pathname.startsWith(prefix)))
    events.push({ time: new Date().toISOString(), method: response.request().method(), url: response.url(), status: response.status() });
});

try {
  const entry = await page.goto(`${base}/app/enforcement/job-pack`, { waitUntil: "networkidle" });
  if (entry?.status() !== 200) throw new Error(`Job Pack entry returned ${entry?.status()}`);
  if (await page.getByText("产品身份入口不可用").count()) throw new Error("Product client ID is not injected");
  await page.screenshot({ path: resolve(evidence, "01-job-pack-entry.png"), fullPage: true });
  await page.getByRole("button", { name: "登录" }).click();
  await page.locator('input[name="username"]').waitFor({ state: "visible" });
  const loginUrl = page.url();
  if (!["https://127.0.0.1:5174/login", "https://127.0.0.1:5444/login"].some(value => loginUrl.startsWith(value)))
    throw new Error(`Unexpected product login URL: ${loginUrl}`);
  await page.screenshot({ path: resolve(evidence, "02-product-login.png"), fullPage: true });
  await writeFile(resolve(evidence, "preflight.json"), JSON.stringify({
    runId: "ENF-HUMAN-RUNTIME-20260929-01",
    checkedAt: new Date().toISOString(),
    entryUrl: `${base}/app/enforcement/job-pack`,
    loginUrl,
    result: "RUNTIME_READY_WAITING_REAL_HUMAN_ACTION",
    events
  }, null, 2));
  process.stdout.write(JSON.stringify({ loginUrl, result: "RUNTIME_READY_WAITING_REAL_HUMAN_ACTION", events }, null, 2));
} catch (failure) {
  await page.screenshot({ path: resolve(evidence, "failure.png"), fullPage: true }).catch(() => {});
  throw failure;
} finally {
  await browser.close();
}
