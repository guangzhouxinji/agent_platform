import { describe, expect, it } from "vitest";
import { routesForWorkspace } from "./navigation";

describe("enforcement navigation", () => {
  it("orders workflow stages before secondary settings", () => {
    const routes = routesForWorkspace("enforcement").map(({ path, label, group }) => ({ path, label, group }));

    expect(routes).toEqual([
      { path: "/enforcement", label: "执法工作台", group: "发现问题" },
      { path: "/enforcement/discovery", label: "线索发现", group: "发现问题" },
      { path: "/enforcement/parsing", label: "线索解析", group: "核实线索" },
      { path: "/enforcement/material", label: "线索与材料 V2", group: "核实线索" },
      { path: "/enforcement/evidence", label: "证据整理 V2", group: "形成材料" },
      { path: "/enforcement/handoff", label: "辅助包与回流 V2", group: "形成材料" },
      { path: "/enforcement/procedure", label: "程序逐项校验 V2", group: "辅助分析" },
      { path: "/enforcement/conduct", label: "行为逐要件分析 V2", group: "辅助分析" },
      { path: "/enforcement/legal-references", label: "法律依据追溯 V2", group: "辅助分析" },
      { path: "/enforcement/document-rules", label: "文书字段映射 V2", group: "辅助分析" },
      { path: "/enforcement/penalty", label: "处罚规则与建议 V2", group: "辅助分析" },
      { path: "/enforcement/document-templates", label: "文书模板 V2", group: "文书" },
      { path: "/enforcement/document-drafts", label: "文书草稿 V2", group: "文书" },
      { path: "/enforcement/job-pack", label: "真实任务包采集", group: "真实集成" },
      { path: "/enforcement/rules", label: "线索规则配置", group: "设置" }
    ]);
  });
});

describe("consultation navigation", () => {
  it("contains no development-only workflow", () => {
    const paths = routesForWorkspace("consultation").map(({ path }) => path);
    expect(paths).not.toContain("/consultation/v2-dev");
    expect(paths).not.toContain("/consultation/patent-fixture");
  });
});
