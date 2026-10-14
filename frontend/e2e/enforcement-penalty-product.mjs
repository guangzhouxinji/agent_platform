import { chromium } from "playwright";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const required = name => { const value = process.env[name]; if (!value) throw new Error(name + " required; no skip"); return value; };
const out = required("ENF_PENALTY_BROWSER_EVIDENCE"); await mkdir(out, { recursive: false });
const prepared = JSON.parse(await readFile(required("ENF_PENALTY_PREP_EVIDENCE"), "utf8"));
const run = required("ENF_PENALTY_RUN_ID"), id = prepared.ruleId, org = required("ENF_ACCEPTANCE_ORGANIZATION");
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1100 }, reducedMotion: "reduce" });
const page = await context.newPage(); const records = []; const pending = new Set(); let recordTail = Promise.resolve();
let savedCommand, authorization;
page.on("request", request => {
  const path = new URL(request.url()).pathname;
  if (path.startsWith("/v2/enforcement/penalty")) {
    authorization = request.headers().authorization || authorization;
    if (path.endsWith("/rules") && request.method() === "POST") savedCommand = { key: request.headers()["idempotency-key"], body: request.postDataJSON() };
  }
});
page.on("response", response => {
  const path = new URL(response.url()).pathname;
  if (!path.startsWith("/v2/enforcement/penalty")) return;
  const capture = (async () => { const entry = { path, method: response.request().method(), status: response.status(), at: new Date().toISOString() }; try { entry.body = await response.json(); } catch {} records.push(entry); recordTail = recordTail.then(() => writeFile(resolve(out, "http-records.json"), JSON.stringify(records, null, 2))); await recordTail; })();
  pending.add(capture); capture.finally(() => pending.delete(capture));
});
async function idle() { await page.locator('main.enf-next[aria-busy="false"]').waitFor(); }
async function clickResult(name, path, expected) {
  await idle(); const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith(path) && r.request().method() === "POST");
  const [actual] = await Promise.all([response, page.getByRole("button", { name, exact: true }).click()]); assert.equal(actual.status(), expected); await idle(); return actual.json();
}
try {
  await page.goto("https://127.0.0.1:5178/app/enforcement/penalty");
  await page.getByRole("button", { name: /^登\s*录$/ }).click();
  await page.locator('input[name="username"]').fill(required("ENF_FOLLOWUP_LOGIN"));
  await page.locator('input[name="password"]').fill(required("ENF_FOLLOWUP_PASSWORD"));
  await page.locator('button[type="submit"]').click(); await page.getByRole("heading", { name: "维护草稿" }).waitFor();
  const ref = prepared.legalReference;
  const amount = value => ({ B: { value, confirmed: true, sourceRef: "PROJECT-EXPLICIT-SCORE-INPUT", sourceVersion: "1", locator: "symbolic project parameter, not statutory amount" } });
  const calculation = { organization: org, procedureApplicable: true, basisRef: ref.referenceId, basisRevision: ref.revision,
    formula: { type: "BINARY", operator: "MULTIPLY", left: { type: "INPUT", name: "B" }, right: { type: "CONSTANT", value: 2, basisRef: "PROJECT-FACTOR@1" } },
    semantics: { kind: "PROJECT_SCORE", currency: "NONE", unit: "POINT", scale: 2, rounding: "HALF_UP" },
    range: { minimum: 0, maximum: 100, legalBasisRef: ref.referenceId, legalBasisRevision: ref.revision },
    boundaries: [{ name: "LOWER", inputs: amount(0), expected: 0 }, { name: "UPPER", inputs: amount(50), expected: 100 }], overlaps: [] };
  const scope = { conductType: "PROJECT_SCORE_INPUT_NOT_LEGAL", authorityRef: "PROJECT-TECHNICAL-VALIDATION", procedure: "PROJECT_TEST", region: "PROJECT_TEST", effectiveFrom: "2026-10-01", effectiveUntil: "2026-10-02" };
  await page.getByLabel("规则编号", { exact: true }).fill(id); await page.getByLabel("规则名称", { exact: true }).fill("Explicit technical score trial, not penalty standard");
  await page.getByLabel("行为、职权、程序、地区、有效期（类型化 JSON）", { exact: true }).fill(JSON.stringify(scope));
  await page.getByLabel("裁量因素及从轻/减轻/不罚条件和证据（JSON 数组）", { exact: true }).fill("[]");
  await page.getByLabel("依据版本、类型化公式、金额口径、法定范围、边界样例及优先关系（JSON）", { exact: true }).fill(JSON.stringify(calculation));
  let save;
  if (process.env.ENF_PENALTY_RESUME_DRAFT === "true") {
    assert.equal(prepared.rules.length, 1); assert.equal(prepared.receipts.length, 1); assert.equal(prepared.events.length, 1);
    await page.getByRole("button", { name: "刷新当前列表", exact: true }).click(); await idle();
    await page.getByRole("row").filter({ hasText: "Explicit technical score trial, not penalty standard" }).getByRole("button", { name: "打开规则", exact: true }).click(); await idle();
    const previous = prepared.rules[0].payload;
    savedCommand = { key: prepared.receipts[0].REQUEST_KEY, body: { expectedRevision: 0, rule: previous } };
    save = { requestId: savedCommand.key, operation: "SAVE_PENALTY_RULE", resourceKind: "PENALTY_RULE", resourceId: id, revision: 1, evidenceOrigin: "EXISTING_ACTUAL_DRAFT_READ_THROUGH_BROWSER_NOT_NEW_SAVE" };
  } else {
    save = await clickResult("保存草稿版本", "/penalty/rules", 200); assert.equal(save.resourceId, id); assert.equal(save.revision, 1);
  }
  await page.getByText(id + " v1", { exact: true }).waitFor(); await page.screenshot({ path: resolve(out, "01-real-product-draft.png"), fullPage: true });
  const validation = await clickResult("发布前核验", "/validate", 200); assert.equal(validation.publishable, false); assert.ok(validation.gaps.length > 0);
  await page.locator("#penalty-trial-amounts").fill("{}");
  const missing = await clickResult("无副作用试算", "/trial", 200); assert.equal(missing.status, "WAITING_INPUT"); assert.equal(missing.amount, null);
  await page.locator("#penalty-trial-amounts").fill(JSON.stringify(amount(0)));
  const zero = await clickResult("无副作用试算", "/trial", 200); assert.equal(zero.status, "TRIAL"); assert.equal(Number(zero.amount), 0);
  await page.locator("#penalty-trial-amounts").fill(JSON.stringify(amount(12)));
  const normal = await clickResult("无副作用试算", "/trial", 200); assert.equal(Number(normal.amount), 24);
  const repeated = await clickResult("无副作用试算", "/trial", 200); assert.deepEqual(normal, repeated);
  await page.screenshot({ path: resolve(out, "02-real-product-trial.png"), fullPage: true });
  // Rejection may have an empty error body; keep the true HTTP status instead of inventing output.
  await idle(); const rejected = page.waitForResponse(r => new URL(r.url()).pathname.endsWith("/publish") && r.request().method() === "POST");
  const [publishResponse] = await Promise.all([rejected, page.getByRole("button", { name: /^发\s*布$/ }).click()]); assert.equal(publishResponse.status(), 409); await idle(); await page.getByRole("alert").last().waitFor();
  const publicationProblem = await publishResponse.json(); assert.equal(publicationProblem.code, "PENALTY_PUBLICATION_REJECTED");
  assert.deepEqual(publicationProblem.gaps, validation.gaps); assert.equal(publicationProblem.request_id, publishResponse.request().headers()["idempotency-key"]);
  await page.locator('pre[aria-label="实际核验或试算结果"]').filter({ hasText: "LEGAL_REFERENCE_REVIEW_REQUIRED" }).waitFor();
  await page.screenshot({ path: resolve(out, "03-unconfirmed-basis-publication-rejected.png"), fullPage: true });
  assert.ok(savedCommand?.key && authorization, "Real browser command and current secure session required");
  const http = [];
  for (const [name, key, body, expected] of [["SAME_COMMAND", savedCommand.key, savedCommand.body, 200], ["CHANGED_SAME_KEY", savedCommand.key, { ...savedCommand.body, rule: { ...savedCommand.body.rule, name: "different payload" } }, 409], ["STALE_NEW_KEY", crypto.randomUUID(), savedCommand.body, 409]]) {
    const response = await page.evaluate(async input => { const r = await fetch("/v2/enforcement/penalty/rules", { method: "POST", headers: { Authorization: input.authorization, "Content-Type": "application/json", "Idempotency-Key": input.key }, body: JSON.stringify(input.body) }); return { status: r.status, body: await r.json().catch(() => null) }; }, { key, body, authorization });
    assert.equal(response.status, expected); http.push({ name, ...response, scope: "ACTUAL_HTTP_NOT_BROWSER_CLICK" });
  }
  // ENF-16 permission boundary through the actual product form. This explicitly
  // controlled request is not a confirmed case or a business acceptance input.
  const unauthorizedSuggestionId = "ENF16-DENIED-" + run;
  await page.getByRole("tab", { name: "ENF-16 建议", exact: true }).click();
  await page.getByLabel("建议编号", { exact: true }).fill(unauthorizedSuggestionId);
  await page.getByLabel("案件、具名事实、因素、金额来源及上游确切版本/SHA（JSON）", { exact: true }).fill(JSON.stringify({
    caseId: "CONTROLLED-UNAUTHORIZED-CASE-" + run, caseRevision: 1, applicableDate: "2026-10-01",
    conductType: scope.conductType, procedure: scope.procedure, region: scope.region,
    dependencies: [], amounts: {}, factors: [], confirmedFacts: []
  }));
  await page.getByLabel("ACTIVE 候选规则确切版本（JSON 数组）", { exact: true }).fill(JSON.stringify([{ kind: "PENALTY_RULE", id, revision: 1, sha256: null }]));
  const unauthorizedResponse = page.waitForResponse(r => new URL(r.url()).pathname.endsWith("/penalty/suggestions") && r.request().method() === "POST");
  const [denied] = await Promise.all([unauthorizedResponse, page.getByRole("button", { name: "生成 / 重跑建议", exact: true }).click()]);
  assert.equal(denied.status(), 403);
  await page.getByRole("button", { name: /^登\s*录$/ }).waitFor();
  await assert.rejects(() => page.getByText(id + " v1", { exact: true }).waitFor({ timeout: 1000 }));
  await page.screenshot({ path: resolve(out, "04-enf16-unauthorized-create-clears-session.png"), fullPage: true });
  const suggestionPermission = { id: unauthorizedSuggestionId, status: denied.status(), sessionCleared: true, nature: "EXPLICIT_CONTROLLED_UNAUTHORIZED_INPUT_NOT_REAL_CASE" };
  await Promise.all(pending);
  await writeFile(resolve(out, "http-records.json"), JSON.stringify(records, null, 2));
  await writeFile(resolve(out, "result.json"), JSON.stringify({ runId: run, scope: "REAL_HTTPS_OAUTH_PKCE_BROWSER_PARTIAL_ENF15_NOT_FINAL_ACCEPTANCE", inputNature: "FUNCTIONAL_VALIDATION_NOT_BUSINESS_ACCEPTANCE", ruleId: id, save, validation, missing, zero, normal, repeated, replay: http, suggestionPermission, expected: { state: "DRAFT", revisions: 1, receipts: 1, events: 1, noSuccessfulPublish: true, noSuggestionOrOfficialDecision: true } }, null, 2));
  console.log("Actual OAuth/PKCE + HTTPS browser draft/trial/rejection and HTTP replay passed; remaining lifecycle/recovery not accepted");
} catch (error) {
  await page.screenshot({ path: resolve(out, "failure.png"), fullPage: true }).catch(() => {});
  const password = process.env.ENF_FOLLOWUP_PASSWORD;
  const detail = password ? String(error).replaceAll(password, "[REDACTED]") : String(error);
  await Promise.all(pending); await writeFile(resolve(out, "http-records.json"), JSON.stringify(records, null, 2));
  await writeFile(resolve(out, "failure.json"), JSON.stringify({ runId: run, scope: "FAILED_NOT_ACCEPTED", error: detail }, null, 2));
  throw new Error("Actual penalty browser validation failed; inspect retained failure evidence");
} finally { authorization = undefined; await browser.close(); }
