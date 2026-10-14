import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { parseDependencies, parseTaskRows, executionEligibility, reverseDependencies } from "./plan-dependencies.mjs";
const ids = new Set(["REL-01", "REL-02", "REL-03", "REL-04", "P0-05", "P0-06", "SPK-B", "SPK-C"]);
for (const text of ["REL-01/02/03", "P0-05/06", "SPK-B/C", "REL-*", "REL-01～REL-03", "三域闭环", "REL-99", "REL-01、未知条件", "REL-01,REL-02", "", "REL-01、", "REL-01、REL-01", " REL-01"]) {
  test(`dependency never silently drops ${JSON.stringify(text)}`, () => assert.throws(() => parseDependencies(text, ids)));
}
test("all release prerequisites survive", () => assert.deepEqual(parseDependencies("REL-01、REL-02、REL-03", ids).ids, ["REL-01", "REL-02", "REL-03"]));
test("no dependency is explicit", () => assert.deepEqual(parseDependencies("-", ids), { ids: [], gates: [] }));
test("gates require documented conditions and are never implicitly passed", () => {
  assert.throws(() => parseDependencies("GATE:REL-03-SCOPE", ids, { allowGates: true }));
  const parsed = parseDependencies("REL-01、GATE:REL-03-SCOPE", ids, { allowGates: true, notes: "GATE:REL-03-SCOPE=范围冻结及责任人证据；后文" });
  assert.deepEqual(parsed.ids, ["REL-01"]);
  assert.equal(parsed.gates[0].satisfied, false);
  assert.equal(parsed.gates[0].condition, "范围冻结及责任人证据");
  assert.throws(() => parseDependencies("GATE:REL-03-SCOPE", ids, { notes: "GATE:REL-03-SCOPE=批准" }));
});
const table = rows => `#### 7.8.3 L3\n| taskId | 状态 |\n|---|---|\n${rows}\n#### 7.8.4 Logs`;
test("unterminated row cannot disappear from generated plan", () => assert.throws(() => parseTaskRows(table("| REL-01 | 已完成")), /UNTERMINATED/));
test("duplicate rows fail closed", () => assert.throws(() => parseTaskRows(table("| REL-01 | 已完成 |\n| REL-01 | 未开始 |")), /INVALID_ROW/));
test("invalid column count fails closed", () => assert.throws(() => parseTaskRows(table("| REL-01 | 已完成 | surprise |")), /INVALID_ROW/));
test("complete row is preserved", () => assert.equal(parseTaskRows(table("| REL-01 | 已完成 |"))[0].taskId, "REL-01"));
const map = new Map([["REL-01", { status: "已完成" }], ["REL-02", { status: "阻塞" }]]);
test("release waits for every required task", () => assert.equal(executionEligibility({ status: "未开始", predecessorIds: ["REL-01", "REL-02"] }, map, true).state, "WAITING"));
test("in progress cannot bypass a stage or dependency", () => {
  assert.equal(executionEligibility({ status: "进行中", predecessorIds: ["REL-01"] }, map, false).state, "WAITING");
  assert.equal(executionEligibility({ status: "进行中", predecessorIds: ["REL-02"] }, map, true).state, "WAITING");
});
test("unresolved formal gate prevents both READY and RUNNING", () => {
  for (const status of ["未开始", "进行中"]) assert.equal(executionEligibility({ status, predecessorIds: [], dependencyGates: [{ id: "GATE:REL-03-SCOPE", satisfied: false }] }, map, true).state, "WAITING");
});
test("historical completion and explicit blocked status are not rewritten", () => {
  assert.equal(executionEligibility({ status: "已完成", predecessorIds: ["REL-02"] }, map, false).state, "DONE");
  assert.equal(executionEligibility({ status: "阻塞", predecessorIds: [] }, map, true).state, "BLOCKED");
});
test("ready only after task and stage gates; running remains running", () => {
  for (const [status, state] of [["未开始", "READY"], ["进行中", "RUNNING"]]) assert.equal(executionEligibility({ status, predecessorIds: ["REL-01"] }, map, true).state, state);
});
test("hard graph is reversed from prerequisites, not advisory navigation", () => {
  const graph = reverseDependencies([{ id: "REL-01", predecessorIds: [], declaredSuccessorIds: ["REL-03"] }, { id: "REL-02", predecessorIds: ["REL-01"] }, { id: "REL-03", predecessorIds: [] }]);
  assert.deepEqual([...graph.get("REL-01")], ["REL-02"]);
});
test("live Markdown uses complete declarations and release dependencies", () => {
  const text = fs.readFileSync(new URL("../../docs/08-总开发实现计划.md", import.meta.url), "utf8");
  const rows = parseTaskRows(text), known = new Set(rows.map(row => row.taskId));
  for (const row of rows) {
    parseDependencies(row["前置任务"], known, { allowGates: true, notes: row["备注"] });
    parseDependencies(row["后续任务"], known);
  }
  const release = rows.find(row => row.taskId === "REL-04");
  assert.deepEqual(parseDependencies(release["前置任务"], known).ids, ["REL-01", "REL-02", "REL-03"]);
  const platform = rows.find(row => row.taskId === "REL-01");
  assert.deepEqual(parseDependencies(platform["前置任务"], known, { allowGates: true, notes: platform["备注"] }).ids, ["ENG-05", "ENG-06", "ENG-07", "ENG-08", "ENG-09", "ENG-10", "ENG-11"]);
});
