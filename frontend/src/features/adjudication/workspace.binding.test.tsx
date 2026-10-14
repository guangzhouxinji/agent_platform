import { createHash } from "node:crypto";
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import AdjudicationWorkspace from "./AdjudicationWorkspace";
import { AdjudicationError, digest, request } from "./api";
import type { CaseView, Receipt, Version } from "./contract";
vi.mock("./api", async () => ({ ...await vi.importActual<typeof import("./api")>("./api"), completeLogin: vi.fn().mockResolvedValue("session"), request: vi.fn(), digest: vi.fn(), forgetLogin: vi.fn() }));
vi.mock("./WorkArea", () => ({ default: ({ onDenied }: { onDenied: () => void }) => <button onClick={onDenied}>失权隔离</button> }));
vi.mock("./Forms", () => ({
 CaseForm: ({ command, create }: { command?: (suffix: string, body: Record<string, unknown>) => Promise<unknown>; create?: (body: unknown) => Promise<unknown> }) => <button onClick={() => void (command ? command("/context-revisions", { input: { id: "temporary-body-id" }, originalText: "原始正文" }) : create?.({ input: { id: "new-input" }, originalText: "创建正文" }))}>{command ? "发送原案件命令" : "提交创建"}</button>,
 CitationForm: () => null, ClaimForm: () => null, FindingForm: () => null, MaterialForm: () => null,
}));
vi.mock("./Reports", () => ({ default: ({ reports }: { reports: CaseView["reports"] }) => <div>{reports.map(v => <p key={v.report.revision}>{v.report.sections[0].content}</p>)}</div> }));
vi.mock("./Readers", () => ({
 CaseRead: ({ view }: { view: CaseView }) => <h2>{view.caseInput.name}</h2>, CitationsRead: () => null, ClaimsRead: () => null, FindingsRead: () => null, MaterialsRead: () => null,
 HistoryRead: ({ versions, onOpen }: { versions: Version[]; onOpen: (v: number) => void }) => <>{versions.map(v => <button key={v.workVersion} onClick={() => onOpen(v.workVersion)}>读取历史{v.workVersion}</button>)}</>,
}));
const originalText = "案件输入原文：连接器。\r\n保留 UTF8 与空格 ";
const originalSha = createHash("sha256").update(originalText, "utf8").digest("hex");
const originals = () => [{ source: sample().caseInput.source, originalText }];
const sha = "a".repeat(64), reportSha = "b".repeat(64);
function sample(id = "case-a", version = 3): CaseView { return { version, inputVersion: 1, caseInput: { id, name: "已绑定案件" + id, organization: "o", dataScope: "s", applicant: "a", respondent: "r", issues: ["争点"], region: null, procedure: null, relevantDate: null, source: { id: "s", version: "1", locator: "/s", sha256: originalSha, kind: "PROJECT_AUTHORED" } }, materials: [], claims: [], findings: [], citations: [], inputContributors: [], audits: [], reports: [{ current: true, stale: false, report: { revision: 1, inputVersion: 1, policyVersion: "p", author: "a", contributors: [], sections: [{ id: "analysis", content: "正常非空报告", sources: [] }], diagnostics: [], contentSha256: reportSha, reviewState: "DRAFT", reviews: [] } }] }; }
function versions(v = 3): Version[] { return Array.from({ length: v }, (_, i) => ({ workVersion: i + 1, inputVersion: 1, snapshotSha256: sha, audit: { version: i + 1, action: "CONTEXT", actor: "a", objectId: "case-a", at: "2026-10-07T00:00:00Z" } })); }
function result(key: string, changes: Partial<Receipt> = {}): Receipt { return { requestId: key, action: "CONTEXT", caseId: "case-a", workVersion: 4, inputVersion: 1, reportRevision: 1, reportSha256: reportSha, snapshotSha256: sha, ...changes }; }
let get: (path: string) => Promise<unknown>, post: (path: string, body: unknown, key: string) => Promise<unknown>;
beforeEach(() => {
 vi.mocked(digest).mockImplementation(async text => createHash("sha256").update(text, "utf8").digest("hex"));
 history.replaceState(null, "", "/app/adjudication?case=case-a");
 get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions() : sample();
 post = async (_path, _body, key) => result(key);
 vi.mocked(request).mockImplementation(async (_token, path, body, key) => (body === undefined ? await get(path) : await post(path, body, key!)) as never);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); });
