import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppShell } from "./AppShell";

describe("AppShell", () => {
  it("shows only the navigation for the current workspace", () => {
    render(
      <MemoryRouter initialEntries={["/libraries/patents"]}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path="/libraries/patents" element={<div>专利内容</div>} />
          </Route>
        </Routes>
      </MemoryRouter>
    );

    expect(screen.getByRole("heading", { name: "专利库" })).toBeInTheDocument();
    expect(screen.getAllByText("检索分析").length).toBeGreaterThan(0);
    expect(screen.getAllByText("资料库").length).toBeGreaterThan(0);
    expect(screen.queryByText("咨询服务")).not.toBeInTheDocument();
    expect(screen.queryByText("执法业务")).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "返回智能体工作台" }).length).toBeGreaterThan(0);
    expect(screen.getByText("专利内容")).toBeInTheDocument();
  });
});
