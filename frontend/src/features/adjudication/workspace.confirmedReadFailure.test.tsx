import { createHash } from "node:crypto";
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import AdjudicationWorkspace from "./AdjudicationWorkspace";
import { AdjudicationError, digest, request } from "./api";
import type { CaseView, Receipt, Version } from "./contract";

// Isolated transport/component contracts; no real identity, source, model or database access.
vi.mock("./api", async () => ({ ...await vi.importActual<typeof import("./api")>("./api"), completeLogin: vi.fn().mockResolvedValue("session"), request: vi.fn(), digest: vi.fn(), forgetLogin: vi.fn() }));
vi.mock("./WorkArea", () => ({ default: () => <section aria-label="隔离测试Agent入口"><button>启动原案件Agent</button></section> }));
vi.mock("./Forms", () => ({
 CaseForm: ({ command }: { command?: (suffix: string, body: Record<string, unknown>) => Promise<unknown> }) => command ? <button onClick={() => void command("/context-revisions", { originalText: "人工更新" })}>发送原案件命令</button> : null,
 CitationForm: () => null, ClaimForm: () => null, FindingForm: () => null, MaterialForm: () => null,
}));
vi.mock("./Readers", () => ({
 CaseRead: ({ view }: { view: CaseView }) => <h2>案件正文版本{view.version}</h2>, CitationsRead: () => null, ClaimsRead: () => null, FindingsRead: () => null, MaterialsRead: () => null, HistoryRead: () => null,
}));

const originalText = "隔离案件原文\r\n保留UTF8";
const originalSha = createHash("sha256").update(originalText, "utf8").digest("hex");
const snapshotSha = "a".repeat(64);
const root = "/v2/adjudication/case-works/case-a";
function sample(version = 3): CaseView {
 return { version, inputVersion: 1, caseInput: { id: "case-a", name: "隔离案件", organization: "o", dataScope: "s", applicant: "a", respondent: "r", issues: ["争点"], region: null, procedure: null, relevantDate: null, source: { id: "source-a", version: "1", locator: "/source-a", sha256: originalSha, kind: "PROJECT_AUTHORED" } }, materials: [], claims: [], findings: [], citations: [], reports: [], inputContributors: [], audits: [] };
}
function versions(head = 3): Version[] {
 return Array.from({ length: head }, (_, i) => ({ workVersion: i + 1, inputVersion: 1, snapshotSha256: snapshotSha, audit: { version: i + 1, action: "CONTEXT", actor: "a", objectId: "case-a", at: "2026-10-07T00:00:00Z" } }));
}
function receipt(key: string, changes: Partial<Receipt> = {}): Receipt {
 return { requestId: key, action: "CONTEXT", caseId: "case-a", workVersion: 4, inputVersion: 1, reportRevision: null, reportSha256: null, snapshotSha256: snapshotSha, ...changes };
}
function reads(head = 3, exact = head) {
 return async (path: string): Promise<unknown> => path.endsWith("/originals") ? [{ source: sample().caseInput.source, originalText }] : path.endsWith("/versions") ? versions(head) : sample(exact);
}
let get: (path: string) => Promise<unknown>;
let post: (path: string, body: unknown, key: string) => Promise<unknown>;
const writes = () => vi.mocked(request).mock.calls.filter(call => call[2] !== undefined);
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
beforeEach(() => {
 history.replaceState(null, "", "/app/adjudication?case=case-a");
 vi.mocked(digest).mockImplementation(async text => createHash("sha256").update(text, "utf8").digest("hex"));
 get = reads(); post = async (_path, _body, key) => receipt(key);
 vi.mocked(request).mockImplementation(async (_token, path, body, key) => (body === undefined ? get(path) : post(path, body, key!)) as never);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); });
