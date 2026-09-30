import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import V2HumanAcceptanceSection from "./V2HumanAcceptanceSection";
import { loadHumanOpinion } from "./v2HumanOpinionHistory";
import { loadHumanReturn } from "./v2HumanReturnHistory";
import V2HumanRequestSection from "./V2HumanRequestSection";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it("restores only the accepted package and submits fixed opinion with one retry key", async () => {
  const item = { taskId: "b845302b-8836-4a9c-962f-f0bf90b01827",
    requestId: "19aca18d-1a79-45a4-b482-507a34c79062", requestSequence: 1,
    inputRevision: 2, acceptedAt: "2026-09-26T02:00:00Z" };
  const keys: string[] = [];
  let submitted = false;
  const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
    expect(path).not.toBe(`/v2/consultation/tasks/${item.taskId}`);
    if (path.endsWith("/pending")) return new Response("[]", { status: 200 });
    if (path.endsWith("/mine")) return new Response(JSON.stringify(submitted ? [] : [item]), { status: 200 });
    expect(path).toBe(`/v2/consultation/tasks/${item.taskId}/human-requests/1/opinion`);
    expect(JSON.parse(String(options.body))).toEqual({ expectedTaskRevision: 2,
      expectedRequestSequence: 1, requestId: item.requestId, opinionInputId: "OPINION_GAP" });
    keys.push((options.headers as Record<string, string>)["Idempotency-Key"]);
    if (!submitted) { submitted = true; return new Response('{"code":"DATABASE_UNAVAILABLE"}', { status: 503 }); }
    return new Response(JSON.stringify({ ...item, opinionId: "78d0f792-c90d-4713-a809-36ba2ef2a522",
      submittedBy: "dev-con-b", submittedAt: "2026-09-26T03:00:00Z", opinionText: "固定内部意见" }), { status: 200 });
  });
  vi.stubGlobal("fetch", fetchMock);
  const view = render(<V2HumanAcceptanceSection token="Bearer test" onUnauthorized={vi.fn()} />);
  await waitFor(() => expect(screen.getByRole("button", { name: "提交内部意见" })).toBeInTheDocument());
  view.unmount();
  render(<V2HumanAcceptanceSection token="Bearer test" onUnauthorized={vi.fn()} />);
  await waitFor(() => expect(screen.getByRole("button", { name: "提交内部意见" })).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: "提交内部意见" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "重试意见" })).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: "重试意见" }));
  await waitFor(() => expect(screen.getByText("本次内部意见已提交")).toBeInTheDocument());
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect(screen.queryByRole("button", { name: "提交内部意见" })).not.toBeInTheDocument();
});

it("does not turn an inaccessible owned queue into an empty queue", async () => {
  vi.stubGlobal("fetch", vi.fn(async (path: string) => new Response(
    path.endsWith("/mine") ? '{"code":"HUMAN_OPINION_SOURCE_UNAVAILABLE"}' : "[]",
    { status: path.endsWith("/mine") ? 409 : 200 })));
  render(<V2HumanAcceptanceSection token="Bearer test" onUnauthorized={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("已领请求未确认，请检查当前身份和来源后重试。")).toBeInTheDocument());
  expect(screen.queryByText("当前没有待处理的已领请求")).not.toBeInTheDocument();
});

