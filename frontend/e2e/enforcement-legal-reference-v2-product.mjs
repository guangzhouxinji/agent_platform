import { chromium } from "playwright";
import { mkdir, readFile, writeFile, copyFile } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
const runId = process.env.ENF_LEGAL_BROWSER_RUN_ID;
if (!/^[A-Za-z0-9_-]+$/.test(runId ?? "")) throw new Error("Unique execution ID required");
const root = resolve("..");
const out = resolve(root, "docs/evidence/enforcement-followup-final-20260930", runId);
await mkdir(out, { recursive: false });
const target = JSON.parse(await readFile(process.env.ENF_LEGAL_PREP_OUTPUT, "utf8"));
const records = [];
const grants = new Map();
let referenceId;
async function provision(id, revision) {
  if (!/^[a-f0-9-]{36}$/.test(id) || !/^[1-9][0-9]*$/.test(String(revision))) throw new Error("Invalid reference coordinates");
  const scope = id + "-" + revision;
  if (grants.has(scope)) return grants.get(scope);
  const pending = (async () => {
    const suffix = runId + "-grant-" + scope;
    const output = resolve(out, scope + ".json");
    const env = { ...process.env, ENF_LEGAL_PREP_OUTPUT: output, ENF_LEGAL_PREP_REFERENCE_ID: id, ENF_LEGAL_PREP_REFERENCE_VERSION: String(revision) };
    const logs = [];
    const exit = await new Promise((accept, reject) => {
      const child = spawn("mvn", ["-q", "-f", "backend/pom.xml", "-pl", "apps/platform-api", "-am",
        "-Dtest=EnforcementLegalAcceptanceGrantFixtureTest", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dsurefire.reportNameSuffix=" + suffix, "test"],
        { cwd: root, env, shell: true });
      child.stdout.on("data", chunk => logs.push(chunk.toString()));
      child.stderr.on("data", chunk => logs.push(chunk.toString()));
      child.on("error", reject); child.on("close", accept);
    });
    await writeFile(resolve(out, scope + ".log"), logs.join(""));
    if (exit !== 0) throw new Error("Exact-grant preparation failed: " + exit);
    const file = resolve(root, "backend/apps/platform-api/target/surefire-reports", "TEST-cn.xinji.agentplatform.security.product.EnforcementLegalAcceptanceGrantFixtureTest-" + suffix + ".xml");
    const xml = await readFile(file, "utf8");
    const suite = xml.match(/<testsuite\b[^>]*>/)?.[0];
    if (!suite || !/tests="1"/.test(suite) || !/failures="0"/.test(suite) || !/errors="0"/.test(suite) || !/skipped="0"/.test(suite)) throw new Error("Non-zero or skipped grant execution");
    await copyFile(file, resolve(out, "grant-" + scope + ".xml"));
    records.push({ kind: "EXACT_TECHNICAL_GRANTS", referenceId: id, revision, exit, tests: 1, failures: 0, errors: 0, skipped: 0 });
  })();
  grants.set(scope, pending);
  return pending;
}
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true });
const page = await context.newPage();
page.setDefaultTimeout(180000);
page.on("response", async response => {
  const path = new URL(response.url()).pathname;
  if (path.startsWith("/v2/enforcement")) {
    const entry = { path, method: response.request().method(), status: response.status(), at: new Date().toISOString() };
    try { entry.body = await response.json(); } catch {}
    records.push(entry);
  }
});
// Delays real reads only for explicit external grant issuance. No fulfill/mocked response is used.
await page.route("**/v2/enforcement/legal-references/*?revision=*", async route => {
  try {
    const url = new URL(route.request().url());
    const id = url.pathname.split("/").at(-1);
    await provision(id, url.searchParams.get("revision"));
    await route.continue();
  } catch (error) { records.push({ kind: "GRANT_FAILURE", error: String(error).split("\n")[0] }); await route.abort(); }
});
try {
  await page.goto("https://127.0.0.1:5174/app/enforcement/legal-references");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.locator('input[name="username"]').fill(process.env.ENF_FOLLOWUP_LOGIN);
  await page.locator('input[name="password"]').fill(process.env.ENF_FOLLOWUP_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.getByRole("heading", { name: "按目标位置检索" }).waitFor();
  await page.getByLabel("目标类型").click();
  await page.getByText("程序校验报告", { exact: true }).click();
  for (const [label, value] of Object.entries({ "目标 ID": target.targetId, "目标版本": String(target.targetRevision),
      "目标锚点": target.targetAnchor, "案件 Ref": "ENF17-TECH-CASE-01", "地区": "PROJECT_TEST", "用途": "LEGAL_REFERENCE",
      "事件时点": "2026-09-14T09:00", "检索事项": "第一条", "来源 Ref（逗号或换行分隔）": "CNIPA_PATENT_IMPLEMENTING_RULES_2023" }))
    await page.getByLabel(label, { exact: true }).fill(value);
  const searchResponse = page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === "/v2/enforcement/legal-basis/searches");
  await page.getByRole("button", { name: "检索候选" }).click();
  const searched = await searchResponse;
  if (!searched.ok()) throw new Error("Actual product search failed: " + searched.status());
  await page.getByRole("cell", { name: "第一条", exact: true }).click();
  const bind = page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === "/v2/enforcement/legal-references");
  await page.getByRole("button", { name: "绑定到确切位置" }).click();
  const bindResponse = await bind;
  const bound = await bindResponse.json();
  if (!bindResponse.ok()) throw new Error("Binding failed: " + bindResponse.status());
  referenceId = bound.resultId;
  await page.getByText(referenceId + " v1", { exact: true }).waitFor();
  await page.screenshot({ path: resolve(out, "01-bound.png"), fullPage: true });
  await page.getByLabel("人工复核").click();
  await page.getByText("确认引用", { exact: true }).click();
  await page.getByLabel("具名理由").fill("技术反例：法律适用缺口未关闭，不得确认；不构成正式业务审批");
  const rejected = page.waitForResponse(r => new URL(r.url()).pathname.endsWith("/reviews") && r.request().method() === "POST");
  await page.getByRole("button", { name: "提交复核版本" }).click();
  const rejection = await rejected;
  if (rejection.status() !== 409) throw new Error("Unresolved-gap confirmation did not fail closed");
  await page.screenshot({ path: resolve(out, "02-confirmation-rejected.png"), fullPage: true });
  await page.getByLabel("人工复核").click();
  await page.getByText("退回", { exact: true }).click();
  await page.getByLabel("具名理由").fill("技术验证退回：公开原文可核验，但具体事项法律适用尚未确认；非正式业务批准");
  await page.getByRole("button", { name: "提交复核版本" }).click();
  await page.getByText(referenceId + " v2", { exact: true }).waitFor();
  await page.screenshot({ path: resolve(out, "03-returned.png"), fullPage: true });
  await page.getByRole("button", { name: "解除绑定" }).click();
  await page.getByText(referenceId + " v3", { exact: true }).waitFor();
  await page.getByText("RETURNED · UNBOUND", { exact: true }).waitFor();
  await page.screenshot({ path: resolve(out, "04-unbound-history.png"), fullPage: true });
  const db = JSON.parse(await readFile(resolve(out, referenceId + "-3.json.db.json"), "utf8"));
  if (db.versions.length !== 3 || db.receipts.length !== 3 || db.events.length !== 3) throw new Error("Revision/receipt/event mismatch");
  if (db.versions.some(v => v.reviewState === "CONFIRMED")) throw new Error("Forbidden confirmed-state side effect");
  const latest = db.versions.at(-1);
  if (latest.reviewState !== "RETURNED" || latest.bindingState !== "UNBOUND") throw new Error("Final DB state mismatch");
  const receiptKeys = db.receipts.map(row => row.REQUEST_KEY).sort();
  const eventKeys = db.events.map(row => row.REQUEST_KEY).sort();
  if (JSON.stringify(receiptKeys) !== JSON.stringify(eventKeys)) throw new Error("Receipt/event key reconciliation failed");
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, passed: true, inputNature: "FUNCTIONAL_VALIDATION", businessAcceptance: "PENDING", referenceId, records, db }, null, 2));
  console.log("PASSED " + runId + " reference=" + referenceId);
} catch (error) {
  await page.screenshot({ path: resolve(out, "failure.png"), fullPage: true });
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, passed: false, error: String(error).split("\n")[0], referenceId, records }, null, 2));
  throw new Error(String(error).split("\n")[0]);
} finally { await browser.close(); }
