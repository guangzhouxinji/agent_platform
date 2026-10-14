import { describe,it,expect } from "vitest";
import { bindConsultationResult } from "./agentResultHandoff";
import { fixture } from "./agentResultFixtures";

// Clone each projection separately: the shared fixture aliases result/answer citations.
function independentFixture(){
 const original=fixture();
 const workspace=structuredClone(original.workspace);
 const turn=structuredClone(original.turn);
 workspace.dialogue=[turn];
 return {workspace,turn,run:structuredClone(original.run)};
}
function multipleMembers(count=20){
 const f=independentFixture(),result=f.workspace.result!;
 const member=result.members[0],citation=result.citations[0];
 result.members=Array.from({length:count},(_,ordinal)=>({...structuredClone(member),memberId:`member-${ordinal}`,ordinal,documentId:`patent-${ordinal}`}));
 result.citations=result.members.map(m=>({...structuredClone(citation),memberId:m.memberId,documentId:m.documentId}));
 f.turn.citations=[structuredClone(result.citations[count-1])];
 return f;
}

describe("consultation current result handoff",()=>{
 it("binds independent answer and current result projections",()=>{const f=independentFixture();expect(f.turn.citations[0]).not.toBe(f.workspace.result!.citations[0]);expect(bindConsultationResult(f.run,f.workspace)).toBe(f.turn)});
 it("preserves partial source coverage as human work, not completed",()=>{const f=independentFixture();f.run.checkpoint.outcome={reason:"NEED_HUMAN",reference:"partial-source-coverage"};f.workspace.result!.status="PARTIAL";expect(bindConsultationResult(f.run,f.workspace)).toBe(f.turn)});
 it.each(["task","input","version","result","sha","blank","citations","outcome","incomplete","duplicate"])("rejects mismatched %s",kind=>{const f=independentFixture();switch(kind){case "task":f.run.checkpoint.task.id="other";break;case "input":f.workspace.goal!.inputId="new";break;case "version":f.turn.taskVersion=1;break;case "result":f.turn.resultSetId="other";break;case "sha":f.turn.resultSha="f".repeat(64);break;case "blank":f.turn.output.answer=" ";break;case "citations":f.workspace.result!.citations=[];break;case "outcome":f.run.checkpoint.outcome!.reference="answer-turn:other";break;case "incomplete":f.run.checkpoint.phase="ACT";break;case "duplicate":f.run.checkpoint.observations.push(structuredClone(f.run.checkpoint.observations[2]));break;}expect(bindConsultationResult(f.run,f.workspace)).toBeNull()});
 it.each(["ERROR","INSUFFICIENT_EVIDENCE","SOURCE_UNAVAILABLE","PERMISSION_BLOCKED"])("never treats %s as a usable answer exit",reason=>{const f=independentFixture();f.run.checkpoint.outcome!.reason=reason;expect(bindConsultationResult(f.run,f.workspace)).toBeNull()});
 it.each(["sourceId","documentId","documentVersion","sourceVersion"] as const)("rejects result citation %s not matching its member even when the answer agrees",field=>{
  const f=independentFixture();f.workspace.result!.citations[0][field]="other";f.turn.citations[0][field]="other";
  expect(bindConsultationResult(f.run,f.workspace)).toBeNull();
 });
 it.each(["memberId","sourceId","documentId","documentVersion","sourceVersion","rawSha","locatorRef","sourceUrl"] as const)("rejects stale or foreign answer citation %s",field=>{
  const f=independentFixture();f.turn.citations[0][field]="other";
  expect(f.workspace.result!.citations[0][field]).not.toBe("other");
  expect(bindConsultationResult(f.run,f.workspace)).toBeNull();
 });
 it.each(["value","missing-key","extra-key","absent-answer","absent-current"])("rejects inconsistent optional fieldLocators: %s",kind=>{
  const f=independentFixture();const current=f.workspace.result!.citations[0],answer=f.turn.citations[0];
  current.fieldLocators={TITLE:"title:1",CLAIMS:"claims:1"};answer.fieldLocators=structuredClone(current.fieldLocators);
  switch(kind){case "value":answer.fieldLocators.TITLE="title:2";break;case "missing-key":delete answer.fieldLocators.CLAIMS;break;case "extra-key":answer.fieldLocators.ABSTRACT="abstract:1";break;case "absent-answer":delete answer.fieldLocators;break;case "absent-current":delete current.fieldLocators;break;}
  expect(bindConsultationResult(f.run,f.workspace)).toBeNull();
 });
 it("compares fieldLocators by exact keys and values, not insertion order",()=>{
  const f=independentFixture();f.workspace.result!.citations[0].fieldLocators={TITLE:"title:1",CLAIMS:"claims:1"};f.turn.citations[0].fieldLocators={CLAIMS:"claims:1",TITLE:"title:1"};
  expect(bindConsultationResult(f.run,f.workspace)).toBe(f.turn);
 });
 it.each(["member","result-citation","answer-citation"])("rejects duplicate %s IDs",kind=>{
  const f=multipleMembers(2),result=f.workspace.result!;
  if(kind==="member")result.members[1]={...structuredClone(result.members[0]),ordinal:1};
  if(kind==="result-citation")result.citations[1]=structuredClone(result.citations[0]);
  if(kind==="answer-citation")f.turn.citations.push(structuredClone(f.turn.citations[0]));
  expect(bindConsultationResult(f.run,f.workspace)).toBeNull();
 });
 it.each(["missing","extra","foreign"])("rejects %s result citations",kind=>{
  const f=multipleMembers(2),result=f.workspace.result!;
  if(kind==="missing")result.citations.pop();
  if(kind==="extra")result.citations.push({...structuredClone(result.citations[0]),memberId:"foreign"});
  if(kind==="foreign")result.citations[0].memberId="foreign";
  expect(bindConsultationResult(f.run,f.workspace)).toBeNull();
 });
 it("rejects an empty answer citation list for nonempty results",()=>{const f=independentFixture();f.turn.citations=[];expect(bindConsultationResult(f.run,f.workspace)).toBeNull()});
 it.each(["COMPLETED","NEED_HUMAN"])("accepts %s with one of twenty members cited, without fixed candidates",reason=>{
  const f=multipleMembers();if(reason==="NEED_HUMAN"){f.run.checkpoint.outcome={reason,reference:"partial-source-coverage"};f.workspace.result!.status="PARTIAL";}
  expect(bindConsultationResult(f.run,f.workspace)).toBe(f.turn);
 });
 it("accepts several distinct answer citations in any order",()=>{const f=multipleMembers();f.turn.citations.push(structuredClone(f.workspace.result!.citations[3]));expect(bindConsultationResult(f.run,f.workspace)).toBe(f.turn)});
 it.each(["COMPLETED","NEED_HUMAN"])("preserves a legal zero-member zero-citation %s explanation",reason=>{
  const f=independentFixture();f.workspace.result!.members=[];f.workspace.result!.citations=[];f.turn.citations=[];f.turn.output.answer="本次检索无结果";
  if(reason==="NEED_HUMAN"){f.run.checkpoint.outcome={reason,reference:"partial-source-coverage"};f.workspace.result!.status="PARTIAL";}
  expect(bindConsultationResult(f.run,f.workspace)).toBe(f.turn);
 });
 it.each(["result","answer"])("rejects orphan %s citations in zero-member results",projection=>{
  const f=independentFixture();f.workspace.result!.members=[];
  if(projection==="result")f.turn.citations=[];else f.workspace.result!.citations=[];
  expect(bindConsultationResult(f.run,f.workspace)).toBeNull();
 });
});
