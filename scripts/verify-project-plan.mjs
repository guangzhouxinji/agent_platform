import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(scriptDir, "..");
const planPath = path.join(rootDir, "docs", "08-总开发实现计划.md");
const jsonPath = path.join(rootDir, "docs", "project-plan.json");
const htmlPath = path.join(rootDir, "docs", "project-progress.html");

for (const file of [planPath, jsonPath, htmlPath]) if (!fs.existsSync(file)) throw new Error(`Missing project control file: ${path.relative(rootDir, file)}`);
const markdown = fs.readFileSync(planPath, "utf8");
const html = fs.readFileSync(htmlPath, "utf8");
const plan = JSON.parse(fs.readFileSync(jsonPath, "utf8"));
const currentTaskId = markdown.match(/^当前(?:唯一主|优先)任务：\s*`([^`\s]+)[^`]*`/m)?.[1];
const planVersion = markdown.match(/^计划版本：([^\s]+)/m)?.[1];
const clean = (value) => String(value ?? "").replace(/\*\*/g, "").replace(/`/g, "").replace(/\[([^\]]+)\]\([^\)]+\)/g, "$1");
const requirementSection = markdown.slice(markdown.indexOf("## 8."), markdown.indexOf("## 9."));
const ledgerRequirements = requirementSection.split(/\r?\n/)
  .filter((line) => /^\|\s*(?:CON|ENF|ADJ)-\d{2}\s*\|/.test(line))
  .map((line) => line.trim().slice(1, -1).split("|").map((cell) => clean(cell.trim())))
  .map(([id, source, requirementGate, design, apiData, code, verification, drift, acceptance]) => ({ id, source, requirementGate, design, apiData, code, verification, drift, acceptance }));
const hasRequirementEvidence = (value) => Boolean(value && value !== "-" && !/^(未执行|未验收|未开始|待确认)$/.test(value));
const realIntegrated = (item) => hasRequirementEvidence(item.apiData) && hasRequirementEvidence(item.code)
  && /真实(?:中心)?(?:数据|API|索引|接入)|中心(?:数据|API|索引|接入)/.test(`${item.apiData} ${item.code} ${item.verification}`)
  && !/未接入|待接入|合成|模拟/.test(`${item.apiData} ${item.code} ${item.verification}`);
const expectedMaturity = {
  total: ledgerRequirements.length,
  requirementReady: ledgerRequirements.filter((item) => hasRequirementEvidence(item.requirementGate)).length,
  designed: ledgerRequirements.filter((item) => hasRequirementEvidence(item.design)).length,
  apiDataTraced: ledgerRequirements.filter((item) => hasRequirementEvidence(item.apiData)).length,
  codeTraced: ledgerRequirements.filter((item) => hasRequirementEvidence(item.code)).length,
  verified: ledgerRequirements.filter((item) => hasRequirementEvidence(item.verification)).length,
  driftChecked: ledgerRequirements.filter((item) => hasRequirementEvidence(item.drift)).length,
  realIntegrated: ledgerRequirements.filter(realIntegrated).length,
  accepted: ledgerRequirements.filter((item) => hasRequirementEvidence(item.acceptance) && !/^未验收/.test(item.acceptance)).length
};

if (plan.sourceOfTruth !== "docs/08-总开发实现计划.md") throw new Error("JSON does not identify the governance source of truth");
if (plan.currentTaskId !== currentTaskId) throw new Error("Current task drift between Markdown and JSON");
if (plan.planVersion !== planVersion) throw new Error("Plan version drift between Markdown and JSON");
if (/const DATA=\s*\{/.test(html)) throw new Error("HTML contains an embedded project plan");
if (!html.includes('fetch("./project-plan.json"')) throw new Error("HTML does not load project-plan.json");
if (ledgerRequirements.length !== 41) throw new Error(`Master plan requirement ledger has ${ledgerRequirements.length} rows instead of 41`);
if (JSON.stringify(plan.requirements?.map((item) => item.id)) !== JSON.stringify(ledgerRequirements.map((item) => item.id))) throw new Error("JSON requirement ledger differs from master plan section 8");
for (const [key, value] of Object.entries(expectedMaturity)) {
  if (plan.derived.requirementMaturity?.[key] !== value) throw new Error(`Requirement maturity ${key} drift: expected ${value}, got ${plan.derived.requirementMaturity?.[key]}`);
}
if (!html.includes("合成 V0 ≠ 正式需求完成") || !html.includes("技术执行任务进度（次要指标）")) throw new Error("Dashboard does not distinguish formal requirement progress from technical task progress");

const taskMap = new Map(plan.tasks.map((task) => [task.id, task]));
for (const id of plan.derived.readyTaskIds) {
  const task = taskMap.get(id);
  if (!task) throw new Error(`READY references missing task ${id}`);
  if (task.executionState !== "READY" || task.status === "阻塞") throw new Error(`READY/BLOCKED conflict for ${id}`);
  if (!task.stageGate?.satisfied) throw new Error(`READY task ${id} has an unmet stage gate`);
  const unfinished = task.predecessorIds.filter((dependency) => !["已完成", "已验证", "已验收", "跳过"].includes(taskMap.get(dependency)?.status));
  if (unfinished.length) throw new Error(`READY task ${id} has unfinished predecessors: ${unfinished.join(", ")}`);
}
const plannedBlocked = plan.tasks.filter((task) => task.status === "阻塞").map((task) => task.id).sort();
const derivedBlocked = plan.tasks.filter((task) => task.executionState === "BLOCKED").map((task) => task.id).sort();
if (JSON.stringify(plannedBlocked) !== JSON.stringify(derivedBlocked)) throw new Error("BLOCKED execution state differs from explicit plan status");
if (JSON.stringify(derivedBlocked) !== JSON.stringify([...(plan.derived.blockedTaskIds ?? [])].sort())) throw new Error("blockedTaskIds differs from task execution states");
const derivedWaiting = plan.tasks.filter((task) => task.executionState === "WAITING").map((task) => task.id).sort();
if (JSON.stringify(derivedWaiting) !== JSON.stringify([...(plan.derived.waitingTaskIds ?? [])].sort())) throw new Error("waitingTaskIds differs from task execution states");
for (const task of plan.tasks.filter((value) => ["未开始", "准备中", "就绪"].includes(value.status))) {
  if (!task.stageGate) throw new Error(`Task ${task.id} has no stage gate result`);
  if (task.executionState === "BLOCKED") throw new Error(`Non-blocked task ${task.id} was derived as BLOCKED`);
}
if (!plan.derived.criticalPath.length && plan.tasks.some((task) => task.executionState !== "DONE")) throw new Error("Critical path missing for incomplete plan");
for (const gate of plan.derived.stageGates ?? []) {
  if (!gate.entry || !gate.exit || !Array.isArray(gate.exit.evidence)) throw new Error(`Stage ${gate.stageId} lacks entry/exit evidence`);
}
const p3Gate = plan.derived.stageGates.find((gate) => gate.stageId === "P3");
const p2Exit = plan.derived.stageGates.find((gate) => gate.stageId === "P2")?.exit;
if (p3Gate?.entry.satisfied !== p2Exit?.satisfied) throw new Error("P3 entry gate is not derived from P2 exit evidence");
for (const task of plan.tasks) {
  if (!task.estimate || task.estimate.durationPoints < 1 || task.estimate.riskWeight < 1) throw new Error(`Task ${task.id} lacks duration/risk estimate`);
}
const criticalIds = new Set(plan.derived.criticalPath);
const classifiedIds = new Set((plan.derived.criticalPathStates ?? []).flatMap((group) => group.taskIds));
if (criticalIds.size !== classifiedIds.size || [...criticalIds].some((id) => !classifiedIds.has(id))) throw new Error("Critical path state classification is incomplete");
if (!(plan.derived.criticalPathScore > 0)) throw new Error("Weighted critical path score is missing");
if (plan.derived.health.errorCount) {
  const details = plan.derived.health.issues.filter((issue) => issue.severity === "error").map((issue) => `${issue.code}:${issue.taskId ?? "project"}`).join(", ");
  throw new Error(`Project plan health errors: ${details}`);
}

console.log(`Project plan verification passed: ${plan.tasks.length} tasks, ${plan.derived.readyTaskIds.length} READY, health ${plan.derived.health.score}/100 with ${plan.derived.health.warningCount} warnings.`);
