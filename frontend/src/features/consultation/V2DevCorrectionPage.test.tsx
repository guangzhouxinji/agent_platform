import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import V2DevConsultationPage from "./V2DevConsultationPage";
import { CORRECTED_SUPPLEMENT, CORRECTION_MESSAGE, GOAL, SUPPLEMENT } from "./v2DevApi";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
it("submits the fixed correction and keeps old references historical", async () => {
  const id = "b845302b-8836-4a9c-962f-f0bf90b01827";
  const setId = "ad899101-cae1-4e1d-a842-7d03545aad23";
  let revision = 2;
  vi.stubGlobal("fetch", vi.fn(async (path: string, options: RequestInit) => {
    if (options.method === "POST" && path.endsWith("/corrections")) {
      expect(JSON.parse(String(options.body))).toEqual({ expectedVersion: 2, sourceRevision: 2,
        newText: CORRECTED_SUPPLEMENT, userMessage: CORRECTION_MESSAGE });
      expect((options.headers as Record<string, string>)["Idempotency-Key"]).toBeTruthy();
      revision = 3;
      return new Response(JSON.stringify({ revision: 3 }), { status: 200 });
    }
    if (path.endsWith("/current-work-state")) return new Response(JSON.stringify({ taskId: id,
      taskRevision: revision, taskState: "ACTIVE", resultSequence: 1, focusSequence: 0,
      featureRevision: 0, focus: null, feature: null,
      resultSet: { setId, inputRevision: 2, currentInput: revision === 2 } }), { status: 200 });
    if (path.endsWith("/human-requests/current")) return new Response(JSON.stringify(
      { requestSequence: 0, request: null }), { status: 200 });
    if (path.endsWith(setId)) return new Response(JSON.stringify({ setId, members: [] }), { status: 200 });
    return new Response(JSON.stringify({ taskId: id, state: "ACTIVE", revision,
      expiresAt: "2027-03-26T00:00:00Z",
      messages: [{ revision: 1, body: GOAL }, { revision: 2, body: SUPPLEMENT },
        ...(revision === 3 ? [{ revision: 3, body: CORRECTED_SUPPLEMENT }] : [])],
      corrections: revision === 3 ? [{ oldRevision: 2, newRevision: 3, utterance: CORRECTION_MESSAGE }] : [] }),
    { status: 200 });
  }));
  render(<MemoryRouter initialEntries={[`/consultation/v2-dev?task=${id}`]}>
    <Routes><Route path="/consultation/v2-dev" element={<V2DevConsultationPage />} /></Routes>
  </MemoryRouter>);
  fireEvent.change(screen.getByLabelText("开发身份凭据"), { target: { value: "Bearer xj_human-a.test" } });
  fireEvent.click(screen.getByRole("button", { name: "恢复任务" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "更正 F3" })).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: "更正 F3" }));
  fireEvent.click(screen.getByRole("button", { name: "确认更正" }));
  await waitFor(() => expect(screen.getByText("任务版本 3")).toBeInTheDocument());
  expect(screen.getByText(CORRECTION_MESSAGE)).toBeInTheDocument();
  expect(screen.getByText(CORRECTED_SUPPLEMENT)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "生成合成候选" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "确认三项原文特征" })).not.toBeInTheDocument();
});
