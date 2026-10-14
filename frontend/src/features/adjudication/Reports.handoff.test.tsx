import "@testing-library/jest-dom/vitest";
import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import Reports from "./Reports";
import type {ReportView} from "./contract";
import type {ReportHandoff} from "./reportHandoff";
afterEach(()=>{cleanup();vi.restoreAllMocks()});
function report(revision:number):ReportView{return {current:revision===2,stale:false,report:{revision,inputVersion:1,policyVersion:"p",author:"a",contributors:[],sections:[{id:"analysis",content:"正文"+revision,sources:[]}],diagnostics:[],contentSha256:String(revision).repeat(64),reviewState:"DRAFT",reviews:[]}}}
const target:ReportHandoff={caseId:"case-a",runId:"run-a",runVersion:1,revision:2,workVersion:3,generatedWorkVersion:2,inputVersion:1,reportSha256:"2".repeat(64),observationSha256:"a".repeat(64)};
const reports=[report(1),report(2)];
it("handoff replaces an already selected old draft; manual selection remains available",()=>{
 const props={reports,command:vi.fn(),readOnly:false};const {rerender}=render(<Reports {...props}/>);
 fireEvent.click(screen.getByRole("button",{name:/报告 v1 ·/}));expect(screen.getByRole("heading",{name:"报告 v1"})).toBeInTheDocument();
 rerender(<Reports {...props} target={target}/>);expect(screen.getByRole("heading",{name:"报告 v2"})).toBeInTheDocument();
 fireEvent.click(screen.getByRole("button",{name:/报告 v1 ·/}));expect(screen.getByRole("heading",{name:"报告 v1"})).toBeInTheDocument();
 rerender(<Reports {...props} target={{...target}}/>);expect(screen.getByRole("heading",{name:"报告 v2"})).toBeInTheDocument();
});
for(const [name,change] of Object.entries({sha:{reportSha256:"f".repeat(64)},revision:{revision:9},input:{inputVersion:9}}))it("rejects wrong "+name+" without latest fallback",()=>{
 render(<Reports reports={reports} command={vi.fn()} readOnly={false} target={{...target,...change}}/>);
 expect(screen.getByRole("alert")).toBeInTheDocument();expect(screen.queryByRole("heading",{name:"报告 v2"})).not.toBeInTheDocument();
});
it("rejects duplicate exact reports",()=>{render(<Reports reports={[...reports,report(2)]} command={vi.fn()} readOnly={false} target={target}/>);expect(screen.getByRole("alert")).toBeInTheDocument()});
it("historical snapshot stays read-only and manually selectable",()=>{
 render(<Reports reports={reports} command={vi.fn()} readOnly/>);expect(screen.getByRole("button",{name:"提交确切版本"})).toBeDisabled();
 fireEvent.click(screen.getByRole("button",{name:/报告 v1 ·/}));expect(screen.getByRole("heading",{name:"报告 v1"})).toBeInTheDocument();expect(screen.getByRole("button",{name:"提交确切版本"})).toBeDisabled();
});
it("manual switching preserves edits when discard is cancelled",()=>{
 vi.spyOn(window,"confirm").mockReturnValue(false);render(<Reports reports={reports} command={vi.fn()} readOnly={false}/>);
 fireEvent.change(screen.getByLabelText("修订内容"),{target:{value:"未保存"}});fireEvent.click(screen.getByRole("button",{name:/报告 v1 ·/}));
 expect(screen.getByRole("heading",{name:"报告 v2"})).toBeInTheDocument();expect(screen.getByLabelText("修订内容")).toHaveValue("未保存");
});
it("exact target is selected even when another revision is last in the list",()=>{
 const later={...report(3),current:false};render(<Reports reports={[...reports,later]} command={vi.fn()} readOnly={false} target={target}/>);
 expect(screen.getByRole("heading",{name:"报告 v2"})).toBeInTheDocument();expect(screen.queryByRole("heading",{name:"报告 v3"})).not.toBeInTheDocument();
});
for(const flag of ["stale","policyStale","current"] as const)it("invalid target report flag "+flag+" never falls back",()=>{
 const invalid={...report(2),[flag]:flag!=="current"};render(<Reports reports={[report(1),invalid]} command={vi.fn()} readOnly={false} target={target}/>);expect(screen.getByRole("alert")).toBeInTheDocument();expect(screen.queryByRole("heading",{name:"报告 v2"})).not.toBeInTheDocument();
});
