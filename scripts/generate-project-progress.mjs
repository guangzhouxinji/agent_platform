import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(scriptDir, "..");
const planPath = path.join(rootDir, "docs", "08-总开发实现计划.md");
const outputPath = path.join(rootDir, "docs", "project-progress.html");
const dataPath = path.join(rootDir, "docs", "project-plan.json");
const markdown = fs.readFileSync(planPath, "utf8");
const currentTaskMatch = markdown.match(/^当前唯一主任务：\s*`([^`\s]+)[^`]*`/m);
if (!currentTaskMatch) throw new Error("Missing current primary task in master plan");
const currentTaskId = currentTaskMatch[1];

function sectionBetween(start, end) {
  const startAt = markdown.indexOf(start);
  if (startAt < 0) throw new Error(`Missing plan section: ${start}`);
  const endAt = markdown.indexOf(end, startAt + start.length);
  return markdown.slice(startAt, endAt < 0 ? markdown.length : endAt);
}

function parseTable(section) {
  return section
    .split(/\r?\n/)
    .filter((line) => /^\|.*\|$/.test(line.trim()))
    .map((line) => line.trim().slice(1, -1).split("|").map((cell) => cell.trim()))
    .filter((cells) => !cells.every((cell) => /^-+:?$/.test(cell)));
}

function rowsAsObjects(rows) {
  const [headers, ...values] = rows;
  return values.map((row) => Object.fromEntries(headers.map((header, index) => [header, row[index] ?? ""])));
}

