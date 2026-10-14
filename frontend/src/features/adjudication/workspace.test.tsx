/// <reference types="node" />
import "@testing-library/jest-dom/vitest";
import { createHash } from "node:crypto";
import AdjudicationWorkspace from "./AdjudicationWorkspace";
import { request } from "./api";
import { render,screen,within,fireEvent,cleanup,waitFor } from "@testing-library/react";
import { vi,it,expect,afterEach } from "vitest";
import Reports from "./Reports";
import { CaseForm } from "./Forms";
import { FindingsRead,MaterialsRead,HistoryRead } from "./Readers";
import type { CaseView,ReportView } from "./contract";
vi.mock("./api",async()=>{const original=await vi.importActual<typeof import("./api")>("./api");return {...original,completeLogin:vi.fn().mockResolvedValue("test-session"),beginLogin:vi.fn(),forgetLogin:vi.fn(),request:vi.fn(),digest:vi.fn(async(text:string)=>createHash("sha256").update(text).digest("hex"))}});
afterEach(()=>{cleanup();vi.clearAllMocks()});
const originalText="原创工程案件输入原文";
const source={id:"engineering",version:"v1",locator:"/original",sha256:createHash("sha256").update(originalText,"utf8").digest("hex"),kind:"PROJECT_AUTHORED" as const};
const report:ReportView={stale:true,current:false,report:{revision:1,inputVersion:1,policyVersion:"p",author:"i#a",contributors:["i#a"],sections:[{id:"analysis",content:"辅助说明",sources:[source]}],diagnostics:[{code:"ELEMENT_COVERAGE_MISSING",objectId:"e",blocking:true}],contentSha256:"b".repeat(64),reviewState:"RETURNED",reviews:[{actor:"i#r",reason:"澄清",outcome:"RETURNED",reportSha256:"b".repeat(64),at:"2026-10-02T01:00:00Z"}]}};
const view:CaseView={version:9,inputVersion:3,caseInput:{id:"c",organization:"o",dataScope:"d",name:"原创工程案件",applicant:"a",respondent:"r",issues:["工程争点"],region:null,procedure:null,relevantDate:null,source},claims:[{caseId:"c",patentReference:"工程输入",claimNumber:"1",revision:2,originalText:"连接器",source,elements:[{id:"e",exactText:"连接器"}],selectedProduct:"工程对象",confirmationReason:"人工"}],materials:[{id:"m",caseId:"c",side:"APPLICANT",submitter:"a",content:"连接器片段",assertedPurpose:"工程验证",source,readable:true,supersedesMaterialId:null}],findings:[],citations:[],reports:[report],inputContributors:["i#a"],audits:[]};
it("旧稿/退回/缺项可读，按钮仍发送确切版由后端裁决",()=>{const command=vi.fn().mockResolvedValue(undefined);render(<Reports reports={[report]} command={command} readOnly={false}/>);expect(screen.getByText(/已失效：输入版本/)).toBeInTheDocument();expect(screen.getByText(/阻断性缺项/)).toBeInTheDocument();fireEvent.click(screen.getByRole("button",{name:"提交确切版本"}));expect(command).toHaveBeenCalledWith("/reports/1/submit",{reportSha256:"b".repeat(64)});expect(screen.getAllByText(/已登记资料/).length).toBeGreaterThan(0);});
it("历史快照操作禁用，不冒当前有效报告",()=>{render(<Reports reports={[{...report,stale:false,current:true}]} command={vi.fn()} readOnly/>);expect(screen.getByRole("button",{name:"提交确切版本"})).toBeDisabled();expect(screen.getByText(/历史快照内最新稿/)).toBeInTheDocument();});
it("逐要素显示未对应，相反记录仅为冲突提示不作法律判断",()=>{const {rerender}=render(<FindingsRead view={view}/>);expect(screen.getByText(/缺证提示：尚无已记录对应/)).toBeInTheDocument();rerender(<FindingsRead view={{...view,findings:[{id:"f1",caseId:"c",claimRevision:2,elementId:"e",materialId:"m",relation:"SUPPORT",exactExcerpt:"连接器",reason:"工程说明"},{id:"f2",caseId:"c",claimRevision:2,elementId:"e",materialId:"m",relation:"OPPOSING",exactExcerpt:"片段",reason:"反向说明"}]}}/>);expect(screen.getByText(/冲突提示：同时存在支持及反向记录/)).toBeInTheDocument();expect(screen.getByText(/支持记录 · f1/)).toBeInTheDocument();});
it("不可读材料不显示正文，材料版本替换与审计是结构化阅读",()=>{render(<><MaterialsRead view={{...view,materials:[{...view.materials[0],readable:false,content:"不得显示正文"}]}}/><HistoryRead view={{...view,audits:[{version:9,action:"SUBMIT_REPORT",actor:"issuer#author",objectId:"3",at:"2026-10-02T01:00:00Z"}]}} versions={[]} onOpen={vi.fn()}/></>);expect(screen.queryByText("不得显示正文")).not.toBeInTheDocument();expect(within(screen.getByRole("table")).getByText("issuer#author")).toBeInTheDocument();});

it("预填长文本保持稳定具名字段，不将原文内容并入字段名称",()=>{render(<CaseForm current={view.caseInput} command={vi.fn()}/>);expect(screen.getByLabelText("争议事项（每行一项）",{exact:true})).toHaveValue("工程争点");});

