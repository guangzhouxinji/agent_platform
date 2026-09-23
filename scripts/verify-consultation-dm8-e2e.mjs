import { spawn, spawnSync } from "node:child_process";
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
  run(maven, [`-Ddm8.browser.phase=${phase}`, "-Dtest=Dm8BrowserDatabaseProbe", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]);
}

function startApplication() {
  const logs = [];
  const child = spawn(java, ["-jar", "backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      CONSULTATION_STORAGE: "dm8",
      PATENT_SEARCH_STORAGE: "dm8",
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
      const response = await fetch(`${baseUrl}/`, { redirect: "manual" });
      if (response.status >= 200 && response.status < 400) return;
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
  if (process.env.CONSULTATION_E2E_SKIP_PACKAGE !== "true") run(maven, ["-DskipTests", "package"]);
  application = startApplication();
  await waitUntilReady(application);

  const request = async (path, options = {}) => {
    const response = await fetch(`${baseUrl}${path}`, options);
    if (response.status === 204) return { status: response.status, body: null };
    const rawBody = await response.text();
    if (!rawBody) return { status: response.status, body: null };
    try {
      return { status: response.status, body: JSON.parse(rawBody) };
    } catch (error) {
      throw new Error(`Expected JSON from ${options.method || "GET"} ${path}; HTTP ${response.status}; body=${rawBody || "<empty>"}`, {
        cause: error
      });
    }
  };
  const headers = { "X-V0-Requester": requester, "Content-Type": "application/json" };
  const first = (await request("/api/v0/consultations", {
    method: "POST", headers: { ...headers, "Idempotency-Key": "dm8-e2e-initial" },
    body: JSON.stringify({ question: "帮我查一下涂布工艺相关专利。", purpose: "" })
  })).body;
  assert(first.outcomeType === "NEEDS_CLARIFICATION", "Initial question must require clarification");

  const supplementKey = "dm8-e2e-supplement";
  const completed = (await request(`/api/v0/consultations/${first.consultationId}/inputs`, {
    method: "POST", headers: { ...headers, "Idempotency-Key": supplementKey },
    body: JSON.stringify({ content: "水性粘结剂、双区烘干；用于技术参考。" })
  })).body;
  assert((completed.outcomeType === "ANSWER_READY" || completed.outcomeType === "PARTIAL_RESULT") && completed.answerVersion === 2,
    `Supplement must create answer version 2: ${JSON.stringify(completed)}`);
  assert(completed.retrievalTaskId, "Supplement must reference a persisted patent search task");
  assert(completed.candidates.length > 0 && completed.sourceLinks.length === completed.candidates.length, "Answer must retain cited task candidates");
  const taskSnapshot = await request(`/api/v0/patent-search-tasks/${completed.retrievalTaskId}`, { headers: { "X-V0-Requester": requester } });
  assert(taskSnapshot.status === 200, "Referenced patent search task must be readable");
  assert(JSON.stringify(taskSnapshot.body.candidates.map(candidate => candidate.sourceId)) === JSON.stringify(completed.candidates.map(candidate => candidate.sourceId)),
    "Consultation candidate projection must preserve the task snapshot order and sources");
  await stopApplication();
  application = startApplication();
  await waitUntilReady(application);
  const readHeaders = { "X-V0-Requester": requester };
  const persisted = {
    latest: await request(`/api/v0/consultations/${first.consultationId}`, { headers: readHeaders }),
    firstAnswer: await request(`/api/v0/consultations/${first.consultationId}/answers/${first.answerId}`, { headers: readHeaders }),
    completedAnswer: await request(`/api/v0/consultations/${first.consultationId}/answers/${completed.answerId}`, { headers: readHeaders }),
    source: await request(`/api/v0/consultations/${first.consultationId}/source-links/${completed.sourceLinks[0].sourceLinkId}`, { headers: readHeaders }),
    replay: await request(`/api/v0/consultations/${first.consultationId}/inputs`, { method: "POST", headers: { ...headers, "Idempotency-Key": supplementKey }, body: JSON.stringify({ content: "水性粘结剂、双区烘干；用于技术参考。" }) }),
    conflict: await request(`/api/v0/consultations/${first.consultationId}/inputs`, { method: "POST", headers: { ...headers, "Idempotency-Key": supplementKey }, body: JSON.stringify({ content: "不同的补充内容" }) })
  };

  assert(persisted.latest.body?.answerId === completed.answerId, "Restarted application must return latest answer");
  assert(persisted.firstAnswer.body?.outcomeType === "NEEDS_CLARIFICATION", "Restarted application must retain first answer");
  assert(persisted.completedAnswer.body?.answerVersion === 2, "Restarted application must retain answer version 2");
  assert(persisted.completedAnswer.body?.retrievalTaskId === completed.retrievalTaskId, "Restarted application must retain the retrieval task reference");
  assert(persisted.source.body?.sourceId === completed.candidates[0].sourceId, "Restarted application must resolve the cited source location");
  assert(persisted.replay.status === 200 && persisted.replay.body.answerId === completed.answerId, "Idempotent replay must return the exact answer");
  assert(persisted.conflict.status === 409, "Changed content with the same idempotency key must conflict");
  await stopApplication();
  application = undefined;
  runProbe("verify");
  verified = true;
  console.log("DM8 HTTP E2E passed: clarification, supplement, task reference, restart reads, source resolution, idempotency, and record integrity.");
} finally {
  if (application) await stopApplication();
  if (!verified) {
    try { runProbe("cleanup"); }
    catch { }
  }
}
