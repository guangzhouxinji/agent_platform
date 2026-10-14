import path from "node:path";
import fs from "node:fs";
import http from "node:http";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const docsDir = path.resolve(process.cwd(), "docs");
const htmlPath = path.join(docsDir, "project-progress.html");
const jsonPath = path.join(docsDir, "project-plan.json");
const plan = fs.readFileSync(path.join(docsDir, "08-总开发实现计划.md"), "utf8");
const currentTaskId = plan.match(/^当前(?:唯一主|优先)任务：\s*`([^`\s]+)[^`]*`/m)?.[1];
if (!currentTaskId) throw new Error("Master plan has no current primary task");
const requirementSection = plan.slice(plan.indexOf("## 8."), plan.indexOf("## 9."));
const requirementRows = requirementSection.split(/\r?\n/)
  .filter((line) => /^\|\s*(?:CON|ENF|ADJ)-\d{2}\s*\|/.test(line))
  .map((line) => line.trim().slice(1, -1).split("|").map((cell) => cell.trim()));
const hasEvidence = (value) => Boolean(value && value !== "-" && !/^(未执行|未验收|未开始|待确认)$/.test(value));
const expectedVerified = requirementRows.filter((row) => hasEvidence(row[6])).length;
const expectedAccepted = requirementRows.filter((row) => hasEvidence(row[8]) && !/^未验收/.test(row[8])).length;
const html = fs.readFileSync(htmlPath, "utf8");
if (/const DATA=\s*\{/.test(html)) throw new Error("Dashboard still embeds project data");
if (!html.includes('fetch("./project-plan.json"')) throw new Error("Dashboard does not load project-plan.json");
const projectPlan = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
if (projectPlan.currentTaskId !== currentTaskId) throw new Error("JSON current task differs from master plan");
if (requirementRows.length !== 41 || projectPlan.derived.requirementMaturity?.total !== requirementRows.length) throw new Error("Formal requirement total does not come from master plan section 8");
if (projectPlan.derived.requirementMaturity.verified !== expectedVerified || projectPlan.derived.requirementMaturity.accepted !== expectedAccepted) throw new Error("Formal requirement maturity differs from master plan section 8");
if (!html.includes("合成 V0 ≠ 正式需求完成") || !html.includes("技术执行任务进度（次要指标）")) throw new Error("Dashboard does not expose the formal-versus-technical progress distinction");
if (!html.includes("已经做了什么") || !html.includes("为什么不能继续") || !html.includes("查看完整执行详情、任务树和技术证据")) throw new Error("Dashboard does not provide the required conclusion-first summary");
if (!projectPlan.derived?.criticalPath?.length) throw new Error("Critical path is empty");
for (const id of projectPlan.derived.readyTaskIds ?? []) {
  const task = projectPlan.tasks.find((value) => value.id === id);
  if (!task || task.executionState !== "READY" || task.status === "阻塞" || !task.stageGate?.satisfied) throw new Error(`Invalid READY task ${id}`);
}
const plannedBlockedCount = projectPlan.tasks.filter((task) => task.status === "阻塞").length;
const derivedBlockedCount = projectPlan.tasks.filter((task) => task.executionState === "BLOCKED").length;
const waitingCount = projectPlan.tasks.filter((task) => task.executionState === "WAITING").length;
if (plannedBlockedCount !== derivedBlockedCount) throw new Error("Dashboard data derives BLOCKED from fields other than explicit plan status");
const p3Gate = projectPlan.derived.stageGates.find((gate) => gate.stageId === "P3");
const p2Exit = projectPlan.derived.stageGates.find((gate) => gate.stageId === "P2")?.exit;
if (!p3Gate?.entry || p3Gate.entry.satisfied !== p2Exit?.satisfied) throw new Error("Dashboard stage Gate is not based on predecessor exit evidence");
if (!(projectPlan.derived.criticalPathScore > 0) || !projectPlan.derived.criticalPathStates?.length) throw new Error("Dashboard lacks weighted/status-classified critical path data");

const server = http.createServer((request, response) => {
  const name = decodeURIComponent(new URL(request.url, "http://127.0.0.1").pathname).replace(/^\/+/, "") || "project-progress.html";
  if (!["project-progress.html", "project-plan.json"].includes(name)) { response.writeHead(404).end(); return; }
  const file = path.join(docsDir, name);
  response.writeHead(200, { "Content-Type": name.endsWith(".json") ? "application/json; charset=utf-8" : "text/html; charset=utf-8", "Cache-Control": "no-store" });
  fs.createReadStream(file).pipe(response);
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const address = server.address();
const dashboardUrl = `http://127.0.0.1:${address.port}/project-progress.html`;
const browser = await chromium.launch({ headless: true });

try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    const page = await browser.newPage({ viewport });
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.goto(dashboardUrl);
    await page.locator("h1").waitFor();
    await page.locator("#current-task h3").filter({ hasText: `${currentTaskId} ·` }).waitFor();
    await page.locator("#formal-progress-caption").filter({ hasText: `${expectedVerified} / ${requirementRows.length} 项正式需求` }).waitFor();
    await page.locator("#real-integration-count").filter({ hasText: `${projectPlan.derived.requirementMaturity.realIntegrated}` }).waitFor();
    await page.locator("#formal-accepted-count").filter({ hasText: `${expectedAccepted} / ${requirementRows.length}` }).waitFor();
    await page.locator("#formal-pilot").filter({ hasText: projectPlan.derived.requirementMaturity.pilotRequirementId || "未指定" }).waitFor();
    await page.locator("#truth-note").getByText("合成 V0 ≠ 正式需求完成").waitFor();
    await page.locator("#local-proof").getByText("MVP-CON-V0-05").waitFor();
    await page.locator("#current-blocker").waitFor();
    await page.screenshot({path:`docs/evidence/consultation-product-bundle-20261006/dashboard-wrap/final-${viewport.width}.png`});
    // Task IDs, table names and source digests are unbounded tokens, not prose to clip.
    const longToken = "SOURCE_SHA256_" + "a".repeat(256);
    await page.locator("#current-blocker").evaluate((element, token) => {
      const probe = document.createElement("p");
      probe.dataset.wrapProbe = "true";
      probe.textContent = token;
      element.append(probe);
    }, longToken);
    const tokenProof = await page.locator('[data-wrap-probe="true"]').evaluate(element => ({
      text: element.textContent, scroll: element.scrollWidth, client: element.clientWidth,
      wrap: getComputedStyle(element).overflowWrap
    }));
    if (tokenProof.text !== longToken || tokenProof.scroll > tokenProof.client + 1 || tokenProof.wrap !== "anywhere") throw new Error(`Long source token clipped or overflows at ${viewport.width}px`);
    await page.locator('[data-wrap-probe="true"]').evaluate(element => element.remove());

    if (await page.locator("details.advanced").evaluate((element) => element.open)) throw new Error("Detailed dashboard sections should be collapsed initially");
    await page.locator("details.advanced").evaluate((element) => { element.open = true; });
    await page.locator(`#dependency-chain [data-task="${currentTaskId}"]`).waitFor();
    await page.locator("#health-summary .health-score").waitFor();
    await page.locator("#critical-path .critical-node").first().waitFor();
    await page.locator("#waiting-count").filter({ hasText: `${waitingCount} 项` }).waitFor();
    await page.locator("#blocked-list-count").filter({ hasText: `${plannedBlockedCount} 项` }).waitFor();
    if ((await page.locator("#blocked-list").textContent())?.includes("MVP-ENF-V0-04")) throw new Error("WAITING task leaked into BLOCKED list");
    if (await page.locator(".task-row").count() < 70) throw new Error("Task tree is incomplete");
    const currentTaskRow = page.locator(`[data-task="${currentTaskId}"]`).last();
    const parentTree = currentTaskRow.locator("xpath=ancestor::details[1]");
    if (await parentTree.count()) await parentTree.evaluate((element) => { element.open = true; });
    await currentTaskRow.click();
    await page.locator("#task-detail").getByText(currentTaskId).waitFor();
    await page.locator("#status-filter").selectOption({ label: "阻塞" });
    if (await page.locator('.task-row:not([hidden])').count() < 1) throw new Error("Status filter returned no blocked tasks");
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
    if (overflow) throw new Error(`Horizontal page overflow at ${viewport.width}px`);
    if (errors.length) throw new Error(`Browser errors: ${errors.join("; ")}`);
    await page.close();
  }
  console.log("Project progress dashboard passed desktop/mobile structure, interaction, and overflow checks.");
} finally {
  await browser.close();
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}
