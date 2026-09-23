import { chromium } from "@playwright/test";
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";

for (const name of ["DM8_JDBC_URL", "DM8_JDBC_USER", "DM8_JDBC_PASSWORD"]) {
  if (!process.env[name]) throw new Error(`${name} is required`);
}

const port = process.env.ADJUDICATION_E2E_PORT || "18086";
const baseUrl = `http://127.0.0.1:${port}`;
const user = "v0-adjudication-user";
const maven = process.platform === "win32" ? "mvn.cmd" : "mvn";
const java = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java")
  : "java";
let application;
let browser;
let verified = false;

function run(command, args) {
  const executable = process.platform === "win32" ? (process.env.ComSpec || "cmd.exe") : command;
  const commandArgs = process.platform === "win32" ? ["/d", "/s", "/c", command, ...args] : args;
  const result = spawnSync(executable, commandArgs, { cwd: process.cwd(), env: process.env, stdio: "inherit" });
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}: ${result.error?.message || "unknown error"}`);
}

function probe(phase, workItemId) {
  const args = [`-Dadjudication.browser.phase=${phase}`];
  if (workItemId) args.push(`-Dadjudication.browser.workId=${workItemId}`);
  args.push("-Dtest=Dm8AdjudicationBrowserDatabaseProbe", "test");
  run(maven, args);
}

function startApplication() {
  const logs = [];
  const child = spawn(java, ["-jar", "backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      ADJUDICATION_STORAGE: "dm8",
      ENFORCEMENT_STORAGE: "memory",
      CONSULTATION_STORAGE: "memory",
      SERVER_PORT: port,
      SPRING_DATASOURCE_URL: process.env.DM8_JDBC_URL,
      SPRING_DATASOURCE_USERNAME: process.env.DM8_JDBC_USER,
      SPRING_DATASOURCE_PASSWORD: process.env.DM8_JDBC_PASSWORD,
      SPRING_DATASOURCE_DRIVER_CLASS_NAME: "dm.jdbc.driver.DmDriver"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  child.stdout.on("data", chunk => logs.push(chunk.toString()));
  child.stderr.on("data", chunk => logs.push(chunk.toString()));
  child.logs = logs;
  return child;
}

async function waitUntilReady(child) {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`Application stopped during startup\n${child.logs.join("")}`);
    try { const response = await fetch(`${baseUrl}/index.html`); if (response.ok) return; } catch { }
    await new Promise(resolve => setTimeout(resolve, 300));
  }
  throw new Error(`Application did not become ready\n${child.logs.join("")}`);
}

async function stopApplication() {
  if (!application || application.exitCode !== null) return;
  application.kill();
  await Promise.race([
    new Promise(resolve => application.once("exit", resolve)),
    new Promise((_, reject) => setTimeout(() => reject(new Error("Application did not stop")), 10000))
  ]);
}

function assert(condition, message) { if (!condition) throw new Error(message); }

try {
  probe("setup");
  run(maven, ["-DskipTests", "package"]);
  application = startApplication();
  await waitUntilReady(application);
  browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });
  await page.goto(baseUrl);
  await page.getByRole("button", { name: "裁决验证" }).click();
  const workspace = page.locator("#adjudication-workspace");

  const openResponsePromise = page.waitForResponse(response => response.url().endsWith("/api/v0/adjudication/work-items") && response.request().method() === "POST");
  await workspace.getByRole("button", { name: "创建裁决工作项" }).click();
  const openResponse = await openResponsePromise;
  const openKey = (await openResponse.request().allHeaders())["idempotency-key"];
  const openBody = openResponse.request().postDataJSON();
  const opened = await openResponse.json();
  const workItemId = opened.data.work.workItemId;

  await workspace.getByRole("button", { name: "检查材料" }).click();
  await workspace.getByText("材料检查已完成").waitFor();
  await workspace.getByRole("button", { name: "生成法规候选" }).click();
  await workspace.getByText("法规候选已生成").waitFor();
  await workspace.getByRole("button", { name: "选择法规引用" }).click();
  await workspace.getByText("法规引用已留痕").waitFor();

  const draftResponsePromise = page.waitForResponse(response => response.url().endsWith(`/${workItemId}/drafts`) && response.request().method() === "POST");
  await workspace.getByRole("button", { name: "生成合成草稿" }).click();
  const draftResponse = await draftResponsePromise;
  const draftKey = (await draftResponse.request().allHeaders())["idempotency-key"];
  const draftBody = draftResponse.request().postDataJSON();
  const drafted = await draftResponse.json();
  const draftV1 = drafted.data.drafts[0];

  await workspace.getByLabel("草稿修订内容").fill("重启恢复验证的人工合成修订");
  await workspace.getByRole("button", { name: "修订草稿" }).click();
  await workspace.getByText("草稿修订版本已保存").waitFor();
  await workspace.getByRole("button", { name: "确认测试复核" }).click();
  await workspace.getByText("测试复核已留痕").waitFor();
  const latestBeforeRestart = await (await fetch(`${baseUrl}/api/v0/adjudication/work-items/${workItemId}`, { headers: { "X-V0-Actor": user } })).json();
  const draftV2 = latestBeforeRestart.data.drafts[1];
  const citation = latestBeforeRestart.data.citations[0];
  const review = latestBeforeRestart.data.reviews[0];
  mkdirSync("target/playwright-artifacts", { recursive: true });
  await page.screenshot({ path: "target/playwright-artifacts/adjudication-dm8-before-restart.png", fullPage: true });

  await stopApplication(); application = undefined;
  application = startApplication(); await waitUntilReady(application);
  await page.goto(baseUrl);

  const recovered = await page.evaluate(async input => {
    const headers = { "X-V0-Actor": input.user };
    const read = path => fetch(path, { headers }).then(async response => ({ status: response.status, body: await response.json() }));
    const base = `/api/v0/adjudication/work-items/${input.workItemId}`;
    const latest = await read(base);
    const draftV1 = await read(`${base}/drafts/${input.draftV1Id}/versions/1`);
    const draftV2 = await read(`${base}/drafts/${input.draftV2Id}/versions/2`);
    const citation = await read(`${base}/citations/${input.citationId}/versions/1`);
    const review = await read(`${base}/test-reviews/${input.reviewId}/versions/1`);
    const replayDraftResponse = await fetch(`${base}/drafts`, {
      method: "POST", headers: { ...headers, "Content-Type": "application/json", "Idempotency-Key": input.draftKey },
      body: JSON.stringify(input.draftBody)
    });
    const replayDraft = await replayDraftResponse.json();
    const conflictResponse = await fetch(`${base}/drafts`, {
      method: "POST", headers: { ...headers, "Content-Type": "application/json", "Idempotency-Key": input.draftKey },
      body: JSON.stringify({ inspectionVersion: 2 })
    });
    const openReplayResponse = await fetch("/api/v0/adjudication/work-items", {
      method: "POST", headers: { ...headers, "X-V0-Organization": "org-v0", "Content-Type": "application/json", "Idempotency-Key": input.openKey },
      body: JSON.stringify(input.openBody)
    });
    const openReplay = await openReplayResponse.json();
    const officialResponse = await fetch(`${base}/official-decisions`, { method: "POST", headers: { ...headers, "Content-Type": "application/json" }, body: "{}" });
    return { latest, draftV1, draftV2, citation, review, replayDraftStatus: replayDraftResponse.status, replayDraft,
      conflictStatus: conflictResponse.status, openReplayStatus: openReplayResponse.status, openReplay, officialStatus: officialResponse.status };
  }, { workItemId, user, draftV1Id: draftV1.draftId, draftV2Id: draftV2.draftId, citationId: citation.citationId,
    reviewId: review.reviewId, draftKey, draftBody, openKey, openBody });

  assert(recovered.latest.status === 200 && recovered.latest.body.data.drafts.length === 2, "Restart must restore both draft versions");
  assert(recovered.latest.body.data.reviews.length === 1 && recovered.latest.body.data.reviews[0].notOfficialDecision, "Restart must restore non-official test review");
  assert(recovered.draftV1.status === 200 && recovered.draftV1.body.data.version === 1, "Restart must restore exact draft version 1");
  assert(recovered.draftV2.status === 200 && recovered.draftV2.body.data.sections[0].content === "重启恢复验证的人工合成修订", "Restart must restore revised draft content");
  assert(recovered.citation.status === 200 && recovered.citation.body.data.action === "SELECT", "Restart must restore exact citation");
  assert(recovered.review.status === 200 && recovered.review.body.data.action === "CONFIRM", "Restart must restore exact test review");
  assert(recovered.replayDraftStatus === 200 && recovered.replayDraft.data.drafts.length === 1, "Draft replay must return the original historical snapshot");
  assert(recovered.conflictStatus === 409, "Changed draft input with the same key must conflict after restart");
  assert(recovered.openReplayStatus === 200 && recovered.openReplay.data.work.workItemId === workItemId, "Open replay must retain work identity after restart");
  assert(recovered.officialStatus === 404, "Formal decision route must not exist");

  await page.getByRole("button", { name: "裁决验证" }).click();
  await page.screenshot({ path: "target/playwright-artifacts/adjudication-dm8-after-restart.png", fullPage: true });
  await browser.close(); browser = undefined;
  await stopApplication(); application = undefined;
  probe("verify", workItemId);
  verified = true;
  console.log("Adjudication DM8 browser E2E passed: browser write, HTTP/service/DM8, restart, exact versions, citation, non-official review, idempotency, forbidden formal route, and database integrity.");
} finally {
  if (browser) await browser.close();
  if (application) await stopApplication();
  if (!verified) { try { probe("cleanup"); } catch { } }
}