it("reads only the exact accepted package and clears its text on refresh", async () => {
  const item = { taskId: "b845302b-8836-4a9c-962f-f0bf90b01827",
    requestId: "19aca18d-1a79-45a4-b482-507a34c79062", requestSequence: 1,
    inputRevision: 2, acceptedAt: "2026-09-26T02:00:00Z" };
  const fetchMock = vi.fn(async (path: string) => {
    if (path.endsWith("/pending")) return new Response("[]", { status: 200 });
    if (path.endsWith("/mine")) return new Response(JSON.stringify([item]), { status: 200 });
    expect(path).toBe(`/v2/consultation/tasks/${item.taskId}/human-requests/1/context`);
    return new Response(JSON.stringify({ ...item, messages: [{ revision: 1, kind: "GOAL", text: "目标原文" }],
      requestText: "请求原文" }), { status: 200 });
  });
  vi.stubGlobal("fetch", fetchMock);
  render(<V2HumanAcceptanceSection token="Bearer test" onUnauthorized={vi.fn()} />);
  await waitFor(() => expect(screen.getByRole("button", { name: "查看上下文" })).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: "查看上下文" }));
  await waitFor(() => expect(screen.getByText("目标原文")).toBeInTheDocument());
  expect(screen.getByText("请求原文")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "刷新" }));
  await waitFor(() => expect(screen.queryByText("目标原文")).not.toBeInTheDocument());
  expect(fetchMock.mock.calls.some(([path]) => path === `/v2/consultation/tasks/${item.taskId}`)).toBe(false);
});

it("never retains package text after a failed re-read", async () => {
  const item = { taskId: "b845302b-8836-4a9c-962f-f0bf90b01827",
    requestId: "19aca18d-1a79-45a4-b482-507a34c79062", requestSequence: 1,
    inputRevision: 2, acceptedAt: "2026-09-26T02:00:00Z" };
  let first = true;
  vi.stubGlobal("fetch", vi.fn(async (path: string) => {
    if (path.endsWith("/pending")) return new Response("[]", { status: 200 });
    if (path.endsWith("/mine")) return new Response(JSON.stringify([item]), { status: 200 });
    if (first) { first = false; return new Response(JSON.stringify({ ...item,
      messages: [{ revision: 1, kind: "GOAL", text: "目标原文" }], requestText: "请求原文" }), { status: 200 }); }
    return new Response('{"code":"SOURCE_UNAVAILABLE"}', { status: 409 });
  }));
  render(<V2HumanAcceptanceSection token="Bearer test" onUnauthorized={vi.fn()} />);
  const button = await screen.findByRole("button", { name: "查看上下文" });
  fireEvent.click(button);
  await waitFor(() => expect(screen.getByText("目标原文")).toBeInTheDocument());
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
  await waitFor(() => expect(screen.getByText("上下文未获准读取，请检查当前包、身份和来源后重试。"))
    .toBeInTheDocument());
  expect(screen.queryByText("目标原文")).not.toBeInTheDocument();
});

it("clears the session on an expired owned-queue credential", async () => {
  const onUnauthorized = vi.fn();
  vi.stubGlobal("fetch", vi.fn(async (path: string) => new Response(
    path.endsWith("/mine") ? '{"code":"ACCESS_DENIED"}' : "[]",
    { status: path.endsWith("/mine") ? 401 : 200 })));
  render(<V2HumanAcceptanceSection token="Bearer test" onUnauthorized={onUnauthorized} />);
  await waitFor(() => expect(onUnauthorized).toHaveBeenCalledTimes(1));
});

it("separates an absent opinion from a source failure for the owner", async () => {
  const human = { requestSequence: 1, request: { requestSequence: 1,
    acceptanceState: "ACCEPTED" as const } };
  vi.stubGlobal("fetch", vi.fn(async () => new Response('{"code":"TASK_NOT_FOUND"}', { status: 404 })));
  expect(await loadHumanOpinion("Bearer test", "b845302b-8836-4a9c-962f-f0bf90b01827",
    human as Parameters<typeof loadHumanOpinion>[2])).toEqual({ opinionMissing: true });
  vi.stubGlobal("fetch", vi.fn(async () => new Response(
    '{"code":"HUMAN_OPINION_SOURCE_UNAVAILABLE"}', { status: 409 })));
  expect((await loadHumanOpinion("Bearer test", "b845302b-8836-4a9c-962f-f0bf90b01827",
    human as Parameters<typeof loadHumanOpinion>[2])).opinionError).toContain("409");
});

