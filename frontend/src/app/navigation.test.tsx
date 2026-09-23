import { describe, expect, it } from "vitest";
import { routesForWorkspace } from "./navigation";

describe("enforcement navigation", () => {
  it("orders workflow stages before secondary settings", () => {
    const routes = routesForWorkspace("enforcement").map(({ path, label, group }) => ({ path, label, group }));

    expect(routes).toEqual([
      { path: "/enforcement", label: "执法工作台", group: "发现问题" },
      { path: "/enforcement/discovery", label: "线索发现", group: "发现问题" },
      { path: "/enforcement/parsing", label: "线索解析", group: "核实线索" },
      { path: "/enforcement/evidence", label: "证据材料", group: "形成材料" },
      { path: "/enforcement/sources", label: "数据源配置", group: "设置" },
      { path: "/enforcement/rules", label: "线索规则配置", group: "设置" }
    ]);
  });
});
