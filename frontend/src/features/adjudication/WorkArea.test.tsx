/// <reference types="node" />
import "@testing-library/jest-dom/vitest";
import { createHash } from "node:crypto";
import { afterEach,it,expect,vi } from "vitest";
import { act,cleanup,fireEvent,render,screen,waitFor } from "@testing-library/react";
import WorkArea from "./WorkArea";
import type { CaseView,Version } from "./contract";
import type { AgentRunView } from "../agent/AgentTaskPanel";
import { AdjudicationError,request } from "./api";
vi.mock("./api",async()=>{const original=await vi.importActual<typeof import("./api")>("./api");return {...original,request:vi.fn(),digest:async(t:string)=>createHash("sha256").update(t).digest("hex")}});
afterEach(()=>{cleanup();vi.clearAllMocks()});
function sample(){
 const report={revision:1,inputVersion:1,policyVersion:"p1",author:"a",contributors:["a"],sections:[],diagnostics:[],contentSha256:"a".repeat(64),reviewState:"DRAFT" as const,reviews:[]};
 const audit={version:2,action:"GENERATE_AUXILIARY_REPORT",actor:"a",objectId:"1",at:"2026-10-06T00:00:00Z"};
 const view={materials:[],claims:[],findings:[],citations:[],inputContributors:[],version:2,inputVersion:1,caseInput:{id:"case-a",organization:"org",dataScope:"scope",name:"测试案件",applicant:"a",respondent:"r",issues:[],region:null,procedure:null,relevantDate:null,source:{id:"source",version:"v1",locator:"/test",sha256:"d".repeat(64),kind:"PROJECT_AUTHORED"}},reports:[{report,current:true,stale:false}],audits:[audit]} as CaseView;
 const versions:Version[]=[{workVersion:2,inputVersion:1,snapshotSha256:"b".repeat(64),audit}];
 const run:AgentRunView={goalText:"实际目标",checkpoint:{runId:"run-a",version:9,phase:"STOP",task:{id:"case-a"},cursor:0,invocations:1,replans:0,plan:null,pending:null,observations:[{reference:"case-report:case-a/1",sha256:createHash("sha256").update(JSON.stringify(report)).digest("hex")}],outcome:{reason:"NEED_HUMAN",outcomeRef:"case-report:case-a/1"}},events:[]};
 vi.mocked(request).mockImplementation(async(_token,path)=>path.endsWith("/versions")?versions:path.includes("/agent-runs")?run:view);
 return {view,run,versions};
}
async function readRun(){fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"run-a"}});fireEvent.click(screen.getByRole("button",{name:"读取当前运行"}));await screen.findByRole("button",{name:"定位报告编辑 / 复核区"})}
it("real panel onView binds report; repeats GETs on navigation without business POST",async()=>{
 const s=sample(),open=vi.fn();render(<WorkArea token="session" view={s.view} onDenied={vi.fn()} onOpenReport={open}/>);await readRun();fireEvent.click(screen.getByRole("button",{name:"定位报告编辑 / 复核区"}));
 await waitFor(()=>expect(open).toHaveBeenCalledOnce());expect(open.mock.calls[0][0]).toMatchObject({caseId:"case-a",runId:"run-a",revision:1,workVersion:2,reportSha256:"a".repeat(64)});
 expect(request).toHaveBeenCalledTimes(7);expect(vi.mocked(request).mock.calls.every(c=>c[2]===undefined)).toBe(true);expect(screen.getByText(/读取记录或识别缺口不等于自主专业分析/)).toBeInTheDocument();
});
it("start keeps modelMaterialIds explicitly empty",async()=>{
 const s=sample();render(<WorkArea token="session" view={s.view} onDenied={vi.fn()} onOpenReport={vi.fn()}/>);fireEvent.click(screen.getByRole("button",{name:"启动当前目标"}));await waitFor(()=>expect(request).toHaveBeenCalled());
 expect(vi.mocked(request).mock.calls[0]).toEqual(["session","/v2/adjudication/case-works/case-a/agent-runs",{expectedVersion:2,modelMaterialIds:[]},expect.any(String)]);
});
it("manual run replacement clears parent handoff",async()=>{
 const s=sample();render(<WorkArea token="session" view={s.view} onDenied={vi.fn()} onOpenReport={vi.fn()}/>);await readRun();fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"other-run"}});expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();
});
it.each([401,403,503,409,0])("fresh handoff failure %s hides cached binding",async(status)=>{
 const s=sample(),denied=vi.fn(),open=vi.fn();render(<WorkArea token="session" view={s.view} onDenied={denied} onOpenReport={open}/>);await readRun();vi.mocked(request).mockRejectedValueOnce(new AdjudicationError(status,"CASE_ACCESS_DENIED"));fireEvent.click(screen.getByRole("button",{name:"定位报告编辑 / 复核区"}));
 await waitFor(()=>expect(screen.getByRole("button",{name:"启动当前目标"})).not.toBeDisabled());expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();expect(open).not.toHaveBeenCalled();expect(denied).toHaveBeenCalledTimes(status===401||status===403?1:0);
});
it.each(["identity","case","version","historical"])("context change %s hides parent binding",async(change)=>{
 const s=sample(),props={token:"session",view:s.view,onDenied:vi.fn(),onOpenReport:vi.fn()};const {rerender}=render(<WorkArea {...props}/>);await readRun();rerender(<WorkArea {...props} token={change==="identity"?"new-session":props.token} historical={change==="historical"} view={change==="case"?{...s.view,caseInput:{...s.view.caseInput,id:"case-b"}}:change==="version"?{...s.view,version:3}:s.view}/>);expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();
});
it("late old session response cannot restore result",async()=>{
 const s=sample(),props={token:"session",view:s.view,onDenied:vi.fn(),onOpenReport:vi.fn()};let resolve!:(value:unknown)=>void;vi.mocked(request).mockImplementationOnce(()=>new Promise(r=>{resolve=r}));const {rerender}=render(<WorkArea {...props}/>);fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"run-a"}});fireEvent.click(screen.getByRole("button",{name:"读取当前运行"}));rerender(<WorkArea {...props} token="new-session"/>);await act(async()=>{resolve(s.run)});expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();
});
it("changed Run version on click refuses navigation",async()=>{
 const s=sample(),open=vi.fn();render(<WorkArea token="session" view={s.view} onDenied={vi.fn()} onOpenReport={open}/>);await readRun();vi.mocked(request).mockResolvedValueOnce({...s.run,checkpoint:{...s.run.checkpoint,version:10}});fireEvent.click(screen.getByRole("button",{name:"定位报告编辑 / 复核区"}));await screen.findByText(/当前运行、权限或报告版本未能核实/);expect(open).not.toHaveBeenCalled();expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();
});