it("returns the exact owned package with the same key after an uncertain response", async () => {
  const item = { taskId: "b845302b-8836-4a9c-962f-f0bf90b01827",
    requestId: "19aca18d-1a79-45a4-b482-507a34c79062", requestSequence: 1,
    inputRevision: 2, acceptedAt: "2026-09-26T02:00:00Z" };
  const keys: string[] = [];
  let attempted = false;
  vi.stubGlobal("fetch", vi.fn(async (path: string, options: RequestInit) => {
    if (path.endsWith("/pending")) return new Response("[]", { status: 200 });
    if (path.endsWith("/mine")) return new Response(JSON.stringify(attempted ? [] : [item]), { status: 200 });
    expect(path).toBe(`/v2/consultation/tasks/${item.taskId}/human-requests/1/return`);
    expect(JSON.parse(String(options.body))).toEqual({ expectedTaskRevision: 2,
      expectedRequestSequence: 1, requestId: item.requestId });
    keys.push((options.headers as Record<string, string>)["Idempotency-Key"]);
    if (!attempted) { attempted = true; return new Response('{"code":"DATABASE_UNAVAILABLE"}', { status: 503 }); }
    return new Response(JSON.stringify({ ...item, returnId: "78d0f792-c90d-4713-a809-36ba2ef2a522",
      returnedBy: "dev-con-b", returnedAt: "2026-09-26T03:00:00Z", currentInput: true }), { status: 200 });
  }));
  render(<V2HumanAcceptanceSection token="Bearer test" onUnauthorized={vi.fn()} />);
  await waitFor(() => expect(screen.getByRole("button", { name: "退回" })).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: "退回" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "重试退回" })).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: "重试退回" }));
  await waitFor(() => expect(screen.getByText("本次请求已退回")).toBeInTheDocument());
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect(screen.queryByRole("button", { name: "提交内部意见" })).not.toBeInTheDocument();
});

it("keeps missing return distinct from a source failure and allows explicit new request", async () => {
  const human = { requestSequence: 1, request: { requestSequence: 1,
    acceptanceState: "ACCEPTED" as const } };
  vi.stubGlobal("fetch", vi.fn(async () => new Response('{"code":"TASK_NOT_FOUND"}', { status: 404 })));
  expect(await loadHumanReturn("Bearer test", "b845302b-8836-4a9c-962f-f0bf90b01827",
    human as Parameters<typeof loadHumanReturn>[2])).toEqual({ returnMissing: true });
  vi.stubGlobal("fetch", vi.fn(async () => new Response(
    '{"code":"HUMAN_REQUEST_SOURCE_UNAVAILABLE"}', { status: 409 })));
  expect((await loadHumanReturn("Bearer test", "b845302b-8836-4a9c-962f-f0bf90b01827",
    human as Parameters<typeof loadHumanReturn>[2])).returnError).toContain("来源目前不可用");
  const onRequest = vi.fn();
  render(<V2HumanRequestSection current={{ requestSequence: 1, request: {
    taskId: "b845302b-8836-4a9c-962f-f0bf90b01827", requestId: "19aca18d-1a79-45a4-b482-507a34c79062",
    requestSequence: 1, inputRevision: 2, requesterRef: "dev-con-a", requestInputId: "REQUEST_GAP",
    requestText: "固定请求", createdAt: "2026-09-26T02:00:00Z", currentInput: true,
    taskState: "ACTIVE", acceptanceState: "ACCEPTED", acceptedBy: "dev-con-b",
    acceptedAt: "2026-09-26T02:10:00Z" } }} active busy={false} disabled={false}
    returned={{ taskId: "b845302b-8836-4a9c-962f-f0bf90b01827",
      requestId: "19aca18d-1a79-45a4-b482-507a34c79062", requestSequence: 1,
      inputRevision: 2, returnId: "78d0f792-c90d-4713-a809-36ba2ef2a522",
      returnedBy: "dev-con-b", returnedAt: "2026-09-26T03:00:00Z", currentInput: true }}
    onRequest={onRequest} />);
  fireEvent.click(screen.getByRole("button", { name: "重新请求人工帮助" }));
  expect(onRequest).toHaveBeenCalledWith(1);
});
