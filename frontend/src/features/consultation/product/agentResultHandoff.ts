import type { AgentRunView } from "../../agent/AgentTaskPanel";
import type { Citation, Turn, Workspace } from "./types";

function sameFieldLocators(a:Citation["fieldLocators"], b:Citation["fieldLocators"]):boolean {
  if(a===undefined||b===undefined)return a===b;
  const keys=Object.keys(a);
  return keys.length===Object.keys(b).length
    &&keys.every(key=>Object.prototype.hasOwnProperty.call(b,key)&&a[key]===b[key]);
}

/** Current domain reads, not historical Runtime evidence, authorize the handoff. */
export function bindConsultationResult(run:AgentRunView, workspace:Workspace):Turn|null {
  const c=run.checkpoint, goal=workspace.goal, result=workspace.result;
  const task=c.task as {id:string;goal?:{description?:string}};
  if(!c.runId||c.phase!=="STOP"||!c.outcome||!goal||!result||task.id!==workspace.task.id
    ||task.goal?.description!=="confirmed-input:"+goal.inputId||goal.taskVersion!==workspace.task.version)return null;
  if(!["COMPLETED","NEED_HUMAN"].includes(c.outcome.reason))return null;
  const ref=c.outcome.reference??c.outcome.outcomeRef;
  const answers=c.observations.filter(o=>o.reference.startsWith("answer-turn:"));
  if(answers.length!==1)return null;
  const answerRef=answers[0].reference;
  if(c.outcome.reason==="COMPLETED"&&ref!==answerRef)return null;
  if(c.outcome.reason==="NEED_HUMAN"&&(ref!=="partial-source-coverage"||result.status!=="PARTIAL"))return null;
  if(!c.observations.some(o=>o.reference==="retrieval:"+result.resultSetId)
    ||!c.observations.some(o=>o.reference==="retrieval-citations:"+result.resultSetId))return null;
  const candidates=workspace.dialogue.filter(t=>"answer-turn:"+t.turnId===answerRef);
  if(candidates.length!==1)return null;
  const turn=candidates[0] as Turn&{taskId?:string;resultSha?:string};
  if(turn.kind!=="EXPLANATION"||turn.taskId!==workspace.task.id||turn.taskVersion!==workspace.task.version
    ||turn.resultSetId!==result.resultSetId||turn.resultSha!==result.resultSha||!turn.output.answer?.trim())return null;

  const members=new Map(result.members.map(member=>[member.memberId,member]));
  if(members.size!==result.members.length||result.citations.length!==members.size)return null;
  const citations=new Map<string,Citation>();
  for(const citation of result.citations){
    const member=members.get(citation.memberId);
    if(!member||citations.has(citation.memberId)
      ||citation.sourceId!==member.sourceId||citation.documentId!==member.documentId
      ||citation.documentVersion!==member.documentVersion||citation.sourceVersion!==member.sourceVersion)return null;
    citations.set(citation.memberId,citation);
  }
  if(members.size>0&&turn.citations.length===0)return null;
  const answeredMembers=new Set<string>();
  for(const citation of turn.citations){
    const current=citations.get(citation.memberId);
    if(!current||answeredMembers.has(citation.memberId)
      ||citation.sourceId!==current.sourceId||citation.documentId!==current.documentId
      ||citation.documentVersion!==current.documentVersion||citation.sourceVersion!==current.sourceVersion
      ||citation.rawSha!==current.rawSha||citation.locatorRef!==current.locatorRef
      ||citation.sourceUrl!==current.sourceUrl||!sameFieldLocators(citation.fieldLocators,current.fieldLocators))return null;
    answeredMembers.add(citation.memberId);
  }
  return turn;
}
