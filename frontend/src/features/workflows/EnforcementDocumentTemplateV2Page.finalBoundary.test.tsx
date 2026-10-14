import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Page from "./EnforcementDocumentTemplateV2Page";
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
const identity = { issuer: "https://test-only-issuer", subject: "test-only-human", sessionId: "test-only-sid", grants: [{ resourceType: "DOCUMENT_TEMPLATE", organization: "TEST-ONLY-ORG" }] };
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function settle(action: () => void) { await act(async () => { action(); await Promise.resolve(); }); }
function fill(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }); }
async function setup() { const view = render(<Page />); await screen.findByLabelText("模板 ID"); return view; }
function expectLocked() {
  expect(screen.queryByLabelText("模板 ID")).not.toBeInTheDocument(); expect(screen.queryByDisplayValue("TEST-ONLY-ORG")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /退\s*出/ })).not.toBeInTheDocument();
}
const sha = "a".repeat(64);
let state: string, evidence: string | undefined;
function resetFixture() { state = "DRAFT"; evidence = undefined; }
function revision(id = "template-a") { return { templateId: id, currentRevision: 2, revision: 2, state, name: id, organization: "TEST-ONLY-ORG", stage: "TEST-ONLY-STAGE", scope: { organization: "TEST-ONLY-ORG", region: "TEST-ONLY-REGION", procedure: "TEST-ONLY-PROCEDURE" }, fields: [{ id: "party", type: "TEXT", required: true, repeat: false }], fontNames: ["SimSun"], fileName: "test-only-template.docx", fileSha256: sha, createdBy: "test-only-human", createdAt: "2026-10-10T00:00:00Z", transitionEvidenceRef: evidence }; }
function preview() { return { previewId: "test-only-preview", templateId: "template-a", revision: 2, rendererVersion: "test-only-renderer", diagnostics: [], artifactSha256: sha }; }
function response(path: string, body?: any): any {
  if (/\/(publish|disable)$/.test(path)) { state = path.endsWith("publish") ? "PUBLISHED" : "DISABLED"; evidence = body.evidenceRef; return { templateId: "template-a", revision: 2, state, fileSha256: sha }; }
  if (path.endsWith("/previews")) return preview();
  if (path.includes("?organization=")) return [revision(), revision("template-b")];
  return revision(path.includes("template-b") ? "template-b" : "template-a");
}
async function trigger() { fireEvent.click(screen.getByRole("button", { name: "刷新" })); }
async function loadTemplate() { await trigger(); await screen.findByRole("button", { name: "row:template-a" }); fireEvent.click(screen.getByRole("button", { name: "row:template-a" })); await screen.findByLabelText(state === "DRAFT" ? "发布证据引用" : "停用证据引用"); }
async function uploadDraft(container: HTMLElement) {
  for (const [label, value] of [["模板 ID", "test-only-upload"], ["模板名称", "test-only-name"], ["业务环节", "test-only-stage"], ["地区", "test-only-region"], ["程序", "test-only-procedure"], ["占位符 ID", "party"], ["字段类型", "TEXT"]]) fill(label, value);
  const file = new File(["test-only-docx-bytes"], "test-only-upload.docx", { type: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" });
  fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
  await screen.findByText("test-only-upload.docx"); fireEvent.click(screen.getByRole("button", { name: "保存模板草稿" }));
}
beforeEach(() => {
  vi.resetAllMocks(); api.finishes = []; resetFixture(); api.login.mockResolvedValue(token); api.identity.mockResolvedValue(identity);
  api.logout.mockResolvedValue(undefined); api.begin.mockResolvedValue(undefined); api.request.mockImplementation((_token, path, body) => Promise.resolve(response(path, body)));
  api.upload.mockResolvedValue({ templateId: "template-a", revision: 2, state: "DRAFT" }); api.download.mockResolvedValue(new Blob(["test-only-file"]));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
describe("EnforcementDocumentTemplateV2Page final boundary", () => {
  it("rejects an old form-validation completion after logout before any request or error", async () => {
    await setup(); const finish = api.finishes.at(-1)!;
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await act(async () => { finish({ templateId: "old-input", fields: [], fontNames: "SimSun" }); });
    expectLocked(); expect(api.request).not.toHaveBeenCalled(); expect(api.upload).not.toHaveBeenCalled(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it("keeps callback-token work locked until matching identity resolves", async () => {
    const pending = deferred<typeof identity>(); api.identity.mockReturnValueOnce(pending.promise);
    render(<Page />); await screen.findByRole("button", { name: /退\s*出/ }); expect(screen.queryByLabelText("模板 ID")).not.toBeInTheDocument(); expect(api.request).not.toHaveBeenCalled();
    await settle(() => pending.resolve(identity)); await screen.findByLabelText("模板 ID");
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
    await setup(); fill("模板 ID", "sensitive-input"); const pending = deferred<void>(); api.logout.mockReturnValueOnce(pending.promise);
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked(); expect(api.logout).toHaveBeenCalledExactlyOnceWith(token);
    fireEvent.click(screen.getByRole("button", { name: /登\s*录/ })); expect(api.begin).not.toHaveBeenCalled();
    await settle(() => outcome === "success" ? pending.resolve() : pending.reject(new Error("退出失败 (503)")));
    expectLocked(); expect(screen.queryByDisplayValue("sensitive-input")).not.toBeInTheDocument(); expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
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
    await screen.findByText("current-request-error"); expect(screen.getByLabelText("模板 ID")).toBeInTheDocument();
  });
  it.each(["success", "failure", "denied"])("drops late business %s and finally without disturbing subsequent login", async outcome => {
    await setup(); const pending = deferred<unknown>(), login = deferred<void>(); api.request.mockReturnValueOnce(pending.promise);
    await trigger(); await waitFor(() => expect(api.request).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked();
    await waitFor(() => expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled());
    api.begin.mockReturnValueOnce(login.promise); fireEvent.click(screen.getByRole("button", { name: /登\s*录/ }));
    await settle(() => outcome === "success" ? pending.resolve([revision()]) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(403) : new Error("old-operation-error")));
    expectLocked(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(api.request).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: /登\s*录/ })).toBeDisabled(); await settle(() => login.resolve()); expect(screen.getByRole("button", { name: /登\s*录/ })).not.toBeDisabled();
  });
  it.each(["success", "failure", "denied"])("drops late business %s after unmount without touching a new authenticated page", async outcome => {
    const old = await setup(); const pending = deferred<unknown>(); api.request.mockReturnValueOnce(pending.promise); await trigger();
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(1)); old.unmount(); await setup();
    await settle(() => outcome === "success" ? pending.resolve([revision()]) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(401) : new Error("old-operation-error")));
    expect(screen.getByLabelText("模板 ID")).toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument(); expect(api.request).toHaveBeenCalledTimes(1);
  });
  it.each(["publish", "disable"])("sends user's %s evidence and exact original version, not the historical constant", async action => {
    await setup(); if (action === "disable") state = "PUBLISHED"; await loadTemplate(); api.request.mockClear();
    const label = action === "publish" ? "发布" : "停用", ref = `test-only-human-evidence:${action}:20261010`;
    fill(`${label}证据引用`, ref); fireEvent.click(screen.getByRole("button", { name: new RegExp("^" + label.split("").join("\\s*") + "$") }));
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(3));
    expect(api.request.mock.calls[0]).toEqual([token, `/document-templates/template-a/revisions/2/${action}`, { expectedRevision: 2, evidenceRef: ref }, expect.any(String)]);
    await screen.findByText(ref); expect(screen.getByText("已记录转换证据引用")).toBeInTheDocument();
    expect(JSON.stringify(api.request.mock.calls)).not.toContain("PROJECT-FUNCTIONAL-VALIDATION");
  });
  it.each(["", "   "])("rejects missing/whitespace evidence %j without a FUNCTIONAL fallback", async value => {
    await setup(); await loadTemplate(); api.request.mockClear(); fill("发布证据引用", value);
    fireEvent.click(screen.getByRole("button", { name: /^发\s*布$/ })); await screen.findByText("请填写本次发布证据引用"); expect(api.request).not.toHaveBeenCalled();
  });
  it("clears evidence and preview input when another exact template is selected", async () => {
    await setup(); await loadTemplate(); fill("发布证据引用", "test-only-not-reusable-evidence"); fill("样例字段 JSON", '{"party":"sensitive"}');
    fireEvent.click(screen.getByRole("button", { name: "row:template-b" }));
    await waitFor(() => expect(screen.getByLabelText("发布证据引用")).toHaveValue("")); expect(screen.getByLabelText("样例字段 JSON")).toHaveValue("");
  });
  it.each([0, 1, 2])("stops publish successor chain at pending stage %s after logout", async stage => {
    await setup(); await loadTemplate(); api.request.mockClear(); const pending = deferred<unknown>(); let index = 0;
    api.request.mockImplementation((_token, path, body) => index++ === stage ? pending.promise : Promise.resolve(response(path, body)));
    fill("发布证据引用", "test-only-operation-evidence"); fireEvent.click(screen.getByRole("button", { name: /^发\s*布$/ }));
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(stage + 1)); const call = api.request.mock.calls[stage];
    fireEvent.click(screen.getByRole("button", { name: /退\s*出/ })); expectLocked();
    await settle(() => pending.resolve(response(call[1], call[2]))); expect(api.request).toHaveBeenCalledTimes(stage + 1); expectLocked();
  });
  it.each([401, 403])("clears current upload denial %s including the selected file", async status => {
    const { container } = await setup(); api.upload.mockRejectedValueOnce(new EnforcementAccessDenied(status)); await uploadDraft(container);
    await screen.findByRole("alert"); expectLocked(); expect(screen.queryByText("test-only-upload.docx")).not.toBeInTheDocument(); expect(api.request).not.toHaveBeenCalled();
  });
  it.each(["success", "failure", "denied"])("does not read or revive late upload %s", async outcome => {
    const { container } = await setup(); const pending = deferred<unknown>(); api.upload.mockReturnValueOnce(pending.promise); await uploadDraft(container);
    await waitFor(() => expect(api.upload).toHaveBeenCalledTimes(1)); fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await settle(() => outcome === "success" ? pending.resolve({ templateId: "template-a", revision: 2 }) : pending.reject(outcome === "denied" ? new EnforcementAccessDenied(403) : new Error("old-upload-error")));
    expectLocked(); expect(api.request).not.toHaveBeenCalled(); expect(screen.queryByText("test-only-upload.docx")).not.toBeInTheDocument(); expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it.each([401, 403])("clears current preview denial %s", async status => {
    await setup(); await loadTemplate(); api.request.mockClear(); api.request.mockRejectedValueOnce(new EnforcementAccessDenied(status));
    fill("样例字段 JSON", '{"party":"test-only-value"}'); fireEvent.click(screen.getByRole("button", { name: "生成样例预览" })); await screen.findByRole("alert"); expectLocked(); expect(api.request).toHaveBeenCalledTimes(1);
  });
  it("drops late preview without showing the artifact", async () => {
    await setup(); await loadTemplate(); const pending = deferred<unknown>(); api.request.mockReturnValueOnce(pending.promise);
    fill("样例字段 JSON", '{"party":"test-only-value"}'); fireEvent.click(screen.getByRole("button", { name: "生成样例预览" }));
    await waitFor(() => expect(api.request).toHaveBeenCalledTimes(3)); fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await settle(() => pending.resolve(preview())); expectLocked(); expect(screen.queryByText(/test-only-preview/)).not.toBeInTheDocument();
  });
  it.each(["original", "preview"])("does not create a browser download for a late %s file", async kind => {
    await setup(); await loadTemplate();
    if (kind === "preview") { fill("样例字段 JSON", '{}'); fireEvent.click(screen.getByRole("button", { name: "生成样例预览" })); await screen.findByText(/test-only-preview/); }
    const pending = deferred<Blob>(); api.download.mockReturnValueOnce(pending.promise);
    const create = vi.fn(); vi.stubGlobal("URL", Object.assign(URL, { createObjectURL: create, revokeObjectURL: vi.fn() }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    fireEvent.click(screen.getByRole("button", { name: kind === "original" ? "下载原版" : /^下\s*载$/ }));
    await waitFor(() => expect(api.download).toHaveBeenCalledTimes(1)); fireEvent.click(screen.getByRole("button", { name: /退\s*出/ }));
    await settle(() => pending.resolve(new Blob(["old-private-file"]))); expect(create).not.toHaveBeenCalled(); expect(click).not.toHaveBeenCalled(); expectLocked();
  });
  it.each([401, 403])("clears current file-download denial %s", async status => {
    await setup(); await loadTemplate(); api.download.mockRejectedValueOnce(new EnforcementAccessDenied(status));
    fireEvent.click(screen.getByRole("button", { name: "下载原版" })); await screen.findByRole("alert"); expectLocked();
  });
});
