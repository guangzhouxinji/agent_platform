import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Page from "./EnforcementLegalReferenceV2Page";
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
const identity = { issuer: "https://test-only-issuer", subject: "test-only-human", sessionId: "test-only-sid", grants: [{ resourceType: "LEGAL_REFERENCE", organization: "TEST-ONLY-ORG" }] };
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function settle(action: () => void) { await act(async () => { action(); await Promise.resolve(); }); }
function fill(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }); }
async function setup() { const view = render(<Page />); await screen.findByLabelText("已有引用 ID"); return view; }
function expectLocked() {
  expect(screen.queryByLabelText("已有引用 ID")).not.toBeInTheDocument();
  expect(screen.queryByDisplayValue("TEST-ONLY-ORG")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /退\s*出/ })).not.toBeInTheDocument();
}
function resetFixture() {}
const candidate = { candidateId: "test-only-candidate", sourceId: "test-only-source", sourceVersion: "2", articleId: "test-only-article", officialTitle: "test-only-title", locator: "test-only-locator", verbatimText: "test-only-verbatim", verbatimSha256: "a".repeat(64), explanation: "test-only-explanation", effectStatus: "UNKNOWN", transitionStatus: "UNKNOWN", gaps: ["test-only-gap"], reviewStatus: "PENDING" };
const reference = { ...candidate, referenceId: "test-only-reference", revision: 2, organization: "TEST-ONLY-ORG", searchId: "test-only-search", searchRevision: 2, targetType: "CONDUCT_REPORT", targetId: "test-only-target", targetRevision: 3, targetAnchor: "test-only-anchor", reviewState: "PENDING", bindingState: "ACTIVE", previousRevision: 1 };
const result = { searchId: "test-only-search", revision: 2, status: "PARTIAL", targetType: "CONDUCT_REPORT", targetId: "test-only-target", targetRevision: 3, targetAnchor: "test-only-anchor", scope: ["test-only-scope"], failedSources: ["test-only-unavailable-source"], members: [candidate] };
function response(path: string, body?: any): any {
  if (path.endsWith("/history")) return [reference];
  if (path.startsWith("/legal-basis/searches") && body === undefined) return result;
  if (body !== undefined) return { requestId: "test-only-receipt", operation: "test-only-operation", resultId: path === "/legal-basis/searches" ? "test-only-search" : "test-only-reference", version: 2 };
  return reference;
}
async function trigger() { fill("已有引用 ID", "test-only-reference"); fireEvent.click(screen.getByRole("button", { name: "读取已有引用与历史" })); }
async function loadReference() { await trigger(); await screen.findByText("test-only-verbatim"); await waitFor(() => expect(screen.getByRole("button", { name: "重新核对来源" })).not.toBeDisabled()); }
async function search() {
  for (const [label, value] of [["目标 ID", "test-only-target"], ["目标版本", "3"], ["目标锚点", "test-only-anchor"], ["案件 Ref", "test-only-case"], ["地区", "test-only-region"], ["事件时点", "2026-10-10T10:00"], ["检索事项", "test-only-query"]]) fill(label, value);
  fireEvent.click(screen.getByRole("button", { name: "检索候选" }));
}
async function loadSearch() { await search(); await screen.findByRole("button", { name: "row:test-only-candidate" }); fireEvent.click(screen.getByRole("button", { name: "row:test-only-candidate" })); await screen.findByText("test-only-verbatim"); }
beforeEach(() => {
  vi.resetAllMocks(); api.finishes = []; resetFixture(); api.login.mockResolvedValue(token); api.identity.mockResolvedValue(identity);
  api.logout.mockResolvedValue(undefined); api.begin.mockResolvedValue(undefined);
  api.request.mockImplementation((_token, path, body) => Promise.resolve(response(path, body)));
  api.upload.mockResolvedValue({}); api.download.mockResolvedValue(new Blob(["test-only-file"]));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
describe("EnforcementLegalReferenceV2Page final boundary", () => {
  it("rejects an old form-validation completion after logout before any request or error", async () => {
    await setup(); const finish = api.finishes.at(-1)!;
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await act(async () => { finish({ query: "old-input", eventAt: "2026-10-10T10:00", sourceRefs: "test-only-source" }); });
    expectLocked(); expect(api.request).not.toHaveBeenCalled(); expect(api.upload).not.toHaveBeenCalled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it("keeps callback-token work locked until matching identity resolves", async () => {
    const pending = deferred<typeof identity>(); api.identity.mockReturnValueOnce(pending.promise);
    render(<Page />); await screen.findByRole("button", { name: /退\s*出/ });
    expect(screen.queryByLabelText("已有引用 ID")).not.toBeInTheDocument(); expect(api.request).not.toHaveBeenCalled();
    await settle(() => pending.resolve(identity)); await screen.findByLabelText("已有引用 ID");
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
    const pending = deferred<typeof identity>(), logout = deferred<void>();
    api.identity.mockReturnValueOnce(pending.promise); api.logout.mockReturnValueOnce(logout.promise);
    render(<Page />); await screen.findByRole("button", { name: /退\s*出/ });
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked();
    expect(screen.getByRole("button", { name: /登\s*录/ })).toBeDisabled();
    await settle(() => outcome === "success" ? pending.resolve(identity) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(403) : new Error("old-identity-error")));
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /登\s*录/ })).toBeDisabled();
    await settle(() => logout.resolve()); expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
  });
  it.each(["success", "failure", "denied"])("discards identity %s after unmount", async outcome => {
    const pending = deferred<typeof identity>(); api.identity.mockReturnValueOnce(pending.promise);
    const old = render(<Page />); await screen.findByRole("button", { name: /退\s*出/ }); old.unmount();
    api.login.mockResolvedValueOnce(null); render(<Page />);
    await settle(() => outcome === "success" ? pending.resolve(identity) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(401) : new Error("old-identity-error")));
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(api.request).not.toHaveBeenCalled();
  });
  it.each(["success", "failure"])("locks immediately while server logout is pending and stays locked on %s", async outcome => {
    await setup(); fill("已有引用 ID", "sensitive-input"); const pending = deferred<void>(); api.logout.mockReturnValueOnce(pending.promise);
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked(); expect(api.logout).toHaveBeenCalledExactlyOnceWith(token);
    fireEvent.click(screen.getByRole("button", { name: /登\s*录/ })); expect(api.begin).not.toHaveBeenCalled();
    await settle(() => outcome === "success" ? pending.resolve() : pending.reject(new Error("退出失败 (503)")));
    expectLocked(); expect(screen.queryByDisplayValue("sensitive-input")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
    if (outcome === "failure") expect(screen.getByText("退出失败 (503)")).toBeInTheDocument(); expect(api.logout).toHaveBeenCalledTimes(1);
  });
  it("uses existing logout protocol including 401 completion", async () => {
    const actual = await vi.importActual<typeof import("./enforcementNextV2Api")>("./enforcementNextV2Api");
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 })); vi.stubGlobal("fetch", fetch);
    api.logout.mockImplementation(actual.endEnforcementLogin); await setup();
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
    await screen.findByText("current-request-error"); expect(screen.getByLabelText("已有引用 ID")).toBeInTheDocument();
  });
  it.each(["success", "failure", "denied"])("drops late business %s and finally without disturbing subsequent login", async outcome => {
    await setup(); const pending = deferred<unknown>(), login = deferred<void>(); api.request.mockReturnValueOnce(pending.promise);
    await trigger(); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked();
    await waitFor(() => expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled());
    api.begin.mockReturnValueOnce(login.promise); fireEvent.click(screen.getByRole("button", { name: /登\s*录/ }));
    await settle(() => outcome === "success" ? pending.resolve(reference) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(403) : new Error("old-operation-error")));
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(api.request).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: /登\s*录/ })).toBeDisabled();
    await settle(() => login.resolve()); expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
  });
  it.each(["success", "failure", "denied"])("drops late business %s after unmount without touching a new authenticated page", async outcome => {
    const old = await setup(); const pending = deferred<unknown>(); api.request.mockReturnValueOnce(pending.promise); await trigger();
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(1)); old.unmount(); await setup();
    await settle(() => outcome === "success" ? pending.resolve(reference) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(401) : new Error("old-operation-error")));
    expect(screen.getByLabelText("已有引用 ID")).toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(api.request).toHaveBeenCalledTimes(1);
  });
  it("clears candidate, verbatim, explanation, history and human inputs immediately on logout", async () => {
    await setup(); await loadReference(); fill("具名理由", "sensitive-human-reason"); fill("已有引用 ID", "sensitive-existing-reference");
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked();
    for (const text of ["test-only-verbatim", "test-only-explanation", "test-only-title", "test-only-reference"]) expect(screen.queryByText(text)).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue("sensitive-human-reason")).not.toBeInTheDocument();
  });
  it.each([0, 1])("stops reference/history read at stage %s", async stage => {
    await setup(); const pending = deferred<unknown>(); let index = 0;
    api.request.mockImplementation((_token, path, body) => index++ === stage ? pending.promise : Promise.resolve(response(path, body)));
    await trigger(); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1)); const call = api.request.mock.calls[stage];
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(response(call[1], call[2])));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1);
  });
  it.each([0, 1])("stops search/write-result stage %s without reviving candidates", async stage => {
    await setup(); const pending = deferred<unknown>(); let index = 0;
    api.request.mockImplementation((_token, path, body) => index++ === stage ? pending.promise : Promise.resolve(response(path, body)));
    await search(); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1)); const call = api.request.mock.calls[stage];
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(response(call[1], call[2])));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1); expect(screen.queryByText("test-only-title")).not.toBeInTheDocument();
  });
  it.each([0, 1, 2])("stops binding/reference/history stage %s and preserves exact target", async stage => {
    await setup(); await loadSearch(); api.request.mockClear(); const pending = deferred<unknown>(); let index = 0;
    api.request.mockImplementation((_token, path, body) => index++ === stage ? pending.promise : Promise.resolve(response(path, body)));
    fireEvent.click(screen.getByRole("button", { name: "绑定到确切位置" })); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1)); const call = api.request.mock.calls[stage];
    expect(api.request.mock.calls[0]).toEqual([token, "/legal-references", { searchId: result.searchId, searchRevision: 2, candidateId: candidate.candidateId, targetType: result.targetType, targetId: result.targetId, targetRevision: 3, targetAnchor: result.targetAnchor }, expect.any(String)]);
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(response(call[1], call[2])));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1);
  });
  it.each(["rechecks", "unbind", "reviews"])("preserves %s human action and stops late write before rereading", async action => {
    await setup(); await loadReference(); api.request.mockClear(); const pending = deferred<unknown>(); api.request.mockReturnValueOnce(pending.promise);
    if (action === "reviews") {
      fireEvent.mouseDown(screen.getByLabelText("人工复核")); fireEvent.click(await screen.findByText("确认引用")); fill("具名理由", "test-only-human-reason");
      fireEvent.click(screen.getByRole("button", { name: "提交复核版本" }));
    } else fireEvent.click(screen.getByRole("button", { name: action === "rechecks" ? "重新核对来源" : "解除绑定" }));
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(1));
    expect(api.request.mock.calls[0][1]).toBe(`/legal-references/test-only-reference/${action}`);
    expect(api.request.mock.calls[0][2]).toEqual(action === "reviews" ? { expectedRevision: 2, decision: "CONFIRMED", reason: "test-only-human-reason" } : { expectedRevision: 2, reason: action === "rechecks" ? "来源复核" : "人工解除绑定" });
    expect(JSON.stringify(api.request.mock.calls)).not.toContain("evidenceRef");
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(response(api.request.mock.calls[0][1], {})));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(1);
  });
  it.each([0, 1, 2])("stops recheck successor chain at stage %s", async stage => {
    await setup(); await loadReference(); api.request.mockClear(); const pending = deferred<unknown>(); let index = 0;
    api.request.mockImplementation((_token, path, body) => index++ === stage ? pending.promise : Promise.resolve(response(path, body)));
    fireEvent.click(screen.getByRole("button", { name: "重新核对来源" })); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1)); const call = api.request.mock.calls[stage];
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); await settle(() => pending.resolve(response(call[1], call[2])));
    expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1);
  });
  it.each([0, 1])("keeps current search denial at stage %s effective", async stage => {
    await setup(); let index = 0; api.request.mockImplementation((_token, path, body) => index++ === stage ? Promise.reject(new EnforcementAccessDenied(403)) : Promise.resolve(response(path, body)));
    await search(); await screen.findByRole("alert"); expectLocked(); expect(api.request).toHaveBeenCalledTimes(stage + 1);
  });
});
