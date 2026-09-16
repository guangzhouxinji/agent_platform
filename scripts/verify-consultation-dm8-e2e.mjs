import { chromium } from "@playwright/test";
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";

const required = ["DM8_JDBC_URL", "DM8_JDBC_USER", "DM8_JDBC_PASSWORD"];
for (const name of required) {
  if (!process.env[name]) throw new Error(`${name} is required`);
}

const port = process.env.CONSULTATION_E2E_PORT || "18082";
const baseUrl = `http://127.0.0.1:${port}`;
const requester = "v0-authorized-user";
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
  const result = spawnSync(executable, commandArgs, {
    cwd: process.cwd(),
    env: process.env,
    stdio: "inherit"
  });
  if (result.status !== 0) throw new Error(`${command} exited with ${result.status}: ${result.error?.message || "unknown error"}`);
}

function runProbe(phase) {
  run(maven, [`-Ddm8.browser.phase=${phase}`, "-Dtest=Dm8BrowserDatabaseProbe", "test"]);
}

function startApplication() {
  const logs = [];
  const child = spawn(java, ["-jar", "target/agent-platform-0.0.1-SNAPSHOT.jar"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      CONSULTATION_STORAGE: "dm8",
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
  await page.getByLabel("咨询问题").fill("帮我查一下涂布工艺相关专利。");
  const firstResponsePromise = page.waitForResponse(response => response.url().endsWith("/api/v0/consultations") && response.request().method() === "POST");
  await page.getByRole("button", { name: "开始检索" }).click();
  const firstResponse = await firstResponsePromise;
  const first = await firstResponse.json();
  assert(first.outcomeType === "NEEDS_CLARIFICATION", "Initial question must require clarification");

  await page.getByLabel("补充所需信息").fill("水性粘结剂、双区烘干；用于技术参考。");
  const supplementResponsePromise = page.waitForResponse(response => response.url().includes(`/api/v0/consultations/${first.consultationId}/inputs`) && response.request().method() === "POST");
  await page.getByRole("button", { name: "提交补充" }).click();
  const supplementResponse = await supplementResponsePromise;
  const supplementKey = (await supplementResponse.request().allHeaders())["idempotency-key"];
  const completed = await supplementResponse.json();
  assert(completed.outcomeType === "ANSWER_READY" && completed.answerVersion === 2, "Supplement must create answer version 2");
  assert(completed.candidates.length === 2 && completed.sourceLinks.length === 2, "Answer must retain two cited candidates");
  mkdirSync("target/playwright-artifacts", { recursive: true });
  await page.screenshot({ path: "target/playwright-artifacts/consultation-dm8-e2e.png", fullPage: true });

  await stopApplication();
  application = startApplication();
  await waitUntilReady(application);
  await page.goto(baseUrl);

  const persisted = await page.evaluate(async ({ consultationId, firstAnswerId, completedAnswerId, sourceLinkId, requesterRef, supplementIdempotencyKey }) => {
    const headers = { "X-V0-Requester": requesterRef };
    const read = path => fetch(path, { headers }).then(async response => ({ status: response.status, body: response.ok ? await response.json() : null }));
    const latest = await read(`/api/v0/consultations/${consultationId}`);
    const firstAnswer = await read(`/api/v0/consultations/${consultationId}/answers/${firstAnswerId}`);
    const completedAnswer = await read(`/api/v0/consultations/${consultationId}/answers/${completedAnswerId}`);
    const source = await read(`/api/v0/consultations/${consultationId}/source-links/${sourceLinkId}`);
    const replayResponse = await fetch(`/api/v0/consultations/${consultationId}/inputs`, {
      method: "POST",
      headers: { ...headers, "Content-Type": "application/json", "Idempotency-Key": supplementIdempotencyKey },
      body: JSON.stringify({ content: "水性粘结剂、双区烘干；用于技术参考。" })
    });
    const replay = await replayResponse.json();
    const conflict = await fetch(`/api/v0/consultations/${consultationId}/inputs`, {
      method: "POST",
      headers: { ...headers, "Content-Type": "application/json", "Idempotency-Key": supplementIdempotencyKey },
      body: JSON.stringify({ content: "不同的补充内容" })
    });
    return { latest, firstAnswer, completedAnswer, source, replayStatus: replayResponse.status, replay, conflictStatus: conflict.status };
  }, {
    consultationId: first.consultationId,
    firstAnswerId: first.answerId,
    completedAnswerId: completed.answerId,
    sourceLinkId: completed.sourceLinks[0].sourceLinkId,
    requesterRef: requester,
    supplementIdempotencyKey: supplementKey
  });

  assert(persisted.latest.body?.answerId === completed.answerId, "Restarted application must return latest answer");
  assert(persisted.firstAnswer.body?.outcomeType === "NEEDS_CLARIFICATION", "Restarted application must retain first answer");
  assert(persisted.completedAnswer.body?.answerVersion === 2, "Restarted application must retain answer version 2");
  assert(persisted.source.body?.sourceId === "V0-SYN-PAT-001", "Restarted application must resolve source location");
  assert(persisted.replayStatus === 200 && persisted.replay.answerId === completed.answerId, "Idempotent replay must return the exact answer");
  assert(persisted.conflictStatus === 409, "Changed content with the same idempotency key must conflict");

  await browser.close();
  browser = undefined;
  await stopApplication();
  application = undefined;
  runProbe("verify");
  verified = true;
  console.log("DM8 browser E2E passed: clarification, supplement, restart reads, source resolution, idempotency, and record integrity.");
} finally {
  if (browser) await browser.close();
  if (application) await stopApplication();
  if (!verified) {
    try { runProbe("cleanup"); }
    catch { }
  }
}
