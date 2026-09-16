import { chromium } from "@playwright/test";
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";

for (const name of ["DM8_JDBC_URL", "DM8_JDBC_USER", "DM8_JDBC_PASSWORD"]) {
  if (!process.env[name]) throw new Error(`${name} is required`);
}

const port = process.env.ENFORCEMENT_E2E_PORT || "18083";
const baseUrl = `http://127.0.0.1:${port}`;
const user = "v0-enforcement-user";
const reviewer = "v0-enforcement-reviewer";
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

function runProbe(phase, workItemId) {
  const args = [`-Denforcement.browser.phase=${phase}`];
  if (workItemId) args.push(`-Denforcement.browser.workId=${workItemId}`);
  args.push("-Dtest=Dm8EnforcementBrowserDatabaseProbe", "test");
  run(maven, args);
}

function startApplication() {
  const logs = [];
  const child = spawn(java, ["-jar", "target/agent-platform-0.0.1-SNAPSHOT.jar"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      ENFORCEMENT_STORAGE: "dm8",
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
    try {
      const response = await fetch(`${baseUrl}/index.html`);
      if (response.ok) return;
    } catch { }
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

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

try {
  runProbe("setup");
  run(maven, ["-DskipTests", "package"]);
  application = startApplication();
  await waitUntilReady(application);

  browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });
  await page.goto(baseUrl);
  await page.getByRole("button", { name: "执法验证" }).click();

  const openResponsePromise = page.waitForResponse(response => response.url().endsWith("/api/v0/enforcement/work-items") && response.request().method() === "POST");
  await page.getByRole("button", { name: "创建工作项" }).click();
  const openedResponse = await openResponsePromise;
  const openKey = (await openedResponse.request().allHeaders())["idempotency-key"];
  const opened = await openedResponse.json();
  const workItemId = opened.data.workItem.workItemId;

  const processResponsePromise = page.waitForResponse(response => response.url().endsWith(`/${workItemId}/process`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "生成辅助分析" }).click();
  const processedResponse = await processResponsePromise;
  const processKey = (await processedResponse.request().allHeaders())["idempotency-key"];
  const processed = await processedResponse.json();
  const clueV1 = processed.data.clueAnalyses[0];
  const auxiliaryV1 = processed.data.auxiliaryAnalyses[0];
  assert(processed.resultType === "AUXILIARY_ANALYSIS_READY", "Browser process must produce an auxiliary analysis");

  await page.getByRole("button", { name: "修订线索分析" }).click();
  await page.getByText("线索修订版本已保存").waitFor();
  await page.getByRole("button", { name: "确认辅助分析" }).click();
  await page.getByText("人工审核已留痕").waitFor();
  mkdirSync("target/playwright-artifacts", { recursive: true });
  await page.screenshot({ path: "target/playwright-artifacts/enforcement-dm8-before-restart.png", fullPage: true });

  await stopApplication();
  application = undefined;
  application = startApplication();
  await waitUntilReady(application);
  await page.goto(baseUrl);

  const persisted = await page.evaluate(async input => {
    const readHeaders = { "X-V0-Actor": input.user };
    const read = path => fetch(path, { headers: readHeaders }).then(async response => ({ status: response.status, body: await response.json() }));
    const base = `/api/v0/enforcement/work-items/${input.workItemId}`;
    const latest = await read(base);
    const oldClue = await read(`${base}/clue-analyses/${input.clueId}/versions/1`);
    const oldAnalysis = await read(`${base}/auxiliary-analyses/${input.analysisId}/versions/1`);
    const source = await read(`${base}/source-links/V0-SYN-EVD-001`);
    const replayResponse = await fetch(`${base}/process`, {
      method: "POST",
      headers: { ...readHeaders, "Content-Type": "application/json", "Idempotency-Key": input.processKey },
      body: JSON.stringify({ evidenceIds: ["V0-SYN-EVD-001", "V0-SYN-EVD-002"] })
    });
    const replay = await replayResponse.json();
    const conflictResponse = await fetch(`${base}/process`, {
      method: "POST",
      headers: { ...readHeaders, "Content-Type": "application/json", "Idempotency-Key": input.processKey },
      body: JSON.stringify({ evidenceIds: ["V0-SYN-EVD-001"] })
    });
    const openReplayResponse = await fetch("/api/v0/enforcement/work-items", {
      method: "POST",
      headers: { ...readHeaders, "X-V0-Organization": "org-v0", "Content-Type": "application/json", "Idempotency-Key": input.openKey },
      body: JSON.stringify({ clueId: "V0-SYN-CLUE-001", clueVersion: "v1" })
    });
    const openReplay = await openReplayResponse.json();
    return { latest, oldClue, oldAnalysis, source, replayStatus: replayResponse.status, replay, conflictStatus: conflictResponse.status, openReplayStatus: openReplayResponse.status, openReplay };
  }, { workItemId, clueId: clueV1.analysisId, analysisId: auxiliaryV1.analysisResultId, processKey, openKey, user });

  assert(persisted.latest.status === 200 && persisted.latest.body.data.clueAnalyses.length === 2, "Restart must restore both clue versions");
  assert(persisted.latest.body.data.reviews.length === 1 && persisted.latest.body.data.reviews[0].notOfficialDecision, "Restart must restore non-official review");
  assert(persisted.oldClue.status === 200 && persisted.oldClue.body.data.version === 1, "Restart must restore exact clue version 1");
  assert(persisted.oldAnalysis.status === 200 && persisted.oldAnalysis.body.data.version === 1, "Restart must restore exact analysis version 1");
  assert(persisted.source.status === 200 && persisted.source.body.data.sourceId === "V0-SYN-EVD-001", "Restart must reauthorize source metadata");
  assert(persisted.replayStatus === 200 && persisted.replay.data.clueAnalyses.length === 1, "Process idempotency replay must return its historical snapshot");
  assert(persisted.conflictStatus === 409, "Changed process input with the same key must conflict after restart");
  assert(persisted.openReplayStatus === 200 && persisted.openReplay.data.workItem.workItemId === workItemId, "Open idempotency replay must retain the work id after restart");

  await page.getByRole("button", { name: "执法验证" }).click();
  await page.screenshot({ path: "target/playwright-artifacts/enforcement-dm8-after-restart.png", fullPage: true });
  await browser.close();
  browser = undefined;
  await stopApplication();
  application = undefined;
  runProbe("verify", workItemId);
  verified = true;
  console.log("Enforcement DM8 browser E2E passed: browser write, HTTP/service/DM8, process restart, exact versions, source authorization, idempotency, review, and database integrity.");
} finally {
  if (browser) await browser.close();
  if (application) await stopApplication();
  if (!verified) {
    try { runProbe("cleanup"); }
    catch { }
  }
}
