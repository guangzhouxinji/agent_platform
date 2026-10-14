import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import EnforcementJobPackV2Page from "./EnforcementJobPackV2Page";

const api = vi.hoisted(() => ({
  complete: vi.fn(),
  logout: vi.fn(),
  identity: vi.fn(),
  request: vi.fn(),
  upload: vi.fn()
}));

vi.mock("./enforcementNextV2Api", async importOriginal => {
  const actual = await importOriginal<typeof import("./enforcementNextV2Api")>();
  return { ...actual, completeEnforcementLogin: api.complete, endEnforcementLogin: api.logout, enforcementRequest: api.request,
    readEnforcementIdentity: api.identity, enforcementUpload: api.upload };
});

const snapshot = {
  id: "ENF-JOB-REAL-01",
  revision: 3,
  contentRevision: 1,
  organization: "ORG-REAL",
  title: "真实任务采集",
  purpose: "REAL_INTEGRATION",
  state: "WAITING_DATA",
  facts: [{
    type: "TASK",
    fields: { externalTaskRef: "TASK-REAL-01", externalTaskVersion: "1", originalTaskSha256: "a".repeat(64),
      currentProcess: "人工核查", expectedOutput: "核查任务包" },
    evidence: [{ ref: "EVIDENCE-TASK-01", sha256: "a".repeat(64), fileKey: "private/task-01", originalName: "task.pdf" }],
    submittedBy: "real-submitter",
    submittedAt: "2026-09-29T03:00:00Z",
    verification: "VERIFIED",
    verifiedBy: "real-verifier",
    verifiedAt: "2026-09-29T03:10:00Z",
    verificationReason: "原件、任务和提交主体一致"
  }],
  findings: ["MISSING:SOURCE_RIGHTS", "MISSING:ACTOR_POSITION", "MISSING:APPROVAL", "MISSING:HUMAN_RECEIPT",
    "MISSING:BUSINESS_EVALUATION"],
  sha256: "b".repeat(64)
};

beforeEach(() => {
  api.complete.mockReset().mockResolvedValue("product-token");
  api.logout.mockReset().mockResolvedValue(undefined);
  api.identity.mockReset().mockResolvedValue({ issuer: "https://issuer", subject: "actual-login-subject", sessionId: "session-1",
    grants: [{ organization: "ORG-A", domain: "ENFORCEMENT", resourceType: "ENFORCEMENT_JOB_PACK",
      resourceId: "ENF-JOB-CNIPA-GUIDANCE-CASE-1", action: "COLLECT", purpose: "JOB_PACK" },
    { organization: "ORG-COLLECTION", domain: "ENFORCEMENT", resourceType: "ENFORCEMENT_JOB_PACK",
      resourceId: "ENF-JOB-COLLECTION-01", action: "CREATE", purpose: "JOB_PACK" }] });
  api.request.mockReset().mockImplementation((_token: string, path: string) =>
    Promise.resolve(path === "/job-packs/collection-tasks" ? [] : snapshot));
  api.upload.mockReset();
});
afterEach(cleanup);

