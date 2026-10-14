import { createHash } from "node:crypto";
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import AdjudicationWorkspace from "./AdjudicationWorkspace";
import { AdjudicationError, digest, request } from "./api";
import type { CaseView, Original, ReportView, Version } from "./contract";
import type { WorkAreaProps } from "./WorkArea";
import type { ReportHandoff } from "./reportHandoff";
const control = vi.hoisted(() => ({ props: null as WorkAreaProps | null, real: false }));
vi.mock("./api", async () => ({ ...await vi.importActual<typeof import("./api")>("./api"), completeLogin: vi.fn().mockResolvedValue("session"), forgetLogin: vi.fn(), request: vi.fn(), digest: vi.fn() }));
vi.mock("./WorkArea", async () => { const { default: Real } = await vi.importActual<typeof import("./WorkArea")>("./WorkArea"); return { default: (props: WorkAreaProps) => { control.props = props; return control.real ? <Real {...props}/> : <div aria-label="测试交接入口"/>; } }; });
const hash = (text: string) => createHash("sha256").update(text, "utf8").digest("hex");
const oldText = "旧案件原文\r\n", freshText = "交接案件原文\r\n保留空白  ";
function original(text: string, version = "1"): Original { return { source: { id: "source", version, locator: "/source", kind: "PROJECT_AUTHORED", sha256: hash(text) }, originalText: text }; }
function report(revision: number): ReportView { return { current: revision === 2, stale: false, report: { revision, inputVersion: 1, policyVersion: "p", author: "a", contributors: [], sections: [{ id: "analysis", content: "报告正文" + revision, sources: [] }], diagnostics: [], contentSha256: String(revision).repeat(64), reviewState: "DRAFT", reviews: [] } }; }
function sample(): CaseView { return { version: 3, inputVersion: 1, caseInput: { id: "case-a", organization: "o", dataScope: "s", name: "案件", applicant: "a", respondent: "r", issues: [], region: null, procedure: null, relevantDate: null, source: original(oldText).source }, reports: [report(1), report(2)], materials: [], claims: [], findings: [], citations: [], inputContributors: [], audits: [] }; }
function versions(workVersion = 4): Version[] { return [{ workVersion: 3, inputVersion: 1 }, ...(workVersion === 3 ? [] : [{ workVersion, inputVersion: 1 }])] as Version[]; }
let fresh: CaseView, historyRead: Version[], originalsRead: Original[], target: ReportHandoff;
beforeEach(() => {
 history.replaceState(null, "", "/app/adjudication?case=case-a"); control.props = null; control.real = false;
 fresh = { ...sample(), version: 4, caseInput: { ...sample().caseInput, name: "交接案件", source: original(freshText, "2").source } };
 historyRead = versions(); originalsRead = [original(oldText), original(freshText, "2")];
 target = { caseId: "case-a", runId: "run-a", runVersion: 1, revision: 2, workVersion: 4, generatedWorkVersion: 2, inputVersion: 1, reportSha256: "2".repeat(64), observationSha256: "a".repeat(64) };
 vi.mocked(digest).mockImplementation(async text => hash(text));
 vi.mocked(request).mockImplementation(async (_token, path) => path.endsWith("/originals") ? [original(oldText)] : path.endsWith("/versions") ? versions(3) : sample());
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); });
async function open() {
 const rendered = render(<AdjudicationWorkspace/>);
 await waitFor(() => expect(screen.getByRole("button", { name: "进入 / 刷新案件" })).toBeEnabled());
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByLabelText("测试交接入口");
 await waitFor(() => expect(screen.getByRole("button", { name: "进入 / 刷新案件" })).toBeEnabled());
 fireEvent.click(screen.getByRole("button", { name: "报告" })); fireEvent.click(screen.getByRole("button", { name: /报告 v1 ·/ }));
 vi.mocked(request).mockImplementation(async (_token, path) => { if (path.endsWith("/originals")) return originalsRead; throw new Error("意外请求 " + path); });
 return rendered;
}
async function handoff() { let accepted: void | boolean; await act(async () => { accepted = await control.props!.onOpenReport(target, fresh, historyRead); }); return accepted!; }
it("commits exact report and newly SHA-bound originals together, focuses report, GET only", async () => {
 await open(); expect(await handoff()).toBe(true); expect(screen.getByRole("heading", { name: "报告 v2" })).toBeInTheDocument();
 expect(screen.getByLabelText("报告编辑与复核")).toHaveFocus(); fireEvent.click(screen.getByRole("button", { name: "案件" }));
 expect(screen.getAllByText(/交接案件原文/).length).toBeGreaterThan(0);
 expect(vi.mocked(request).mock.calls.filter(c => c[1].endsWith("/originals"))).toHaveLength(2);
 expect(vi.mocked(request).mock.calls.every(c => c[2] === undefined && c[3] === undefined)).toBe(true);
});
for (const [name, mutate] of Object.entries({
 crossMaterial: () => { fresh.materials = [{ caseId: "case-b" }] as CaseView["materials"]; },
 crossClaim: () => { fresh.claims = [{ caseId: "case-b" }] as CaseView["claims"]; },
 crossCitation: () => { fresh.citations = [{ caseId: "case-b" }] as CaseView["citations"]; },
 crossFinding: () => { fresh.findings = [{ caseId: "case-b" }] as CaseView["findings"]; },
 duplicateHistory: () => { historyRead = [historyRead[0], historyRead[0], historyRead[1]]; },
 unorderedHistory: () => { historyRead = [historyRead[1], historyRead[0], historyRead[1]]; },
 invalidHistoryInput: () => { historyRead[0].inputVersion = 9; },
 missingCollection: () => { fresh.audits = undefined as unknown as CaseView["audits"]; },
 invalidOtherReport: () => { fresh.reports[0].report.contentSha256 = "invalid"; },
 emptyOriginals: () => { originalsRead = []; },
 missingCurrentOriginal: () => { originalsRead = [original(oldText)]; },
 wrongSourceCoordinates: () => { originalsRead[1].source.locator = "/wrong"; },
 tamperedOriginal: () => { originalsRead[1].originalText += "篡改"; },
 tamperedHistoricalOriginal: () => { originalsRead[0].originalText += "篡改"; },
 duplicateOriginal: () => { originalsRead.push(originalsRead[1]); }
})) it("rejects entire handoff: " + name, async () => {
 await open(); mutate(); expect(await handoff()).toBe(false);
 expect(screen.queryByRole("heading", { name: "报告 v2" })).not.toBeInTheDocument();
 expect(screen.queryByText("交接案件")).not.toBeInTheDocument(); expect(screen.getByRole("alert")).toHaveTextContent("精确校验");
 expect(screen.queryByRole("heading", { name: "报告 v1" })).not.toBeInTheDocument();
 expect(vi.mocked(request).mock.calls.every(c => c[2] === undefined)).toBe(true);
});
for (const [name, mutate] of Object.entries({
 reportSHA: () => { target.reportSha256 = "f".repeat(64); }, revision: () => { target.revision = 9; }, work: () => { target.workVersion = 9; }, input: () => { target.inputVersion = 9; },
 case: () => { target.caseId = "case-b"; }, organization: () => { fresh.caseInput.organization = "other"; }, scope: () => { fresh.caseInput.dataScope = "other"; },
 changedInput: () => { fresh.inputVersion = target.inputVersion = 2; historyRead[1].inputVersion = 2; }, historicalHead: () => { historyRead.push({ workVersion: 5, inputVersion: 1 } as Version); },
 stale: () => { fresh.reports[1].stale = true; }, policyStale: () => { fresh.reports[1].policyStale = true; }, notCurrent: () => { fresh.reports[1].current = false; }
})) it("keeps ReportHandoff guard: " + name, async () => {
 await open(); mutate(); const calls = vi.mocked(request).mock.calls.length; expect(await handoff()).toBe(false);
 expect(vi.mocked(request)).toHaveBeenCalledTimes(calls); expect(screen.getByRole("heading", { name: "报告 v1" })).toBeInTheDocument();
});
it("cancel preserves dirty revision, confirmed retry only GETs originals", async () => {
 await open(); const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
 fireEvent.change(screen.getByLabelText("修订内容"), { target: { value: "未保存" } });
 expect(await handoff()).toBe(false); expect(screen.getByLabelText("修订内容")).toHaveValue("未保存");
 confirm.mockReturnValue(true); expect(await handoff()).toBe(true); expect(screen.getByRole("heading", { name: "报告 v2" })).toBeInTheDocument();
});
for (const status of [0, 401, 403, 503]) it("original read denial/content clearing " + status, async () => {
 await open(); vi.mocked(request).mockRejectedValueOnce(new AdjudicationError(status, "CASE_ACCESS_DENIED")); expect(await handoff()).toBe(false);
 expect(screen.queryByRole("heading", { name: "报告 v1" })).not.toBeInTheDocument(); expect(screen.queryByRole("heading", { name: "报告 v2" })).not.toBeInTheDocument();
 expect(vi.mocked(request).mock.calls.every(c => c[2] === undefined)).toBe(true);
});
function deferred<T>() { let resolve!: (value: T) => void, reject!: (reason: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
for (const boundary of ["originalGET", "digest"]) for (const exit of ["denied", "unmount"]) it("isolates delayed " + boundary + " after " + exit, async () => {
 const rendered = await open(), saved = control.props!, reads = deferred<Original[]>(), sha = deferred<string>();
 if (boundary === "originalGET") vi.mocked(request).mockReturnValueOnce(reads.promise); else vi.mocked(digest).mockReturnValueOnce(sha.promise);
 let outcome!: Promise<void | boolean>; await act(async () => { outcome = Promise.resolve(saved.onOpenReport(target, fresh, historyRead)); });
 await act(async () => { if (exit === "denied") saved.onDenied(); else rendered.unmount(); });
 await act(async () => { reads.resolve(originalsRead); sha.resolve(hash(oldText)); expect(await outcome).toBe(false); });
 expect(screen.queryByRole("heading", { name: "报告 v2" })).not.toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
it("rejected read after context exit cannot resurrect error", async () => {
 await open(); const saved = control.props!, gate = deferred<Original[]>(); vi.mocked(request).mockReturnValueOnce(gate.promise);
 let outcome!: Promise<void | boolean>; await act(async () => { outcome = Promise.resolve(saved.onOpenReport(target, fresh, historyRead)); });
 await act(async () => { saved.onDenied(); gate.reject(new AdjudicationError(403, "CASE_ACCESS_DENIED")); expect(await outcome).toBe(false); }); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
it("obsolete callback after locking cannot start read", async () => {
 await open(); const saved = control.props!; fireEvent.click(screen.getByRole("button", { name: "锁定工作台" })); const calls = vi.mocked(request).mock.calls.length;
 await act(async () => { expect(await saved.onOpenReport(target, fresh, historyRead)).toBe(false); }); expect(vi.mocked(request)).toHaveBeenCalledTimes(calls);
});
it("binding failure recovers by GET only, never report generation POST", async () => {
 await open(); originalsRead = []; expect(await handoff()).toBe(false);
 vi.mocked(request).mockImplementation(async (_token, path) => path.endsWith("/originals") ? [original(oldText), original(freshText, "2")] : path.endsWith("/versions") ? versions() : fresh);
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByRole("heading", { name: "报告 v2" });
 expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(vi.mocked(request).mock.calls.every(c => c[2] === undefined)).toBe(true);
});
it("digest infrastructure failure withdraws the whole refreshed group", async () => {
 await open(); vi.mocked(digest).mockRejectedValueOnce(new Error("摘要不可用")); expect(await handoff()).toBe(false);
 expect(screen.getByRole("alert")).toHaveTextContent("摘要不可用");
 expect(screen.queryByRole("heading", { name: "报告 v1" })).not.toBeInTheDocument();
});
it("old completion cannot overwrite a new case read after context exit", async () => {
 await open(); const saved = control.props!, gate = deferred<Original[]>(); vi.mocked(request).mockReturnValueOnce(gate.promise);
 let outcome!: Promise<void | boolean>; await act(async () => { outcome = Promise.resolve(saved.onOpenReport(target, fresh, historyRead)); });
 await act(async () => { saved.onDenied(); });
 const other = { ...sample(), caseInput: { ...sample().caseInput, id: "case-b", name: "新案件B" } };
 vi.mocked(request).mockImplementation(async (_token, path) => path.endsWith("/originals") ? [original(oldText)] : path.endsWith("/versions") ? versions(3) : other);
 fireEvent.change(screen.getByLabelText("案件ID"), { target: { value: "case-b" } }); fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" }));
 await waitFor(() => expect(screen.getByRole("button", { name: "进入 / 刷新案件" })).toBeEnabled()); fireEvent.click(screen.getByRole("button", { name: "案件" })); await screen.findByText("新案件B");
 await act(async () => { gate.resolve(originalsRead); expect(await outcome).toBe(false); });
 expect(screen.getByText("新案件B")).toBeInTheDocument(); expect(screen.queryByText("交接案件")).not.toBeInTheDocument();
});
it("real WorkArea result path still reaches exact Reports through asynchronous parent originals read", async () => {
 control.real = true;
 const initial = sample(), audit = { version: 4, action: "GENERATE_AUXILIARY_REPORT", actor: "a", objectId: "2", at: "2026-10-07T00:00:00Z" };
 fresh.audits = [audit]; historyRead[1] = { ...historyRead[1], audit, snapshotSha256: "b".repeat(64) };
 const run = { goalText: "辅助报告", checkpoint: { runId: "run-a", version: 9, phase: "STOP", task: { id: "case-a" }, cursor: 0, invocations: 1, replans: 0, plan: null, pending: null,
 observations: [{ reference: "case-report:case-a/2", sha256: hash(JSON.stringify(fresh.reports[1].report)) }], outcome: { reason: "NEED_HUMAN", outcomeRef: "case-report:case-a/2" } }, events: [] };
 let loaded = false;
 vi.mocked(request).mockImplementation(async (_token, path) => path.includes("/agent-runs") ? run : path.endsWith("/originals") ? loaded ? originalsRead : [original(oldText)] : path.endsWith("/versions") ? loaded ? historyRead : versions(3) : loaded ? fresh : initial);
 render(<AdjudicationWorkspace/>); await waitFor(() => expect(screen.getByRole("button", { name: "进入 / 刷新案件" })).toBeEnabled());
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByLabelText("运行编号");
 await waitFor(() => expect(screen.getByRole("button", { name: "进入 / 刷新案件" })).toBeEnabled()); loaded = true;
 fireEvent.click(screen.getByRole("button", { name: "报告" })); fireEvent.click(screen.getByRole("button", { name: /报告 v1 ·/ }));
 fireEvent.change(screen.getByLabelText("运行编号"), { target: { value: "run-a" } }); fireEvent.click(screen.getByRole("button", { name: "读取当前运行" }));
 fireEvent.click(await screen.findByRole("button", { name: "定位报告编辑 / 复核区" }));
 await screen.findByRole("heading", { name: "报告 v2" }); await waitFor(()=>expect(screen.getByLabelText("报告编辑与复核")).toHaveFocus());
 fireEvent.click(screen.getByRole("button", { name: "案件" })); expect(screen.getAllByText(/交接案件原文/).length).toBeGreaterThan(0);
 expect(vi.mocked(request).mock.calls.every(c => c[2] === undefined)).toBe(true);
});
for (const change of ["case", "input"]) it("awaited handoff clears its exact report target on subsequent " + change + " refresh", async () => {
 await open(); expect(await handoff()).toBe(true);
 const updated: CaseView = { ...sample(), version: 5, inputVersion: 2, caseInput: { ...sample().caseInput, id: change === "case" ? "case-b" : "case-a" }, reports: [{ ...report(3), current: true, report: { ...report(3).report, inputVersion: 2 } }] };
 vi.mocked(request).mockImplementation(async (_token, path) => path.endsWith("/originals") ? [original(oldText)] : path.endsWith("/versions") ? [{ workVersion: 5, inputVersion: 2 }] : updated);
 if (change === "case") fireEvent.change(screen.getByLabelText("案件ID"), { target: { value: "case-b" } });
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByRole("heading", { name: "报告 v3" });
 expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(screen.queryByRole("heading", { name: "报告 v2" })).not.toBeInTheDocument();
});

