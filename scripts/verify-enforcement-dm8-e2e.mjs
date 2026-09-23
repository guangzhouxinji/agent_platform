import { chromium } from "@playwright/test";
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";

for (const key of ["DM8_JDBC_URL", "DM8_JDBC_USER", "DM8_JDBC_PASSWORD"]) if (!process.env[key]) throw new Error(`${key} is required`);
const port = process.env.ENFORCEMENT_E2E_PORT || "18083";
const baseUrl = `http://127.0.0.1:${port}`;
const actor = "v0-authorized-user", reviewer = "v0-enforcement-reviewer", organization = "org-v0";
const token = `e2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
const sourceId = `main-flow-${token}`, sourceName = `执法主线验证 ${token}`;
const ruleId = `rule-${token}`, ruleName = `内容存在开发规则 ${token}`;
const mvn = process.platform === "win32" ? "mvn.cmd" : "mvn";
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
let app, browser;

function assert(ok, message) { if (!ok) throw new Error(message); }
function run(command, args) {
  const executable = process.platform === "win32" ? (process.env.ComSpec || "cmd.exe") : command;
  const commandArgs = process.platform === "win32" ? ["/d", "/s", "/c", command, ...args] : args;
  const result = spawnSync(executable, commandArgs, { cwd: process.cwd(), env: process.env, stdio: "inherit" });
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}`);
}
function start() {
  const logs = [], child = spawn(java, ["-jar", "backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar"], { cwd: process.cwd(), env: { ...process.env, ENFORCEMENT_STORAGE: "dm8", ENFORCEMENT_SOURCE: "database", ENFORCEMENT_SOURCE_IMPORT_ENABLED: "true", CONSULTATION_STORAGE: "memory", SERVER_PORT: port, SPRING_DATASOURCE_URL: process.env.DM8_JDBC_URL, SPRING_DATASOURCE_USERNAME: process.env.DM8_JDBC_USER, SPRING_DATASOURCE_PASSWORD: process.env.DM8_JDBC_PASSWORD, SPRING_DATASOURCE_DRIVER_CLASS_NAME: "dm.jdbc.driver.DmDriver" }, stdio: ["ignore", "pipe", "pipe"] });
  child.stdout.on("data", data => logs.push(String(data))); child.stderr.on("data", data => logs.push(String(data))); child.logs = logs; return child;
}
async function ready(child) {
  for (const end = Date.now() + 45000; Date.now() < end; await new Promise(resolve => setTimeout(resolve, 300))) {
    if (child.exitCode !== null) throw new Error(`Application stopped\n${child.logs.join("")}`);
    try { if ((await fetch(`${baseUrl}/app/`)).ok) return; } catch { }
  } throw new Error(`Application did not become ready\n${child.logs.join("")}`);
}
async function stop() { if (app?.exitCode === null) { app.kill(); await Promise.race([new Promise(resolve => app.once("exit", resolve)), new Promise((_, reject) => setTimeout(() => reject(new Error("Application did not stop")), 10000))]); } }
async function request(page, path, options = {}) {
  return page.evaluate(async ({ path, options, actor, organization }) => { const response = await fetch(path, { ...options, headers: { "X-V0-Actor": actor, "X-V0-Organization": organization, ...(options.headers || {}) } }); return { status: response.status, body: await response.json().catch(() => null) }; }, { path, options, actor, organization });
}
async function select(page, label, text) {
  await page.getByLabel(label).click();
  await page.locator(".ant-select-dropdown:visible .ant-select-item-option:visible").filter({ hasText: text }).click();
}

