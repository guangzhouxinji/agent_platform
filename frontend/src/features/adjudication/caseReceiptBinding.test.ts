import { describe, expect, it } from "vitest";
import { bindCaseRead, bindCaseReceipt, CaseBindingError } from "./caseReceiptBinding";
import type { CasePacket } from "./caseReceiptBinding";
import type { CaseView, Receipt, Version } from "./contract";
const sha = "a".repeat(64), reportSha = "b".repeat(64);
const source = { id: "s", version: "1", locator: "/s", sha256: sha, kind: "PROJECT_AUTHORED" as const };
function sample(): CaseView { return { version: 3, inputVersion: 2, caseInput: { id: "case-a", organization: "o", dataScope: "d", name: "案件", applicant: "a", respondent: "r", issues: ["争点"], region: null, procedure: null, relevantDate: null, source }, materials: [], claims: [], findings: [], citations: [], inputContributors: [], audits: [], reports: [{ current: true, stale: false, report: { revision: 1, inputVersion: 2, policyVersion: "p", author: "a", contributors: [], sections: [{ id: "analysis", content: "非空报告", sources: [source] }], diagnostics: [], contentSha256: reportSha, reviewState: "DRAFT", reviews: [] } }] }; }
const history: Version[] = [1, 2, 3].map(v => ({ workVersion: v, inputVersion: v === 3 ? 2 : 1, snapshotSha256: sha, audit: { version: v, action: "CONTEXT", actor: "a", objectId: "case-a", at: "2026-10-07T00:00:00Z" } }));
function packet(suffix = "/context-revisions"): CasePacket { return { path: "/v2/adjudication/case-works/case-a" + suffix, body: { expectedVersion: 3, input: { id: "temporary-edit" }, reportSha256: reportSha }, key: "original-key" }; }
function receipt(change: Partial<Receipt> = {}): Receipt { return { requestId: "original-key", action: "CONTEXT", caseId: "case-a", workVersion: 4, inputVersion: 3, reportRevision: 1, reportSha256: reportSha, snapshotSha256: sha, ...change }; }
describe("case read binding", () => {
 it("accepts a real nonempty report structure and exact head", () => { expect(bindCaseRead("case-a", sample(), history).view.reports[0].report.sections[0].content).toBe("非空报告"); });
 it("binds historical input/work coordinates without requiring historical head", () => { const old = { ...sample(), version: 2, inputVersion: 1, reports: [] }; expect(bindCaseRead("case-a", old, history, 2).head).toBe(3); });
 it.each([
  ["wrong Case", { ...sample(), caseInput: { ...sample().caseInput, id: "case-b" } }, history, undefined],
  ["old current work", { ...sample(), version: 2 }, history, undefined],
  ["wrong historical work", sample(), history, 2],
  ["wrong head input", sample(), history.map(v => v.workVersion === 3 ? { ...v, inputVersion: 1 } : v), undefined],
  ["illegal work", { ...sample(), version: 0 }, history, undefined],
  ["illegal report SHA", { ...sample(), reports: [{ ...sample().reports[0], report: { ...sample().reports[0].report, contentSha256: "bad" } }] }, history, undefined],
  ["duplicate history", sample(), [...history, history[2]], undefined],
 ] as const)("rejects %s", (_name, value, versions, requestedVersion) => { expect(() => bindCaseRead("case-a", value, versions, requestedVersion)).toThrow(CaseBindingError); });
});
describe("receipt binding", () => {
 it("binds the path Case, not editable body identity; ordinary commands can retain report coordinates", () => { expect(bindCaseReceipt(packet(), receipt()).caseId).toBe("case-a"); });
 it.each([
  { caseId: "case-b" }, { requestId: "other-key" }, { workVersion: 3 }, { workVersion: 4.5 }, { inputVersion: 0 }, { snapshotSha256: "bad" }, { snapshotSha256: "A".repeat(64) }, { reportSha256: "z".repeat(64) }, { reportRevision: null }, { action: "CREATE" },
 ])("keeps malformed 200 distinct from HTTP rejection: %j", change => { expect(() => bindCaseReceipt(packet(), receipt(change))).toThrow(CaseBindingError); });
 it("allows genuinely unknown create Case and verifies original key", () => { const p = { path: "/v2/adjudication/case-works", body: { input: { id: "editable" } }, key: "original-key" }; const result = receipt({ action: "CREATE", caseId: "new-case", workVersion: 1, inputVersion: 1, reportRevision: null, reportSha256: null }); expect(bindCaseReceipt(p, result).caseId).toBe("new-case"); expect(() => bindCaseReceipt(p, { ...result, requestId: "other" })).toThrow(); });
 it.each(["submit", "reviews"])("%s preserves exact report version/SHA", action => { const p = packet("/reports/1/" + action); const result = receipt({ action: action === "submit" ? "SUBMIT" : "REVIEW", inputVersion: 2 }); expect(bindCaseReceipt(p, result)).toBe(result); expect(() => bindCaseReceipt(p, { ...result, reportRevision: 2 })).toThrow(); expect(() => bindCaseReceipt(p, { ...result, reportSha256: sha })).toThrow(); });
 it("revision returns new revision/SHA, not the submitted base SHA", () => { const result = receipt({ action: "REVISE_REPORT", reportRevision: 2, reportSha256: sha, inputVersion: 2 }); expect(bindCaseReceipt(packet("/reports/1/revisions"), result)).toBe(result); });
 it("generation requires legal report coordinates without inventing review state", () => { const result = receipt({ action: "GENERATE_REPORT", inputVersion: 2 }); expect(bindCaseReceipt(packet("/reports"), result)).toBe(result); expect(() => bindCaseReceipt(packet("/reports"), { ...result, reportRevision: null, reportSha256: null })).toThrow(); });
});

it("confirmed receipt cannot be displayed as a different current work, input, snapshot or report", () => {
 const committed = { ...sample(), version: 4, inputVersion: 3 };
 const versions = [...history, { ...history[2], workVersion: 4, inputVersion: 3 }];
 expect(bindCaseRead("case-a", committed, versions, undefined, receipt()).view.version).toBe(4);
 for (const changed of [receipt({ workVersion: 3 }), receipt({ inputVersion: 2 }), receipt({ snapshotSha256: "c".repeat(64) }), receipt({ reportSha256: "d".repeat(64) })]) {
  expect(() => bindCaseRead("case-a", committed, versions, undefined, changed)).toThrow(CaseBindingError);
 }
});
