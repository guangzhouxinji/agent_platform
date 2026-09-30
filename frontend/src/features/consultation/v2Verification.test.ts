import { describe, expect, it } from "vitest";
import { assertSameTask, verificationLines, type VerificationSnapshot } from "./v2Verification";

const taskId = "b845302b-8836-4a9c-962f-f0bf90b01827";
const setId = "ad899101-cae1-4e1d-a842-7d03545aad23";
const requestId = "19aca18d-1a79-45a4-b482-507a34c79062";
const base = (): VerificationSnapshot => ({
  task: { taskId, state: "ACTIVE", revision: 2, expiresAt: "2027-03-26T00:00:00Z", messages: [] },
  work: { taskId, taskRevision: 2, taskState: "ACTIVE", resultSequence: 0, focusSequence: 0,
    featureRevision: 0, resultSet: null, focus: null, feature: null }
});
const withResult = (): VerificationSnapshot => {
  const s = base();
  s.work.resultSequence = 1;
  s.work.resultSet = { setId, inputRevision: 2, currentInput: true };
  s.result = { setId, sequence: 1, inputRevision: 2, method: "SYNTHETIC", actualScope: 2,
    members: [{ ordinal: 2, recordId: "P2", version: "V1", locator: "claim:1",
      available: true, claimText: "受限权项正文" }] };
  return s;
};

describe("read-only verification projection", () => {
  it("keeps absent references distinct from no search result", () => {
    expect(verificationLines(base()).map((line) => line.mark)).toEqual(
      ["未开始", "未开始", "未开始", "未开始"]);
  });
  it("rejects task/work revision or state interleaving before any projection", () => {
    const s = withResult();
    s.work.taskRevision = 3;
    expect(() => assertSameTask(s.task, s.work)).toThrow("READ_SNAPSHOT_CHANGED");
    expect(verificationLines(s)).toEqual([{ label: "任务版本", mark: "待核实",
      detail: "任务与工作状态不一致" }]);
  });
  it("binds the set and exact focus member without exposing source body", () => {
    const s = withResult();
    s.work.focus = { setId, ordinal: 2, inputRevision: 2, currentInput: true };
    const lines = verificationLines(s);
    expect(lines[0]).toMatchObject({ mark: "已记录", detail: "集合序号 1 · 合成范围 2 项" });
    expect(lines[1]).toMatchObject({ mark: "已记录", detail: "P2 · V1 · claim:1" });
    expect(JSON.stringify(lines)).not.toContain("受限权项正文");
    s.work.focus.ordinal = 1;
    expect(verificationLines(s)[1].mark).toBe("待核实");
  });
  it("does not replace a missing or drifted set with an older ordinal", () => {
    const s = withResult();
    s.result!.setId = "another-set";
    expect(verificationLines(s)[0].mark).toBe("待核实");
    s.result = undefined;
    s.resultError = "来源目前不可用";
    expect(verificationLines(s)[0]).toMatchObject({ mark: "待核实" });
  });
  it("keeps all old references historical after correction", () => {
    const s = withResult();
    s.task.revision = 3;
    s.task.corrections = [{ oldRevision: 2, newRevision: 3, utterance: "更正原话", createdAt: "now" }];
    s.work.taskRevision = 3;
    s.work.resultSet!.currentInput = false;
    s.work.focus = { setId, ordinal: 2, inputRevision: 2, currentInput: false };
    s.work.featureRevision = 1;
    s.work.feature = { inputRevision: 2, manifestId: "DEV-CON-COMPARE-INPUT-01", currentInput: false };
    s.confirmation = { featureRevision: 1, inputRevision: 2, actorRef: "dev-con-a",
      historicalInput: true, candidates: [] };
    expect(verificationLines(s).slice(0, 3).map((line) => line.mark)).toEqual(["历史", "历史", "历史"]);
  });
  it("uses the exact human package and never promotes an old opinion", () => {
    const s = base();
    s.human = { requestSequence: 2, request: { taskId, requestId, requestSequence: 2,
      inputRevision: 2, requesterRef: "dev-con-a", requestInputId: "REQUEST_GAP", requestText: "正文",
      createdAt: "now", currentInput: true, taskState: "ACTIVE", acceptanceState: "NOT_ACCEPTED",
      acceptedBy: null, acceptedAt: null } };
    expect(verificationLines(s)[3].detail).toContain("待领取");
    s.opinion = { taskId, requestId: "old-request", requestSequence: 1, inputRevision: 2,
      opinionId: "old", opinionInputId: "OPINION_GAP", opinionText: "旧意见正文",
      submittedBy: "dev-con-b", submittedAt: "now", currentInput: false };
    expect(verificationLines(s)[3].mark).toBe("待核实");
    expect(JSON.stringify(verificationLines(s))).not.toContain("正文");
  });
  it("marks source and human read errors as unverified, not empty", () => {
    const s = withResult();
    s.result = undefined;
    s.resultError = "SOURCE_UNAVAILABLE";
    s.humanError = "DATABASE_UNAVAILABLE";
    expect(verificationLines(s).map((line) => line.mark)).toEqual(
      ["待核实", "未开始", "未开始", "待核实"]);
  });
  it("keeps authorized cancelled history without claiming completion", () => {
    const s = withResult();
    s.task.state = "CANCELLED";
    s.task.revision = 3;
    s.work.taskState = "CANCELLED";
    s.work.taskRevision = 3;
    s.work.resultSet!.currentInput = false;
    expect(verificationLines(s)[0].mark).toBe("历史");
    expect(JSON.stringify(verificationLines(s))).not.toContain("完成");
  });
});