function clean(value) {
  return String(value ?? "")
    .replace(/\*\*/g, "")
    .replace(/`/g, "")
    .replace(/\[([^\]]+)\]\([^\)]+\)/g, "$1");
}

function existing(relativePath) {
  return fs.existsSync(path.join(rootDir, relativePath));
}

function knownRefs(text, allTasks) {
  const value = String(text ?? "");
  return allTasks.filter((candidate) => {
    const escaped = candidate.id.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return new RegExp(`(^|[^A-Z0-9-])${escaped}(?![A-Z0-9-])`).test(value);
  }).map((candidate) => candidate.id);
}

function taskDomain(task) {
  if (task.id.includes("-CON")) return "consultation";
  if (task.id.includes("-ENF")) return "enforcement";
  if (task.id.includes("-ADJ")) return "adjudication";
  if (task.id.startsWith("P0-") || task.id.startsWith("PLAN-") || task.id.startsWith("GOV-")) return "governance";
  if (task.id.startsWith("REL-") || task.id.startsWith("ACCEPT-")) return "release";
  if (task.id.startsWith("ENG-")) return "platform";
  if (task.id.startsWith("SPK-")) return "spike";
  return task.path.split("/")[0].toLowerCase();
}

function taskModules(task) {
  const text = `${task.name} ${task.acceptance} ${task.notes}`;
  const domain = taskDomain(task);
  const modules = [];
  if (/DM8|仓储|持久化|数据库/.test(text)) modules.push(`${domain}:persistence`);
  if (/HTTP|API|接口/.test(text)) modules.push(`${domain}:api`);
  if (/前端|工作台|浏览器|页面/.test(text)) modules.push(`${domain}:frontend`);
  if (/领域|行为|用例|Service/.test(text)) modules.push(`${domain}:core`);
  if (/计划|治理|样例|设计|门/.test(text)) modules.push(`${domain}:design`);
  return modules.length ? [...new Set(modules)] : [`${domain}:general`];
}

function taskResources(task) {
  const text = `${task.name} ${task.acceptance} ${task.blocker}`;
  const resources = [];
  if (/DM8|数据库/.test(text)) resources.push("local-dm8");
  if (/浏览器|前端|工作台/.test(text)) resources.push("browser");
  if (/中心|责任方|业务专家|运维|DBA|安全|外部证据/.test(text)) resources.push("external-owner");
  return resources;
}

function actualEvidence() {
  const reports = [];
  function collectSurefireReports(directory) {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name);
      if (!entry.isDirectory()) continue;
      if (entry.name === "target") {
        const reportsDir = path.join(target, "surefire-reports");
        if (!fs.existsSync(reportsDir)) continue;
        for (const name of fs.readdirSync(reportsDir)) {
          if (/^TEST-.*\.xml$/.test(name)) reports.push(path.join(reportsDir, name));
        }
        continue;
      }
      collectSurefireReports(target);
    }
  }
  collectSurefireReports(path.join(rootDir, "backend"));
  const testTotals = { tests: 0, failures: 0, errors: 0, skipped: 0 };
  let latestJavaTestAt = null;
  for (const report of reports) {
    const content = fs.readFileSync(report, "utf8");
    const suite = content.match(/<testsuite\b[^>]*>/)?.[0] ?? "";
    for (const key of Object.keys(testTotals)) testTotals[key] += Number(suite.match(new RegExp(`${key}="(\\d+)"`))?.[1] ?? 0);
    const modified = fs.statSync(report).mtime;
    if (!latestJavaTestAt || modified > latestJavaTestAt) latestJavaTestAt = modified;
  }
  const browserResultPath = path.join(rootDir, "test-results", ".last-run.json");
  let browser = null;
  if (fs.existsSync(browserResultPath)) {
    try { browser = JSON.parse(fs.readFileSync(browserResultPath, "utf8")); } catch { browser = { status: "unreadable" }; }
  }
  const businessFiles = [];
  function collectFiles(directory) {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory() && entry.name !== "target") collectFiles(target);
      else if (entry.isFile()) businessFiles.push(target);
    }
  }
  collectFiles(path.join(rootDir, "backend"));
  collectFiles(path.join(rootDir, "db"));
  const latestBusinessChangeAt = businessFiles.reduce((latest, file) => {
    const modified = fs.statSync(file).mtime;
    return !latest || modified > latest ? modified : latest;
  }, null);
  return {
    java: { ...testTotals, reportCount: reports.length, latestAt: latestJavaTestAt?.toISOString() ?? null },
    latestBusinessChangeAt: latestBusinessChangeAt?.toISOString() ?? null,
    browser,
    checkedFiles: {
      dashboardGenerator: existing("scripts/generate-project-progress.mjs"),
      dashboardVerifier: existing("scripts/verify-project-progress.mjs"),
      consultationDm8Store: existing("backend/adapters/persistence-dm8/src/main/java/cn/xinji/agentplatform/consultation/adapter/out/dm8/Dm8ConsultationStore.java"),
      consultationHttp: existing("backend/consultation/src/main/java/cn/xinji/agentplatform/consultation/adapter/in/web/ConsultationController.java"),
      consultationFrontend: existing("frontend/src/app/AppRoutes.tsx")
        && existing("frontend/src/features/consultation/ConsultationPage.tsx")
        && existing("backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/web/ReactFrontendController.java"),
      enforcementCore: existing("backend/enforcement/src/main/java/cn/xinji/agentplatform/enforcement/application/EnforcementApplicationService.java"),
      enforcementHttp: existing("backend/enforcement/src/main/java/cn/xinji/agentplatform/enforcement/adapter/in/web/EnforcementController.java"),
      enforcementDm8Schema: existing("db/dm8/V002__enforcement_mvp_v0.sql")
    }
  };
}

const stages = rowsAsObjects(parseTable(sectionBetween("#### 7.8.1", "#### 7.8.2"))).map((row) => ({
  id: clean(row["阶段"]), name: clean(row["阶段名称"]), goal: clean(row["目标"]), prerequisite: clean(row["前置阶段"]), exit: clean(row["阶段退出条件"])
}));
const milestones = rowsAsObjects(parseTable(sectionBetween("#### 7.8.2", "#### 7.8.3"))).map((row) => ({
  id: clean(row["里程碑"]), phase: clean(row["所属阶段"]), name: clean(row["名称"]), tasks: clean(row["主要 L3 任务"]), status: clean(row["状态"])
}));
const tasks = rowsAsObjects(parseTable(sectionBetween("#### 7.8.3", "#### 7.8.4"))).map((row) => ({
  id: clean(row.taskId), name: clean(row["任务名称"]), path: clean(row["阶段/里程碑"]), status: clean(row["状态"]), priority: clean(row["优先级"]), predecessors: clean(row["前置任务"]), successors: clean(row["后续任务"]), parallel: clean(row["可并行"]), acceptance: clean(row["验收标准"]), completedAt: clean(row["实际完成时间"]), notes: clean(row["备注"]), risk: clean(row["风险"]), blocker: clean(row["阻塞原因"])
}));
const logs = rowsAsObjects(parseTable(sectionBetween("#### 7.8.4", "## 8."))).map((row) => ({
  time: clean(row["时间"]), event: clean(row["事件"]), detail: clean(row["说明"])
}));
const requirementRows = sectionBetween("## 8.", "## 9.")
  .split(/\r?\n/)
  .filter((line) => /^\|\s*(?:CON|ENF|ADJ)-\d{2}\s*\|/.test(line))
  .map((line) => line.trim().slice(1, -1).split("|").map((cell) => clean(cell.trim())))
  .map(([id, source, requirementGate, design, apiData, code, verification, drift, acceptance]) => ({
    id, domain: id.split("-")[0], source, requirementGate, design, apiData, code, verification, drift, acceptance
  }));

if (!stages.length || !milestones.length || !tasks.length || !requirementRows.length) throw new Error("Dashboard source tables are empty");

const planVersion = markdown.match(/^计划版本：([^\s]+)/m)?.[1] ?? "unknown";
const baselineDate = markdown.match(/^基线日期：([^\s]+)/m)?.[1] ?? null;
const currentPhaseName = markdown.match(/^当前阶段：(.+)$/m)?.[1]?.trim() ?? null;
const completedStatuses = new Set(["已完成", "已验证", "已验收", "跳过"]);
const taskMap = new Map(tasks.map((task) => [task.id, task]));
const hasRequirementEvidence = (value) => Boolean(value && value !== "-" && !/^(未执行|未验收|未开始|待确认)$/.test(value));
const acceptedRequirement = (value) => hasRequirementEvidence(value) && !/^未验收/.test(value);
const realIntegrationEvidence = (requirement) => {
  const evidenceText = `${requirement.apiData} ${requirement.code} ${requirement.verification}`;
  return hasRequirementEvidence(requirement.apiData)
    && hasRequirementEvidence(requirement.code)
    && /真实(?:中心)?(?:数据|API|索引|接入)|中心(?:数据|API|索引|接入)/.test(evidenceText)
    && !/未接入|待接入|合成|模拟/.test(evidenceText);
};
const requirementMaturity = {
  total: requirementRows.length,
  requirementReady: requirementRows.filter((item) => hasRequirementEvidence(item.requirementGate)).length,
  designed: requirementRows.filter((item) => hasRequirementEvidence(item.design)).length,
  apiDataTraced: requirementRows.filter((item) => hasRequirementEvidence(item.apiData)).length,
  codeTraced: requirementRows.filter((item) => hasRequirementEvidence(item.code)).length,
  verified: requirementRows.filter((item) => hasRequirementEvidence(item.verification)).length,
  driftChecked: requirementRows.filter((item) => hasRequirementEvidence(item.drift)).length,
  realIntegrated: requirementRows.filter(realIntegrationEvidence).length,
  accepted: requirementRows.filter((item) => acceptedRequirement(item.acceptance)).length,
  pilotRequirementId: tasks.find((task) => /-PILOT-/.test(task.id))?.id.match(/^[A-Z]+-\d+/)?.[0] ?? null
};
for (const task of tasks) {
  task.predecessorIds = knownRefs(task.predecessors, tasks);
  task.declaredSuccessorIds = knownRefs(task.successors, tasks);
  task.domain = taskDomain(task);
  task.modules = taskModules(task);
  task.resources = taskResources(task);
}

const successorMap = new Map(tasks.map((task) => [task.id, new Set(task.declaredSuccessorIds)]));
for (const task of tasks) for (const dependencyId of task.predecessorIds) successorMap.get(dependencyId)?.add(task.id);
for (const task of tasks) task.successorIds = [...(successorMap.get(task.id) ?? [])];
const currentTask = taskMap.get(currentTaskId);
if (!currentTask) throw new Error(`Current task ${currentTaskId} is absent from the L3 ledger`);
const currentStageId = currentTask.path.split("/")[0];
const currentStageIndex = stages.findIndex((stage) => stage.id === currentStageId);
if (currentStageIndex < 0) throw new Error(`Current task ${currentTaskId} references unknown stage ${currentStageId}`);

let stageGateAssessments = [];

const healthIssues = [];
const issue = (severity, code, taskId, message) => healthIssues.push({ severity, code, taskId, message });
if (requirementRows.length !== 41) issue("error", "REQUIREMENT_LEDGER_COUNT", null, `正式需求台账应为 41 项，当前为 ${requirementRows.length} 项`);
const requirementIds = new Set();
for (const requirement of requirementRows) {
  if (requirementIds.has(requirement.id)) issue("error", "DUPLICATE_REQUIREMENT", requirement.id, "正式需求台账存在重复编号");
  requirementIds.add(requirement.id);
  if (!requirement.source || requirement.source === "-") issue("error", "REQUIREMENT_SOURCE_MISSING", requirement.id, "缺少原需求定位");
  if (!hasRequirementEvidence(requirement.requirementGate)) issue("error", "REQUIREMENT_R_GATE_MISSING", requirement.id, "缺少 R 门证据");
  if (acceptedRequirement(requirement.acceptance) && !hasRequirementEvidence(requirement.verification)) issue("error", "ACCEPTED_WITHOUT_VERIFICATION", requirement.id, "已验收但缺少测试 V 证据");
}
const candidateRefPattern = /(?:[A-Z]{2,}|P\d+)(?:-[A-Z0-9]+)+/g;
for (const task of tasks) {
  for (const field of ["predecessors", "successors"]) {
    for (const ref of task[field].match(candidateRefPattern) ?? []) {
      if (!taskMap.has(ref) && !requirementIds.has(ref) && /\d$/.test(ref)) issue("warning", "UNKNOWN_REFERENCE", task.id, `${field} 引用了未登记任务 ${ref}`);
    }
  }
  const phaseId = task.path.split("/")[0];
  if (!stages.some((stage) => stage.id === phaseId)) issue("error", "MISSING_PHASE", task.id, `所属阶段 ${phaseId || "(空)"} 不存在`);
  if (!task.acceptance || task.acceptance === "-") issue("error", "MISSING_ACCEPTANCE", task.id, "缺少可验证的验收标准");
  const unfinished = task.predecessorIds.filter((id) => !completedStatuses.has(taskMap.get(id)?.status));
  if (task.status === "进行中" && unfinished.length) issue("error", "ACTIVE_WITH_UNFINISHED_PREDECESSOR", task.id, `进行中但前置未完成：${unfinished.join("、")}`);
  if (task.status === "已完成" && (task.completedAt === "-" || !task.notes || task.notes === "-")) issue("error", "COMPLETED_WITHOUT_EVIDENCE", task.id, "已完成但缺少完成时间或实现/测试证据");
  if (!task.predecessorIds.length && !task.successorIds.length && !["PLAN-01", "GOV-BASE"].includes(task.id)) issue("warning", "ORPHAN_TASK", task.id, "未建立任何可解析的前置或后续任务关系");
}

const visitState = new Map();
const cycleNodes = [];
function detectCycle(id, stack = []) {
  if (visitState.get(id) === 1) { cycleNodes.push([...stack.slice(stack.indexOf(id)), id]); return; }
  if (visitState.get(id) === 2) return;
  visitState.set(id, 1);
  for (const next of taskMap.get(id)?.successorIds ?? []) detectCycle(next, [...stack, id]);
  visitState.set(id, 2);
}
for (const task of tasks) detectCycle(task.id);
for (const cycle of cycleNodes) issue("error", "DEPENDENCY_CYCLE", cycle[0], `循环依赖：${cycle.join(" -> ")}`);

for (const milestone of milestones.filter((value) => value.status === "已完成")) {
  const children = tasks.filter((task) => task.path === `${milestone.phase}/${milestone.id}`);
  const unfinished = children.filter((task) => !completedStatuses.has(task.status));
  if (unfinished.length) issue("error", "COMPLETED_MILESTONE_WITH_OPEN_TASKS", milestone.id, `里程碑已完成但仍有 ${unfinished.length} 个子任务未完成`);
}
for (const stage of stages) {
  const childMilestones = milestones.filter((milestone) => milestone.phase === stage.id);
  const childTasks = tasks.filter((task) => task.path.startsWith(`${stage.id}/`));
  stage.status = childMilestones.length && childMilestones.every((milestone) => milestone.status === "已完成")
    ? "已完成"
    : childMilestones.some((milestone) => milestone.status === "进行中") || stage.id === currentStageId
      ? "进行中"
      : childMilestones.some((milestone) => milestone.status === "准备中")
        ? "准备中"
        : childMilestones.length && childMilestones.every((milestone) => milestone.status === "阻塞")
          ? "阻塞"
          : "未开始";
  if (stage.status === "已完成") {
    const unfinished = childTasks.filter((task) => !completedStatuses.has(task.status));
    if (unfinished.length) issue("error", "COMPLETED_STAGE_WITH_OPEN_TASKS", stage.id, `阶段已完成但仍有 ${unfinished.length} 个子任务未完成`);
  }
}

function hasDeferredExitEvidence(stage, milestone, children) {
  if (milestone.status !== "阻塞" || !children.length) return false;
  const hasBlockedTask = children.some((task) => task.status === "阻塞");
  const blockersAreExplicit = hasBlockedTask && children.every((task) =>
    completedStatuses.has(task.status) || (task.status === "阻塞" && task.blocker && task.blocker !== "-"));
  if (!blockersAreExplicit) return false;
  if (stage.id === "P0") return /受阻外部条件.*责任方.*解除条件/.test(stage.exit);
  return /真实.*(?:保留|单独取证)/.test(stage.exit) && /真实接入与验收/.test(milestone.name);
}

function stageExitFor(stage) {
  const stageMilestones = milestones.filter((milestone) => milestone.phase === stage.id);
  const evidence = stageMilestones.map((milestone) => {
    const children = tasks.filter((task) => task.path === `${stage.id}/${milestone.id}`);
    if (milestone.status === "已完成") return { milestoneId: milestone.id, satisfied: true, reason: `${milestone.id} 已完成` };
    if (hasDeferredExitEvidence(stage, milestone, children)) {
      return { milestoneId: milestone.id, satisfied: true, deferred: true, reason: `${milestone.id} 的真实/外部缺口已显式阻塞，并记录责任方与解除条件` };
    }
    return { milestoneId: milestone.id, satisfied: false, reason: `${milestone.id} 当前为${milestone.status}` };
  });
  return { satisfied: evidence.length > 0 && evidence.every((item) => item.satisfied), exitCriteria: stage.exit, evidence };
}

const stageExitMap = new Map(stages.map((stage) => [stage.id, stageExitFor(stage)]));
const completedLocalMvpCount = ["M1.2", "M2.2", "M3.2"].filter((id) => milestones.find((milestone) => milestone.id === id)?.status === "已完成").length;

function stageEntryFor(stage, task = null) {
  const previousExit = (stageId) => stageExitMap.get(stageId)?.satisfied === true;
  let satisfied = false;
  let reason;
  switch (stage.id) {
    case "P0": satisfied = true; reason = "首阶段无前置阶段"; break;
    case "P1": satisfied = previousExit("P0"); reason = satisfied ? "P0 退出条件已有证据" : "等待 P0 退出条件"; break;
    case "P2": {
      const consultationLocalClosed = milestones.find((milestone) => milestone.id === "M1.2")?.status === "已完成";
      satisfied = consultationLocalClosed;
      reason = satisfied ? "P1 本地技术闭环已完成；正式验证缺口可与执法本地试点并行" : "等待 P1 本地技术闭环";
      break;
    }
    case "P3": satisfied = previousExit("P2"); reason = satisfied ? "P2 合成本地闭环与缺口保留条件已满足" : "等待 P2 退出条件"; break;
    case "P4": satisfied = completedLocalMvpCount >= 2; reason = `${completedLocalMvpCount}/2 个 MVP 已形成可复核的本地重复证据`; break;
    case "P5": {
      const requiredStage = task?.domain === "consultation" ? "P1" : task?.domain === "enforcement" ? "P2" : task?.domain === "adjudication" ? "P3" : null;
      satisfied = requiredStage ? previousExit(requiredStage) : ["P1", "P2", "P3"].every(previousExit);
      reason = requiredStage ? `${requiredStage} 退出条件${satisfied ? "已满足" : "未满足"}` : `三个 MVP 退出条件${satisfied ? "已满足" : "未全部满足"}`;
      break;
    }
    case "P6": satisfied = previousExit("P5"); reason = satisfied ? "P5 范围与证据链已收口" : "等待 P5 退出条件及目标范围冻结"; break;
    case "P7": {
      const formalP0 = tasks.filter((value) => value.path === "P0/M0.3").every((value) => completedStatuses.has(value.status));
      satisfied = previousExit("P6") && formalP0;
      reason = satisfied ? "P6 已退出且 P0 正式外部条件全部完成" : `P6 退出=${previousExit("P6") ? "是" : "否"}，P0 正式条件=${formalP0 ? "是" : "否"}`;
      break;
    }
    default: reason = `阶段 ${stage.id} 没有 Gate 规则`;
  }
  return { stageId: stage.id, satisfied, prerequisite: stage.prerequisite, reason };
}

stageGateAssessments = stages.map((stage) => ({ stageId: stage.id, entry: stageEntryFor(stage), exit: stageExitMap.get(stage.id) }));

function stageGateFor(task) {
  const stageId = task.path.split("/")[0];
  const stage = stages.find((value) => value.id === stageId);
  if (!stage) return { stageId, satisfied: false, reason: `阶段 ${stageId || "(空)"} 未登记` };
  return stageEntryFor(stage, task);
}

const evidence = actualEvidence();
const completedArtifactChecks = {
  "PLAN-02": ["dashboardGenerator", "dashboardVerifier"],
  "PLAN-03": ["dashboardGenerator", "dashboardVerifier"],
  "MVP-CON-V0-02": ["consultationDm8Store"],
  "MVP-CON-V0-03": ["consultationHttp"],
  "MVP-CON-V0-04": ["consultationFrontend"],
  "MVP-ENF-V0-02": ["enforcementCore"],
  "ARC-10-ENF-PHYSICAL": ["enforcementDm8Schema"],
  "ARC-10-ENF-PHYSICAL-FIX": ["enforcementDm8Schema"]
};
for (const [taskId, checks] of Object.entries(completedArtifactChecks)) {
  const task = taskMap.get(taskId);
  if (task?.status === "已完成" && checks.some((key) => !evidence.checkedFiles[key])) issue("error", "IMPLEMENTATION_EVIDENCE_MISSING", taskId, "计划完成状态与实际文件证据不一致");
}
if (evidence.java.failures || evidence.java.errors) issue("error", "TESTS_FAILING", null, `最近 Java 报告含 ${evidence.java.failures} 个失败、${evidence.java.errors} 个错误`);
if (!evidence.java.latestAt && tasks.some((task) => task.status === "已完成" && /实现|验证/.test(task.name))) {
  issue("error", "TEST_EVIDENCE_MISSING", null, "存在已完成实现任务，但没有可读取的 Java 测试报告");
} else if (evidence.java.latestAt && evidence.latestBusinessChangeAt && new Date(evidence.java.latestAt) < new Date(evidence.latestBusinessChangeAt)) {
  issue("error", "TEST_EVIDENCE_STALE", null, "业务代码或数据库脚本晚于最近 Java 测试报告，计划状态尚未由当前代码验证");
}

const ready = [];
const waiting = [];
for (const task of tasks) {
  const unfinished = task.predecessorIds.filter((id) => !completedStatuses.has(taskMap.get(id)?.status));
  const eligibleStatus = ["未开始", "准备中", "就绪"].includes(task.status);
  const explicitlyBlocked = task.status === "阻塞";
  task.stageGate = stageGateFor(task);
  task.executionState = completedStatuses.has(task.status)
    ? "DONE"
    : task.status === "进行中"
      ? "RUNNING"
      : explicitlyBlocked
        ? "BLOCKED"
        : eligibleStatus && !unfinished.length && task.stageGate.satisfied
          ? "READY"
          : "WAITING";
  if (task.executionState === "READY") {
    task.readinessReasons = [
      "全部已登记前置任务已完成",
      "计划状态未标记阻塞",
      task.stageGate.reason,
      `优先级 ${task.priority}`
    ];
  } else if (task.executionState === "BLOCKED") {
    task.readinessReasons = [task.blocker && task.blocker !== "-" ? task.blocker : "计划已标记阻塞，但未登记解除条件"];
  } else if (task.executionState === "RUNNING") {
    task.readinessReasons = ["计划状态为进行中", task.stageGate.reason];
  } else if (task.executionState === "DONE") {
    task.readinessReasons = [`计划状态为${task.status}`];
  } else {
    task.readinessReasons = [
      ...(unfinished.length ? [`等待前置：${unfinished.join("、")}`] : []),
      ...(!task.stageGate.satisfied ? [task.stageGate.reason] : []),
      ...(!eligibleStatus ? [`当前状态：${task.status}`] : []),
      ...(task.blocker && task.blocker !== "-" ? [`等待说明：${task.blocker}`] : [])
    ];
  }
  if (task.executionState === "READY") ready.push(task);
  if (task.executionState === "WAITING") waiting.push(task);
}
for (const task of tasks.filter((value) => value.status === "阻塞" && value.executionState !== "BLOCKED")) issue("error", "BLOCKED_STATE_DRIFT", task.id, `计划状态为阻塞但执行状态为 ${task.executionState}`);
for (const task of tasks.filter((value) => value.executionState === "READY" && value.status === "阻塞")) issue("error", "BLOCKED_READY_CONFLICT", task.id, "任务同时被判定为 BLOCKED 与 READY");

const priorityValue = (value) => Number(String(value).replace(/^P/, "")) || 9;
function estimateTask(task) {
  const text = `${task.name} ${task.acceptance} ${task.risk}`;
  let durationPoints = /真实|验收|交付|跨域集成/.test(text) ? 8
    : /实现|扩展|闭环/.test(text) ? 5
      : /验证|测试|Spike|回归/.test(text) ? 4
        : /设计|样例|门/.test(text) ? 3 : 2;
  if (task.resources.includes("external-owner")) durationPoints += 2;
  let riskWeight = 1;
  if (task.resources.includes("external-owner")) riskWeight += 2;
  if (/高|法律|安全|权限|真实|未知|失效|性能|恢复|偏移|耦合/.test(text)) riskWeight += 2;
  if (task.executionState === "BLOCKED") riskWeight += 2;
  return { durationPoints, riskWeight, weightedPoints: durationPoints + riskWeight, basis: "基于任务类型、外部资源、风险说明和执行状态的初始相对估算" };
}
for (const task of tasks) task.estimate = estimateTask(task);

const longestMemo = new Map();
function weightedFrom(id, visiting = new Set()) {
  if (longestMemo.has(id)) return longestMemo.get(id);
  if (visiting.has(id)) return { ids: [], score: 0 };
  const nextVisiting = new Set(visiting).add(id);
  const nextPaths = (taskMap.get(id)?.successorIds ?? []).filter((next) => !completedStatuses.has(taskMap.get(next)?.status)).map((next) => weightedFrom(next, nextVisiting));
  const best = nextPaths.sort((a, b) => b.score - a.score || b.ids.length - a.ids.length)[0] ?? { ids: [], score: 0 };
  const task = taskMap.get(id);
  const result = { ids: [id, ...best.ids], score: (task?.estimate.weightedPoints ?? 0) + best.score };
  longestMemo.set(id, result);
  return result;
}
const incomplete = tasks.filter((task) => !completedStatuses.has(task.status));
const pathCandidates = incomplete.map((task) => weightedFrom(task.id));
const weightedCritical = pathCandidates.sort((a, b) => b.score - a.score || b.ids.length - a.ids.length)[0] ?? { ids: [], score: 0 };
const criticalPath = weightedCritical.ids;
const criticalSet = new Set(criticalPath);
for (const task of tasks) task.onCriticalPath = criticalSet.has(task.id);
ready.sort((a, b) => Number(b.onCriticalPath) - Number(a.onCriticalPath) || priorityValue(a.priority) - priorityValue(b.priority) || a.id.localeCompare(b.id));

const criticalPathStates = ["RUNNING", "READY", "WAITING", "BLOCKED"].map((state) => ({
  state,
  taskIds: criticalPath.filter((id) => taskMap.get(id)?.executionState === state),
  weightedPoints: criticalPath.filter((id) => taskMap.get(id)?.executionState === state).reduce((sum, id) => sum + taskMap.get(id).estimate.weightedPoints, 0)
}));

function dependsOn(task, targetId, seen = new Set()) {
  if (!task || seen.has(task.id)) return false;
  if (task.predecessorIds.includes(targetId)) return true;
  seen.add(task.id);
  return task.predecessorIds.some((id) => dependsOn(taskMap.get(id), targetId, seen));
}
const parallelAssessment = tasks.filter((task) => !completedStatuses.has(task.status) && task.id !== currentTaskId).map((task) => {
  const dependencyConflict = dependsOn(task, currentTaskId) || dependsOn(currentTask, task.id);
  const moduleConflicts = task.modules.filter((module) => currentTask?.modules.includes(module));
  const resourceConflicts = task.resources.filter((resource) => resource !== "external-owner" && currentTask?.resources.includes(resource));
  let level = "green", reason = "依赖独立，且与当前主任务无模块或独占资源冲突";
  if (dependencyConflict || moduleConflicts.length || resourceConflicts.length) {
    level = "red";
    reason = dependencyConflict ? "与当前主任务存在直接或传递依赖" : `与当前主任务冲突：${[...moduleConflicts, ...resourceConflicts].join("、")}`;
  } else if (task.executionState === "RUNNING") {
    reason = "已在并行执行，且与当前主任务无依赖、模块或独占资源冲突";
  } else if (task.executionState !== "READY") {
    level = "yellow";
    reason = `${task.readinessReasons.join("；")}；条件满足后可与当前主任务并行`;
  }
  return { taskId: task.id, level, reason };
});

const errorCount = healthIssues.filter((value) => value.severity === "error").length;
const warningCount = healthIssues.filter((value) => value.severity === "warning").length;
const health = {
  score: Math.max(0, 100 - errorCount * 10 - warningCount * 2),
  status: errorCount ? "ERROR" : warningCount ? "WARNING" : "HEALTHY",
  errorCount,
  warningCount,
  issues: healthIssues
};

const now = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false
}).format(new Date()).replaceAll("/", "-");
const projectPlan = {
  schemaVersion: 1,
  sourceOfTruth: "docs/08-总开发实现计划.md",
  planVersion,
  baselineDate,
  currentPhaseName,
  currentTaskId,
  generatedAt: `${now} Asia/Shanghai`,
  stages,
  milestones,
  tasks,
  requirements: requirementRows,
  logs,
  derived: {
    requirementMaturity,
    readyTaskIds: ready.map((task) => task.id),
    waitingTaskIds: waiting.map((task) => task.id),
    blockedTaskIds: tasks.filter((task) => task.executionState === "BLOCKED").map((task) => task.id),
    stageGates: stageGateAssessments,
    criticalPath,
    criticalPathScore: weightedCritical.score,
    criticalPathStates,
    criticalPathBasis: "加权关键路径：初始工期点 + 风险权重，按未完成依赖链最大累计值计算；这是相对排序，不表示日历工期",
    parallelAssessment,
    health
  },
  actualEvidence: evidence
};
const data = JSON.stringify(projectPlan).replaceAll("<", "\\u003c");
fs.writeFileSync(dataPath, `${JSON.stringify(projectPlan, null, 2)}\n`, "utf8");

const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>知识产权智能助手 · 项目执行控制台</title>
<style>
:root{color-scheme:light;--bg:#f5f6f3;--surface:#fff;--ink:#18201c;--muted:#647069;--line:#d9ded9;--accent:#126b54;--accent-soft:#e0f0e9;--blue:#236799;--blue-soft:#e4eff7;--amber:#9a6508;--amber-soft:#fff0cc;--red:#a33434;--red-soft:#f9e1df;--grey:#68706b;--grey-soft:#ecefed;--shadow:0 12px 34px rgba(25,48,38,.08)}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.55 Inter,"Microsoft YaHei",system-ui,sans-serif;letter-spacing:0}button,select,input{font:inherit}button,select{min-height:36px}button:focus-visible,select:focus-visible,input:focus-visible,summary:focus-visible{outline:3px solid rgba(18,107,84,.25);outline-offset:2px}
.shell{min-height:100vh}.topbar{background:#173e32;color:#fff;padding:22px max(22px,calc((100% - 1480px)/2));display:grid;grid-template-columns:minmax(280px,1fr) auto;gap:24px;align-items:end}.brand h1{margin:0 0 5px;font-size:24px;font-weight:500}.brand p{margin:0;color:#c8d7d1}.stamp{text-align:right;color:#c8d7d1;font-size:12px}.stamp strong{display:block;color:#fff;font-size:14px;font-weight:500}
.layout{max-width:1480px;margin:0 auto;padding:22px;display:grid;gap:18px}.overview{display:grid;grid-template-columns:1.6fr repeat(4,minmax(110px,.65fr));background:var(--surface);border:1px solid var(--line);box-shadow:var(--shadow)}.overview>div{padding:18px;border-right:1px solid var(--line)}.overview>div:last-child{border-right:0}.overview .progress-cell{display:grid;gap:10px}.metric-label{color:var(--muted);font-size:12px}.metric-value{font-size:24px;font-weight:500;line-height:1.1}.metric-value.small{font-size:18px}.progress-track{height:9px;background:var(--grey-soft);overflow:hidden}.progress-fill{height:100%;background:var(--accent);transition:width .3s ease}.truth-note{border-left:4px solid var(--amber);background:var(--amber-soft);padding:13px 16px}.truth-note strong{font-weight:600}.truth-note p{margin:3px 0 0;color:#664506}
.summary-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px}.summary-card{background:var(--surface);border:1px solid var(--line);padding:18px;min-width:0}.summary-card h2{font-size:16px;font-weight:500;margin:3px 0 12px}.summary-card.current{border-top:4px solid var(--blue)}.summary-card.warning{border-top:4px solid var(--amber)}.summary-list{margin:0;padding-left:18px;display:grid;gap:10px}.summary-list li{padding-left:2px}.summary-list strong,.summary-list span{display:block}.summary-list span{margin-top:2px;color:var(--muted);font-size:12px}.advanced{border:1px solid var(--line);background:transparent}.advanced>summary{cursor:pointer;background:var(--surface);padding:15px 18px;font-weight:500;list-style:none}.advanced>summary::-webkit-details-marker{display:none}.advanced>summary::after{content:"展开";float:right;color:var(--accent);font-size:12px;font-weight:400}.advanced[open]>summary{border-bottom:1px solid var(--line)}.advanced[open]>summary::after{content:"收起"}.advanced-body{padding:18px;display:grid;gap:18px}
.grid{display:grid;grid-template-columns:1.2fr .8fr;gap:18px}.panel{background:var(--surface);border:1px solid var(--line);padding:18px;min-width:0}.panel h2{font-size:16px;font-weight:500;margin:0 0 14px}.section-head{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:14px}.section-head h2{margin:0}.eyebrow{color:var(--accent);font-weight:500;font-size:12px}.current-task{border-left:4px solid var(--accent);padding:2px 0 2px 16px}.current-task h3{font-size:20px;font-weight:500;margin:3px 0}.current-task p{margin:4px 0;color:var(--muted)}.meta-row{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.tag{display:inline-flex;align-items:center;min-height:26px;padding:2px 8px;border:1px solid var(--line);background:var(--bg);font-size:12px}.tag.status-进行中{color:var(--blue);background:var(--blue-soft);border-color:transparent}.tag.status-阻塞{color:var(--red);background:var(--red-soft);border-color:transparent}.tag.status-已完成{color:var(--accent);background:var(--accent-soft);border-color:transparent}
.queue{display:grid;gap:8px}.queue-item{display:grid;grid-template-columns:auto 1fr auto;gap:10px;align-items:start;padding:10px 0;border-top:1px solid var(--line)}.queue-item:first-child{border-top:0}.dot{width:9px;height:9px;background:var(--accent);margin-top:6px}.queue-item.blocked .dot{background:var(--red)}.queue-item strong{font-weight:500}.queue-item small{display:block;color:var(--muted)}
.queue-item.waiting .dot{background:var(--amber)}.queue-item.conflict .dot{background:var(--red)}.reason-list{margin:4px 0 0;padding-left:18px;color:var(--muted)}.reason-list li{margin:2px 0}.health-summary{display:flex;align-items:baseline;gap:12px;margin-bottom:10px}.health-score{font-size:30px;font-weight:500}.health-ok{color:var(--accent)}.health-warning{color:var(--amber)}.health-error{color:var(--red)}.health-list{display:grid;gap:8px}.health-item{border-left:3px solid var(--amber);padding-left:10px}.health-item.error{border-color:var(--red)}.health-item p{margin:2px 0;color:var(--muted)}.critical-node{min-width:170px;border-top:4px solid var(--line);background:var(--bg);padding:10px}.critical-node.active{border-color:var(--blue);background:var(--blue-soft)}
.roadmap{display:grid;grid-template-columns:repeat(8,minmax(150px,1fr));gap:8px;overflow-x:auto;padding-bottom:4px}.phase{border-top:4px solid var(--line);background:var(--bg);padding:12px;min-height:134px}.phase.active{border-color:var(--blue);background:var(--blue-soft)}.phase.done{border-color:var(--accent)}.phase h3{font-size:14px;font-weight:500;margin:5px 0}.phase p{color:var(--muted);font-size:12px;margin:6px 0}.phase-stat{display:flex;justify-content:space-between;color:var(--muted);font-size:12px}.mini-track{height:5px;background:var(--grey-soft);margin:8px 0}.mini-track span{display:block;height:100%;background:var(--accent)}
.toolbar{display:flex;gap:8px;flex-wrap:wrap}.toolbar select,.toolbar input{border:1px solid var(--line);background:var(--surface);color:var(--ink);padding:6px 9px}.toolbar input{min-width:220px}.tree{display:grid;gap:8px}.tree details{border:1px solid var(--line);background:var(--surface)}.tree summary{cursor:pointer;padding:12px 14px;font-weight:500;display:flex;justify-content:space-between;gap:10px}.tree summary::-webkit-details-marker{display:none}.tree-body{border-top:1px solid var(--line);padding:0 14px}.milestone{padding:14px 0;border-top:1px solid var(--line)}.milestone:first-child{border-top:0}.milestone-title{display:flex;justify-content:space-between;gap:10px;margin-bottom:7px}.task-row{display:grid;grid-template-columns:130px minmax(180px,1fr) 86px 80px;gap:10px;padding:9px 0;border-top:1px dashed var(--line);align-items:center}.task-row[hidden]{display:none}.task-id{font-family:ui-monospace,Consolas,monospace;font-size:12px;color:var(--blue)}.task-name button{border:0;background:transparent;color:inherit;padding:0;text-align:left;cursor:pointer}.task-name button:hover{text-decoration:underline}.status{font-size:12px}.status::before{content:"";display:inline-block;width:8px;height:8px;margin-right:6px;background:var(--grey)}.status.已完成::before{background:var(--accent)}.status.进行中::before{background:var(--blue)}.status.阻塞::before{background:var(--red)}.status.准备中::before{background:var(--amber)}
.dependency-chain{display:flex;align-items:center;gap:7px;overflow-x:auto;padding:4px 0 12px}.node{min-width:150px;border:1px solid var(--line);padding:10px;background:var(--bg)}.node.active{border-color:var(--blue);background:var(--blue-soft)}.arrow{color:var(--muted);font-size:18px}.detail-box{border-top:1px solid var(--line);padding-top:12px;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.detail-box div{min-width:0}.detail-box dt{color:var(--muted);font-size:12px}.detail-box dd{margin:3px 0;overflow-wrap:anywhere}.risks{display:grid;gap:10px}.risk{border-left:3px solid var(--amber);padding-left:11px}.risk.block{border-color:var(--red)}.risk h3{font-size:14px;font-weight:500;margin:0}.risk p{margin:3px 0;color:var(--muted);overflow-wrap:anywhere}.log{display:grid}.log-item{display:grid;grid-template-columns:145px 190px 1fr;gap:12px;padding:10px 0;border-top:1px solid var(--line)}.log-item:first-child{border-top:0}.log time{color:var(--muted);font-variant-numeric:tabular-nums}.empty{color:var(--muted);padding:12px 0}
@media(max-width:900px){.topbar{grid-template-columns:1fr}.stamp{text-align:left}.overview{grid-template-columns:repeat(2,1fr)}.overview .progress-cell{grid-column:1/-1}.overview>div{border-bottom:1px solid var(--line)}.grid,.summary-grid{grid-template-columns:1fr}.roadmap{grid-template-columns:repeat(8,170px)}.task-row{grid-template-columns:110px minmax(160px,1fr) 76px}.task-row .priority{display:none}}
@media(max-width:560px){.layout{padding:12px}.topbar{padding:18px 14px}.overview{grid-template-columns:1fr 1fr}.overview>div{padding:14px}.metric-value{font-size:20px}.toolbar{display:grid;grid-template-columns:1fr}.toolbar input{min-width:0;width:100%}.task-row{grid-template-columns:94px 1fr}.task-row .status,.task-row .priority{grid-column:2}.detail-box{grid-template-columns:1fr}.log-item{grid-template-columns:1fr;gap:2px}.queue-item{grid-template-columns:auto 1fr}.queue-item>.tag{grid-column:2}.brand h1{font-size:20px}}
</style>
</head>
<body>
<div class="shell">
  <header class="topbar"><div class="brand"><h1>三类知识产权智能助手</h1><p>项目执行控制台 · 真实状态来自总开发实现计划</p></div><div class="stamp"><span>最后更新时间</span><strong id="generated-at"></strong></div></header>
  <main class="layout">
    <section class="overview" aria-label="正式业务需求总体进度">
      <div class="progress-cell"><div><span class="metric-label">正式需求已验证</span><div class="metric-value" id="formal-progress-value"></div></div><div class="progress-track" aria-hidden="true"><div class="progress-fill" id="formal-progress-fill"></div></div><div class="metric-label" id="formal-progress-caption"></div></div>
      <div><div class="metric-label">详细设计 D</div><div class="metric-value" id="formal-designed-count"></div></div>
      <div><div class="metric-label">真实接入</div><div class="metric-value" id="real-integration-count"></div></div>
      <div><div class="metric-label">业务验收 A</div><div class="metric-value" id="formal-accepted-count"></div></div>
      <div><div class="metric-label">当前正式试点</div><div class="metric-value small" id="formal-pilot"></div></div>
    </section>
    <section class="truth-note" id="truth-note"><strong>合成 V0 ≠ 正式需求完成</strong><p>本地技术闭环只证明代码链路可运行；正式进度只取自总计划第 8 节的 D、代码、V、真实接入与 A 证据。</p></section>
    <section class="summary-grid" aria-label="当前项目结论">
      <article class="summary-card"><div class="eyebrow">已经做了什么</div><h2>本地技术闭环</h2><div id="local-proof"></div></article>
      <article class="summary-card current"><div class="eyebrow">现在在做什么</div><div class="current-task" id="current-task"></div></article>
      <article class="summary-card warning"><div class="eyebrow">为什么不能继续</div><h2>需要外部确认</h2><div id="current-blocker"></div></article>
    </section>
    <details class="advanced">
      <summary>查看完整执行详情、任务树和技术证据</summary>
      <div class="advanced-body">
    <section class="panel"><div class="section-head"><h2>计划健康度</h2><span class="metric-label" id="health-status"></span></div><div id="health-summary"></div><div class="health-list" id="health-list"></div></section>
    <section class="panel"><div class="section-head"><h2>READY 队列</h2><span class="metric-label">依赖、Gate、阻塞、优先级与关键路径综合计算</span></div><div class="queue" id="ready-queue"></div></section>
    <section class="panel"><div class="section-head"><h2>Critical Path</h2><span class="metric-label" id="critical-basis"></span></div><div class="dependency-chain" id="critical-path"></div></section>
    <section class="grid">
      <article class="panel"><div class="section-head"><h2>可并行任务</h2><span class="metric-label">依赖、模块与独占资源自动判断</span></div><div class="queue" id="parallel-queue"></div></article>
      <article class="panel"><div class="section-head"><h2>等待前置条件</h2><span class="metric-label" id="waiting-count"></span></div><div class="queue" id="waiting-queue"></div></article>
    </section>
    <section class="panel"><div class="section-head"><h2>BLOCKED 任务</h2><span class="metric-label" id="blocked-list-count"></span></div><div class="risks" id="blocked-list"></div></section>
    <section class="panel"><div class="section-head"><h2>最近执行记录</h2><span class="metric-label">最新 8 条真实事件</span></div><div class="log" id="recent-log"></div></section>
    <section class="overview" aria-label="技术执行任务进度">
      <div class="progress-cell"><div><span class="metric-label">技术执行任务进度（次要指标）</span><div class="metric-value" id="progress-value"></div></div><div class="progress-track" aria-hidden="true"><div class="progress-fill" id="progress-fill"></div></div><div class="metric-label" id="progress-caption"></div></div>
      <div><div class="metric-label">任务已完成</div><div class="metric-value" id="done-count"></div></div>
      <div><div class="metric-label">任务进行中</div><div class="metric-value" id="active-count"></div></div>
      <div><div class="metric-label">未开始/准备</div><div class="metric-value" id="pending-count"></div></div>
      <div><div class="metric-label">任务阻塞</div><div class="metric-value" id="blocked-count"></div></div>
    </section>
    <section class="panel"><div class="section-head"><h2>项目路线图</h2><span class="metric-label">P0 → P7</span></div><div class="roadmap" id="roadmap"></div></section>
    <section class="panel"><div class="section-head"><h2>当前依赖链</h2><span class="metric-label">点击任务查看完整字段</span></div><div class="dependency-chain" id="dependency-chain"></div><dl class="detail-box" id="task-detail" aria-live="polite"></dl></section>
    <section class="panel"><div class="section-head"><h2>任务树</h2><div class="toolbar"><input id="task-search" type="search" placeholder="搜索任务编号或名称" aria-label="搜索任务"><select id="status-filter" aria-label="按状态筛选"><option value="">全部状态</option></select></div></div><div class="tree" id="task-tree"></div></section>
    <section class="panel"><div class="section-head"><h2>执行日志</h2><span class="metric-label">只保留真实事件</span></div><div class="log" id="execution-log"></div></section>
      </div>
    </details>
  </main>
</div>
<script type="module">
const response=await fetch("./project-plan.json",{cache:"no-store"});
if(!response.ok)throw new Error("project-plan.json 加载失败："+response.status);
const DATA=await response.json();
const statusOrder=["进行中","准备中","未开始","阻塞","已完成","跳过"];
const doneStatuses=new Set(["已完成","已验证","已验收"]);
const taskById=new Map(DATA.tasks.map(t=>[t.id,t]));
const count=s=>DATA.tasks.filter(t=>s.includes(t.status)).length;
const completed=count(["已完成"]), active=count(["进行中"]), blocked=count(["阻塞"]), pending=DATA.tasks.length-completed-active-blocked;
const progress=Math.round(completed/DATA.tasks.length*100);
document.getElementById("generated-at").textContent=DATA.generatedAt;
const maturity=DATA.derived.requirementMaturity;
const formalProgress=maturity.total?Math.round(maturity.verified/maturity.total*100):0;
document.getElementById("formal-progress-value").textContent=formalProgress+"%";
document.getElementById("formal-progress-fill").style.width=formalProgress+"%";
document.getElementById("formal-progress-caption").textContent=maturity.verified+" / "+maturity.total+" 项正式需求通过测试 V";
document.getElementById("formal-designed-count").textContent=maturity.designed+" / "+maturity.total;
document.getElementById("real-integration-count").textContent=maturity.realIntegrated;
document.getElementById("formal-accepted-count").textContent=maturity.accepted+" / "+maturity.total;
document.getElementById("formal-pilot").textContent=maturity.pilotRequirementId||"未指定";
document.getElementById("progress-value").textContent=progress+"%";
document.getElementById("progress-fill").style.width=progress+"%";
document.getElementById("progress-caption").textContent=completed+" / "+DATA.tasks.length+" 个 L3 技术执行任务满足退出条件，不代表正式需求完成";
document.getElementById("done-count").textContent=completed;
document.getElementById("active-count").textContent=active;
document.getElementById("pending-count").textContent=pending;
document.getElementById("blocked-count").textContent=blocked;
const esc=s=>String(s??"").replace(/[&<>\"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const current=DATA.tasks.find(t=>t.id===DATA.currentTaskId)||DATA.tasks.find(t=>t.status==="进行中");
const currentPhase=current?.path.split("/")[0]||"-";
const currentPhaseData=DATA.stages.find(p=>p.id===currentPhase);
document.getElementById("current-task").innerHTML=current?'<span class="metric-label">'+esc(currentPhase)+' · '+esc(currentPhaseData?.name)+'</span><h3>'+esc(current.id)+' · '+esc(current.name)+'</h3><p>先补齐正式试点详细设计并通过 D 门。</p><div class="meta-row"><span class="tag status-'+esc(current.status)+'">'+esc(current.status)+'</span><span class="tag">优先级 '+esc(current.priority)+'</span></div>':"<p class='empty'>当前没有进行中的任务</p>";
const localProofIds=["MVP-CON-V0-05","CON-04-SYNTH-VERIFY","MVP-ENF-V0-05"];
const localProofs=localProofIds.map(id=>taskById.get(id)).filter(Boolean);
document.getElementById("local-proof").innerHTML=localProofs.length?'<ul class="summary-list">'+localProofs.map(task=>'<li><strong>'+esc(task.id)+'：'+esc(task.name)+'</strong><span>'+esc(task.notes)+'</span></li>').join("")+"</ul>":"<p class='empty'>尚无已登记的本地技术闭环。</p>";
document.getElementById("current-blocker").innerHTML=current?.blocker&&current.blocker!=="-"?'<strong>当前需要确认</strong><p>'+esc(current.blocker)+'</p>':"<p>当前任务未登记外部阻塞；请按验收标准继续推进。</p>";

function phaseTasks(id){return DATA.tasks.filter(t=>t.path.startsWith(id+"/"))}
document.getElementById("roadmap").innerHTML=DATA.stages.map(stage=>{const list=phaseTasks(stage.id), done=list.filter(t=>doneStatuses.has(t.status)).length, pct=list.length?Math.round(done/list.length*100):0;const cls=stage.id===currentPhase?"active":pct===100?"done":"";return '<article class="phase '+cls+'"><div class="phase-stat"><span>'+esc(stage.id)+'</span><span>'+pct+'%</span></div><h3>'+esc(stage.name)+'</h3><div class="mini-track"><span style="width:'+pct+'%"></span></div><p>'+done+'/'+list.length+' 个任务完成</p><span class="metric-label">'+esc(stage.prerequisite==="-"?"无前置阶段":"前置 "+stage.prerequisite)+'</span></article>'}).join("");

const readyTasks=DATA.derived.readyTaskIds.map(id=>taskById.get(id)).filter(Boolean);
document.getElementById("ready-queue").innerHTML=readyTasks.length?readyTasks.map((t,i)=>'<div class="queue-item"><span class="dot"></span><div><strong>'+(i+1)+'. '+esc(t.id)+' · '+esc(t.name)+'</strong><ul class="reason-list">'+t.readinessReasons.map(reason=>'<li>'+esc(reason)+'</li>').join("")+'</ul></div><span class="tag">READY'+(t.onCriticalPath?' · CP':'')+'</span></div>').join(""):"<p class='empty'>当前没有新的 READY 任务；先完成正在执行的主任务或解除列出的前置条件。</p>";

document.getElementById("critical-basis").textContent=DATA.derived.criticalPathBasis;
document.getElementById("critical-path").innerHTML=DATA.derived.criticalPath.map((id,i)=>{const t=taskById.get(id);return(i?'<span class="arrow" aria-hidden="true">→</span>':'')+'<button class="critical-node state-'+esc((t?.executionState||"").toLowerCase())+' '+(id===current?.id?'active':'')+'" data-task="'+esc(id)+'"><strong>'+esc(id)+'</strong><br><span class="metric-label">'+esc(t?.executionState||"")+' · 工期 '+esc(t?.estimate?.durationPoints||0)+' · 风险 '+esc(t?.estimate?.riskWeight||0)+'</span></button>'}).join("")||"<p class='empty'>依赖图存在错误或没有未完成任务，无法计算关键路径。</p>";

const levelLabel={green:"🟢 可以立即并行",yellow:"🟡 理论可并行但等待",red:"🔴 不可并行"};
const parallel=DATA.derived.parallelAssessment.sort((a,b)=>({green:0,yellow:1,red:2}[a.level]-{green:0,yellow:1,red:2}[b.level])).slice(0,12);
document.getElementById("parallel-queue").innerHTML=parallel.map(item=>{const t=taskById.get(item.taskId);return'<div class="queue-item '+(item.level==='yellow'?'waiting':item.level==='red'?'conflict':'')+'"><span class="dot"></span><div><strong>'+esc(t.id)+' · '+esc(t.name)+'</strong><small>'+esc(item.reason)+'</small></div><span class="tag">'+levelLabel[item.level]+'</span></div>'}).join("")||"<p class='empty'>当前无可评估任务</p>";

const waitingTasks=DATA.derived.waitingTaskIds.map(id=>taskById.get(id)).filter(Boolean).slice(0,10);
document.getElementById("waiting-count").textContent=DATA.derived.waitingTaskIds.length+" 项";
document.getElementById("waiting-queue").innerHTML=waitingTasks.map(t=>'<div class="queue-item waiting"><span class="dot"></span><div><strong>'+esc(t.id)+' · '+esc(t.name)+'</strong><small>'+esc(t.readinessReasons.join("；"))+'</small></div><span class="tag">WAITING</span></div>').join("")||"<p class='empty'>当前没有等待前置条件的任务</p>";

const blockedTasks=DATA.tasks.filter(t=>t.executionState==="BLOCKED");
document.getElementById("blocked-list-count").textContent=blockedTasks.length+" 项";
document.getElementById("blocked-list").innerHTML=blockedTasks.map(t=>'<article class="risk block"><h3>'+esc(t.id)+' · '+esc(t.name)+'</h3><p>'+esc(t.blocker||"已标记阻塞但未记录原因")+'</p></article>').join("")||"<p class='empty'>当前没有 BLOCKED 任务</p>";

const health=DATA.derived.health,healthClass=health.status==="HEALTHY"?"health-ok":health.status==="WARNING"?"health-warning":"health-error";
document.getElementById("health-status").textContent=health.status;
document.getElementById("health-summary").innerHTML='<div class="health-summary"><span class="health-score '+healthClass+'">'+health.score+'</span><span>错误 '+health.errorCount+' · 警告 '+health.warningCount+'</span></div>';
document.getElementById("health-list").innerHTML=health.issues.slice(0,12).map(item=>'<article class="health-item '+esc(item.severity)+'"><strong>'+esc(item.code)+(item.taskId?' · '+esc(item.taskId):'')+'</strong><p>'+esc(item.message)+'</p></article>').join("")||"<p class='empty'>未发现计划结构、状态或已登记实现证据问题</p>";

function showTask(task){if(!task)return;document.getElementById("task-detail").innerHTML=[["任务",task.id+" · "+task.name],["计划/执行状态",task.status+" / "+task.executionState+" / "+task.priority],["工期/风险权重",task.estimate.durationPoints+" / "+task.estimate.riskWeight+"（加权 "+task.estimate.weightedPoints+"）"],["阶段 Gate",task.stageGate.reason],["前置任务",task.predecessors],["后续任务",task.successors],["模块/资源",[...task.modules,...task.resources].join("、")||"-"],["READY 判定",task.readinessReasons.join("；")],["验收标准",task.acceptance],["完成时间",task.completedAt],["备注",task.notes],["风险/阻塞",[task.risk,task.blocker].filter(x=>x&&x!=="-").join("；")||"-"]].map(([k,v])=>'<div><dt>'+esc(k)+'</dt><dd>'+esc(v)+'</dd></div>').join("")}
const chainIds=[],chainSeen=new Set();
function appendDependencyChain(task,depth=0){if(!task||depth>8||chainSeen.has(task.id))return;const ids=task.predecessors.match(/[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+/g)||[];ids.map(id=>taskById.get(id)).filter(Boolean).forEach(value=>appendDependencyChain(value,depth+1));chainSeen.add(task.id);chainIds.push(task.id)}
appendDependencyChain(current);
const visibleChain=chainIds.slice(-10);
document.getElementById("dependency-chain").innerHTML=visibleChain.map((id,i)=>{const t=taskById.get(id);return (i?'<span class="arrow" aria-hidden="true">→</span>':'')+'<button class="node '+(id===current?.id?'active':'')+'" data-task="'+esc(id)+'"><strong>'+esc(id)+'</strong><br><span class="metric-label">'+esc(t?.status||"")+'</span></button>'}).join("");

const statuses=[...new Set(DATA.tasks.map(t=>t.status))].sort((a,b)=>statusOrder.indexOf(a)-statusOrder.indexOf(b));
document.getElementById("status-filter").insertAdjacentHTML("beforeend",statuses.map(s=>'<option>'+esc(s)+'</option>').join(""));
const tree=document.getElementById("task-tree");
tree.innerHTML=DATA.stages.map(stage=>{const ms=DATA.milestones.filter(m=>m.phase===stage.id);return '<details '+(stage.id===currentPhase?'open':'')+'><summary><span>'+esc(stage.id)+' · '+esc(stage.name)+'</span><span class="metric-label">'+phaseTasks(stage.id).length+' 个任务</span></summary><div class="tree-body">'+ms.map(m=>{const list=DATA.tasks.filter(t=>t.path===stage.id+"/"+m.id);return '<section class="milestone"><div class="milestone-title"><strong>'+esc(m.id)+' · '+esc(m.name)+'</strong><span class="tag status-'+esc(m.status)+'">'+esc(m.status)+'</span></div>'+(list.map(t=>'<div class="task-row" data-status="'+esc(t.status)+'" data-search="'+esc((t.id+" "+t.name).toLowerCase())+'"><span class="task-id">'+esc(t.id)+'</span><span class="task-name"><button data-task="'+esc(t.id)+'">'+esc(t.name)+'</button></span><span class="status '+esc(t.status)+'">'+esc(t.status)+'</span><span class="priority">'+esc(t.priority)+'</span></div>').join("")||'<p class="empty">任务由范围门禁后拆分</p>')+'</section>'}).join("")+'</div></details>'}).join("");
document.addEventListener("click",event=>{const button=event.target.closest("[data-task]");if(button){showTask(taskById.get(button.dataset.task));document.getElementById("task-detail").scrollIntoView({behavior:"smooth",block:"nearest"})}});
function filterTasks(){const q=document.getElementById("task-search").value.trim().toLowerCase(),s=document.getElementById("status-filter").value;document.querySelectorAll(".task-row").forEach(row=>{row.hidden=!!((q&&!row.dataset.search.includes(q))||(s&&row.dataset.status!==s))})}
document.getElementById("task-search").addEventListener("input",filterTasks);document.getElementById("status-filter").addEventListener("change",filterTasks);
document.getElementById("execution-log").innerHTML=[...DATA.logs].reverse().map(l=>'<div class="log-item"><time>'+esc(l.time)+'</time><strong>'+esc(l.event)+'</strong><span>'+esc(l.detail)+'</span></div>').join("");
document.getElementById("recent-log").innerHTML=[...DATA.logs].reverse().slice(0,8).map(l=>'<div class="log-item"><time>'+esc(l.time)+'</time><strong>'+esc(l.event)+'</strong><span>'+esc(l.detail)+'</span></div>').join("");
showTask(current);
</script>
</body>
</html>`;

fs.writeFileSync(outputPath, html, "utf8");
console.log(`Generated ${path.relative(rootDir, dataPath)} and ${path.relative(rootDir, outputPath)} from docs/08-总开发实现计划.md (${tasks.length} tasks)`);