async function open() { render(<AdjudicationWorkspace />); await waitFor(() => expect(screen.getByRole("button", { name: "进入 / 刷新案件" })).toBeEnabled()); fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByRole("heading", { name: "已绑定案件case-a" }); }
const writes = () => vi.mocked(request).mock.calls.filter(call => call[2] !== undefined);
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
it("loads a normal nonempty report", async () => { await open(); fireEvent.click(screen.getByRole("button", { name: "报告" })); expect(screen.getByText("正常非空报告")).toBeInTheDocument(); });
it.each(["cross-case", "old-work", "illegal-sha"])("rejects %s GET without displaying its body", async mode => {
 const bad = sample(mode === "cross-case" ? "case-b" : "case-a", mode === "old-work" ? 2 : 3); bad.caseInput.name = "不得展示错误正文"; if (mode === "illegal-sha") bad.reports[0].report.contentSha256 = "bad";
 get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions() : bad;
 render(<AdjudicationWorkspace />); await waitFor(() => expect(screen.getByRole("button", { name: "进入 / 刷新案件" })).toBeEnabled()); fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); expect(await screen.findByRole("alert")).toHaveTextContent("精确校验"); expect(screen.queryByText("不得展示错误正文")).not.toBeInTheDocument();
});
it("rejects a wrong historical version even though it equals current head", async () => { await open(); fireEvent.click(screen.getByRole("button", { name: "历史" })); fireEvent.click(screen.getByRole("button", { name: "读取历史2" })); expect(await screen.findByRole("alert")).toHaveTextContent("确切历史"); expect(screen.queryByText("历史快照只读", { exact: false })).not.toBeInTheDocument(); });
it.each([{ caseId: "case-b" }, { requestId: "wrong-key" }, { snapshotSha256: "bad" }, { reportSha256: "bad" }])("bad 200 retains original Packet for explicit same-key reconciliation: %j", async change => {
 await open(); post = async (_path, _body, key) => result(key, change);
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" })); expect(await screen.findByRole("alert")).toHaveTextContent("尚未确认"); expect(screen.queryByText(/服务器已确认/)).not.toBeInTheDocument(); expect(location.search).toBe("?case=case-a"); expect(vi.mocked(request).mock.calls.some(call => call[1].includes("case-b"))).toBe(false);
 const first = writes()[0]; expect(screen.getByText("原请求键：" + first[3])).toBeInTheDocument();
 post = async (_path, _body, key) => { get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(4) : sample("case-a", 4); return result(key); };
 fireEvent.click(screen.getByRole("button", { name: "重试同一请求" })); await screen.findByText(/服务器已确认/);
 expect(writes()).toHaveLength(2); expect(writes()[1][1]).toBe(first[1]); expect(writes()[1][2]).toBe(first[2]); expect(writes()[1][3]).toBe(first[3]); expect(screen.queryByRole("button", { name: "重试同一请求" })).not.toBeInTheDocument();
});
it("confirmed write followed by failed GET never restores the Packet or re-POSTs", async () => {
 await open(); post = async (_path, _body, key) => { get = async () => { throw new AdjudicationError(503, "READ_FAILED"); }; return result(key); };
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" })); await screen.findByRole("alert"); expect(screen.getByText(/服务器已确认/)).toBeInTheDocument(); expect(screen.getByText(/请仅刷新案件/)).toBeInTheDocument(); expect(screen.queryByRole("button", { name: "重试同一请求" })).not.toBeInTheDocument();
 get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(4) : sample("case-a", 4);
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByRole("heading", { name: "已绑定案件case-a" }); expect(writes()).toHaveLength(1);
});
it("known Case binds to path despite temporary body and entry-field identity", async () => {
 await open(); fireEvent.change(screen.getByRole("textbox", { name: "案件ID" }), { target: { value: "typed-other-case" } }); post = async (_path, _body, key) => { get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(4) : sample("case-a", 4); return result(key); };
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" })); await screen.findByText(/服务器已确认/); expect(writes()[0][1]).toContain("/case-a/"); expect(screen.getByRole("textbox", { name: "案件ID" })).toHaveValue("case-a");
});
it("create accepts an initially unknown Case without borrowing editable Case id", async () => {
 await open(); fireEvent.click(screen.getByRole("button", { name: "新建案件" })); post = async (_path, _body, key) => { get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(1) : sample("new-case", 1); return result(key, { action: "CREATE", caseId: "new-case", workVersion: 1, reportRevision: null, reportSha256: null }); };
 fireEvent.click(screen.getByRole("button", { name: "提交创建" })); await screen.findByRole("heading", { name: "已绑定案件new-case" }); expect(writes()[0][1]).toBe("/v2/adjudication/case-works"); expect(location.search).toBe("?case=new-case");
});
it("a stale receipt cannot confirm, restore pending, or navigate after denied epoch", async () => {
 await open(); const delayed = deferred<Receipt>(); post = async () => delayed.promise;
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" })); await waitFor(() => expect(writes()).toHaveLength(1));
 const key = writes()[0][3]!; fireEvent.click(screen.getByRole("button", { name: "失权隔离" }));
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByRole("heading", { name: "已绑定案件case-a" });
 await act(async () => { delayed.resolve(result(key, { caseId: "case-b" })); await delayed.promise; });
 expect(screen.getByRole("button", { name: "发送原案件命令" })).toBeEnabled(); expect(screen.queryByText(/服务器已确认/)).not.toBeInTheDocument(); expect(screen.queryByRole("button", { name: "重试同一请求" })).not.toBeInTheDocument(); expect(location.search).toBe("?case=case-a"); expect(vi.mocked(request).mock.calls.some(call => call[1].includes("case-b"))).toBe(false);
});
it("ignores late GET mismatch after newer epoch succeeds", async () => {
 await open(); const delayed = deferred<CaseView>(); let first = true;
 get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions() : first ? (first = false, delayed.promise) : sample();
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); fireEvent.click(screen.getByRole("button", { name: "失权隔离" })); fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByRole("heading", { name: "已绑定案件case-a" });
 await act(async () => { delayed.resolve(sample("case-b")); await delayed.promise; });
 expect(screen.getByRole("heading", { name: "已绑定案件case-a" })).toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
it("deterministic 409 remains a refusal, not an unknown receipt", async () => {
 await open(); post = async () => { throw new AdjudicationError(409, "WORK_VERSION_CONFLICT"); };
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" })); await screen.findByRole("alert"); expect(screen.queryByRole("button", { name: "重试同一请求" })).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: "发送原案件命令" })).toBeEnabled(); expect(writes()).toHaveLength(1);
});

it.each(["network", "wrong-case"])("confirmed receipt survives %s follow-up GET without claiming an unknown write", async mode => {
 await open(); post = async (_path, _body, key) => {
  get = async path => { if (mode === "network") throw new AdjudicationError(0, "NETWORK_UNCONFIRMED"); return path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(4) : sample("case-b", 4); };
  return result(key);
 };
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" })); await screen.findByRole("alert");
 expect(screen.getByText(/服务器已确认/)).toBeInTheDocument(); expect(screen.getByRole("alert")).toHaveTextContent("写入已确认"); expect(screen.getByRole("alert")).not.toHaveTextContent("命令结果尚未确认"); expect(screen.queryByRole("button", { name: "重试同一请求" })).not.toBeInTheDocument(); expect(writes()).toHaveLength(1); expect(location.search).toBe("?case=case-a");
});

it("valid write receipt followed by another current version hides that read and keeps the confirmed key", async () => {
 await open();
 get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(5) : sample("case-a",5);
 fireEvent.click(screen.getByRole("button",{name:"发送原案件命令"}));
 await screen.findByText(/写入已确认，后续读取失败/);
 expect(screen.queryByRole("heading",{name:"已绑定案件case-a"})).toBeNull();
 expect(screen.getByText(/服务器已确认/)).toBeTruthy();
 expect(screen.queryByText(/重试同一请求对账/)).toBeNull();
 expect(vi.mocked(request).mock.calls.filter(call => call[2] !== undefined)).toHaveLength(1);
});

it("reads the confirmed exact version when a later current head exists", async () => {
 await open(); get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(5) : path.endsWith("/versions/4") ? sample("case-a",4) : sample("case-a",5);
 fireEvent.click(screen.getByRole("button",{name:"发送原案件命令"})); await screen.findByText(/历史快照只读/);
 expect(screen.queryByRole("alert")).toBeNull(); expect(screen.getByText(/服务器已确认/)).toBeInTheDocument();
 expect(vi.mocked(request).mock.calls.some(call=>call[1].endsWith("/versions/4"))).toBe(true); expect(writes()).toHaveLength(1);
});
it("recovers a confirmed read failure through the receipt version without another write", async () => {
 await open(); post = async (_path,_body,key)=>{get=async()=>{throw new AdjudicationError(503,"READ_FAILED")};return result(key)};
 fireEvent.click(screen.getByRole("button",{name:"发送原案件命令"})); await screen.findByRole("alert");
 get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(5) : path.endsWith("/versions/4") ? sample("case-a",4) : sample("case-a",5);
 fireEvent.click(screen.getByRole("button",{name:"读取已确认版本"})); await screen.findByText(/历史快照只读/);
 expect(screen.queryByRole("alert")).toBeNull(); expect(writes()).toHaveLength(1);
});
it.each(["empty", "wrong-body", "missing-current", "duplicate", "invalid-history", "drifted-history"])("withdraws whole display on %s originals", async mode => {
 await open(); const good = originals()[0], historical = { source: { ...good.source, version: "old" }, originalText };
 const invalid = mode === "empty" ? [] : mode === "wrong-body" ? [{ ...good, originalText: "错误原件" }] : mode === "missing-current" ? [historical] : mode === "duplicate" ? [good, good] : mode === "invalid-history" ? [good, { ...historical, source: { ...historical.source, locator: " " } }] : [good, { ...historical, originalText: "损坏历史正文" }];
 get = async path => path.endsWith("/originals") ? invalid : path.endsWith("/versions") ? versions() : { ...sample(), caseInput: { ...sample().caseInput, name: "不得展示整组新案" } };
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByRole("alert");
 expect(screen.queryByRole("heading", { name: "不得展示整组新案" })).not.toBeInTheDocument(); expect(screen.queryByRole("heading", { name: "已绑定案件case-a" })).not.toBeInTheDocument(); expect(screen.queryByRole("button", { name: "发送原案件命令" })).not.toBeInTheDocument(); expect(screen.queryByRole("button", { name: "报告" })).not.toBeInTheDocument(); expect(writes()).toHaveLength(0);
});
it("allows legitimate historical originals alongside current input", async () => {
 const historicalText = "合法历史案件输入";
 get = async path => path.endsWith("/originals") ? [{ source: { ...sample().caseInput.source, id: "old", version: "old", sha256: createHash("sha256").update(historicalText).digest("hex") }, originalText: historicalText }, ...originals()] : path.endsWith("/versions") ? versions() : sample();
 await open(); fireEvent.click(screen.getByRole("button", { name: "报告" })); expect(screen.getByText("正常非空报告")).toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
it.each(["valid", "mismatch", "failure"])("isolates late %s digest after denial and newer case", async mode => {
 await open(); let resolve!: (value: string) => void, reject!: (reason: Error) => void;
 const delayed = new Promise<string>((yes,no) => { resolve=yes; reject=no; });
 vi.mocked(digest).mockImplementationOnce(() => delayed);
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await waitFor(() => expect(digest).toHaveBeenCalledTimes(2));
 fireEvent.click(screen.getByRole("button", { name: "失权隔离" })); fireEvent.change(screen.getByLabelText("案件ID"), { target: { value: "case-b" } });
 get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions() : sample("case-b");
 fireEvent.click(screen.getByRole("button", { name: "进入 / 刷新案件" })); await screen.findByRole("heading", { name: "已绑定案件case-b" });
 await act(async () => { if(mode === "failure") reject(new Error("摘要失败")); else resolve(mode === "valid" ? originalSha : "f".repeat(64)); await delayed.catch(() => undefined); });
 expect(screen.getByRole("heading", { name: "已绑定案件case-b" })).toBeInTheDocument(); expect(screen.queryByRole("heading", { name: "已绑定案件case-a" })).not.toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(location.search).toBe("?case=case-b");
});
it("preserves confirmed receipt on original mismatch and recovers by GET only", async () => {
 await open(); get = async path => path.endsWith("/originals") ? [] : path.endsWith("/versions") ? versions(4) : sample("case-a", 4);
 fireEvent.click(screen.getByRole("button", { name: "发送原案件命令" })); await screen.findByRole("alert");
 expect(screen.getByText(/服务器已确认/)).toBeInTheDocument(); expect(screen.queryByRole("button", { name: "重试同一请求" })).not.toBeInTheDocument(); expect(screen.queryByRole("heading", { name: "已绑定案件case-a" })).not.toBeInTheDocument();
 get = async path => path.endsWith("/originals") ? originals() : path.endsWith("/versions") ? versions(4) : sample("case-a", 4);
 fireEvent.click(screen.getByRole("button", { name: "读取已确认版本" })); await screen.findByRole("heading", { name: "已绑定案件case-a" }); expect(writes()).toHaveLength(1);
});
it("binds a historical view to its own CaseInput source while permitting a later source", async () => {
 await open(); const oldText="历史输入原件", oldSource={...sample().caseInput.source,version:"old",sha256:createHash("sha256").update(oldText).digest("hex")};
 get=async path=>path.endsWith("/originals")?[...originals(),{source:oldSource,originalText:oldText}]:path.endsWith("/versions")?versions():{...sample("case-a",1),caseInput:{...sample().caseInput,source:oldSource}};
 fireEvent.click(screen.getByRole("button",{name:"历史"}));fireEvent.click(screen.getByRole("button",{name:"读取历史1"}));await screen.findByText(/历史快照只读/);expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
it("digest infrastructure failure withdraws the refreshed group", async () => {
 await open();vi.mocked(digest).mockRejectedValueOnce(new Error("摘要不可用"));fireEvent.click(screen.getByRole("button",{name:"进入 / 刷新案件"}));await screen.findByRole("alert");expect(screen.queryByRole("heading",{name:"已绑定案件case-a"})).not.toBeInTheDocument();expect(screen.queryByRole("button",{name:"报告"})).not.toBeInTheDocument();
});
it.each(["lock","unmount"])("ignores a late original digest after %s", async mode=>{
 await open();const delayed=deferred<string>();vi.mocked(digest).mockImplementationOnce(()=>delayed.promise);fireEvent.click(screen.getByRole("button",{name:"进入 / 刷新案件"}));await waitFor(()=>expect(digest).toHaveBeenCalledTimes(2));
 if(mode==="lock") { fireEvent.click(screen.getByRole("button",{name:"失权隔离"}));fireEvent.click(screen.getByRole("button",{name:"锁定工作台"})); } else cleanup();
 await act(async()=>{delayed.resolve(originalSha);await delayed.promise});expect(screen.queryByRole("heading",{name:"已绑定案件case-a"})).not.toBeInTheDocument();expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