async function open() {
 render(<AdjudicationWorkspace />);
 await waitFor(() => expect(screen.getByRole("button", { name: "进入 / 刷新案件" })).toBeEnabled());
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" }));
 await screen.findByRole("heading", { name: "案件正文版本3" });
 expect(screen.getByRole("button", { name: "发送原案件命令" })).toBeEnabled();
 expect(screen.getByRole("button", { name: "启动原案件Agent" })).toBeInTheDocument();
}
function expectWithdrawn() {
 expect(screen.queryByRole("heading", { name: "案件正文版本3" })).not.toBeInTheDocument();
 expect(screen.queryByRole("button", { name: "发送原案件命令" })).not.toBeInTheDocument();
 expect(screen.queryByRole("region", { name: "隔离测试Agent入口" })).not.toBeInTheDocument();
}
async function failAfterConfirmation(status: number) {
 post = async (_path, _body, key) => { get = async () => { throw new AdjudicationError(status, "READ_FAILED"); }; return receipt(key); };
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" }));
 await screen.findByRole("alert");
}
it.each([0, 404, 409, 503])("withdraws old forms and Agent after confirmed write then HTTP %s read failure", async status => {
 await open(); await failAfterConfirmation(status);
 expectWithdrawn();
 expect(screen.getByText(/服务器已确认：CONTEXT/)).toHaveTextContent("工作版本 4");
 expect(screen.getByRole("button", { name: "读取已确认版本" })).toBeEnabled();
 expect(screen.queryByRole("button", { name: "重试同一请求" })).not.toBeInTheDocument();
 expect(writes()).toHaveLength(1);
 expect(vi.mocked(request).mock.calls.some(call => call[1] === root + "/versions/4")).toBe(true);
});
it("withdraws old view before the confirmed exact GET settles", async () => {
 await open(); const gate = deferred<CaseView>();
 post = async (_path, _body, key) => { const normal = reads(4); get = path => path === root + "/versions/4" ? gate.promise : normal(path); return receipt(key); };
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" }));
 await screen.findByText(/服务器已确认：CONTEXT/);
 expectWithdrawn(); expect(writes()).toHaveLength(1);
 await act(async () => { gate.resolve(sample(4)); });
 await screen.findByRole("heading", { name: "案件正文版本4" });
 expect(screen.getByRole("button", { name: "发送原案件命令" })).toBeEnabled();
});
it.each([4, 5])("recovers only the confirmed GET with current head %s", async head => {
 await open(); await failAfterConfirmation(503); expectWithdrawn();
 const callsBeforeRecovery = vi.mocked(request).mock.calls.length;
 get = reads(head, 4);
 fireEvent.click(screen.getByRole("button", { name: "读取已确认版本" }));
 await screen.findByRole("heading", { name: "案件正文版本4" });
 expect(screen.queryByRole("alert")).not.toBeInTheDocument();
 expect(screen.getByText(/服务器已确认：CONTEXT/)).toBeInTheDocument();
 expect(writes()).toHaveLength(1);
 const recovered = vi.mocked(request).mock.calls.slice(callsBeforeRecovery);
 expect(recovered.map(call => call[1]).sort()).toEqual([root + "/versions/4", root + "/versions", root + "/originals"].sort());
 expect(recovered.every(call => call[2] === undefined)).toBe(true);
 if (head === 5) {
  expect(screen.getByText(/历史快照只读/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "发送原案件命令" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "启动原案件Agent" })).not.toBeInTheDocument();
 } else {
  expect(screen.getByRole("button", { name: "发送原案件命令" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "启动原案件Agent" })).toBeInTheDocument();
 }
});
it.each(["case", "key"])("keeps the original Packet for a wrong %s receipt, not a confirmed read", async wrong => {
 await open(); post = async (_path, _body, key) => receipt(key, wrong === "case" ? { caseId: "case-b" } : { requestId: "wrong-key" });
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" }));
 await screen.findByRole("alert");
 expect(screen.queryByText(/服务器已确认/)).not.toBeInTheDocument();
 expect(screen.getByRole("heading", { name: "案件正文版本3" })).toBeInTheDocument();
 expect(screen.getByRole("button", { name: "发送原案件命令" })).toBeDisabled();
 expect(screen.queryByRole("button", { name: "读取已确认版本" })).not.toBeInTheDocument();
 const first = writes()[0];
 post = async (_path, _body, key) => { get = reads(4); return receipt(key); };
 fireEvent.click(screen.getByRole("button", { name: "重试同一请求" }));
 await screen.findByRole("heading", { name: "案件正文版本4" });
 expect(writes()).toHaveLength(2);
 expect(writes()[1]).toEqual(first);
});
it("keeps a failed recovery withdrawn until a later GET succeeds", async () => {
 await open(); await failAfterConfirmation(0);
 fireEvent.click(screen.getByRole("button", { name: "读取已确认版本" }));
 await waitFor(() => expect(screen.getByRole("button", { name: "读取已确认版本" })).toBeEnabled());
 expectWithdrawn(); expect(writes()).toHaveLength(1);
 get = reads(4);
 fireEvent.click(screen.getByRole("button", { name: "读取已确认版本" }));
 await screen.findByRole("heading", { name: "案件正文版本4" });
 expect(writes()).toHaveLength(1);
});
