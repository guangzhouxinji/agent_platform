import { createHash } from "node:crypto";
import { parseTaskRows } from "./plan-dependencies.mjs";

const sourceOfTruth = "docs/08-总开发实现计划.md";
const fail = code => { throw new Error(`${code}: refresh with npm run build:progress and reconcile the master plan before execution.`); };
function one(matches, code) {
  const values = [...matches];
  if (values.length !== 1) fail(code);
  return values[0];
}

/** Historical successor prose never supplies scheduling instructions. */
export function readCurrentHandoff(markdown) {
  const section = one(markdown.matchAll(/^### 0\.11[^\r\n]*\r?\n([\s\S]*?)(?=^#{1,3} |$(?![\s\S]))/gm), "BRIEF_HANDOFF_SECTION")[1];
  const heading = one(section.matchAll(/^#### 现行交接\s*$/gm), "BRIEF_HANDOFF_UNIQUE");
  const current = section.slice(heading.index + heading[0].length);
  const navigation = one(current.matchAll(/^执行导航：父交付：`([^`]+)`；执行项：`([^`]+)`；通过后：`([^`]+)`[^\r\n]*$/gm), "BRIEF_NAVIGATION_REQUIRED");
  const sourceVersion = one(current.matchAll(/源计划：([^；\s]+)；/g), "BRIEF_HANDOFF_VERSION")[1];
  const fields = Object.fromEntries(["交付与范围", "最近证明", "父交付差异", "第一未解依赖", "下一操作", "执行边界"].map(label => {
    const pattern = new RegExp(`^- \\*\\*${label}\\*\\*：([^\\r\\n]+)$`, "gm");
    return [label, one(current.matchAll(pattern), "BRIEF_HANDOFF_FIELD")[1]];
  }));
  return { parentId: navigation[1], executionId: navigation[2], nextId: navigation[3], sourceVersion, fields };
}

/** Explicit row markers are many-to-many; no task-name inference. */
export function explicitRequirementIds(row, knownIds) {
  const text = Object.values(row).join("\n");
  const markers = [...text.matchAll(/需求关联=([^；\r\n]*)；/g)];
  if (!markers.length) {
    if (text.includes("需求关联=")) fail("BRIEF_REQUIREMENT_MAPPING_INVALID");
    return [];
  }
  if (markers.length !== 1) fail("BRIEF_REQUIREMENT_MAPPING_DUPLICATE");
  const ids = markers[0][1].split("、");
  if (!ids.length || new Set(ids).size !== ids.length
      || ids.some(id => !/^(CON|ENF|ADJ)-\d{2}$/.test(id) || !knownIds.has(id))) fail("BRIEF_REQUIREMENT_MAPPING_INVALID");
  return ids;
}

export function currentTaskBrief(sourceBytes, plan) {
  const markdown = sourceBytes.toString("utf8");
  const version = one(markdown.matchAll(/^计划版本：([^\s]+)\s*$/gm), "BRIEF_PLAN_VERSION")[1];
  const currentId = one(markdown.matchAll(/^当前(?:唯一主|优先)任务：\s*`([^`\s]+)[^`]*`/gm), "BRIEF_PRIMARY_TASK")[1];
  if (plan.sourceOfTruth !== sourceOfTruth || plan.planVersion !== version || plan.currentTaskId !== currentId
      || plan.sourceSha256 !== createHash("sha256").update(sourceBytes).digest("hex")) fail("BRIEF_GENERATED_PLAN_STALE");
  const handoff = readCurrentHandoff(markdown);
  if (handoff.sourceVersion !== version || handoff.parentId !== currentId) fail("BRIEF_HANDOFF_STALE");
  const rawRows = parseTaskRows(markdown);
  const requirements = new Map((plan.requirements ?? []).map(item => [item.id, item]));
  const requirementSection = one(markdown.matchAll(/^## 8\.[^\r\n]*\r?\n([\s\S]*?)(?=^## |$(?![\s\S]))/gm), "BRIEF_REQUIREMENT_LEDGER")[1];
  const sourceRequirements = new Set([...requirementSection.matchAll(/^\|\s*((?:CON|ENF|ADJ)-\d{2})\s*\|/gm)].map(match => match[1]));
  const resolve = id => {
    const raw = rawRows.filter(row => row.taskId === id);
    const generated = (plan.tasks ?? []).filter(task => task.id === id);
    if (raw.length !== 1 || generated.length !== 1) fail("BRIEF_NAVIGATION_TASK_MISSING");
    const requirementIds = explicitRequirementIds(raw[0], sourceRequirements);
    if (requirementIds.some(item => !requirements.has(item))) fail("BRIEF_REQUIREMENT_EVIDENCE_MISSING");
    return { task: generated[0], requirementIds, requirements: requirementIds.map(item => requirements.get(item)) };
  };
  return { planVersion: version, currentPhaseName: plan.currentPhaseName, handoff,
    parent: resolve(handoff.parentId), execution: resolve(handoff.executionId), next: resolve(handoff.nextId) };
}

export function renderCurrentTaskBrief(brief, plan) {
  const lines = [], line = (label, value) => lines.push(`${label}: ${value || "-"}`);
  const task = brief.execution.task;
  lines.push("Current Task Brief");
  line("Plan", `${brief.planVersion} | ${brief.currentPhaseName}`);
  line("Parent delivery", `${brief.parent.task.id} | ${brief.parent.task.name}`);
  line("Execution item", `${task.id} | ${task.name}`);
  line("After exit (navigation, not admission)", `${brief.next.task.id} | ${brief.next.task.name}`);
  line("Status", `${task.status} | execution=${task.executionState}`);
  line("Acceptance", task.acceptance);
  line("Unresolved gates", task.dependencyGates?.map(gate => `${gate.id}: ${gate.condition}`).join("; ") || "none recorded");
  line("Stage gate", `${task.stageGate?.satisfied ? "passed" : "waiting"} | ${task.stageGate?.reason ?? "-"}`);
  line("Dependencies", task.predecessorIds?.map(id => `${id}(${plan.tasks.find(item => item.id === id)?.status ?? "missing"})`).join(", ") || "none");
  line("Admission", task.readinessReasons?.join("; "));
  for (const [label, scope] of [["Parent", brief.parent], ["Execution", brief.execution], ["After exit", brief.next]])
    line(`${label} requirement mapping`, scope.requirementIds.join(", ") || "未显式关联；不从任务名称推断");
  for (const requirement of brief.execution.requirements) {
    line("Requirement", requirement.id);
    line("R gate", requirement.requirementGate);
    line("Design", requirement.design);
    line("Verification", requirement.verification);
    line("Acceptance evidence", requirement.acceptance);
  }
  lines.push("Authoritative handoff (section 0.11)");
  for (const [label, value] of Object.entries(brief.handoff.fields)) line(label, value);
  lines.push("Navigation does not approve unmet task dependencies, R/D gates, or runtime operations.");
  return lines.join("\n");
}
