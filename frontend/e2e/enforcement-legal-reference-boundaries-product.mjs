import { chromium } from "playwright";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { resolve } from "node:path";
const runId = process.env.ENF_LEGAL_BROWSER_RUN_ID;
if (!/^[A-Za-z0-9_-]+$/.test(runId ?? "")) throw new Error("Fresh run ID required");
const out = resolve("../docs/evidence/enforcement-followup-final-20260930", runId);
await mkdir(out, { recursive: false });
const baseline = JSON.parse(await readFile("../docs/evidence/enforcement-followup-final-20260930/ENF17-LEGAL-BROWSER-20260930-24-browser/result.json", "utf8"));
const ref = baseline.db.versions[0];
const records = [];
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true });
const page = await context.newPage(); let token;
page.on("response", async response => { if (new URL(response.url()).pathname === "/oauth2/token" && response.ok()) token = (await response.json()).access_token; });
async function login(p, user, password) {
  await p.goto("https://127.0.0.1:5174/app/enforcement/legal-references");
  await p.getByRole("button", { name: "登录", exact: true }).click();
  await p.locator('input[name="username"]').fill(user); await p.locator('input[name="password"]').fill(password);
  await p.locator('button[type="submit"]').click(); await p.getByLabel("已有引用 ID").waitFor();
}
const input = { organization: ref.organization, targetType: ref.targetType, targetId: ref.targetId,
  targetRevision: ref.targetRevision, targetAnchor: ref.targetAnchor, caseRef: "ENF17-TECH-CASE-01", purpose: "LEGAL_REFERENCE",
  region: "PROJECT_TEST", eventAt: "2026-09-14T01:00:00Z", query: "第一条", sourceRefs: [ref.sourceId] };
let sequence = 0;
async function post(name, path, data, expected, key = runId + "-" + (++sequence)) {
  const response = await context.request.post("https://127.0.0.1:5444/v2/enforcement" + path,
    { headers: { Authorization: "Bearer " + token, "Idempotency-Key": key }, data });
  let body; try { body = await response.json(); } catch {}
  records.push({ name, path, status: response.status(), expected, body, requestKey: key, at: new Date().toISOString() });
  if (response.status() !== expected) throw new Error(name + " expected " + expected + " actual " + response.status());
  return body;
}
async function search(name, changed, status, members) {
  const receipt = await post(name, "/legal-basis/searches", { ...input, ...changed }, 200);
  const response = await context.request.get("https://127.0.0.1:5444/v2/enforcement/legal-basis/searches/" + receipt.resultId + "?revision=1", { headers: { Authorization: "Bearer " + token } });
  const body = await response.json();
  if (!response.ok() || body.status !== status || body.members.length !== members) throw new Error(name + " result mismatch");
  records.push({ name: name + "_RESULT", status: response.status(), body });
  return body;
}
try {
  await login(page, process.env.ENF_FOLLOWUP_LOGIN, process.env.ENF_FOLLOWUP_PASSWORD);
  if (!token) throw new Error("Real PKCE session unavailable");
  await page.getByLabel("已有引用 ID").fill(ref.referenceId); await page.getByLabel("已有引用版本").fill("3");
  await page.getByRole("button", { name: "读取已有引用与历史" }).click(); await page.getByText(ref.referenceId + " v3", { exact: true }).waitFor();
  await page.screenshot({ path: resolve(out, "01-existing-history.png"), fullPage: true });
  await search("NO_MATCH", { query: "PROJECT_NO_MATCH_20260930_X" }, "COMPLETED", 0);
  await search("PARTIAL_SOURCE_FAILURE", { sourceRefs: [ref.sourceId, "PROJECT-MISSING-LEGAL-SOURCE"] }, "PARTIAL", 1);
  await search("SOURCE_FAILED_NOT_NO_MATCH", { sourceRefs: ["PROJECT-MISSING-LEGAL-SOURCE"] }, "FAILED", 0);
  await post("MISSING_TARGET_ANCHOR", "/legal-basis/searches", { ...input, targetAnchor: "items[999]" }, 409);
  await post("UNAUTHORIZED_TARGET_REVISION", "/legal-basis/searches", { ...input, targetRevision: 999 }, 403);
  await post("CROSS_ORGANIZATION", "/legal-basis/searches", { ...input, organization: "PROJECT-OTHER-ORG" }, 403);
  const selection = { searchId: ref.searchId, searchRevision: ref.searchRevision, candidateId: ref.candidateId,
    targetType: ref.targetType, targetId: ref.targetId, targetRevision: ref.targetRevision, targetAnchor: ref.targetAnchor };
  const key = baseline.db.receipts[0].REQUEST_KEY;
  const replay = await post("SAME_KEY_BIND_REPLAY", "/legal-references", selection, 200, key);
  if (replay.resultId !== ref.referenceId || replay.version !== 1) throw new Error("Replay created a new reference");
  await post("BIND_ANCHOR_MISMATCH", "/legal-references", { ...selection, targetAnchor: "items[1]" }, 409);
  const broad = await search("SECOND_REAL_CANDIDATE", { query: "细则" }, "COMPLETED", 2);
  await post("KEY_PAYLOAD_CONFLICT", "/legal-references", { ...selection, searchId: broad.searchId, candidateId: broad.members[1].candidateId }, 409, key);
  const other = await browser.newContext({ ignoreHTTPSErrors: true }); const b = await other.newPage();
  await login(b, process.env.ENF_FOLLOWUP_B_LOGIN, process.env.ENF_FOLLOWUP_B_PASSWORD);
  await b.getByLabel("已有引用 ID").fill(ref.referenceId); await b.getByLabel("已有引用版本").fill("3");
  const denied = b.waitForResponse(r => new URL(r.url()).pathname.endsWith(ref.referenceId));
  await b.getByRole("button", { name: "读取已有引用与历史" }).click();
  if ((await denied).status() !== 403) throw new Error("Other subject was not denied");
  await b.getByText("登录后检索、绑定并复核引用", { exact: true }).waitFor();
  if (await b.getByText(ref.referenceId + " v3", { exact: true }).count()) throw new Error("Denied subject retained report body");
  await b.screenshot({ path: resolve(out, "02-other-subject-denied-clear.png"), fullPage: true }); await other.close();
  records.push({ name: "OTHER_SUBJECT_PERMISSION_AND_CLEAR", status: 403, cachedBodyPresent: false });
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, passed: true, referenceId: ref.referenceId, inputNature: "FUNCTIONAL_VALIDATION", businessAcceptance: "PENDING", records }, null, 2));
  console.log("PASSED " + runId);
} catch (error) {
  await page.screenshot({ path: resolve(out, "failure.png"), fullPage: true });
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId, passed: false, error: String(error).split("\n")[0], records }, null, 2));
  throw new Error(String(error).split("\n")[0]);
} finally { await browser.close(); }
