import type { AgentRunView } from "../agent/AgentTaskPanel";
import type { CaseView, Version } from "./contract";
import { digest } from "./api";

export type ReportHandoff = {
 caseId:string; runId:string; runVersion:number; revision:number;
 workVersion:number; generatedWorkVersion:number; inputVersion:number;
 reportSha256:string; observationSha256:string;
};
const hash=/^[a-f0-9]{64}$/;
const positive=(n:number)=>Number.isSafeInteger(n)&&n>0;

/** Bind only actual domain references. The observation hashes the serialized Report,
 * not contentSha256. Preserve the server JSON field/list order; never fall back to
 * the content hash or a guessed canonical encoding if the wire digest differs. */
export async function bindReportHandoff(run:AgentRunView, view:CaseView, versions:Version[]):Promise<ReportHandoff|null>{
 const c=run.checkpoint, caseId=view.caseInput.id;
 if(!c.runId||c.task.id!==caseId||!positive(c.version)||!positive(view.version))return null;
 const head=versions.at(-1);
 if(!head||head.workVersion!==view.version||head.inputVersion!==view.inputVersion)return null;
 const refs=c.observations.filter(o=>o.reference.startsWith("case-report:"));
 if(refs.length!==1)return null;
 const observation=refs[0];
 const match=/^case-report:([A-Za-z0-9_-]{1,120})\/([1-9][0-9]*)$/.exec(observation.reference);
 if(!match||match[1]!==caseId||!hash.test(observation.sha256))return null;
 const revision=Number(match[2]);if(!positive(revision))return null;
 // Outcome may annotate this exact report with critical gaps, not name another result.
 if(c.outcome){
  if(!["NEED_HUMAN","COMPLETED"].includes(c.outcome.reason))return null;
  const ref=c.outcome.reference??c.outcome.outcomeRef;
  if(ref!==observation.reference&&ref!==observation.reference+"#critical-gaps")return null;
 }
 const candidates=view.reports.filter(v=>v.report.revision===revision);
 if(candidates.length!==1)return null;
 const value=candidates[0], report=value.report;
 if(value.stale||value.policyStale||!value.current||report.inputVersion!==view.inputVersion||!hash.test(report.contentSha256))return null;
 const audits=view.audits.filter(a=>a.action==="GENERATE_AUXILIARY_REPORT"&&a.objectId===String(revision));
 if(audits.length!==1||!positive(audits[0].version)||audits[0].version>view.version)return null;
 const generated=versions.filter(v=>v.workVersion===audits[0].version);
 if(generated.length!==1||generated[0].inputVersion!==report.inputVersion||generated[0].audit.action!==audits[0].action||generated[0].audit.objectId!==String(revision))return null;
 if(await digest(JSON.stringify(report))!==observation.sha256)return null;
 return {caseId,runId:c.runId,runVersion:c.version,revision,workVersion:view.version,
  generatedWorkVersion:audits[0].version,inputVersion:view.inputVersion,
  reportSha256:report.contentSha256,observationSha256:observation.sha256};
}