it("已替换材料对应只显示为历史，不虚报当前特征已覆盖",()=>{render(<FindingsRead view={{...view,materials:[...view.materials,{...view.materials[0],id:"m2",supersedesMaterialId:"m"}],findings:[{id:"old",caseId:"c",claimRevision:2,elementId:"e",materialId:"m",relation:"SUPPORT",exactExcerpt:"连接器",reason:"历史"}]}}/>);expect(screen.getByText(/缺证提示：尚无已记录对应/)).toBeInTheDocument();expect(screen.getByText(/已替换历史材料，不当作当前对应/)).toBeInTheDocument();});

it("正式产品呈现不随历史来源种类降级，规则不匹配明确展示",()=>{const {container}=render(<Reports reports={[{...report,policyStale:true}]} command={vi.fn()} readOnly={false}/>);expect(screen.getByText(/规则版本与当前不一致/)).toBeInTheDocument();expect(screen.getByText("规则版本")).toBeInTheDocument();expect(container.textContent).not.toMatch(/非现实案件|非真实专利|非正式法律依据|工程最小输入包|非正式裁决|原创工程输入/);});


async function integratedWorkspace(){
 const old={...report,stale:false,current:true,report:{...report.report,inputVersion:3,reviewState:"DRAFT" as const,reviews:[]}};
 const generated={...old,report:{...old.report,revision:2,contentSha256:"c".repeat(64),sections:[{id:"analysis",content:"任务生成的确切报告",sources:[source]}]}};
 const oldAudit={version:9,action:"GENERATE_AUXILIARY_REPORT",actor:"a",objectId:"1",at:"2026-10-06T01:00:00Z"};
 const newAudit={...oldAudit,version:10,objectId:"2"};
 const initial={...view,reports:[old],audits:[oldAudit]},fresh={...view,version:10,reports:[{...old,current:false},generated],audits:[oldAudit,newAudit]};
 const history=[{workVersion:9,inputVersion:3,snapshotSha256:"a".repeat(64),audit:oldAudit},{workVersion:10,inputVersion:3,snapshotSha256:"b".repeat(64),audit:newAudit}];
 const run={goalText:"真实服务端运行目标",checkpoint:{runId:"native-run",version:12,phase:"STOP",task:{id:"c"},cursor:0,invocations:1,replans:0,plan:null,pending:null,observations:[{reference:"case-report:c/2",sha256:createHash("sha256").update(JSON.stringify(generated.report)).digest("hex")}],outcome:{reason:"NEED_HUMAN",outcomeRef:"case-report:c/2#critical-gaps"}},events:[]};
 let runRead=false;
 vi.mocked(request).mockImplementation(async(_token,path)=>{
  if(path.includes("/agent-runs")){runRead=true;return run}
  if(path.endsWith("/versions"))return runRead?history:history.slice(0,1);
  if(path.endsWith("/originals"))return [{source:view.caseInput.source,originalText}];
  return runRead?fresh:initial;
 });
 window.history.replaceState(null,"","/app/adjudication?case=c");render(<AdjudicationWorkspace/>);
 await screen.findByRole("button",{name:"锁定工作台"});fireEvent.click(screen.getByRole("button",{name:"进入 / 刷新案件"}));
 await screen.findByRole("button",{name:"读取当前运行"});
 return {run,initial,fresh};
}
it("真实裁决入口关联新报告，更新fresh工作版本并定位原编辑/复核区，不自动提交",async()=>{
 await integratedWorkspace();fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"native-run"}});fireEvent.click(screen.getByRole("button",{name:"读取当前运行"}));
 fireEvent.click(await screen.findByRole("button",{name:"定位报告编辑 / 复核区"}));
 const area=await screen.findByLabelText("报告编辑与复核");await waitFor(()=>expect(area).toHaveFocus());
 expect(within(area).getAllByText("任务生成的确切报告")[0]).toBeInTheDocument();expect(screen.getByRole("button",{name:"报告"})).toHaveAttribute("aria-current","page");
 expect(within(area).getByRole("form",{name:"独立复核"})).toBeInTheDocument();expect(within(area).getByRole("button",{name:"提交确切版本"})).toBeInTheDocument();
 expect(document.querySelector(".adj-summary")?.textContent).toContain("工作版本10");expect(vi.mocked(request).mock.calls.every(c=>c[2]===undefined)).toBe(true);
});
it("真实报告区未保存修订沿用navigate保护，取消不离开、不写库",async()=>{
 const confirm=vi.spyOn(window,"confirm").mockReturnValue(false);
 try{
  await integratedWorkspace();fireEvent.click(screen.getByRole("button",{name:"报告"}));
  await screen.findByLabelText("报告编辑与复核");
  const content=screen.getByLabelText("修订内容");fireEvent.change(content,{target:{value:"未保存的人工修订"}});
  fireEvent.click(screen.getByRole("button",{name:"案件"}));expect(confirm).toHaveBeenCalledWith("离开或刷新将放弃未保存的报告修订，是否继续？");
  expect(screen.getByLabelText("修订内容")).toHaveValue("未保存的人工修订");expect(screen.getByRole("button",{name:"报告"})).toHaveAttribute("aria-current","page");expect(vi.mocked(request).mock.calls.every(c=>c[2]===undefined)).toBe(true);
 }finally{confirm.mockRestore()}
});


