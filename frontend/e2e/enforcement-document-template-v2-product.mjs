import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { chromium } from "playwright";

const base = process.env.ENF_FOLLOWUP_BASE ?? "https://127.0.0.1:5174";
const login = process.env.ENF_FOLLOWUP_LOGIN;
const password = process.env.ENF_FOLLOWUP_PASSWORD;
const subject = process.env.ENF_FOLLOWUP_SUBJECT;
const templateId = process.env.ENF_FOLLOWUP_TEMPLATE_ID ?? "PROJECT-FOLLOWUP-TEMPLATE-20260929-01";
const resume = process.env.ENF_FOLLOWUP_RESUME === "true";
if (!login || !password || !subject) throw new Error("Secure functional identity is required");
const runId = process.env.ENF_FOLLOWUP_RUN_ID ?? `ENF12-BROWSER-${Date.now()}`;
if (!/^[A-Za-z0-9_-]+$/.test(runId)) throw new Error("Invalid execution identifier");
const output = resolve(process.env.ENF_FOLLOWUP_OUTPUT_ROOT ?? "target/enforcement-document-template-v2-product", runId);
await mkdir(output, { recursive: false });
const startedAt = new Date().toISOString();
const requests = [];
const source = resolve("../data/enforcement/document-template-v2-d-input-v1/project-functional-template.docx");

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true, acceptDownloads: true });
const page = await context.newPage();
page.setDefaultTimeout(15_000);
const stage = name => process.stdout.write(`STAGE ${name}\n`);
page.on("response", response => {
  const path = new URL(response.url()).pathname;
  if (path.startsWith("/v2/enforcement") || path.startsWith("/oauth2"))
    requests.push({ at: new Date().toISOString(), method: response.request().method(), path, status: response.status() });
});
try {
  await page.goto(`${base}/app/enforcement/document-templates`, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: "登录" }).click();
  await page.locator('input[name="username"]').fill(login);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.getByRole("heading", { name: "上传模板草稿" }).waitFor();
  stage("LOGIN_OK");

  if (resume) {
    const listResponse = page.waitForResponse(response => response.request().method() === "GET" &&
      new URL(response.url()).pathname === "/v2/enforcement/document-templates");
    await page.getByRole("button", { name: "刷新" }).click();
    const summaries = await (await listResponse).json();
    const rowIndex = summaries.findIndex(item => item.templateId === templateId);
    if (rowIndex < 0) throw new Error(`Template unavailable for resume: ${templateId}`);
    const rows = page.locator("tbody tr");
    await rows.nth(rowIndex).click();
    await page.getByText(`${templateId} v1`, { exact: true }).waitFor();
    stage("RESUME_OK");
  } else {
  await page.getByLabel("模板 ID").fill(templateId);
  await page.getByLabel("模板名称").fill("项目执法事项核对记录（功能验证）");
  await page.getByLabel("业务环节").fill("REVIEW");
  await page.getByLabel("地区").fill("PROJECT_TEST");
  await page.getByLabel("程序").fill("PROJECT_DOCUMENT_REVIEW");
  for (let index = 0; index < 5; index += 1) await page.getByRole("button", { name: "添加字段" }).click();
  const ids = ["task_ref", "party_name", "recorded_date", "summary", "materials[].ref", "materials[].note"];
  const types = ["STRING", "STRING", "DATE", "LONG_TEXT", "STRING", "LONG_TEXT"];
  const idInputs = page.locator('input[id^="fields_"][id$="_id"]');
  const typeInputs = page.locator('input[id^="fields_"][id$="_type"]');
  const repeatSelects = page.locator('[id^="fields_"][id$="_repeat"]');
  for (let index = 0; index < ids.length; index += 1) {
    await idInputs.nth(index).fill(ids[index]);
    await typeInputs.nth(index).fill(types[index]);
    if (index >= 4) {
      await repeatSelects.nth(index).click();
      await repeatSelects.nth(index).press("ArrowUp");
      await repeatSelects.nth(index).press("Enter");
      const selected = await repeatSelects.nth(index).evaluate(input =>
        input.closest(".ant-select")?.querySelector(".ant-select-selection-item")?.textContent?.trim());
      stage(`REPEAT ${ids[index]}=${selected}`);
    }
  }
  await page.locator('input[type="file"]').setInputFiles(source);
  await page.getByText("project-functional-template.docx", { exact: false }).waitFor();
  await page.waitForTimeout(500);
  stage("FORM_READY");
  const create = page.waitForResponse(response => response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/v2/enforcement/document-templates", { timeout: 10_000 });
  await page.getByLabel("模板 ID").evaluate(input => input.form?.requestSubmit());
  await page.waitForTimeout(500);
  const immediateValidation = await page.locator(".ant-form-item-explain-error").allInnerTexts();
  const immediateAlerts = await page.getByRole("alert").allInnerTexts();
  stage(`SUBMIT validation=${immediateValidation.join("|") || "none"} alerts=${immediateAlerts.join("|") || "none"}`);
  const created = await Promise.race([create, new Promise((_, reject) => setTimeout(
    () => reject(new Error("Template submit produced no HTTP response")), 12_000))]).catch(async failure => {
    const validation = await page.locator(".ant-form-item-explain-error").allInnerTexts();
    throw new Error(`Template form did not submit: ${validation.join(" | ")}`, { cause: failure });
  });
  if (created.status() !== 200) throw new Error(`Template create failed: ${created.status()} ${await created.text()}`);
  await page.getByText(`${templateId} v1`, { exact: true }).waitFor();
  stage("CREATE_OK");
  }

  await page.getByLabel("样例字段 JSON").fill(JSON.stringify({ task_ref: "PROJECT-TASK-1",
    party_name: "测试主体", recorded_date: "2026-09-29", summary: "功能验证，不代表正式文书",
    materials: [{ ref: "PROJECT-MATERIAL-A@1", note: "定位一" },
      { ref: "PROJECT-MATERIAL-B@1", note: "定位二" }] }));
  await page.getByRole("button", { name: "生成样例预览" }).click();
  await page.getByText(/预览 .*OOXML-DETERMINISTIC\/v1/).waitFor();
  stage("PREVIEW_OK");
  stage(`BUTTONS ${JSON.stringify(await page.getByRole("button").allTextContents())}`);
  const [previewDownload, previewResponse] = await Promise.all([
    page.waitForEvent("download"),
    page.waitForResponse(response => response.request().method() === "GET" &&
      /\/document-templates\/[^/]+\/previews\/[^/]+\/file$/.test(new URL(response.url()).pathname)),
    page.getByRole("button", { name: /^下\s*载$/ }).last().click()
  ]);
  await previewDownload.saveAs(resolve(output, "preview.docx"));
  if (previewResponse.status() !== 200) throw new Error("Preview download failed");
  stage("PREVIEW_DOWNLOAD_OK");

  await page.locator(".enf-next-actions button.ant-btn-primary").click();
  await page.getByText("PUBLISHED", { exact: true }).waitFor();
  stage("PUBLISH_OK");
  const originalDownload = page.waitForEvent("download");
  await page.getByRole("button", { name: "下载原版" }).click();
  await (await originalDownload).saveAs(resolve(output, "original.docx"));
  stage("ORIGINAL_DOWNLOAD_OK");
  await page.locator(".enf-next-actions button.ant-btn-dangerous").click();
  await page.getByText("DISABLED", { exact: true }).waitFor();
  stage("DISABLE_OK");
  await page.screenshot({ path: resolve(output, "document-template-disabled.png"), fullPage: true });

  const fs = await import("node:fs/promises");
  const original = await fs.readFile(resolve(output, "original.docx"));
  const rendered = await fs.readFile(resolve(output, "preview.docx"));
  const expected = await fs.readFile(source);
  if (!original.equals(expected)) throw new Error("Downloaded original differs from uploaded DOCX");
  const result = { runId, startedAt, checkedAt: new Date().toISOString(),
    templateId, subject, state: "DISABLED", sourceSha256: createHash("sha256").update(expected).digest("hex"),
    downloadedSha256: createHash("sha256").update(original).digest("hex"),
    previewSha256: createHash("sha256").update(rendered).digest("hex"), requests };
  await writeFile(resolve(output, "result.json"), JSON.stringify(result, null, 2));
  process.stdout.write(JSON.stringify({ templateId, state: result.state,
    httpFailures: requests.filter(item => item.status >= 400).length, sha: result.downloadedSha256 }, null, 2));
} catch (failure) {
  await page.screenshot({ path: resolve(output, "failure.png"), fullPage: true }).catch(() => {});
  await writeFile(resolve(output, "failure.json"), JSON.stringify({ runId, startedAt, checkedAt: new Date().toISOString(), message: failure.message, requests }, null, 2));
  throw failure;
} finally {
  await browser.close();
}
