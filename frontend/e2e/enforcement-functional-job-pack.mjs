import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { chromium } from "playwright";

const [base = "https://127.0.0.1:5174", output = "frontend/target/enforcement-functional-job-pack"] = process.argv.slice(2);
const runId = process.env.ENF_FUNCTIONAL_RUN_ID ?? "ENF-FUNCTIONAL-BROWSER-20260929-01";
const packageId = process.env.ENF_FUNCTIONAL_PACKAGE_ID ?? "ENF-JOB-FUNCTIONAL-20260929-01";
const createNew = process.env.ENF_FUNCTIONAL_CREATE_NEW === "true";
const required = ["ENF_FUNCTIONAL_A_LOGIN", "ENF_FUNCTIONAL_A_PASSWORD", "ENF_FUNCTIONAL_A_SUBJECT",
  "ENF_FUNCTIONAL_B_LOGIN", "ENF_FUNCTIONAL_B_PASSWORD", "ENF_FUNCTIONAL_B_SUBJECT"];
for (const name of required) if (!process.env[name]) throw new Error(`Missing secure runtime input: ${name}`);

const evidence = resolve(output);
await mkdir(evidence, { recursive: true });
const events = [];
const steps = [];
const file = async (name, text) => {
  const path = resolve(evidence, name);
  await writeFile(path, text, "utf8");
  return { path, sha256: createHash("sha256").update(text).digest("hex") };
};
const taskFile = await file("functional-task.txt", "FUNCTIONAL_VALIDATION\n脱敏测试任务，不代表真实案件或正式业务事实。\n");
const sourceFile = await file("functional-source.txt", "FUNCTIONAL_VALIDATION\n项目生成测试输入，仅用于执法 V2 功能验证。\n");
const approvalFile = await file("functional-approval.txt", "FUNCTIONAL_VALIDATION\n测试主体在产品页面作出的功能验证批准，不是正式业务批准。\n");

function observe(page, actor) {
  page.on("pageerror", error => events.push({ at: new Date().toISOString(), actor, kind: "PAGE_ERROR",
    message: error.message, stack: error.stack }));
  page.on("console", message => {
    if (message.type() === "error") events.push({ at: new Date().toISOString(), actor, kind: "CONSOLE_ERROR",
      message: message.text() });
  });
  page.on("response", response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/v2/") || url.pathname.startsWith("/oauth2/") || url.pathname === "/login") {
      events.push({ at: new Date().toISOString(), actor, method: response.request().method(), path: url.pathname,
        status: response.status() });
    }
  });
}

async function login(browser, actor, loginName, password, expectedSubject) {
  const context = await browser.newContext();
  const page = await context.newPage();
  observe(page, actor);
  await page.goto(`${base}/app/enforcement/job-pack`, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "登录" }).click();
  await page.locator('input[name="username"]').fill(loginName);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.getByText("当前登录身份").waitFor();
  await page.getByText(expectedSubject, { exact: true }).waitFor();
  steps.push({ at: new Date().toISOString(), actor, action: "LOGIN", url: page.url(), subject: expectedSubject });
  return { context, page };
}

async function readPack(page) {
  const toolbar = page.locator("section.enf-job-pack-toolbar");
  await toolbar.getByPlaceholder("输入任务包 ID").fill(packageId);
  await toolbar.getByRole("button", { name: "读取" }).click();
  await page.getByText("功能验证任务包").waitFor();
}

async function choose(page, scope, label, value) {
  const input = scope.getByLabel(label, { exact: true });
  await input.click();
  await input.press("Home");
  const numeric = Number(value);
  if (Number.isInteger(numeric) && numeric > 1) {
    for (let index = 1; index < numeric; index += 1) await input.press("ArrowDown");
  }
  await input.press("Enter");
}

