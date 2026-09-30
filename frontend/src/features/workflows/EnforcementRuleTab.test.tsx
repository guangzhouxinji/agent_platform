import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import EnforcementRuleTab from "./EnforcementRuleTab";

describe("enforcement rule trial", () => {
  it("shows the source version and read-only outcome before publication", async () => {
    const request = vi.fn().mockResolvedValue({ ruleId: "rule-1", observationId: "observation-1",
      sourceVersion: "2", textSha256: "digest-2", outcome: "NO_MATCH" });
    render(<EnforcementRuleTab rule={{ id: "rule-1", revision: 1, state: "DRAFT",
      basisRef: "basis-1", sourceId: "source-1" }} setRule={() => {}} busy={false}
      request={request} run={action => action()} report={() => {}} />);
    fireEvent.change(screen.getByLabelText("观察 ID"), { target: { value: "observation-1" } });
    fireEvent.click(screen.getByRole("button", { name: /试\s*跑/ }));
    await waitFor(() => expect(screen.getByText("未命中 · 2")).toBeInTheDocument());
    expect(screen.getByText("digest-2")).toBeInTheDocument();
    expect(request).toHaveBeenCalledWith("/discovery-rules/rule-1/trial/observation-1");
  });
});
