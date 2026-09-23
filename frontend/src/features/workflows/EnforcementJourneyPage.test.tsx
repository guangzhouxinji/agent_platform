import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import EnforcementJourneyPage from "./EnforcementJourneyPage";

describe("EnforcementJourneyPage", () => {
  it("presents the four-stage journey with real existing entry points and no formal disposition action", () => {
    render(<MemoryRouter><EnforcementJourneyPage /></MemoryRouter>);

    expect(screen.getByRole("heading", { name: "执法工作台", level: 1 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "发现问题", level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "核实线索", level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "形成材料", level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "人工处置 / 移交", level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /进入线索发现/ })).toHaveAttribute("href", "/enforcement/discovery");
    expect(screen.getByRole("link", { name: /进入线索解析/ })).toHaveAttribute("href", "/enforcement/parsing");
    expect(screen.getByRole("link", { name: /进入证据材料/ })).toHaveAttribute("href", "/enforcement/evidence");
    expect(screen.getByText("当前不可用")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /立案|处罚|签发/ })).not.toBeInTheDocument();
  });
});
