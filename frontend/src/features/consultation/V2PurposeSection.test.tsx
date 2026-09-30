import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import V2PurposeSection from "./V2PurposeSection";

afterEach(cleanup);
describe("development purpose selection", () => {
  it("requires current exact input and an explicit action", () => {
    const select = vi.fn();
    const view = render(<V2PurposeSection ready={false} active busy={false} onSelect={select} />);
    expect(screen.getByRole("button", { name: "选择技术相似" })).toBeDisabled();
    view.rerender(<V2PurposeSection ready active busy={false} onSelect={select} />);
    fireEvent.click(screen.getByRole("button", { name: "选择技术相似" }));
    expect(select).toHaveBeenCalledTimes(1);
    expect(screen.getByText("未选择")).toBeInTheDocument();
  });

  it("shows only confirmed current selection and keeps source errors distinct", () => {
    const value = { taskId: "task", taskRevision: 2, purposeSequence: 1,
      analysisPurpose: "TECHNICAL_SIMILARITY" as const, featureRevision: 1, focusSequence: 1,
      setId: "set", ordinal: 1, actor: "H", receiptId: "receipt", currentInput: false };
    const view = render(<V2PurposeSection value={value} ready active busy={false} onSelect={vi.fn()} />);
    expect(screen.getByText("当前输入未选择")).toBeInTheDocument();
    view.rerender(<V2PurposeSection value={{ ...value, currentInput: true }} ready active busy={false}
      onSelect={vi.fn()} />);
    expect(screen.getByText("技术相似已选择")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "选择技术相似" })).not.toBeInTheDocument();
    view.rerender(<V2PurposeSection error="来源不可用" ready active busy={false} onSelect={vi.fn()} />);
    expect(screen.getByText("来源不可用")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择技术相似" })).toBeDisabled();
  });
});
