import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import EnforcementConductV2Page from "./EnforcementConductV2Page";
import EnforcementProcedureV2Page from "./EnforcementProcedureV2Page";
import { ConductFacts } from "./EnforcementConductV2Types";
import { EnforcementAccessDenied } from "./enforcementNextV2Api";

const api = vi.hoisted(() => ({
  complete: vi.fn(),
  request: vi.fn()
}));
vi.mock("./enforcementNextV2Api", async importOriginal => {
  const actual = await importOriginal<typeof import("./enforcementNextV2Api")>();
  return { ...actual, completeEnforcementLogin: api.complete, enforcementRequest: api.request };
});

beforeEach(() => {
  api.complete.mockReset().mockResolvedValue(null);
  api.request.mockReset();
});
afterEach(cleanup);

describe("next enforcement product workfaces", () => {
  it.each([
    ["程序逐项校验", EnforcementProcedureV2Page],
    ["行为逐要件分析", EnforcementConductV2Page]
  ])("keeps %s closed without a product session", async (heading, Page) => {
    render(<Page />);
    expect(await screen.findByText("当前没有产品会话")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: heading })).toBeInTheDocument();
    expect(api.request).not.toHaveBeenCalled();
  });

  it("clears conduct results on current-authority loss", async () => {
    api.complete.mockResolvedValue("current-token");
    api.request.mockRejectedValue(new EnforcementAccessDenied());
    render(<EnforcementConductV2Page />);
    const id = await screen.findByPlaceholderText("规则 ID");
    fireEvent.change(id, { target: { value: "RULE-1" } });
    fireEvent.change(screen.getByRole("spinbutton", { name: "规则版本" }), { target: { value: "1" } });
    fireEvent.click(screen.getByRole("button", { name: /读取/ }));
    expect(await screen.findByText("当前没有产品会话")).toBeInTheDocument();
    await waitFor(() => expect(api.request).toHaveBeenCalledWith("current-token", "/conduct-rules/RULE-1/versions/1", undefined, undefined));
  });

  it("keeps behavior selection and fact provenance explicit", async () => {
    api.complete.mockResolvedValue("current-token");
    render(<EnforcementConductV2Page />);
    fireEvent.click(await screen.findByRole("tab", { name: "案件辅助分析" }));
    expect(await screen.findByText("行为分支")).toBeInTheDocument();
    cleanup();
    render(<ConductFacts facts={[{ ref: "FACT-1", kind: "MACHINE_CANDIDATE",
      sourceRef: "EVIDENCE-1", locator: "line 3" }]} />);
    expect(screen.getByText("MACHINE_CANDIDATE")).toBeInTheDocument();
    expect(screen.getByText("EVIDENCE-1")).toBeInTheDocument();
  });
});
