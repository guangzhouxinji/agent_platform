import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(scriptDir, "..");
const planPath = path.join(rootDir, "docs", "project-plan.json");

if (!fs.existsSync(planPath)) {
  throw new Error("Missing docs/project-plan.json. Run npm run build:progress first.");
}

const plan = JSON.parse(fs.readFileSync(planPath, "utf8"));
const task = plan.tasks.find((item) => item.id === plan.currentTaskId);
if (!task) throw new Error(`Current task ${plan.currentTaskId} is absent from the generated plan.`);

const requirementId = task.id.match(/^(CON|ENF|ADJ)-\d{2}/)?.[0] ?? null;
const requirement = requirementId
  ? plan.requirements?.find((item) => item.id === requirementId)
  : null;
const directDependencies = task.predecessorIds
  .map((id) => plan.tasks.find((item) => item.id === id))
  .filter(Boolean);
const relatedBlockers = requirementId
  ? plan.tasks.filter((item) => item.id.startsWith(`${requirementId}-`) && item.status === "阻塞")
  : [];

function line(label, value) {
  console.log(`${label}: ${value || "-"}`);
}

console.log("Current Task Brief");
line("Plan", `${plan.planVersion} | ${plan.currentPhaseName}`);
line("Task", `${task.id} | ${task.name}`);
line("Status", task.status);
line("Acceptance", task.acceptance);
line("Stage gate", `${task.stageGate?.satisfied ? "passed" : "waiting"} | ${task.stageGate?.reason ?? "-"}`);
line("Dependencies", directDependencies.length
  ? directDependencies.map((item) => `${item.id}(${item.status})`).join(", ")
  : "none");
line("Next", task.successorIds.length ? task.successorIds.join(", ") : "none");

if (requirement) {
  console.log("Requirement Evidence");
  line("Requirement", requirement.id);
  line("R gate", requirement.requirementGate);
  line("Design", requirement.design);
  line("Verification", requirement.verification);
  line("Acceptance", requirement.acceptance);
}

console.log("Relevant Blockers");
if (!relatedBlockers.length) {
  line("Blockers", task.blocker && task.blocker !== "-" ? task.blocker : "none recorded for this task chain");
} else {
  for (const blocker of relatedBlockers) line(blocker.id, blocker.blocker);
}

console.log("Turn Sequence");
console.log("1. npm run codex:preflight");
console.log("2. Implement only this task's accepted scope, then run focused tests.");
console.log("3. Update docs/08 evidence and status when the task outcome changes.");
console.log("4. npm run codex:closeout");
