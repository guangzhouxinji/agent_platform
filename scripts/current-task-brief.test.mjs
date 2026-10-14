import { createHash } from "node:crypto";
import test from "node:test";
import assert from "node:assert/strict";
import { currentTaskBrief, renderCurrentTaskBrief, explicitRequirementIds } from "./lib/current-task-brief.mjs";

const fields = ["交付与范围", "最近证明", "父交付差异", "第一未解依赖", "下一操作", "执行边界"];
const markdown = [
  "计划版本：V1.0", "当前唯一主任务：`IMP-CON-PARENT-01`", "### 0.11 固定续接指令与每轮交接",
  "#### 现行交接", "执行导航：父交付：`IMP-CON-PARENT-01`；执行项：`PC-CON-CHILD-01`；通过后：`IMP-CON-NEXT-01`。导航不是准入。",
  "执行会话：owner；源计划：V1.0；", ...fields.map(label => `- **${label}**：${label}的实际内容`),
  "## 1. 计划定位", "旧导航：OTHER-OLD-01", "#### 7.8.3 任务", "| taskId | 备注 |", "| --- | --- |",
  "| IMP-CON-PARENT-01 | 需求关联=CON-06、CON-12； |", "| PC-CON-CHILD-01 | 需求关联=CON-06、CON-12； |",
  "| IMP-CON-NEXT-01 | 未配置需求映射 |", "#### 7.8.4 历史", "## 8. 需求", "| CON-06 | source |", "| CON-12 | source |", "## 9. 后续", ""
].join("\n");

function fixture(source = markdown) {
  const bytes = Buffer.from(source);
  return { bytes, plan: { sourceOfTruth: "docs/08-总开发实现计划.md", planVersion: "V1.0",
    sourceSha256: createHash("sha256").update(bytes).digest("hex"), currentTaskId: "IMP-CON-PARENT-01",
    currentPhaseName: "P0", tasks: ["IMP-CON-PARENT-01", "PC-CON-CHILD-01", "IMP-CON-NEXT-01"].map(id => ({
      id, name: id, status: "进行中", executionState: "WAITING", predecessorIds: [], dependencyGates: [],
      stageGate: { satisfied: false, reason: "dependency pending" }, readinessReasons: ["no admission"], acceptance: "actual result"
    })), requirements: ["CON-06", "CON-12"].map(id => ({ id, requirementGate: "ready", design: "partial", verification: "component", acceptance: "not accepted" })) } };
}

test("uses current handoff child and explicit many-to-many evidence, without admitting successor", () => {
  const { bytes, plan } = fixture();
  const brief = currentTaskBrief(bytes, plan), output = renderCurrentTaskBrief(brief, plan);
  assert.equal(brief.execution.task.id, "PC-CON-CHILD-01");
  assert.deepEqual(brief.execution.requirementIds, ["CON-06", "CON-12"]);
  assert.deepEqual(brief.parent.requirementIds, ["CON-06", "CON-12"]);
  assert.deepEqual(brief.next.requirementIds, []);
  assert.match(output, /After exit \(navigation, not admission\)/);
  assert.match(output, /未显式关联；不从任务名称推断/);
  assert.match(output, /CON-06[\s\S]*CON-12/);
  assert.match(output, /execution=WAITING/);
  assert.match(output, /下一操作的实际内容/);
  assert.doesNotMatch(output, /OTHER-OLD-01|Implement only|Turn Sequence/);
});

for (const field of ["sourceOfTruth", "planVersion", "currentTaskId", "sourceSha256"]) {
  test(`rejects mismatched generated ${field} before producing a brief`, () => {
    const { bytes, plan } = fixture(); plan[field] = "wrong";
    assert.throws(() => currentTaskBrief(bytes, plan), /BRIEF_GENERATED_PLAN_STALE/);
  });
}
test("rejects legacy JSON without source digest", () => {
  const { bytes, plan } = fixture(); delete plan.sourceSha256;
  assert.throws(() => currentTaskBrief(bytes, plan), /BRIEF_GENERATED_PLAN_STALE/);
});
test("same-version source changes and newline changes invalidate generated data", () => {
  const { plan } = fixture();
  for (const source of [markdown.replace("实际内容", "changed"), markdown.replaceAll("\n", "\r\n")])
    assert.throws(() => currentTaskBrief(Buffer.from(source), plan), /BRIEF_GENERATED_PLAN_STALE/);
});
for (const [name, transform, failure] of [
  ["duplicate handoff", source => source.replace("#### 现行交接", "#### 现行交接\n#### 现行交接"), "BRIEF_HANDOFF_UNIQUE"],
  ["missing navigation", source => source.replace(/^执行导航：.*\n/m, ""), "BRIEF_NAVIGATION_REQUIRED"],
  ["stale handoff version", source => source.replace("源计划：V1.0", "源计划：V0.9"), "BRIEF_HANDOFF_STALE"],
  ["wrong parent", source => source.replace("父交付：`IMP-CON-PARENT-01`", "父交付：`IMP-CON-NEXT-01`"), "BRIEF_HANDOFF_STALE"],
  ["unknown execution", source => source.replace("执行项：`PC-CON-CHILD-01`", "执行项：`PC-CON-MISSING-01`"), "BRIEF_NAVIGATION_TASK_MISSING"],
  ["unknown requirement", source => source.replaceAll("需求关联=CON-06、CON-12", "需求关联=CON-06、CON-99"), "BRIEF_REQUIREMENT_MAPPING_INVALID"]
]) test(`rejects ${name} even with a fresh source digest`, () => {
  const { bytes, plan } = fixture(transform(markdown));
  assert.throws(() => currentTaskBrief(bytes, plan), new RegExp(failure));
});
test("missing generated evidence and duplicate navigation task cannot be silently omitted", () => {
  const { bytes, plan } = fixture(); plan.requirements.pop();
  assert.throws(() => currentTaskBrief(bytes, plan), /BRIEF_REQUIREMENT_EVIDENCE_MISSING/);
  const duplicate = fixture(); duplicate.plan.tasks.push(duplicate.plan.tasks[1]);
  assert.throws(() => currentTaskBrief(duplicate.bytes, duplicate.plan), /BRIEF_NAVIGATION_TASK_MISSING/);
});
test("mapping markers are strict and task names never imply associations", () => {
  const ids = new Set(["CON-06", "CON-12"]);
  assert.deepEqual(explicitRequirementIds({ taskId: "CON-06-IMPLEMENT-01", 备注: "CON-12 in prose" }, ids), []);
  for (const value of ["需求关联=；", "需求关联=CON-06、CON-06；", "需求关联=CON-99；", "需求关联=CON-06",
    "需求关联=CON-06；需求关联=CON-12；", "需求关联=CON-06、 CON-12；"])
    assert.throws(() => explicitRequirementIds({ 备注: value }, ids), /BRIEF_REQUIREMENT_MAPPING/);
});