try {
  // The probe's setup phase creates only missing tables; no user records are deleted.
  run(mvn, ["-Denforcement.browser.phase=setup", "-Dtest=Dm8EnforcementBrowserDatabaseProbe", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]);
  run(mvn, ["-DskipTests", "-Pbundle-frontend", "package"]);
  app = start(); await ready(app); browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });

  await page.goto(`${baseUrl}/app/enforcement/sources`);
  await page.getByRole("button", { name: "新增数据源" }).click();
  await page.getByLabel("数据源编号").fill(sourceId); await page.getByLabel("数据源名称").fill(sourceName); await page.getByLabel("负责人").fill("主线验证人员");
  const createSource = page.waitForResponse(r => r.url().endsWith(`/source-configurations/${sourceId}`) && r.request().method() === "PUT");
  await page.getByRole("button", { name: "保存配置" }).click(); assert((await createSource).status() === 200, "Source creation must succeed through the UI");
  await page.getByText(sourceName, { exact: false }).first().waitFor();
  const testSource = page.waitForResponse(r => r.url().endsWith(`/source-configurations/${sourceId}/connection-tests`) && r.request().method() === "POST"); await page.getByRole("button", { name: "检查连接" }).click(); assert((await testSource).status() === 200, "Connection test must succeed");
  const syncResponse = page.waitForResponse(r => r.url().endsWith(`/source-configurations/${sourceId}/sync-tasks`) && r.request().method() === "POST"); await page.getByRole("button", { name: "立即同步" }).click(); const sync = await (await syncResponse).json(); assert(sync.data.status === "succeeded" && sync.data.importedCount > 0, "Sync must import local snapshots");

  // Dynamic rule conditions are created through the real API; dry run and publication are exercised in the workbench.
  const rule = await request(page, "/api/v0/enforcement/discovery-rules", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ruleId, ruleName, category: "COUNTERFEIT_PATENT", description: "开发期主线验证", sourceIds: [sourceId], condition: { nodeId: "root", operator: "OR", children: [{ nodeId: "content-present", operator: "EXISTS", fieldPath: "content", children: [] }, { nodeId: "content-marker", operator: "TEXT_CONTAINS", fieldPath: "content", value: "开发期内容存在样例", children: [] }] } }) });
  assert(rule.status === 200, "Rule draft must be persisted in DM8");
  await page.goto(`${baseUrl}/app/enforcement/rules`); await page.getByText(ruleName, { exact: false }).first().click(); await page.getByRole("button", { name: "试运行" }).click(); await page.getByLabel("内容字段").fill("开发期内容存在样例");
  const dryRunResponse = page.waitForResponse(r => r.url().includes(`/discovery-rules/${ruleId}/versions/1/dry-runs`) && r.request().method() === "POST"); await page.getByRole("button", { name: "执行试运行" }).click(); const dryRun = await (await dryRunResponse).json(); assert(dryRun.data.matched && dryRun.data.resultStatus === "SUCCEEDED", "Dry run must match"); await page.keyboard.press("Escape");
  const publishResponse = page.waitForResponse(r => r.url().includes(`/discovery-rules/${ruleId}/versions/1/publish`) && r.request().method() === "POST"); await page.getByRole("button", { name: "开发期发布" }).click(); assert((await publishResponse).status() === 200, "Rule must publish through UI");

  await page.goto(`${baseUrl}/app/enforcement/discovery`); await select(page, "同步批次", sourceName); await select(page, "发现规则", ruleName);
  const discoveryResponse = page.waitForResponse(r => r.url().endsWith("/api/v0/enforcement/discovery-tasks") && r.request().method() === "POST"); await page.getByRole("button", { name: "运行发现" }).click(); const discovery = await (await discoveryResponse).json(); const leadId = discovery.data.leadIds?.[0]; assert(discovery.data.status === "succeeded" && leadId, "Discovery must create a lead");
  await page.getByText(leadId, { exact: false }).first().click(); await page.getByRole("button", { name: "开始核实" }).click(); const verifyResponse = page.waitForResponse(r => r.url().includes(`/leads/${leadId}/start-verification`) && r.request().method() === "POST"); await page.getByRole("button", { name: "确认操作" }).click(); assert((await verifyResponse).status() === 200, "Verification start must succeed through UI");

  await page.goto(`${baseUrl}/app/enforcement/parsing`); await page.getByText(leadId, { exact: false }).first().click(); const parseResponse = page.waitForResponse(r => r.url().includes(`/leads/${leadId}/parse-tasks`) && r.request().method() === "POST"); await page.getByRole("button", { name: "运行解析" }).click(); const parsed = await (await parseResponse).json(); assert(parsed.data.resultVersion === 1 && parsed.data.fieldCount > 0, "Parse must create a nonempty first version");
  await page.getByRole("button", { name: "人工修订" }).click(); await page.getByLabel("修订原因").fill("主线验证：已核对本地来源"); const revisionResponse = page.waitForResponse(r => r.url().includes(`/leads/${leadId}/parses/1/revisions`) && r.request().method() === "POST"); await page.getByRole("button", { name: "生成修订版本" }).click(); const revision = await (await revisionResponse).json(); assert(revision.data.version === 2, "Revision must append version 2");
  await page.getByRole("button", { name: "确认版本" }).click(); const confirmResponse = page.waitForResponse(r => r.url().includes(`/leads/${leadId}/parses/2/confirm`) && r.request().method() === "POST"); await page.getByRole("dialog").getByRole("button", { name: "确认版本" }).click(); assert((await confirmResponse).status() === 200, "Reviewer confirmation must succeed");

  await page.goto(`${baseUrl}/app/enforcement/evidence`); await page.getByRole("button", { name: "登记证据" }).click(); await page.getByLabel("证据名称").fill(`主线验证材料 ${token}`); await select(page, "证据类型", "书证"); await select(page, "关联线索", leadId); await page.getByLabel("收集信息缺失原因").fill("开发期受控材料未提供收集人与时间"); await page.locator('input[type="file"]').setInputFiles({ name: `main-flow-${token}.txt`, mimeType: "text/plain", buffer: Buffer.from(`development-evidence:${token}`) });
  const evidenceResponse = page.waitForResponse(r => r.url().endsWith("/api/v0/enforcement/evidence") && r.request().method() === "POST"); await page.getByRole("button", { name: "登记并计算摘要" }).click(); const evidence = await (await evidenceResponse).json(); const evidenceId = evidence.data.evidenceId; assert(evidence.data.versions[0].digest, "Evidence must store a content digest");
  const verifyEvidenceResponse = page.waitForResponse(r => r.url().includes(`/evidence/${evidenceId}/status`) && r.request().method() === "POST"); await page.getByRole("button", { name: "标记已核验" }).click(); const verifiedEvidence = await (await verifyEvidenceResponse).json(); assert(verifiedEvidence.data.status === "VERIFIED" && verifiedEvidence.data.recordVersion === 2, "Evidence verification must append a record version");
  mkdirSync("target/playwright-artifacts", { recursive: true }); await page.screenshot({ path: "target/playwright-artifacts/enforcement-dm8-before-restart.png", fullPage: true });

  await stop(); app = start(); await ready(app); await page.goto(`${baseUrl}/app/enforcement/evidence`);
  const [history, storedSync, storedRule, storedDiscovery, storedLead, parseV1, parseV2, storedEvidence] = await Promise.all([
    request(page, `/api/v0/enforcement/source-configurations/${sourceId}/history`), request(page, `/api/v0/enforcement/source-configurations/${sourceId}/sync-tasks/${sync.data.taskId}`), request(page, `/api/v0/enforcement/discovery-rules/${ruleId}/versions/1`), request(page, `/api/v0/enforcement/discovery-tasks/${discovery.data.taskId}`), request(page, `/api/v0/enforcement/leads/${leadId}`), request(page, `/api/v0/enforcement/leads/${leadId}/parses/1`), request(page, `/api/v0/enforcement/leads/${leadId}/parses/2`), request(page, `/api/v0/enforcement/evidence/${evidenceId}`)
  ]);
  assert(history.status === 200 && history.body.data.length >= 1, "Restart must retain source history"); assert(storedSync.status === 200 && storedSync.body.data.status === "succeeded", "Restart must retain sync task"); assert(storedRule.status === 200 && storedRule.body.data.status === "PUBLISHED", "Restart must retain published rule"); assert(storedDiscovery.status === 200 && storedDiscovery.body.data.leadIds.includes(leadId), "Restart must retain discovery task"); assert(storedLead.status === 200 && storedLead.body.data.lead.processingStatus === "VERIFYING" && storedLead.body.data.lead.version === 2, "Restart must retain lead state"); assert(parseV1.status === 200 && parseV1.body.data.version === 1, "Restart must retain parse V1"); assert(parseV2.status === 200 && parseV2.body.data.version === 2 && parseV2.body.data.reviewStatus === "CONFIRMED", "Restart must retain confirmed parse V2"); assert(storedEvidence.status === 200 && storedEvidence.body.data.status === "VERIFIED" && storedEvidence.body.data.recordVersion === 2, "Restart must retain reviewed evidence");
  await page.screenshot({ path: "target/playwright-artifacts/enforcement-dm8-after-restart.png", fullPage: true }); console.log(`Enforcement DM8 browser E2E passed: ${token}`);
} finally { if (browser) await browser.close(); await stop(); }
