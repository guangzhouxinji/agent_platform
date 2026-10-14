import { chromium } from "playwright";
import { mkdir, readFile, writeFile, rename } from "node:fs/promises";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
const runId = process.env.ENF_LEGAL_RECOVERY_RUN_ID;
if (!/^[A-Za-z0-9_-]+$/.test(runId ?? "")) throw new Error("Fresh run ID required");
const out = resolve("../docs/evidence/enforcement-followup-final-20260930", runId);
await mkdir(out, { recursive: false });
const gate = process.env.ENF_LEGAL_RECOVERY_GATE;
const baseline = JSON.parse(await readFile(process.env.ENF_LEGAL_BROWSER_BASELINE, "utf8"));
if (!baseline.passed) throw new Error("Successful browser baseline required");
const id = baseline.referenceId;
const target = "https://127.0.0.1:5449";
const records = [];
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true });
const page = await context.newPage();
let token; let restored = false;
page.on("response", async response => {
  const path = new URL(response.url()).pathname;
  if (path === "/oauth2/token" && response.ok()) token = (await response.json()).access_token;
  if (path.startsWith("/v2/enforcement")) records.push({ path, status: response.status(), restored, at: new Date().toISOString() });
});
await page.route("**/v2/enforcement/legal-references/**", async route => {
  const url = new URL(route.request().url());
  if (restored) {
    const actualUrl = target + url.pathname + url.search;
    records.push({ kind: "REAL_RESTORED_HTTP", actualUrl });
    await route.continue({ url: actualUrl });
  } else await route.continue();
});
async function waitGate(name) {
  const deadline = Date.now() + 1200000;
  while (Date.now() < deadline) { try { await readFile(gate + name); return; } catch {} await new Promise(done => setTimeout(done, 250)); }
  throw new Error("Recovery gate timeout: " + name);
}
try {
  await page.goto("https://127.0.0.1:5174/app/enforcement/legal-references");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await page.locator('input[name="username"]').fill(process.env.ENF_FOLLOWUP_LOGIN);
  await page.locator('input[name="password"]').fill(process.env.ENF_FOLLOWUP_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.getByLabel("已有引用 ID").fill(id);
  await page.getByLabel("已有引用版本").fill("3");
  await page.getByRole("button", { name: "读取已有引用与历史" }).click();
  await page.getByText(id + " v3", { exact: true }).waitFor();
  await page.getByText("RETURNED · UNBOUND", { exact: true }).waitFor();
  await page.screenshot({ path: resolve(out, "01-source-reloaded.png"), fullPage: true });
  if (!token) throw new Error("Real PKCE session required");
  await writeFile(gate + ".session-ready", JSON.stringify({ runId, at: new Date().toISOString() }), { flag: "wx" });
  await waitGate(".restore-ready");
  restored = true;
  const read = page.waitForResponse(r => new URL(r.url()).pathname.includes(id) && r.request().method() === "GET");
  await page.getByRole("button", { name: "读取已有引用与历史" }).click();
  if ((await read).status() !== 200) throw new Error("Restored UI reference unavailable");
  await page.getByText("RETURNED · UNBOUND", { exact: true }).waitFor();
  await page.screenshot({ path: resolve(out, "02-restored-reference-history.png"), fullPage: true });
  const original = resolve(process.env.ENF_LEGAL_RECOVERY_ORIGINAL);
  const bytes = await readFile(original);
  const sha = createHash("sha256").update(bytes).digest("hex");
  if (sha !== "2105ff670401091637ae352b6c75f4eeb4549c85914eae10afe43c38a7d312bf") throw new Error("Restored public original SHA mismatch");
  const search = async suffix => context.request.post(target + "/v2/enforcement/legal-basis/searches", {
    headers: { Authorization: "Bearer " + token, "Idempotency-Key": runId + "-" + suffix }, data: {
      organization: "ORG-ENF-FUNCTIONAL", targetType: "PROCEDURE_REPORT", targetId: "ENF17-TARGET-REPORT-20260930",
      targetRevision: 1, targetAnchor: "items[0]", caseRef: "ENF17-TECH-CASE-01", purpose: "LEGAL_REFERENCE",
      region: "PROJECT_TEST", eventAt: "2026-09-14T01:00:00Z", query: "第一条", sourceRefs: ["CNIPA_PATENT_IMPLEMENTING_RULES_2023"] } });
  await rename(original, original + ".held");
  try { const response = await search("missing-original"); if (response.status() !== 503) throw new Error("Missing original did not fail closed"); records.push({ scenario: "MISSING_ORIGINAL", status: response.status() }); }
  finally { await rename(original + ".held", original); }
  try { await writeFile(original, "CONTROLLED_INTEGRITY_FAILURE"); const response = await search("sha-drift"); if (response.status() !== 503) throw new Error("Original drift did not fail closed"); records.push({ scenario: "ORIGINAL_SHA_DRIFT", status: response.status() }); }
  finally { await writeFile(original, bytes); }
  const response = await context.request.get(target + "/v2/enforcement/legal-references/" + id + "?revision=3", { headers: { Authorization: "Bearer " + token } });
  if (response.status() !== 200) throw new Error("Restored repeated read failed");
  records.push({ scenario: "REPEATED_RECOVERED_READ", status: response.status(), originalSha256: sha });
  await writeFile(gate + ".read-ready", JSON.stringify({ runId }), { flag: "wx" });
  await waitGate(".withdraw-ready");
  for (const [base, expected] of [["https://127.0.0.1:5444", 403], [target, 503]]) {
    const closed = await context.request.get(base + "/v2/enforcement/legal-references/" + id + "?revision=3", { headers: { Authorization: "Bearer " + token } });
    if (closed.status() !== expected) throw new Error("Old authorization did not fail closed: " + base + " " + closed.status());
    records.push({ scenario: "CURRENT_GRANT_WITHDRAWAL", base, status: closed.status() });
  }
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, passed: true, referenceId: id, inputNature: "FUNCTIONAL_VALIDATION", records }, null, 2));
  console.log("PASSED " + runId);
} catch (error) {
  await page.screenshot({ path: resolve(out, "failure.png"), fullPage: true });
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, passed: false, error: String(error).split("\n")[0], records }, null, 2)); throw new Error(String(error).split("\n")[0]);
} finally { await browser.close(); }