it("another request begins by synchronously hiding the parent result",async()=>{
 const s=sample();render(<WorkArea token="session" view={s.view} onDenied={vi.fn()} onOpenReport={vi.fn()}/>);await readRun();
 vi.mocked(request).mockImplementationOnce(()=>new Promise(()=>{}));fireEvent.click(screen.getByRole("button",{name:"读取当前运行"}));expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();
});
it("cross-case server response is rejected before shared panel renders goal/evidence",async()=>{
 const s=sample();s.run.checkpoint.task.id="case-b";s.run.goalText="禁止展示跨案目标";
 render(<WorkArea token="session" view={s.view} onDenied={vi.fn()} onOpenReport={vi.fn()}/>);fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"run-a"}});fireEvent.click(screen.getByRole("button",{name:"读取当前运行"}));await screen.findByRole("alert");
 expect(screen.queryByText("禁止展示跨案目标")).not.toBeInTheDocument();expect(screen.queryByText("case-report:case-a/1")).not.toBeInTheDocument();expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();
});
it("stale current report on click is not opened",async()=>{
 const s=sample(),open=vi.fn();render(<WorkArea token="session" view={s.view} onDenied={vi.fn()} onOpenReport={open}/>);await readRun();s.view.reports[0].stale=true;
 fireEvent.click(screen.getByRole("button",{name:"定位报告编辑 / 复核区"}));await screen.findByText(/运行结果尚未关联当前有效报告/);expect(open).not.toHaveBeenCalled();expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();
});
it("late domain reconciliation cannot restore a result after parent becomes disabled",async()=>{
 const s=sample(),props={token:"session",view:s.view,onDenied:vi.fn(),onOpenReport:vi.fn()};let resolve!:(v:unknown)=>void;
 vi.mocked(request).mockResolvedValueOnce(s.run).mockImplementationOnce(()=>new Promise(r=>{resolve=r}));
 const {rerender}=render(<WorkArea {...props}/>);fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"run-a"}});fireEvent.click(screen.getByRole("button",{name:"读取当前运行"}));await waitFor(()=>expect(request).toHaveBeenCalledTimes(2));rerender(<WorkArea {...props} disabled/>);await act(async()=>{resolve(s.run)});expect(screen.queryByText("当前报告人工交接")).not.toBeInTheDocument();expect(request).toHaveBeenCalledTimes(2);
});
it("existing unsaved-edit guard can cancel navigation; cancellation never causes a write",async()=>{
 const s=sample(),open=vi.fn().mockReturnValue(false);render(<WorkArea token="session" view={s.view} onDenied={vi.fn()} onOpenReport={open}/>);await readRun();fireEvent.click(screen.getByRole("button",{name:"定位报告编辑 / 复核区"}));await waitFor(()=>expect(open).toHaveBeenCalledOnce());expect(vi.mocked(request).mock.calls.every(c=>c[2]===undefined)).toBe(true);
});