async function submitFact(page, actor, title, fields, attachment) {
  await page.getByRole("button", { name: `打开${title}采集步骤` }).click();
  const scope = page.locator("section.enf-job-pack-step");
  if (await scope.getByText("异人核验通过", { exact: true }).count()) {
    steps.push({ at: new Date().toISOString(), actor, action: "SKIP_ALREADY_VERIFIED", fact: title });
    return;
  }
  for (const [label, value] of Object.entries(fields)) {
    const input = scope.getByLabel(label, { exact: true });
    if (Array.isArray(value)) await choose(page, scope, label, value[0]);
    else await input.fill(value);
  }
  for (const [label, value] of Object.entries(fields)) {
    if (Array.isArray(value)) continue;
    const input = scope.getByLabel(label, { exact: true });
    if (await input.inputValue() !== value) await input.fill(value);
  }
  if (attachment) {
    await scope.getByLabel("证据 Ref").fill(attachment.ref);
    await scope.getByLabel("原件文件").setInputFiles(attachment.path);
  }
  await scope.getByRole("button", { name: "提交新版本" }).click();
  const result = await page.waitForResponse(item => item.request().method() === "POST"
    && item.url().includes(`/v2/enforcement/job-packs/${packageId}/facts/`), { timeout: 8_000 }).catch(() => null);
  if (!result) {
    const validation = await scope.locator(".ant-form-item-explain-error").allInnerTexts();
    const alerts = await page.getByRole("alert").allInnerTexts();
    throw new Error(`${actor} did not submit ${title}; validation=${validation.join("|")} alerts=${alerts.join("|")}`);
  }
  if (!result.ok()) throw new Error(`${actor} failed to submit ${title}: ${result.status()}`);
  await scope.getByText("待异人核验").waitFor();
  steps.push({ at: new Date().toISOString(), actor, action: "SUBMIT", fact: title, status: result.status() });
}

async function verifyFact(page, actor, title) {
  await readPack(page);
  await page.getByRole("button", { name: `打开${title}采集步骤` }).click();
  const scope = page.locator("section.enf-job-pack-step");
  if (await scope.getByText("异人核验通过", { exact: true }).count()) {
    steps.push({ at: new Date().toISOString(), actor, action: "SKIP_ALREADY_VERIFIED", fact: title });
    return;
  }
  const review = page.locator("form.enf-job-pack-review");
  await review.locator("input#reason").fill(`${runId} ${title} 异人功能复核通过`);
  const response = page.waitForResponse(item => item.request().method() === "POST"
    && item.url().includes(`/v2/enforcement/job-packs/${packageId}/facts/`)
    && item.url().endsWith("/verification"));
  await review.getByRole("button", { name: "核验通过" }).click();
  const result = await response;
  if (!result.ok()) throw new Error(`${actor} failed to verify ${title}: ${result.status()}`);
  steps.push({ at: new Date().toISOString(), actor, action: "VERIFY", fact: title, status: result.status() });
}

