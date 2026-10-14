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
page.setDefaultTimeout(45000);
page.on("response", async response => {
  const path = new URL(response.url()).pathname;
  if (path.startsWith("/v2/enforcement")) {
    const entry = { path, method: response.request().method(), status: response.status(), at: new Date().toISOString() };
    try { entry.body = await response.json(); } catch {}
    records.push(entry);
    await writeFile(resolve(out, "http-records.json"), JSON.stringify(records, null, 2));
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
      "事件时点": "2026-09-14T09:00", "检索事项": "第二条", "来源 Ref（逗号或换行分隔）": "CNIPA_PATENT_IMPLEMENTING_RULES" }))
    await page.getByLabel(label, { exact: true }).fill(value);
  const searchResponse = page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === "/v2/enforcement/legal-basis/searches");
  await page.getByRole("button", { name: "检索候选" }).click();
  const searched = await searchResponse;
  if (!searched.ok()) throw new Error("Actual product search failed: " + searched.status());
  await page.getByRole("cell", { name: "第二条", exact: true }).click();
  const bind = page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname === "/v2/enforcement/legal-references");
  await page.getByRole("button", { name: "绑定到确切位置" }).click();
  const bindResponse = await bind;
  const bound = await bindResponse.json();
  if (!bindResponse.ok()) throw new Error("Binding failed: " + bindResponse.status());
  referenceId = bound.resultId;
  await page.getByText(referenceId + " v1", { exact: true }).waitFor();
  await page.screenshot({ path: resolve(out, "01-bound.png"), fullPage: true });
  const initial = records.findLast(r => r.method === "GET" && r.body?.referenceId === referenceId)?.body;
  if (!initial || initial.bindingState !== "ACTIVE") throw new Error("Initial product reference missing");
  await copyFile(process.env.ENF_LEGAL_L06_AFTER, process.env.ENF_LEGAL_L06_RUNTIME);
  const recheck = page.waitForResponse(r => r.request().method() === "POST" && new URL(r.url()).pathname.endsWith("/rechecks"));
  await page.getByRole("button", { name: "重新核对来源" }).click();
  const checked = await recheck;
  if (!checked.ok()) throw new Error("Actual product source recheck failed: " + checked.status());
  await page.getByText(referenceId + " v2", { exact: true }).waitFor();
  await page.getByText("PENDING · NEEDS_REVIEW", { exact: true }).waitFor();
  await page.screenshot({ path: resolve(out, "02-needs-review.png"), fullPage: true });
  const db = JSON.parse(await readFile(resolve(out, referenceId + "-2.json.db.json"), "utf8"));
  if (db.versions.length !== 2 || db.receipts.length !== 2 || db.events.length !== 2) throw new Error("Revision/receipt/event mismatch");
  const [before, after] = db.versions;
  if (before.bindingState !== "ACTIVE" || after.bindingState !== "NEEDS_REVIEW" || after.reviewState !== "PENDING") throw new Error("Update state mismatch");
  if (before.sourceVersion !== "2010-01-09" || after.sourceVersion !== "2023-12-11") throw new Error("Real source versions mismatch");
  if (before.verbatimText === after.verbatimText) throw new Error("Changed original not exercised");
  if (db.versions.some(v => v.reviewState === "CONFIRMED" || v.bindingState === "UNBOUND")) throw new Error("Forbidden side effect");
  const receiptKeys = db.receipts.map(row => row.REQUEST_KEY).sort();
  const eventKeys = db.events.map(row => row.REQUEST_KEY).sort();
  if (JSON.stringify(receiptKeys) !== JSON.stringify(eventKeys)) throw new Error("Receipt/event reconciliation failed");
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, passed: true, inputNature: "FUNCTIONAL_VALIDATION", businessAcceptance: "PENDING", referenceId, records, db }, null, 2));
  console.log("PASSED " + runId + " reference=" + referenceId);
} catch (error) {
  try { await page.screenshot({ path: resolve(out, "failure.png"), fullPage: true, timeout: 10000 }); } catch {}
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, passed: false, error: String(error).split("\n")[0], referenceId, records }, null, 2));
  throw new Error(String(error).split("\n")[0]);
} finally { await browser.close(); }
