import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import V2DevConsultationPage from "./V2DevConsultationPage";

const id = "b845302b-8836-4a9c-962f-f0bf90b01827";
const task = { taskId: id, state: "ACTIVE", revision: 2, expiresAt: "2027-03-26T00:00:00Z",
  messages: [{ revision: 1, body: "合成目标" }, { revision: 2, body: "合成补充" }] };
const work = { taskId: id, taskRevision: 2, taskState: "ACTIVE", resultSequence: 0,
  focusSequence: 0, featureRevision: 0, resultSet: null, focus: null, feature: null };
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function connect() {
  render(<MemoryRouter initialEntries={[`/consultation/v2-dev?task=${id}`]}>
    <Routes><Route path="/consultation/v2-dev" element={<V2DevConsultationPage />} /></Routes>
  </MemoryRouter>);
  fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-a.test" } });
  fireEvent.click(screen.getByRole("button", { name: "恢复任务" }));
}

it("rejects an interleaved task/work version without publishing a verification view", async () => {
  vi.stubGlobal("fetch", vi.fn(async (path: string) => new Response(JSON.stringify(
    path.endsWith("/current-work-state") ? { ...work, taskRevision: 3 } : task), { status: 200 })));
  connect();
  await waitFor(() => expect(screen.getByText("任务状态已变化，请刷新核查。")).toBeInTheDocument());
  expect(screen.queryByRole("region", { name: "核查进度" })).not.toBeInTheDocument();
});

it("clears the previous verification view when authorization fails on refresh", async () => {
  let revoked = false;
  vi.stubGlobal("fetch", vi.fn(async (path: string) => revoked
    ? new Response(JSON.stringify({ code: "SOURCE_UNAVAILABLE" }), { status: 409 })
    : new Response(JSON.stringify(path.endsWith("/current-work-state") ? work
      : path.endsWith("/human-requests/current") ? { requestSequence: 0, request: null } : task), { status: 200 })));
  connect();
  await waitFor(() => expect(screen.getByRole("region", { name: "核查进度" })).toBeInTheDocument());
  revoked = true;
  fireEvent.click(screen.getByRole("button", { name: "刷新" }));
  await waitFor(() => expect(screen.queryByRole("region", { name: "核查进度" })).not.toBeInTheDocument());
  expect(screen.queryByText("合成目标")).not.toBeInTheDocument();
});
