import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import V2DevConsultationPage from "./V2DevConsultationPage";
import { GOAL, SUPPLEMENT } from "./v2DevApi";
const id = "b845302b-8836-4a9c-962f-f0bf90b01827";
const task = { taskId: id, state: "ACTIVE", revision: 2, expiresAt: "2027-03-26T00:00:00Z",
  messages: [{ revision: 1, body: "合成目标" }, { revision: 2, body: "合成补充" }] };
const work = { taskId: id, taskRevision: 2, taskState: "ACTIVE", resultSequence: 1,
  focusSequence: 0, featureRevision: 0,
  resultSet: { setId: "ad899101-cae1-4e1d-a842-7d03545aad23", inputRevision: 2, currentInput: true },
  focus: null, feature: null };

function page() {
  return render(<MemoryRouter initialEntries={[`/consultation/v2-dev?task=${id}`]}>
    <Routes><Route path="/consultation/v2-dev" element={<V2DevConsultationPage />} /></Routes>
  </MemoryRouter>);
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
describe("V2 development consultation workbench", () => {
  it("accepts an exact pending package without reading the owner's task and retries with one key", async () => {
    const pending = { taskId: id, requestId: "19aca18d-1a79-45a4-b482-507a34c79062",
      requestSequence: 1, inputRevision: 2, createdAt: "2026-09-26T02:00:00Z" };
    const keys: string[] = [];
    let completed = false;
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      expect(path).not.toBe(`/v2/consultation/tasks/${id}`);
      if (path.endsWith("/pending")) return new Response(JSON.stringify(completed ? [] : [pending]), { status: 200 });
      expect(path).toBe(`/v2/consultation/tasks/${id}/human-requests/1/accept`);
      expect(JSON.parse(String(options.body))).toEqual({ expectedTaskRevision: 2,
        expectedRequestSequence: 1, requestId: pending.requestId });
      keys.push((options.headers as Record<string, string>)["Idempotency-Key"]);
      if (!completed) { completed = true; return new Response(JSON.stringify({ code: "DATABASE_UNAVAILABLE" }),
        { status: 503 }); }
      return new Response(JSON.stringify({ ...pending, acceptedBy: "dev-con-b",
        acceptedAt: "2026-09-26T03:00:00Z" }), { status: 200 });
    });
    vi.stubGlobal("fetch", fetchMock);
    page();
    fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-b.test" } });
    fireEvent.click(screen.getByRole("button", { name: "查看待领请求" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "领取" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "领取" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "重试领取" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "重试领取" }));
    await waitFor(() => expect(screen.getByText("本次请求已领取")).toBeInTheDocument());
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
    expect(screen.queryByRole("button", { name: "领取" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-a.test" } });
    expect(screen.queryByText("本次请求已领取")).not.toBeInTheDocument();
  });
  it("creates with the signed goal and appends only the signed supplement at the returned revision", async () => {
    let revision = 1;
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      if (options.method === "POST" && path === "/v2/consultation/tasks") {
        expect(JSON.parse(String(options.body))).toEqual({ goal: GOAL });
        expect((options.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
        return new Response(JSON.stringify({ taskId: id }), { status: 201 });
      }
      if (options.method === "POST" && path.endsWith("/messages")) {
        expect(JSON.parse(String(options.body))).toEqual({ message: SUPPLEMENT, expectedVersion: 1 });
        revision = 2;
        return new Response(JSON.stringify({ taskId: id, revision }), { status: 200 });
      }
      if (path.endsWith("/current-work-state")) return new Response(JSON.stringify({ ...work,
        taskRevision: revision, resultSequence: 0, resultSet: null }), { status: 200 });
      if (path.endsWith("/human-requests/current")) return new Response(JSON.stringify(
        { requestSequence: 0, request: null }), { status: 200 });
      if (path.endsWith("/analysis-purposes/current")) return new Response(JSON.stringify(
        { taskId: id, taskRevision: revision, purposeSequence: 0, analysisPurpose: "NOT_SELECTED",
          currentInput: true }), { status: 200 });
      return new Response(JSON.stringify({ ...task, revision,
        messages: revision === 1 ? [{ revision: 1, body: GOAL }]
          : [{ revision: 1, body: GOAL }, { revision: 2, body: SUPPLEMENT }] }), { status: 200 });
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<MemoryRouter initialEntries={["/consultation/v2-dev"]}>
      <Routes><Route path="/consultation/v2-dev" element={<V2DevConsultationPage />} /></Routes>
    </MemoryRouter>);
    fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-a.test" } });
    fireEvent.click(screen.getByRole("button", { name: "创建合成任务" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "提交补充" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "提交补充" }));
    await waitFor(() => expect(screen.getByText("任务版本 2")).toBeInTheDocument());
    expect(fetchMock).toHaveBeenCalledTimes(10);
  });
  it("requires a fresh in-memory credential and does not treat reference metadata as source content", async () => {
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      expect(options.headers).toMatchObject({ Authorization: "Bearer xj_human-a.test" });
      if (path.endsWith("/current-work-state")) return new Response(JSON.stringify(work), { status: 200 });
      if (path.endsWith("/human-requests/current")) return new Response(JSON.stringify(
        { code: "HUMAN_REQUEST_SOURCE_UNAVAILABLE" }), { status: 409 });
      if (path.endsWith("/analysis-purposes/current")) return new Response(JSON.stringify(
        { code: "SOURCE_UNAVAILABLE" }), { status: 409 });
      if (path.endsWith(work.resultSet.setId)) return new Response(JSON.stringify({ code: "SOURCE_UNAVAILABLE" }), { status: 409 });
      return new Response(JSON.stringify(task), { status: 200 });
    });
    vi.stubGlobal("fetch", fetchMock);
    const storage = vi.spyOn(Storage.prototype, "setItem");
    const view = page();
    expect(fetchMock).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-a.test" } });
    fireEvent.click(screen.getByRole("button", { name: "恢复任务" }));
    await waitFor(() => expect(screen.getAllByText("来源目前不可用，未展示原文。")).toHaveLength(2));
    expect(screen.getByText("任务版本 2")).toBeInTheDocument();
    expect(screen.getByText("人工请求来源目前不可用，未展示请求文字。")).toBeInTheDocument();
    expect(screen.queryByText("传感器、手动阀门")).not.toBeInTheDocument();
    expect(storage).not.toHaveBeenCalled();
    view.unmount();
    page();
    expect(screen.getByLabelText("开发身份凭据")).toHaveValue("");
    expect(fetchMock).toHaveBeenCalledTimes(5);
    storage.mockRestore();
  });
  it("keeps a cancelled task read-only after restoring it", async () => {
    vi.stubGlobal("fetch", vi.fn(async (path: string) => new Response(JSON.stringify(
      path.endsWith("/human-requests/current") ? { requestSequence: 0, request: null }
        : path.endsWith("/current-work-state")
          ? { ...work, taskRevision: 3, taskState: "CANCELLED", resultSet: null }
          : { ...task, revision: 3, state: "CANCELLED" }), { status: 200 })));
    page();
    fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human.test" } });
    fireEvent.click(screen.getByRole("button", { name: "恢复任务" }));
    await waitFor(() => expect(screen.getByText("已取消")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "生成合成候选" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "取消任务" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "请求人工帮助" })).not.toBeInTheDocument();
  });
  it("submits one explicit request and restores pending history without claiming acceptance", async () => {
    let sequence = 0;
    let revision = 2;
    const keys: string[] = [];
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      if (options.method === "POST" && path.endsWith("/human-requests")) {
        expect(JSON.parse(String(options.body))).toEqual({ expectedTaskRevision: revision,
          expectedRequestSequence: sequence, requestInputId: "REQUEST_GAP" });
        keys.push((options.headers as Record<string, string>)["Idempotency-Key"]);
        sequence++;
        return new Response(JSON.stringify({ requestSequence: sequence }), { status: 200 });
      }
      if (options.method === "POST" && path.endsWith("/messages")) {
        revision++;
        return new Response(JSON.stringify({ revision }), { status: 200 });
      }
      if (path.endsWith("/human-requests/current")) return new Response(JSON.stringify({ requestSequence: sequence,
        request: sequence ? { requestSequence: sequence, inputRevision: sequence === 1 ? 2 : 3,
          currentInput: sequence === 2 || revision === 2, acceptanceState: "NOT_ACCEPTED",
          createdAt: "2026-09-26T02:00:00Z", requestText: "请人工核对当前分析目的与证据缺口。" } : null }), { status: 200 });
      if (path.endsWith("/current-work-state")) return new Response(JSON.stringify({ ...work,
        taskRevision: revision, resultSet: null }), { status: 200 });
      return new Response(JSON.stringify({ ...task, revision,
        messages: revision === 2 ? task.messages : [...task.messages, { revision: 3, body: SUPPLEMENT }] }),
      { status: 200 });
    });
    vi.stubGlobal("fetch", fetchMock);
    page();
    fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-a.test" } });
    fireEvent.click(screen.getByRole("button", { name: "恢复任务" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "请求人工帮助" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "请求人工帮助" }));
    await waitFor(() => expect(screen.getByText("请求已保存，尚未受理")).toBeInTheDocument());
    expect(keys).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "请求人工帮助" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "提交补充" }));
    await waitFor(() => expect(screen.getByText("历史输入的请求，尚未受理")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "请求人工帮助" }));
    await waitFor(() => expect(screen.getByText("请求序号 2")).toBeInTheDocument());
    expect(keys).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "刷新" }));
    await waitFor(() => expect(screen.getByText("请求序号 2")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "断开" }));
    expect(screen.queryByText("请人工核对当前分析目的与证据缺口。")).not.toBeInTheDocument();
    expect(screen.getByLabelText("开发身份凭据")).toHaveValue("");
  });
  it("retries an uncertain request with the original idempotency key", async () => {
    const keys: string[] = [];
    let saved = false;
    vi.stubGlobal("fetch", vi.fn(async (path: string, options: RequestInit) => {
      if (options.method === "POST" && path.endsWith("/human-requests")) {
        keys.push((options.headers as Record<string, string>)["Idempotency-Key"]);
        if (!saved) { saved = true; return new Response(JSON.stringify({ code: "DATABASE_UNAVAILABLE" }), { status: 503 }); }
        return new Response(JSON.stringify({ requestSequence: 1 }), { status: 200 });
      }
      if (path.endsWith("/human-requests/current")) return new Response(JSON.stringify({ requestSequence: keys.length > 1 ? 1 : 0,
        request: keys.length > 1 ? { requestSequence: 1, inputRevision: 2, currentInput: true,
          acceptanceState: "NOT_ACCEPTED", createdAt: "2026-09-26T02:00:00Z", requestText: "固定请求" } : null }),
      { status: 200 });
      if (path.endsWith("/current-work-state")) return new Response(JSON.stringify({ ...work, resultSet: null }),
      { status: 200 });
      return new Response(JSON.stringify(task), { status: 200 });
    }));
    page();
    fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-a.test" } });
    fireEvent.click(screen.getByRole("button", { name: "恢复任务" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "请求人工帮助" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "请求人工帮助" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "重试提交" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "重试提交" }));
    await waitFor(() => expect(screen.getByText("请求已保存，尚未受理")).toBeInTheDocument());
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it("keeps an accepted request visible when the accept route is disabled", async () => {
    vi.stubGlobal("fetch", vi.fn(async (path: string) => new Response(JSON.stringify(
      path.endsWith("/human-requests/current") ? { requestSequence: 1,
        request: { requestSequence: 1, inputRevision: 2, currentInput: true,
          acceptanceState: "ACCEPTED", acceptedBy: "dev-con-b", acceptedAt: "2026-09-26T02:00:00Z",
          createdAt: "2026-09-26T01:00:00Z", requestText: "固定请求" } }
        : path.endsWith("/current-work-state") ? { ...work, resultSet: null } : task), { status: 200 })));
    page();
    fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-a.test" } });
    fireEvent.click(screen.getByRole("button", { name: "恢复任务" }));
    await waitFor(() => expect(screen.getByText("请求已领取")).toBeInTheDocument());
    expect(screen.getByText("领取人 dev-con-b")).toBeInTheDocument();
    expect(screen.queryByText("请求已保存，尚未受理")).not.toBeInTheDocument();
  });
});
