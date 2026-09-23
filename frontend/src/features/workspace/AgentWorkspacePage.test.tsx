import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import AgentWorkspacePage from "./AgentWorkspacePage";

describe("AgentWorkspacePage", () => {
  it("offers three separated agents and a public resource entry", () => {
    render(<MemoryRouter><AgentWorkspacePage /></MemoryRouter>);

    expect(screen.getByRole("heading", { name: "智能体工作台", level: 1 })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "进入咨询智能体" })).toHaveAttribute("href", "/consultation");
    expect(screen.getByRole("link", { name: "进入执法智能体" })).toHaveAttribute("href", "/enforcement");
    expect(screen.getByRole("link", { name: "进入裁决智能体" })).toHaveAttribute("href", "/adjudication");
    expect(screen.getByRole("heading", { name: "公共资料" })).toBeInTheDocument();
  });
});
