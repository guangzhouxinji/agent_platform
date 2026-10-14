import { chromium } from "playwright";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
const stage = process.env.ENF_TARGET_STAGE;
const base = process.env.ENF_TARGET_BASE ?? "https://127.0.0.1:5175";
const runId = process.env.ENF_TARGET_RUN_ID;
if (!/^[A-Za-z0-9_-]+$/.test(runId ?? "")) throw new Error("Unique run ID required");
const out = resolve("../docs/evidence/enforcement-followup-final-20260930", runId);
await mkdir(out, { recursive: false });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true });
const page = await context.newPage();
let token;
const responses = [];
const inputPath = resolve("../data/enforcement/legal-reference-v2-product-input-v1/target-procedure-input.json");
const inputBytes = await readFile(inputPath);
const input = JSON.parse(inputBytes);
const original = await readFile(resolve("..", input.publicSource.snapshot));
const originalSha = createHash("sha256").update(original).digest("hex");
if (originalSha !== input.publicSource.snapshotSha256) throw new Error("Public source SHA mismatch");
const provenance = { ...input.publicSource, inputSha256: createHash("sha256").update(inputBytes).digest("hex"), actualOriginalSha256: originalSha };
page.on("response", async response => {
  const path = new URL(response.url()).pathname;
  if (path === "/oauth2/token" && response.ok()) token = (await response.json()).access_token;
  if (path.startsWith("/v2/enforcement")) responses.push({ path, status: response.status(), at: new Date().toISOString() });
});
const ruleId = "ENF17-TARGET-PROCEDURE-20260930";
const reportId = "ENF17-TARGET-REPORT-20260930";
const example = caseRef => ({ caseRef, caseVersion: "1", calendarRef: "ENF17-TECH-CALENDAR", calendarVersion: "1" });
try {
  await page.goto(base + "/app/enforcement/procedure");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.locator('input[name="username"]').fill(process.env.ENF_FOLLOWUP_LOGIN);
  await page.locator('input[name="password"]').fill(process.env.ENF_FOLLOWUP_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.getByRole("tab", { name: "案件逐项报告" }).waitFor();
  if (!token) throw new Error("Real PKCE token exchange missing");
  const command = async (path, body) => {
    const result = await context.request.post(base + "/v2/enforcement" + path, {
      headers: { Authorization: "Bearer " + token, "Idempotency-Key": runId }, data: body
    });
    const payload = await result.json();
    responses.push({ path, status: result.status(), at: new Date().toISOString(), payload });
    if (!result.ok()) throw new Error("Product command failed: " + result.status());
    return payload;
  };
  if (stage === "draft") {
    await command("/procedure-rules", { id: ruleId, version: 1, organization: "ORG-ENF-FUNCTIONAL",
      region: "PROJECT_TEST", procedure: "PROJECT_DOCUMENT_REVIEW", stage: "PROJECT_REVIEW", state: "DRAFT",
      basis: { ref: "PROJECT-METHOD-ENF17", version: "1", locator: "TECHNICAL_TEST_ONLY", effectStatus: "VERIFIED",
        scope: "PROJECT_TEST_NOT_LEGAL", effectiveFrom: "2026-09-01", effectiveTo: "2026-10-31" },
      clock: { unit: "CALENDAR_DAY", days: 2, startEvent: "REQUEST_RECORDED", includeStartDay: false,
        deadlineAdjustment: "NONE", calendarRef: "ENF17-TECH-CALENDAR", calendarVersion: "1",
        pausePolicy: "NONE", interruptionPolicy: "NONE", extensionDays: 0 },
      requiredEvents: ["REQUEST_RECORDED"], requiredMaterials: ["PUBLIC_ORIGINAL_NOT_LINKED@1"] });
  } else if (stage === "publish") {
    await command("/procedure-rules/" + ruleId + "/versions/1/publish", {
      positive: [example("ENF17-TECH-CASE-01")], negative: [example("ENF17-TECH-MISSING-01")],
      reviewRef: "CODEX-TECHNICAL-INPUT-REVIEW-NOT-BUSINESS-APPROVAL", reviewer: process.env.ENF_FOLLOWUP_SUBJECT });
  } else if (stage === "report") {
    await page.getByRole("tab", { name: "案件逐项报告" }).click();
    const fields = { "校验 ID": reportId, "案件 Ref": "ENF17-TECH-CASE-01", "案件版本": "1",
      "适用规则 ID": ruleId, "规则版本": "2", "日历 Ref": "ENF17-TECH-CALENDAR", "日历版本": "1" };
    const form = page.getByRole("tabpanel", { name: "案件逐项报告" }).locator("form").first();
    let index = 0;
    for (const value of Object.values(fields)) await form.locator("input").nth(index++).fill(value);
    const responsePromise = page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === "/v2/enforcement/procedure-checks");
    await page.getByRole("button", { name: "生成逐项报告" }).click();
    const response = await responsePromise;
    const receipt = await response.json();
    if (!response.ok()) throw new Error("Browser report creation failed: " + response.status());
    await page.getByText(reportId + " v1", { exact: true }).waitFor();
    await writeFile(resolve(out, "report-receipt.json"), JSON.stringify(receipt, null, 2));
  } else throw new Error("Unknown stage");
  await page.screenshot({ path: resolve(out, "page.png"), fullPage: true });
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, stage, inputNature: "FUNCTIONAL_VALIDATION", provenance, passed: true, responses }, null, 2));
  console.log("PASSED " + runId + " " + stage);
} catch (error) {
  await page.screenshot({ path: resolve(out, "failure.png"), fullPage: true });
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, stage, passed: false, error: String(error).split("\n")[0], responses }, null, 2));
  throw new Error(String(error).split("\n")[0]);
} finally { await browser.close(); }
