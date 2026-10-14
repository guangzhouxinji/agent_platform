import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Page from "./EnforcementDocumentRuleV2Page";
import { EnforcementAccessDenied } from "./enforcementNextV2Api";
// Real AntD forms validate inputs; only transport and heavy display widgets are isolated.
// All mock identities/data remain test-owned and are never product substitutes.
const api = vi.hoisted(() => ({ login: vi.fn(), identity: vi.fn(), request: vi.fn(), logout: vi.fn(), begin: vi.fn(), upload: vi.fn(), download: vi.fn(), finishes: [] as Array<(values: Record<string, unknown>) => void> }));
vi.mock("./enforcementNextV2Api", async original => ({
  ...await original<typeof import("./enforcementNextV2Api")>(),
  completeEnforcementLogin: api.login, readEnforcementIdentity: api.identity,
  enforcementRequest: api.request, endEnforcementLogin: api.logout,
  beginEnforcementLogin: api.begin, enforcementUpload: api.upload, enforcementDownload: api.download
}));
vi.mock("antd", async original => {
  const actual = await original<typeof import("antd")>();
  const TrackedForm = Object.assign((props: any) => { if (props.onFinish) api.finishes.push(props.onFinish); return <actual.Form {...props} />; }, actual.Form);
  return { ...actual, Form: TrackedForm,
    Table: ({ dataSource = [], rowKey, columns = [], onRow }: any) => <div>{dataSource.map((row: any, index: number) => <div key={index}>
      {onRow && <button aria-label={`row:${typeof rowKey === "function" ? rowKey(row) : row[rowKey]}`} onClick={onRow(row).onClick}>选择</button>}
      {columns.map((column: any, cell: number) => <span key={cell}>{column.render ? column.render(row[column.dataIndex], row) : String(row[column.dataIndex] ?? "")}</span>)}
    </div>)}</div>,
    Descriptions: ({ items }: any) => <dl>{items.map((item: any) => <div key={item.key}><dt>{item.label}</dt><dd>{item.children}</dd></div>)}</dl>
  };
});
const token = "test-only-session";
const identity = { issuer: "https://test-only-issuer", subject: "test-only-human", sessionId: "test-only-sid", grants: [{ resourceType: "DOCUMENT_RULE", organization: "TEST-ONLY-ORG" }] };
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function settle(action: () => void) { await act(async () => { action(); await Promise.resolve(); }); }
function fill(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }); }
async function setup() { const view = render(<Page />); await screen.findByLabelText("规则 ID"); return view; }
function expectLocked() {
  expect(screen.queryByLabelText("规则 ID")).not.toBeInTheDocument(); expect(screen.queryByDisplayValue("TEST-ONLY-ORG")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /退\s*出/ })).not.toBeInTheDocument();
}
const sha = "a".repeat(64);
function resetFixture() {}
function rule(revision = 2) { return { id: "test-only-rule", revision, organization: "TEST-ONLY-ORG", state: "DRAFT", templateId: "test-only-template", templateRevision: 2, templateSha256: sha, schemaId: "test-only-schema", schemaVersion: "1", mappings: [{ fieldId: "party", sourcePath: "case.party", schemaVersion: "1", mode: "DIRECT", missing: "BLOCK" }] }; }
const attempt = { id: "test-only-attempt", revision: 2, action: "trial", outcome: "FAILED", failureCode: "TEST-ONLY-GAP", completedAt: "2026-10-10T00:00:00Z" };
function response(path: string, _body?: any): any {
  if (path.endsWith("/history")) return [rule()]; if (path.endsWith("/attempts")) return [attempt];
  if (path.includes("?organization=")) return [rule()];
  if (/\/(validate|trial)$/.test(path)) return { ruleRevision: 2, complete: false, valid: false, reviewRequired: true, diagnostics: [{ fieldId: "party", sourcePath: "case.party", code: "TEST-ONLY-MISSING", blocking: true }], fields: [] };
  if (path.endsWith("publish") || path.endsWith("disable") || path === "/document-rules") return { requestId: "test-only-receipt", ruleId: "test-only-rule", revision: 2, operation: "test-only-operation" };
  return rule();
}
async function trigger() { fireEvent.click(screen.getByRole("button", { name: "刷新列表" })); }
async function readRule() {
  fill("已有规则 ID", "test-only-rule"); fill("版本", "2"); fireEvent.click(screen.getByRole("button", { name: "读取规则" }));
  await screen.findByText("规则版本"); await waitFor(() => expect(screen.getByRole("button", { name: "校验映射" })).not.toBeDisabled());
}
async function saveRule() {
  for (const [label, value] of [["规则 ID", "test-only-rule"], ["模板 ID", "test-only-template"], ["模板版本", "2"], ["模板 SHA", sha], ["来源 Schema ID", "test-only-schema"], ["来源 Schema 版本", "1"], ["模板字段", "party"], ["来源路径", "case.party"], ["路径 Schema 版本", "1"]]) fill(label, value);
  fireEvent.click(screen.getByRole("button", { name: "保存草稿版本" }));
}
beforeEach(() => {
  vi.resetAllMocks(); api.finishes = []; resetFixture(); api.login.mockResolvedValue(token); api.identity.mockResolvedValue(identity);
  api.logout.mockResolvedValue(undefined); api.begin.mockResolvedValue(undefined); api.request.mockImplementation((_token, path, body) => Promise.resolve(response(path, body)));
  api.upload.mockResolvedValue({}); api.download.mockResolvedValue(new Blob(["test-only-file"]));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
describe("EnforcementDocumentRuleV2Page final boundary", () => {
  it("rejects an old form-validation completion after logout before any request or error", async () => {
    await setup(); const finish = api.finishes.at(-1)!;
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await act(async () => { finish({ id: "old-input", expectedRevision: 2, mappings: [] }); });
    expectLocked(); expect(api.request).not.toHaveBeenCalled(); expect(api.upload).not.toHaveBeenCalled(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it("keeps callback-token work locked until matching identity resolves", async () => {
    const pending = deferred<typeof identity>(); api.identity.mockReturnValueOnce(pending.promise);
    render(<Page />); await screen.findByRole("button", { name: /退\s*出/ }); expect(screen.queryByLabelText("规则 ID")).not.toBeInTheDocument(); expect(api.request).not.toHaveBeenCalled();
    await settle(() => pending.resolve(identity)); await screen.findByLabelText("规则 ID");
  });
  it("does not use an unrelated grant as organization fallback", async () => {
    api.identity.mockResolvedValueOnce({ ...identity, grants: [{ resourceType: "UNRELATED", organization: "OTHER-ORG" }] });
    render(<Page />); await screen.findByText("身份上下文不可用"); expectLocked(); expect(api.request).not.toHaveBeenCalled();
  });
  it.each([401, 403])("clears current identity denial %s", async status => {
    api.identity.mockRejectedValueOnce(new EnforcementAccessDenied(status)); render(<Page />);
    await screen.findByRole("alert"); expectLocked(); expect(api.request).not.toHaveBeenCalled();
  });
  it("reports current callback failure without unlocking", async () => {
    api.login.mockRejectedValueOnce(new Error("current-login-error")); render(<Page />);
    await screen.findByText("current-login-error"); expectLocked(); expect(api.identity).not.toHaveBeenCalled();
  });
  it.each(["success", "failure"])("ignores callback %s after unmount without reading its identity", async outcome => {
    const pending = deferred<string | null>(); api.login.mockReturnValueOnce(pending.promise);
    const old = render(<Page />); old.unmount(); api.login.mockResolvedValueOnce(null); render(<Page />);
    await settle(() => outcome === "success" ? pending.resolve("old-session") : pending.reject(new Error("old-login-error")));
    expectLocked(); expect(api.identity).not.toHaveBeenCalled(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it.each(["success", "failure", "denied"])("discards identity %s at logout initiation", async outcome => {
    const pending = deferred<typeof identity>(), logout = deferred<void>(); api.identity.mockReturnValueOnce(pending.promise); api.logout.mockReturnValueOnce(logout.promise);
    render(<Page />); await screen.findByRole("button", { name: /退\s*出/ });
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked(); expect(screen.getByRole("button", { name: /登\s*录/ })).toBeDisabled();
    await settle(() => outcome === "success" ? pending.resolve(identity) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(403) : new Error("old-identity-error")));
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: /登\s*录/ })).toBeDisabled();
    await settle(() => logout.resolve()); expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
  });
  it.each(["success", "failure", "denied"])("discards identity %s after unmount", async outcome => {
    const pending = deferred<typeof identity>(); api.identity.mockReturnValueOnce(pending.promise);
    const old = render(<Page />); await screen.findByRole("button", { name: /退\s*出/ }); old.unmount(); api.login.mockResolvedValueOnce(null); render(<Page />);
    await settle(() => outcome === "success" ? pending.resolve(identity) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(401) : new Error("old-identity-error")));
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(api.request).not.toHaveBeenCalled();
  });
  it.each(["success", "failure"])("locks immediately while server logout is pending and stays locked on %s", async outcome => {
    await setup(); fill("规则 ID", "sensitive-input"); const pending = deferred<void>(); api.logout.mockReturnValueOnce(pending.promise);
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked(); expect(api.logout).toHaveBeenCalledExactlyOnceWith(token);
    fireEvent.click(screen.getByRole("button", { name: /登\s*录/ })); expect(api.begin).not.toHaveBeenCalled();
    await settle(() => outcome === "success" ? pending.resolve() : pending.reject(new Error("退出失败 (503)")));
    expectLocked(); expect(screen.queryByDisplayValue("sensitive-input")).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
    if (outcome === "failure") expect(screen.getByText("退出失败 (503)")).toBeInTheDocument(); expect(api.logout).toHaveBeenCalledTimes(1);
  });
  it("uses existing logout protocol including 401 completion", async () => {
    const actual = await vi.importActual<typeof import("./enforcementNextV2Api")>("./enforcementNextV2Api");
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 })); vi.stubGlobal("fetch", fetch); api.logout.mockImplementation(actual.endEnforcementLogin); await setup();
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled());
    expect(fetch).toHaveBeenCalledExactlyOnceWith("/v2/identity/logout", { method: "POST", headers: { Authorization: `Bearer ${token}` } });
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it.each(["success", "failure"])("ignores logout %s after unmount", async outcome => {
    const old = await setup(); const pending = deferred<void>(); api.logout.mockReturnValueOnce(pending.promise);
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); old.unmount(); api.login.mockResolvedValueOnce(null); render(<Page />);
    await settle(() => outcome === "success" ? pending.resolve() : pending.reject(new Error("old-logout-error")));
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
  });
  it.each([401, 403])("locks current business denial %s with no successor request", async status => {
    await setup(); api.request.mockRejectedValueOnce(new EnforcementAccessDenied(status)); await trigger();
    await screen.findByRole("alert"); expectLocked(); expect(api.request).toHaveBeenCalledTimes(1);
  });
  it("reports current non-auth failure without fabricating success", async () => {
    await setup(); api.request.mockRejectedValueOnce(new Error("current-request-error")); await trigger();
    await screen.findByText("current-request-error"); expect(screen.getByLabelText("规则 ID")).toBeInTheDocument();
  });
  it.each(["success", "failure", "denied"])("drops late business %s and finally without disturbing subsequent login", async outcome => {
    await setup(); const pending = deferred<unknown>(), login = deferred<void>(); api.request.mockReturnValueOnce(pending.promise);
    await trigger(); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked();
    await waitFor(() => expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled());
    api.begin.mockReturnValueOnce(login.promise); fireEvent.click(screen.getByRole("button", { name: /登\s*录/ }));
    await settle(() => outcome === "success" ? pending.resolve([rule()]) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(403) : new Error("old-operation-error")));
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(api.request).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: /登\s*录/ })).toBeDisabled(); await settle(() => login.resolve()); expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
  });
  it.each(["success", "failure", "denied"])("drops late business %s after unmount without touching a new authenticated page", async outcome => {
    const old = await setup(); const pending = deferred<unknown>(); api.request.mockReturnValueOnce(pending.promise); await trigger();
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(1)); old.unmount(); await setup();
    await settle(() => outcome === "success" ? pending.resolve([rule()]) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(401) : new Error("old-operation-error")));
    expect(screen.getByLabelText("规则 ID")).toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(api.request).toHaveBeenCalledTimes(1);
  });
  it("clears attempts, diagnostics, receipt and mapping/read inputs on logout", async () => {
    await setup(); await readRule(); fireEvent.click(screen.getByRole("button", { name: "校验映射" }));
    await screen.findByText("TEST-ONLY-MISSING"); fill("已有规则 ID", "sensitive-existing-id"); fill("规则 ID", "sensitive-rule-id");
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked();
    for (const text of ["test-only-attempt", "TEST-ONLY-MISSING", "sensitive-existing-id", "sensitive-rule-id"]) expect(screen.queryByText(text)).not.toBeInTheDocument();
  });
  it.each([0, 1, 2])("stops read/history/attempts at pending stage %s after logout", async stage => {
    await setup(); const pending = deferred<unknown>(); let index = 0; api.request.mockImplementation((_token, path, body) => index++ === stage ? pending.promise : Promise.resolve(response(path, body)));
    fill("已有规则 ID", "test-only-rule"); fill("版本", "2"); fireEvent.click(screen.getByRole("button", { name: "读取规则" }));
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1)); const call = api.request.mock.calls[stage];
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(response(call[1], call[2]))); expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1);
  });
  it.each([0, 1, 2])("locks current read-chain denial at stage %s without successors", async stage => {
    await setup(); let index = 0; api.request.mockImplementation((_token, path, body) => index++ === stage ? Promise.reject(new EnforcementAccessDenied(403)) : Promise.resolve(response(path, body)));
    fill("已有规则 ID", "test-only-rule"); fill("版本", "2"); fireEvent.click(screen.getByRole("button", { name: "读取规则" }));
    await screen.findByRole("alert"); expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1);
  });
  it.each([0, 1, 2, 3])("stops evaluate/history/attempts/list at pending stage %s", async stage => {
    await setup(); await readRule(); api.request.mockClear(); const pending = deferred<unknown>(); let index = 0;
    api.request.mockImplementation((_token, path, body) => index++ === stage ? pending.promise : Promise.resolve(response(path, body)));
    fireEvent.click(screen.getByRole("button", { name: "试生成" })); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1)); const call = api.request.mock.calls[stage];
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(response(call[1], call[2]))); expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1);
  });
  it.each(["failure", "denied"])("does not start recovery attempts for late evaluation %s", async outcome => {
    await setup(); await readRule(); api.request.mockClear(); const pending = deferred<unknown>(); api.request.mockReturnValueOnce(pending.promise);
    fireEvent.click(screen.getByRole("button", { name: "校验映射" })); fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await settle(() => pending.reject(outcome === "denied" ? new EnforcementAccessDenied(403) : new Error("old-evaluation-error")));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(1); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it.each(["success", "failure", "denied"])("discards recovery attempts %s after session clearing", async outcome => {
    await setup(); await readRule(); api.request.mockClear(); const pending = deferred<unknown>();
    api.request.mockRejectedValueOnce(new Error("current-evaluation-error")).mockReturnValueOnce(pending.promise);
    fireEvent.click(screen.getByRole("button", { name: "校验映射" })); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await settle(() => outcome === "success" ? pending.resolve([attempt]) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(403) : new Error("old-attempts-error")));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(2); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it("keeps current recovery-read denial authoritative", async () => {
    await setup(); await readRule(); api.request.mockClear(); api.request.mockRejectedValueOnce(new Error("current-evaluation-error")).mockRejectedValueOnce(new EnforcementAccessDenied(401));
    fireEvent.click(screen.getByRole("button", { name: "校验映射" })); await screen.findByRole("alert"); expectLocked(); expect(api.request).toHaveBeenCalledTimes(2);
  });
  it.each([0, 1, 2, 3, 4, 5, 6])("stops changed-revision evaluation at successor stage %s", async stage => {
    await setup(); await readRule(); api.request.mockClear(); const pending = deferred<unknown>(); let index = 0;
    api.request.mockImplementation((_token, path, body) => { const n = index++; if (n === stage) return pending.promise; if (n === 0) return Promise.resolve({ ruleRevision: 3, valid: false, reviewRequired: true, diagnostics: [] }); return Promise.resolve(response(path, body)); });
    fireEvent.click(screen.getByRole("button", { name: "校验映射" })); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1));
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(stage === 0 ? { ruleRevision: 3 } : response(api.request.mock.calls[stage][1])));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1);
  });
  it("preserves exact version/purpose for publish without inventing evidenceRef", async () => {
    await setup(); await readRule(); api.request.mockClear(); fireEvent.click(screen.getByRole("button", { name: /^发\s*布$/ }));
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(5));
    expect(api.request.mock.calls[0]).toEqual([token, "/document-rules/test-only-rule/publish", { expectedRevision: 2, purpose: "DOCUMENT_MAPPING" }, expect.any(String)]);
    expect(JSON.stringify(api.request.mock.calls)).not.toContain("evidenceRef");
  });
  it.each([0, 1, 2, 3, 4])("does not revive save/receipt/read/history/attempts stage %s after logout", async stage => {
    await setup(); const pending = deferred<unknown>(); let index = 0; api.request.mockImplementation((_token, path, body) => index++ === stage ? pending.promise : Promise.resolve(response(path, body)));
    await saveRule(); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1)); const call = api.request.mock.calls[stage];
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(response(call[1], call[2])));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1); expect(screen.queryByText(/test-only-receipt/)).not.toBeInTheDocument();
  });
});
