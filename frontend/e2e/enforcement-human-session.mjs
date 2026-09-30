import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { chromium } from "playwright";

const [base, profilePath, evidencePath] = process.argv.slice(2);
if (!base || !profilePath || !evidencePath) throw new Error("Expected base URL, browser profile and evidence directory");
await mkdir(resolve(profilePath), { recursive: true });
await mkdir(resolve(evidencePath), { recursive: true });

const context = await chromium.launchPersistentContext(resolve(profilePath), { headless: false });
const page = context.pages()[0] ?? await context.newPage();
await page.goto(`${base}/app/enforcement/job-pack`, { waitUntil: "domcontentloaded" });
await page.getByRole("button", { name: "登录" }).waitFor({ state: "visible" });
if (await page.getByText("产品身份入口不可用").count()) throw new Error("Product identity entry is unavailable");
if (await page.getByRole("button", { name: "登录" }).count()) await page.getByRole("button", { name: "登录" }).click();
await page.locator('input[name="username"]').waitFor({ state: "visible" });
await page.screenshot({ path: resolve(evidencePath, "03-real-human-login-ready.png"), fullPage: true });
await writeFile(resolve(evidencePath, "session-ready.json"), JSON.stringify({
  runId: "ENF-HUMAN-RUNTIME-20260929-01",
  readyAt: new Date().toISOString(),
  url: page.url(),
  state: "WAITING_REAL_HUMAN_ACTION",
  realHumanFactsCreated: 0
}, null, 2));
process.stdout.write("ENF_HUMAN_LOGIN_READY\n");
await new Promise(resolveClosed => context.on("close", resolveClosed));