describe("enforcement real job-pack collection wizard", () => {
  it("shows all six real-fact states and independent-verification provenance", async () => {
    render(<EnforcementJobPackV2Page />);
    const id = await screen.findByPlaceholderText("输入任务包 ID");
    fireEvent.change(id, { target: { value: "ENF-JOB-REAL-01" } });
    fireEvent.click(screen.getByRole("button", { name: /读取/ }));

    expect(await screen.findByText("1/6 已采集")).toBeInTheDocument();
    expect(screen.getByText("1/6 已完成复核")).toBeInTheDocument();
    for (const title of ["真实任务", "来源许可", "真实身份岗位", "实际人工批准", "正式人工回执", "业务评价"]) {
      expect(screen.getByRole("button", { name: `打开${title}采集步骤` })).toBeInTheDocument();
    }

    fireEvent.click(screen.getByRole("button", { name: "打开真实任务采集步骤" }));
    expect(screen.getByText("real-submitter")).toBeInTheDocument();
    expect(screen.getByText("real-verifier")).toBeInTheDocument();
    expect(screen.getByText("EVIDENCE-TASK-01")).toBeInTheDocument();
    expect(screen.getByText("task.pdf")).toBeInTheDocument();
    expect(screen.getByText("原件、任务和提交主体一致")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "打开来源许可采集步骤" }));
    expect(screen.getByText("许可、授权或合法来源凭证原件")).toBeInTheDocument();
    expect(screen.getByText("本项尚未完成")).toBeInTheDocument();
    expect(screen.getAllByText("尚未提交").length).toBeGreaterThan(0);
    expect(screen.queryByText("已自动触发真实集成")).not.toBeInTheDocument();
    await waitFor(() => expect(api.request).toHaveBeenCalledWith("product-token", "/job-packs/ENF-JOB-REAL-01", undefined, undefined));
  });

  it("lists and claims the first public real-business task without inventing human facts", async () => {
    const task = { taskId: "ENF-RBC-CNIPA-GUIDANCE-CASE-1", candidateId: "ENF-PUBLIC-CNIPA_GUIDANCE_CASE_1_INTERPRETATION",
      packageId: "ENF-JOB-CNIPA-GUIDANCE-CASE-1", title: "指导案例1号理解与适用", assignmentState: "UNCLAIMED",
      workflowState: "WAITING_REAL_HUMAN_ACTION", publicInput: { sourceName: "国家知识产权局",
        sourceUrl: "https://www.cnipa.gov.cn/example", rawSha256: "c".repeat(64) } };
    const publicPack = { ...snapshot, id: task.packageId, title: task.title, state: "WAITING_REAL_HUMAN_ACTION",
      facts: [], findings: ["MISSING:ACTOR_POSITION", "MISSING:APPROVAL", "MISSING:HUMAN_RECEIPT", "MISSING:BUSINESS_EVALUATION"],
      publicInput: { ...task.publicInput, claimedBy: "actual-login-subject" } };
    api.request.mockImplementation((_token: string, path: string) => {
      if (path === "/job-packs/collection-tasks") return Promise.resolve([task]);
      if (path.endsWith("/claim")) return Promise.resolve({ task: { ...task, assignmentState: "CLAIMED",
        claimedBy: "actual-login-subject", organization: "ORG-A" }, jobPack: publicPack });
      return Promise.resolve(publicPack);
    });
    render(<EnforcementJobPackV2Page />);
    expect(await screen.findByText("指导案例1号理解与适用")).toBeInTheDocument();
    expect(screen.getAllByText("ORG-A").length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("button", { name: /领取任务/ }));
    expect(await screen.findByText("actual-login-subject")).toBeInTheDocument();
    expect(screen.getByText("1/5 已采集")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "打开来源许可采集步骤" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开真实身份岗位采集步骤" })).toBeInTheDocument();
    await waitFor(() => expect(api.request).toHaveBeenCalledWith("product-token",
      "/job-packs/collection-tasks/ENF-RBC-CNIPA-GUIDANCE-CASE-1/claim", { organization: "ORG-A" }, expect.any(String)));
  });

  it.each(["FUNCTIONAL_VALIDATION", "UNKNOWN", ""])('rejects purpose %s even from a successful read and clears previous facts', async purpose => {
    render(<EnforcementJobPackV2Page />);
    const id = await screen.findByPlaceholderText("输入任务包 ID");
    fireEvent.change(id, { target: { value: snapshot.id } });
    fireEvent.click(screen.getByRole("button", { name: /读取/ }));
    expect(await screen.findByText("1/6 已采集")).toBeInTheDocument();
    api.request.mockResolvedValue({ ...snapshot, purpose });
    fireEvent.click(screen.getByRole("button", { name: /读取/ }));
    expect(await screen.findByText("当前任务包用途不可用于本工作入口")).toBeInTheDocument();
    expect(screen.queryByText("1/6 已采集")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "打开真实身份岗位采集步骤" })).not.toBeInTheDocument();
    expect(screen.queryByText("功能验证任务包")).not.toBeInTheDocument();
  });

  it("continues to read the existing real-business collection purpose", async () => {
    api.request.mockImplementation((_token: string, path: string) => Promise.resolve(
      path === "/job-packs/collection-tasks" ? [] : { ...snapshot, purpose: "REAL_BUSINESS_COLLECTION" }));
    render(<EnforcementJobPackV2Page />);
    const id = await screen.findByPlaceholderText("输入任务包 ID");
    fireEvent.change(id, { target: { value: snapshot.id } });
    fireEvent.click(screen.getByRole("button", { name: /读取/ }));
    expect(await screen.findByText("1/6 已采集")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开真实身份岗位采集步骤" })).toBeInTheDocument();
  });

  it("derives the organization from the exact create grant", async () => {
    api.request.mockImplementation((_token: string, path: string, body?: unknown) => {
      if (path === "/job-packs/collection-tasks") return Promise.resolve([]);
      if (path === "/job-packs") return Promise.resolve({ packageId: "ENF-JOB-COLLECTION-01" });
      return Promise.resolve({ ...snapshot, id: "ENF-JOB-COLLECTION-01", organization: "ORG-COLLECTION",
        purpose: "REAL_BUSINESS_COLLECTION", facts: [], findings: ["MISSING:TASK"] });
    });
    render(<EnforcementJobPackV2Page />);
    expect(await screen.findByText("身份和组织由产品授权自动带入")).toBeInTheDocument();
    expect(screen.queryByLabelText("授权组织")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByText("功能验证（测试身份与输入）")).not.toBeInTheDocument();
    const create = screen.getByText("建立任务包").closest("section")!;
    fireEvent.change(within(create).getByLabelText("任务包 ID"), { target: { value: "ENF-JOB-COLLECTION-01" } });
    fireEvent.change(within(create).getByLabelText("任务名称"), { target: { value: "正式任务资料采集" } });
    fireEvent.click(within(create).getByRole("button", { name: "开始采集" }));
    await waitFor(() => expect(api.request).toHaveBeenCalledWith("product-token", "/job-packs", {
      id: "ENF-JOB-COLLECTION-01", organization: "ORG-COLLECTION", title: "正式任务资料采集",
      purpose: "REAL_BUSINESS_COLLECTION"
    }, expect.any(String)));
  });

  it("opens a claimed task for an authorized second person and revokes the session on exit", async () => {
    const task = { taskId: "ENF-RBC-CNIPA-GUIDANCE-CASE-1", candidateId: "ENF-PUBLIC-CNIPA_GUIDANCE_CASE_1_INTERPRETATION",
      packageId: "ENF-JOB-CNIPA-GUIDANCE-CASE-1", title: "指导案例1号理解与适用", assignmentState: "CLAIMED",
      workflowState: "WAITING_REAL_HUMAN_ACTION", claimedBy: "first-person", organization: "ORG-A",
      publicInput: { sourceName: "国家知识产权局", sourceUrl: "https://www.cnipa.gov.cn/example", rawSha256: "c".repeat(64) } };
    const publicPack = { ...snapshot, id: task.packageId, title: task.title, state: "WAITING_REAL_HUMAN_ACTION",
      facts: [], findings: ["MISSING:ACTOR_POSITION", "MISSING:APPROVAL", "MISSING:HUMAN_RECEIPT", "MISSING:BUSINESS_EVALUATION"],
      publicInput: { ...task.publicInput, claimedBy: task.claimedBy } };
    api.request.mockImplementation((_token: string, path: string) =>
      Promise.resolve(path === "/job-packs/collection-tasks" ? [task] : publicPack));
    render(<EnforcementJobPackV2Page />);
    expect(await screen.findByText("指导案例1号理解与适用")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "打开任务" }));
    expect(await screen.findByText("first-person")).toBeInTheDocument();
    await waitFor(() => expect(api.request).toHaveBeenCalledWith("product-token",
      "/job-packs/ENF-JOB-CNIPA-GUIDANCE-CASE-1", undefined, undefined));
    fireEvent.click(screen.getByRole("button", { name: "退出" }));
    await waitFor(() => expect(api.logout).toHaveBeenCalledWith("product-token"));
    expect(await screen.findByText("当前没有产品会话")).toBeInTheDocument();
  });
});