const browser = await chromium.launch({ headless: true });
let a;
let b;
try {
  a = await login(browser, "FUNCTIONAL_A", process.env.ENF_FUNCTIONAL_A_LOGIN,
    process.env.ENF_FUNCTIONAL_A_PASSWORD, process.env.ENF_FUNCTIONAL_A_SUBJECT);
  b = await login(browser, "FUNCTIONAL_B", process.env.ENF_FUNCTIONAL_B_LOGIN,
    process.env.ENF_FUNCTIONAL_B_PASSWORD, process.env.ENF_FUNCTIONAL_B_SUBJECT);

  if (!createNew) {
    const toolbar = a.page.locator("section.enf-job-pack-toolbar");
    await toolbar.getByPlaceholder("输入任务包 ID").fill(packageId);
    const existingResponse = a.page.waitForResponse(item => item.request().method() === "GET"
      && new URL(item.url()).pathname === `/v2/enforcement/job-packs/${packageId}`);
    await toolbar.getByRole("button", { name: "读取" }).click();
    const existing = await existingResponse;
    if (!existing.ok()) throw new Error(`Functional package is unavailable: ${existing.status()}`);
    await a.page.getByText("功能验证任务包").waitFor();
    steps.push({ at: new Date().toISOString(), actor: "FUNCTIONAL_A", action: "RESUME", packageId,
      status: existing.status(), purpose: "FUNCTIONAL_VALIDATION" });
  } else {
    const create = a.page.locator("section.enf-job-pack-create");
    await create.getByLabel("任务包 ID").fill(packageId);
    await create.getByLabel("任务名称").fill("执法 V2 功能验证任务（测试）");
    const createResponse = a.page.waitForResponse(item => item.request().method() === "POST"
      && new URL(item.url()).pathname === "/v2/enforcement/job-packs");
    await create.getByRole("button", { name: "开始采集" }).click();
    const created = await createResponse;
    if (!created.ok()) throw new Error(`Functional package create failed: ${created.status()}`);
    await a.page.getByText("功能验证任务包").waitFor();
    steps.push({ at: new Date().toISOString(), actor: "FUNCTIONAL_A", action: "CREATE", packageId,
      status: created.status(), purpose: "FUNCTIONAL_VALIDATION" });
  }
  if (await a.page.getByText("页面加载失败").count()) throw new Error("Job Pack page failed after loading the functional snapshot");
  if (await a.page.getByLabel("授权组织").count()) throw new Error("Organization remained a manual input");

  await submitFact(a.page, "FUNCTIONAL_A", "测试任务输入", {
    "外部任务 Ref": "TEST-TASK-20260929-01", "来源版本": "1", "原始任务 SHA-256": taskFile.sha256,
    "当前人工流程": "项目测试主体通过产品页面完成双人功能验证，不代表真实案件处理",
    "预期输出": "形成可复核任务包并唯一触发 NEXT-V2-INT-ENF-01 功能联调"
  }, { ref: "FUNCTIONAL-TASK-EVIDENCE-01", path: taskFile.path });
  await verifyFact(b.page, "FUNCTIONAL_B", "测试任务输入");

  await readPack(a.page);
  await submitFact(a.page, "FUNCTIONAL_A", "测试数据来源", {
    "来源 Ref": "PROJECT-FUNCTIONAL-DATA-20260929-01", "来源版本": "1",
    "权利人 / 授权方": "Xinji Project Functional Validation", "允许用途（须含 FUNCTIONAL_VALIDATION）": "FUNCTIONAL_VALIDATION",
    "授权范围": packageId, "有效期开始": "2026-09-29", "有效期结束": "2026-10-06"
  }, { ref: "FUNCTIONAL-SOURCE-EVIDENCE-01", path: sourceFile.path });
  await verifyFact(b.page, "FUNCTIONAL_B", "测试数据来源");

  await readPack(b.page);
  await submitFact(b.page, "FUNCTIONAL_B", "测试人工批准", {
    "决定": ["批准"], "批准方法": "双测试主体产品页面操作",
    "决定理由": "仅批准功能验证，不代表正式业务决定", "所批准内容版本": "2"
  }, { ref: "FUNCTIONAL-APPROVAL-EVIDENCE-01", path: approvalFile.path });
  await verifyFact(a.page, "FUNCTIONAL_A", "测试人工批准");

  await readPack(a.page);
  await submitFact(a.page, "FUNCTIONAL_A", "测试人工回执", {
    "人工回执原文": "功能验证回执：页面、权限、版本、审计和触发链已按测试任务执行。",
    "关联内容版本": "2"
  });
  await verifyFact(b.page, "FUNCTIONAL_B", "测试人工回执");

  await readPack(a.page);
  await submitFact(a.page, "FUNCTIONAL_A", "功能评价", {
    "完整性（1-5）": ["5"], "可追溯性（1-5）": ["5"], "可操作性（1-5）": ["5"],
    "事实准确性（1-5）": ["5"], "人工投入改善（1-5）": ["4"],
    "评价依据": "仅评价本次功能验证链的可操作性与证据完整性",
    "评价结论": "技术功能闭环可用；正式业务验收仍待后续受控试点", "关联内容版本": "2"
  });
  await verifyFact(b.page, "FUNCTIONAL_B", "功能评价");

  await readPack(b.page);
  await b.page.getByText("已自动触发功能联调").waitFor();
  await b.page.getByText("NEXT-V2-INT-ENF-01", { exact: false }).waitFor();
  await b.page.getByText("真实集成执行中").waitFor();
  if (await b.page.getByText("已自动触发真实集成").count()) throw new Error("Functional trigger was mislabeled as business acceptance");
  await b.page.screenshot({ path: resolve(evidence, "functional-running.png"), fullPage: true });
  const result = { runId, checkedAt: new Date().toISOString(), packageId, state: "RUNNING",
    classification: ["FUNCTIONAL_READY", "TECHNICAL_VALIDATED", "BUSINESS_ACCEPTANCE_PENDING"],
    trigger: "NEXT-V2-INT-ENF-01", events, steps,
    fixtures: [{ name: "functional-task.txt", sha256: taskFile.sha256 },
      { name: "functional-source.txt", sha256: sourceFile.sha256 },
      { name: "functional-approval.txt", sha256: approvalFile.sha256 }] };
  await writeFile(resolve(evidence, "result.json"), JSON.stringify(result, null, 2));
  process.stdout.write(JSON.stringify({ runId, packageId, state: result.state, steps: steps.length,
    httpFailures: events.filter(item => item.status >= 400).length }, null, 2));
} catch (failure) {
  await writeFile(resolve(evidence, "failure.json"), JSON.stringify({ runId, failedAt: new Date().toISOString(),
    message: failure instanceof Error ? failure.message : String(failure), events, steps }, null, 2));
  await a?.page.screenshot({ path: resolve(evidence, "failure-a.png"), fullPage: true }).catch(() => {});
  await b?.page.screenshot({ path: resolve(evidence, "failure-b.png"), fullPage: true }).catch(() => {});
  throw failure;
} finally {
  await a?.context.close().catch(() => {});
  await b?.context.close().catch(() => {});
  await browser.close();
}
