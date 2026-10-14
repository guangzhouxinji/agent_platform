import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import Reports from "./Reports";
import ReportRevisionEditor from "./ReportRevisionEditor";
import type { ReportView } from "./contract";
vi.mock("./api", () => ({ digest: vi.fn() }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const report: ReportView["report"] = {
 revision: 2, inputVersion: 8, policyVersion: "policy", author: "author", contributors: ["author"],
 sections: [{ id: "case", content: "案件原正文\n第二行", sources: [] },
  { id: "analysis", content: "逐项分析原正文", sources: [] }],
 diagnostics: [], contentSha256: "b".repeat(64), reviewState: "DRAFT", reviews: []
};
const view = (revision: number): ReportView => ({ stale: false, current: revision === 2,
 report: { ...report, revision, contentSha256: String(revision).repeat(64) } });
function change(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }); }
function form() { return screen.getByRole("form", { name: "报告修订" }); }

it("完整原正文预填，各章节正文和原因切换保留，不自动调用API", () => {
 const save = vi.fn(); render(<ReportRevisionEditor report={report} onDirty={vi.fn()} save={save} />);
 expect(screen.getByLabelText("修订内容")).toHaveValue("案件原正文\n第二行");
 change("修订内容", "案件人工修订"); change("修订原因", "案件原因");
 change("修订章节", "analysis"); expect(screen.getByLabelText("修订内容")).toHaveValue("逐项分析原正文");
 change("修订内容", "人工分析"); change("修订原因", "分析原因");
 change("修订章节", "case"); expect(screen.getByLabelText("修订内容")).toHaveValue("案件人工修订");
 expect(screen.getByLabelText("修订原因")).toHaveValue("案件原因");
 expect(save).not.toHaveBeenCalled();
});
it("仅保存所选章节，绑定确切报告摘要；后端拒绝后全部输入保留", async () => {
 vi.spyOn(window, "confirm").mockReturnValue(true);
 const save = vi.fn().mockResolvedValue(false);
 render(<ReportRevisionEditor report={report} onDirty={vi.fn()} save={save} />);
 change("修订内容", "案件人工修订"); change("修订原因", "案件原因");
 change("修订章节", "analysis"); change("修订内容", "人工分析"); change("修订原因", "分析原因");
 fireEvent.submit(form()); await screen.findByText(/保存尚未确认/);
 expect(save).toHaveBeenCalledExactlyOnceWith({ reportSha256: report.contentSha256,
  sectionId: "analysis", content: "人工分析", reason: "分析原因" });
 expect(screen.getByLabelText("修订内容")).toHaveValue("人工分析");
 change("修订章节", "case"); expect(screen.getByLabelText("修订内容")).toHaveValue("案件人工修订");
});
it("异常或未知成功信号不清空编辑稿", async () => {
 const save = vi.fn().mockRejectedValue(new Error("connection lost"));
 render(<ReportRevisionEditor report={report} onDirty={vi.fn()} save={save} />);
 change("修订内容", "保留此内容"); change("修订原因", "原因");
 fireEvent.submit(form()); await screen.findByText(/保存失败，修订内容已保留/);
 expect(screen.getByLabelText("修订内容")).toHaveValue("保留此内容");
 save.mockResolvedValue(undefined); fireEvent.submit(form()); await screen.findByText(/保存尚未确认/);
 expect(screen.getByLabelText("修订原因")).toHaveValue("原因");
});
it("明确放弃需确认，取消不丢稿，确认恢复原文与清空原因", () => {
 const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
 render(<ReportRevisionEditor report={report} onDirty={vi.fn()} save={vi.fn()} />);
 change("修订内容", "人工修改"); change("修订原因", "原因");
 fireEvent.click(screen.getByRole("button", { name: "放弃未保存修订" }));
 expect(screen.getByLabelText("修订内容")).toHaveValue("人工修改");
 confirm.mockReturnValue(true); fireEvent.click(screen.getByRole("button", { name: "放弃未保存修订" }));
 expect(screen.getByLabelText("修订内容")).toHaveValue("案件原正文\n第二行");
 expect(screen.getByLabelText("修订原因")).toHaveValue("");
});
it("有稿才注册页面离开保护，卸载移除保护并清理dirty信号", () => {
 const onDirty = vi.fn(); const { unmount } = render(<ReportRevisionEditor report={report} onDirty={onDirty} save={vi.fn()} />);
 const clean = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(clean); expect(clean.defaultPrevented).toBe(false);
 change("修订内容", "未保存");
 const dirty = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(dirty); expect(dirty.defaultPrevented).toBe(true);
 unmount(); const after = new Event("beforeunload", { cancelable: true }); window.dispatchEvent(after);
 expect(after.defaultPrevented).toBe(false); expect(onDirty).toHaveBeenLastCalledWith(false);
});
it("未保存时取消切换报告或生成，不丢内容、不发新命令", () => {
 vi.spyOn(window, "confirm").mockReturnValue(false); const command = vi.fn();
 render(<Reports reports={[view(1),view(2)]} command={command} readOnly={false} />);
 change("修订内容", "报告二未保存");
 fireEvent.click(screen.getByRole("button", { name: /^报告 v1 ·/ }));
 expect(screen.getByLabelText("修订内容")).toHaveValue("报告二未保存");
 fireEvent.click(screen.getByRole("button", { name: "生成辅助报告" })); expect(command).not.toHaveBeenCalled();
});
it("修订历史选择的稿成功后，显示服务端最新稿而非继续选中旧稿", async () => {
 const command = vi.fn().mockResolvedValue(true);
 const { rerender } = render(<Reports reports={[view(1),view(2)]} command={command} readOnly={false} />);
 fireEvent.click(screen.getByRole("button", { name: /^报告 v1 ·/ }));
 change("修订内容", "人工修订"); change("修订原因", "原因"); fireEvent.submit(form());
 await waitFor(() => expect(command).toHaveBeenCalledWith("/reports/1/revisions", expect.objectContaining({reportSha256:"1".repeat(64)})));
 await waitFor(() => expect(screen.getByRole("button", { name: /^报告 v2 ·/ })).toHaveAttribute("aria-pressed", "true"));
 rerender(<Reports reports={[view(1),view(2),{...view(3),current:true}]} command={command} readOnly={false} />);
 expect(screen.getByRole("button", { name: /^报告 v3 ·/ })).toHaveAttribute("aria-pressed", "true");
});
it("保存处理中重复submit不会发送第二个修订请求", async () => {
 let resolve!: (ok:boolean)=>void;
 const save=vi.fn(()=>new Promise<boolean>(done=>{resolve=done}));
 render(<ReportRevisionEditor report={report} onDirty={vi.fn()} save={save}/>);
 change("修订原因","原因");fireEvent.submit(form());fireEvent.submit(form());expect(save).toHaveBeenCalledTimes(1);
 resolve(false);await screen.findByText(/保存尚未确认/);
});

it("多章节有稿时保存须显式确认，取消不丢弃其他章节", () => {
 vi.spyOn(window,"confirm").mockReturnValue(false);const save=vi.fn();
 render(<ReportRevisionEditor report={report} onDirty={vi.fn()} save={save}/>);
 change("修订内容","案件稿");change("修订原因","案件原因");change("修订章节","analysis");change("修订原因","分析原因");
 fireEvent.submit(form());expect(save).not.toHaveBeenCalled();change("修订章节","case");expect(screen.getByLabelText("修订内容")).toHaveValue("案件稿");
});
