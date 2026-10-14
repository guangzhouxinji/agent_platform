/// <reference types="node" />
import { createHash } from "node:crypto";
import { it,expect,vi } from "vitest";
import type { CaseView,Version } from "./contract";
import type { AgentRunView } from "../agent/AgentTaskPanel";
import { bindReportHandoff } from "./reportHandoff";
vi.mock("./api",()=>({digest:async(text:string)=>createHash("sha256").update(text).digest("hex")}));
function sample(){
 const report={revision:1,inputVersion:2,policyVersion:"p1",author:"issuer#author",contributors:["issuer#author"],sections:[],diagnostics:[],contentSha256:"a".repeat(64),reviewState:"DRAFT" as const,reviews:[]};
 const audit={version:4,action:"GENERATE_AUXILIARY_REPORT",actor:"issuer#author",objectId:"1",at:"2026-10-06T00:00:00Z"};
 const view={materials:[],claims:[],findings:[],citations:[],inputContributors:[],version:4,inputVersion:2,caseInput:{id:"case-a",organization:"org",dataScope:"scope",name:"测试案件",applicant:"a",respondent:"r",issues:[],region:null,procedure:null,relevantDate:null,source:{id:"source",version:"v1",locator:"/test",sha256:"d".repeat(64),kind:"PROJECT_AUTHORED"}},reports:[{report,current:true,stale:false}],audits:[audit]} as CaseView;
 const versions:Version[]=[{workVersion:4,inputVersion:2,snapshotSha256:"c".repeat(64),audit}];
 const run={checkpoint:{runId:"run-a",version:9,task:{id:"case-a"},observations:[{reference:"case-report:case-a/1",sha256:createHash("sha256").update(JSON.stringify(report)).digest("hex")}],outcome:{reason:"NEED_HUMAN",outcomeRef:"case-report:case-a/1#critical-gaps"}}} as AgentRunView;
 return {run,view,versions};
}
it("links exact same-case/run revision and generation/current work versions with distinct SHA semantics",async()=>{
 const s=sample();expect(await bindReportHandoff(s.run,s.view,s.versions)).toEqual({caseId:"case-a",runId:"run-a",runVersion:9,revision:1,workVersion:4,generatedWorkVersion:4,inputVersion:2,reportSha256:"a".repeat(64),observationSha256:s.run.checkpoint.observations[0].sha256});
});
const negative:Record<string,(s:ReturnType<typeof sample>)=>void>={
 "cross-case task":s=>{s.run.checkpoint.task.id="case-b"},
 "cross-case reference":s=>{s.run.checkpoint.observations[0].reference="case-report:case-b/1"},
 "unrelated outcome":s=>{s.run.checkpoint.outcome!.outcomeRef="case-report:case-a/2"},
 "denied outcome":s=>{s.run.checkpoint.outcome!.reason="PERMISSION_BLOCKED"},
 "source unavailable":s=>{s.run.checkpoint.outcome!.reason="SOURCE_UNAVAILABLE"},
 "stale input":s=>{s.view.inputVersion=3;s.versions[0].inputVersion=3},
 "historical report":s=>{s.view.reports[0].current=false},
 "stale report":s=>{s.view.reports[0].stale=true},
 "stale policy":s=>{s.view.reports[0].policyStale=true},
 "head race":s=>{s.versions[0].workVersion=5},
 "missing generation audit":s=>{s.view.audits=[]},
 "wrong generation action":s=>{s.view.audits[0].action="SUBMIT_REPORT"},
 "missing generation version":s=>{s.versions=[]},
 "duplicate references":s=>{s.run.checkpoint.observations.push(s.run.checkpoint.observations[0])},
 "duplicate report":s=>{s.view.reports.push(s.view.reports[0])},
 "content SHA substituted for object SHA":s=>{s.run.checkpoint.observations[0].sha256=s.view.reports[0].report.contentSha256},
 "changed report bytes":s=>{s.view.reports[0].report.author="other"},
 "changed review state":s=>{s.view.reports[0].report.reviewState="SUBMITTED"},
 "invalid SHA":s=>{s.run.checkpoint.observations[0].sha256="not-a-digest"},
 "unsafe revision":s=>{s.run.checkpoint.observations[0].reference="case-report:case-a/9007199254740993"},
 "annotated observation":s=>{s.run.checkpoint.observations[0].reference+="#critical-gaps"},
};
for(const [name,change] of Object.entries(negative))it(`fails closed: ${name}`,async()=>{const s=sample();change(s);expect(await bindReportHandoff(s.run,s.view,s.versions)).toBeNull()});
it("observation without Outcome is not synthesized into completed result",async()=>{const s=sample();s.run.checkpoint.outcome=null;expect(await bindReportHandoff(s.run,s.view,s.versions)).not.toBeNull()});
it("status-gap observation never implies a report",async()=>{const s=sample();s.run.checkpoint.observations=[{reference:"case-work:case-a/4/patent-status-gap",sha256:"b".repeat(64)}];expect(await bindReportHandoff(s.run,s.view,s.versions)).toBeNull()});

