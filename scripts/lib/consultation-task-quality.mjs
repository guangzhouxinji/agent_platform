import {readFileSync,lstatSync,realpathSync} from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {isDeepStrictEqual} from 'node:util';
const IDS=['CON-QUALITY-ROTOR-01','CON-QUALITY-EMPTY-01'];
const ARTIFACTS=['run','workspace','approval','admission','recoveryBefore','recoveryAfter','denialTrace','sourceUse'];
const COVERAGE=['NONEMPTY_RESULT','EMPTY_RESULT','PERMISSION_LOSS','TERMINAL_RECOVERY','INSUFFICIENT_MATERIAL','SOURCE_WITHDRAWAL','EXPIRY_DENIAL','INFLIGHT_UNKNOWN'];
const TOOLS={PatentSearch:['search'],EvidenceAssessment:['source-read'],AnswerDraft:['answer-draft']};
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const exact=(a,b)=>isDeepStrictEqual(a,b);
const keys=(object,allowed)=>object!==null&&typeof object==='object'&&!Array.isArray(object)&&exact(Object.keys(object).sort(),[...allowed].sort());
const array=v=>Array.isArray(v);
const nonempty=v=>typeof v==='string'&&v.trim().length>0;
const hex=v=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const unique=xs=>new Set(xs).size===xs.length;

// Preserve Jackson's decimal literals and string escaping when checking Observation bytes.
// JSON.stringify(JSON.parse(...)) would turn 0.0 into 0 and change a legitimate SHA.
function rawJson(text){
 const value=JSON.parse(text),spans=new Map();let i=0;
 const ws=()=>{while(/\s/.test(text[i]??'')&&i<text.length)i++;};
 function string(){const start=i++;while(i<text.length){if(text[i]==='\\'){i+=2;continue;}if(text[i++]==='"')return JSON.parse(text.slice(start,i));}throw Error('INVALID_JSON_STRING');}
 function node(pointer){
  ws();const start=i,ch=text[i];
  if(ch==='{'){
   i++;ws();const seen=new Set();if(text[i]!=='}')while(true){ws();const key=string();if(seen.has(key))throw Error('DUPLICATE_JSON_KEY');seen.add(key);ws();if(text[i++]!==':')throw Error('INVALID_JSON_OBJECT');node(pointer+'/'+key.replace(/~/g,'~0').replace(/\//g,'~1'));ws();if(text[i]!==',')break;i++;}
   if(text[i++]!=='}')throw Error('INVALID_JSON_OBJECT');
  }else if(ch==='['){i++;ws();let n=0;if(text[i]!==']')while(true){node(pointer+'/'+n++);ws();if(text[i]!==',')break;i++;}if(text[i++]!==']')throw Error('INVALID_JSON_ARRAY');}
  else if(ch==='"')string();else{while(i<text.length&&!/[\s,}\]]/.test(text[i]))i++;}
  let compact='',quoted=false,escaped=false;
  for(const c of text.slice(start,i)){if(quoted||! /\s/.test(c))compact+=c;if(escaped){escaped=false;continue;}if(quoted&&c==='\\'){escaped=true;continue;}if(c==='"')quoted=!quoted;}
  spans.set(pointer,compact);
 }
 node('');ws();if(i!==text.length)throw Error('TRAILING_JSON_DATA');return {value,spans};
}
function safeFile(root,relative,prefix){
 if(typeof relative!=='string'||!relative.startsWith(prefix)||path.isAbsolute(relative)||relative.includes('\\')||relative.includes('\0')||relative.includes(':')||relative.split('/').some(x=>!x||x==='.'||x==='..'||/[. ]$/.test(x)))throw Error('UNSAFE_INPUT_PATH');
 const base=realpathSync(root);let full=base;
 for(const part of relative.split('/')){full=path.join(full,part);if(lstatSync(full).isSymbolicLink())throw Error('LINKED_INPUT_PATH');}
 const rel=path.relative(base,realpathSync(full));if(rel.startsWith('..')||path.isAbsolute(rel))throw Error('INPUT_OUTSIDE_WORKSPACE');
 const stat=lstatSync(full);if(!stat.isFile()||stat.size>8*1024*1024)throw Error('INVALID_INPUT_FILE');return full;
}
function blankMetrics(){return {
 completion:{status:'FAIL',scope:'MECHANICAL_COMPLETION_ONLY'},evidenceSupport:{status:'FAIL'},resultAccuracy:{status:'NOT_ASSESSED',reason:'Professional rubric and responsible review required'},toolUse:{status:'FAIL'},replanAbility:{status:'NOT_OBSERVED',reason:'Fixed three-step historical plan is not evidence-driven replanning'},humanIntervention:{status:'NOT_OBSERVED'},errorRecovery:{status:'FAIL',scope:'HISTORICAL_PERMISSION_AND_TERMINAL_RECOVERY_ONLY'},outputQuality:{status:'FAIL',professionalSemanticQuality:'NOT_ASSESSED',humanCost:'NOT_OBSERVED'}
};}
function failCase(id,reason){return {id,passed:false,checks:[{id:'artifactIntegrity',passed:false,reason}],metrics:blankMetrics()};}
function evaluateCase(sample,docs){
 const checks=[];function check(id,action){try{checks.push({id,passed:action()===true,reason:''});if(!checks.at(-1).passed)checks.at(-1).reason='CONTRACT_NOT_SATISFIED';}catch{checks.push({id,passed:false,reason:'MALFORMED_OR_MISSING_EVIDENCE'});}}
 const run=docs.run.value,w=docs.workspace.value,c=run?.checkpoint,g=w?.goal,result=w?.result,e=sample.expected;
 let measuredAllHits=null;
 const turn=w?.dialogue?.find(t=>'answer-turn:'+t.turnId===(c?.outcome?.reference??c?.outcome?.outcomeRef));
 check('identityBinding',()=>w.task.id===e.taskId&&c.task.id===e.taskId&&c.runId===e.runId&&g.goalId===e.goalId&&c.task.goal.id===g.goalId&&g.inputId===e.inputId&&g.taskId===e.taskId&&g.taskVersion===w.task.version&&c.task.scope===w.task.scopeId+':'+w.task.scopeVersion&&c.task.goal.version===g.goalRevision+':'+g.taskVersion+':'+g.inputId&&c.task.goal.description==='confirmed-input:'+g.inputId&&g.queryObject===e.query);
 check('completionContract',()=>c.definition.agentId==='consultation-agent'&&c.task.type==='CONSULTATION_RETRIEVAL'&&c.task.goal.completionContract==='CONSULTATION_COMPLETION/v1'&&c.phase==='STOP'&&c.pending===null&&c.outcome.reason===e.stopReason&&((c.outcome.reason==='COMPLETED'&&c.outcome.reference==='answer-turn:'+turn.turnId&&result.status==='SUCCEEDED')||(c.outcome.reason==='NEED_HUMAN'&&c.outcome.reference==='partial-source-coverage'&&result.status==='PARTIAL')));
 check('resultBinding',()=>turn.kind==='EXPLANATION'&&turn.taskId===e.taskId&&turn.taskVersion===w.task.version&&turn.resultSetId===result.resultSetId&&turn.resultSha===result.resultSha&&hex(result.resultSha)&&nonempty(turn.output.answer)&&w.dialogue.filter(t=>t.turnId===turn.turnId).length===1);
 check('citationBinding',()=>{
  if(!array(result.members)||!array(result.citations)||!array(turn.citations)||!unique(result.members.map(m=>m.memberId))||result.members.length!==result.citations.length||!unique(result.citations.map(x=>x.memberId))||!unique(turn.citations.map(x=>x.memberId)))return false;
  if(!array(turn.output?.citations)||!unique(turn.output.citations)||!turn.output.citations.every(nonempty)||!exact(turn.output.citations,turn.citations.map(x=>x.memberId)))return false;
  const members=new Map(result.members.map(x=>[x.memberId,x])),citations=new Map(result.citations.map(x=>[x.memberId,x]));
  const sources=new Map(w.sources.map(x=>[x.sourceId,x]));
  for(const citation of result.citations){const m=members.get(citation.memberId),s=sources.get(citation.sourceId);if(!m||!s||s.taskVersion!==w.task.version||!hex(citation.rawSha)||citation.documentVersion!=='RAW_SHA256:'+citation.rawSha||!citation.locatorRef.includes('#json-pointer=/')||!/^https:\/\//.test(citation.sourceUrl)||!['sourceId','documentId','documentVersion','sourceVersion'].every(k=>citation[k]===m[k]))return false;}
  if(result.members.length>0&&turn.citations.length===0)return false;
  if(!result.members.length&&turn.citations.length)return false;
  for(const citation of turn.citations){const actual=citations.get(citation.memberId);if(!actual||!exact(citation,actual))return false;}
  return true;
 });
 check('observationHashes',()=>{
  const refs=new Map([[`task:${e.taskId}/goal:${g.goalId}`,'/goal'],[`retrieval:${result.resultSetId}`,'/result'],[`retrieval-citations:${result.resultSetId}`,'/result/citations'],['answer-turn:'+turn.turnId,'/dialogue/'+w.dialogue.indexOf(turn)]]);
  if(!array(c.observations)||c.observations.length!==refs.size||!unique(c.observations.map(o=>o.reference)))return false;
  return c.observations.every(o=>refs.has(o.reference)&&hex(o.sha256)&&hash(docs.workspace.spans.get(refs.get(o.reference)))===o.sha256);
 });
 check('toolScope',()=>exact(c.definition.taskTypes,['CONSULTATION_RETRIEVAL'])&&exact(c.definition.skillTools,TOOLS)&&array(c.plan.steps)&&c.plan.steps.length===3&&unique(c.plan.steps.map(x=>x.id))&&c.plan.steps.every((x,i)=>x.skill===Object.keys(TOOLS)[i]&&x.tool===TOOLS[x.skill][0]&&x.inputRef==='confirmed-input:'+g.inputId)&&c.invocations===3&&c.cursor===3&&c.replans===0);
 check('resultCoverage',()=>{
  const terms=g.queryObject.toLowerCase().split(/\s+/).filter(Boolean),allHits=result.members.filter(m=>array(m.matchedTerms)&&terms.every(t=>m.matchedTerms.map(x=>x.toLowerCase()).includes(t))&&m.matchFields.includes('TITLE')).length;
  measuredAllHits=allHits;
  return result.candidateCount===e.motherSet&&result.members.length===e.pageCount&&allHits===e.allQueryTermHits&&g.requestedFields.length===1&&g.requestedFields[0]==='TITLE'&&((sample.scenario==='NONEMPTY_RESULT'&&result.members.length>0)||(sample.scenario==='EMPTY_RESULT'&&result.members.length===0&&result.confidenceStatus==='NO_RESULT'&&result.confidenceReason==='NO_MATCHING_CANDIDATE'));
 });
 check('historicalAdmission',()=>{
  const a=docs.admission.value,approval=docs.approval.value,use=docs.sourceUse.value;
  const expiry=Date.parse(a.fixedLatestExpiry),started=Date.parse(c.startedAt),approved=Date.parse(approval.recordedAt),confirmed=Date.parse(g.confirmedAt);
  return a.taskId===e.taskId&&a.goalId===g.goalId&&a.inputId===g.inputId&&a.humanApprovalId===approval.approvalId&&a.sourceId===g.sourceIds[0]&&g.sourceIds.length===1&&w.sources.length===1&&use.taskId===e.taskId&&use.sourceId===a.sourceId&&w.sources[0].sourceEpoch===a.sourceEpoch&&w.sources[0].contractVersion===use.contractVersion&&use.fieldUses.TITLE.includes('MODEL')&&!use.fieldUses.CLAIMS.includes('MODEL')&&Number.isFinite(expiry)&&Number.isFinite(started)&&Number.isFinite(approved)&&Number.isFinite(confirmed)&&started>=approved&&started>=confirmed&&started<expiry&&Date.parse(g.expiresAt)<=expiry&&Date.parse(w.task.expiresAt)<=expiry&&Date.parse(use.validUntil)<=expiry&&Date.parse(turn.createdAt)<=expiry;
 });
 check('permissionLoss',()=>{
  const trace=docs.denialTrace.value;if(!array(trace))return false;
  const route='/v2/consultation/tasks/'+e.taskId;
  return [route+'/agent-runs/'+e.runId,route+'/retrieval-workspace'].every(p=>trace.some(x=>x.method==='GET'&&x.path===p&&x.status===404&&Number.isFinite(Date.parse(x.observedAt))&&Date.parse(x.observedAt)>=Date.parse(c.startedAt)&&Date.parse(x.observedAt)<Date.parse(g.expiresAt)));
 });
 check('recoveryLedger',()=>{
  const before=docs.recoveryBefore.value,after=docs.recoveryAfter.value;
  const columns=['task','runs','revisions','attempts','modelInvocationFences','modelTurns','modelPayloadSummaries'];
  if(!columns.every(k=>array(before[k])&&exact(before[k],after[k])))return false;
  if(before.task.length!==1||before.runs.length!==1||before.attempts.length!==1||before.modelInvocationFences.length!==1||before.modelTurns.length!==1||before.modelPayloadSummaries.length!==1||before.revisions.length!==c.version+1)return false;
  const row=before.runs[0],fence=before.modelInvocationFences[0],mt=before.modelTurns[0],payload=before.modelPayloadSummaries[0];
  if(before.task[0].TASK_ID!==e.taskId||row.TASK_ID!==e.taskId||row.RUN_ID!==e.runId||row.AGENT_ID!=='consultation-agent'||row.GOAL_ID!==g.goalId||row.GOAL_VERSION!==c.task.goal.version||Number(row.HEAD_VERSION)!==c.version||before.attempts[0].INPUT_REVISION!==g.inputId||fence.INVOCATION_ID!==turn.turnId||mt.TURN_ID!==turn.turnId||Number(fence.TASK_VERSION)!==w.task.version||Number(mt.TASK_VERSION)!==w.task.version||fence.KIND!==turn.kind||mt.KIND!==turn.kind||!hex(fence.REQUEST_SHA)||mt.MODEL_ID!==turn.modelId||mt.PROMPT_VERSION!==turn.promptVersion||fence.RESULT_SET_ID!==result.resultSetId||mt.RESULT_SET_ID!==result.resultSetId||fence.RESULT_SHA!==result.resultSha||mt.RESULT_SHA!==result.resultSha)return false;
  if(!before.revisions.every((x,i)=>x.RUN_ID===e.runId&&Number(x.REVISION)===i&&hex(x.PAYLOAD_SHA)))return false;
  if(!exact([...payload.keys].sort(),['confidence','confidenceReason','goal','kind','members','methodVersion','pageSize','scope','status','totalCandidates'].sort())||payload.memberCount!==result.members.length||payload.goal!==g.queryObject)return false;
  const events=run.events;if(!array(events)||events.length!==before.revisions.length||!events.every((x,i)=>x.runId===e.runId&&x.version===i)||events.at(-1).phase!=='STOP'||events.at(-1).stopReason!==c.outcome.reason)return false;
  return Date.parse(after.observedAt)>=Date.parse(before.observedAt);
 });
 const passed=id=>checks.find(x=>x.id===id)?.passed===true,metrics=blankMetrics();
 metrics.completion.status=passed('identityBinding')&&passed('completionContract')&&passed('resultBinding')?'PASS':'FAIL';
 metrics.evidenceSupport.status=passed('citationBinding')&&passed('observationHashes')?'PASS':'FAIL';
 metrics.toolUse.status=passed('toolScope')?'PASS':'FAIL';
 metrics.errorRecovery.status=passed('permissionLoss')&&passed('recoveryLedger')?'PASS':'FAIL';
 metrics.outputQuality.status=passed('resultCoverage')&&passed('resultBinding')?'PASS':'FAIL';
 return {id:sample.id,scenario:sample.scenario,passed:checks.every(x=>x.passed),taskId:e.taskId,runId:e.runId,checks,metrics,counts:{candidateCount:result?.candidateCount??null,pageMembers:result?.members?.length??null,allQueryTermHits:measuredAllHits},currentAuthorization:'NOT_EVALUATED',officialBusinessClosed:'NOT_VERIFIED'};
}

/** Reuse the same strict byte/path/JSON reader for offline review references only. */
export function readPinnedConsultationArtifact(root, relative, expectedSha) {
 if(!hex(expectedSha))throw Error('ARTIFACT_RECEIPT_REQUIRED');
 const prefix=relative==='governance/evaluations/consultation-task-quality-v1.json'?'governance/evaluations/':'docs/evidence/';
 const data=readFileSync(safeFile(root,relative,prefix));
 if(hash(data)!==expectedSha)throw Error('ARTIFACT_SHA_MISMATCH');
 return rawJson(new TextDecoder('utf-8',{fatal:true}).decode(data).replace(/^\uFEFF/,'')).value;
}

/** No API, model, clock rollback, or domain mutation: audit only the pinned historical receipts. */
export function evaluateConsultationSuite(workspaceRoot,manifestPath){
 const report={schema:'CONSULTATION_TASK_QUALITY_REPORT/v1',scope:'OFFLINE_RETAINED_ARTIFACTS_ONLY',denominator:2,mechanicalPassed:false,professionalAccepted:false,overallAcceptance:'INCOMPLETE',currentAuthorization:'NOT_EVALUATED',cases:[],inputFiles:[],unobservedCoverage:[],errors:[]};
 try{
  const manifestFile=safeFile(workspaceRoot,manifestPath,'governance/evaluations/'),bytes=readFileSync(manifestFile),manifest=rawJson(new TextDecoder('utf-8',{fatal:true}).decode(bytes).replace(/^\uFEFF/,'')).value;
  report.inputFiles.push({path:manifestPath,sha256:hash(bytes)});
  if(!keys(manifest,['schema','version','scope','contractRef','denominator','caseIds','requiredCoverage','cases'])||manifest.schema!=='CONSULTATION_TASK_QUALITY/v1'||manifest.version!=='1'||manifest.scope!=='RETAINED_VALIDATION_ARTIFACTS_ONLY'||manifest.contractRef!=='docs/adr/consultation-task-quality-evaluation-20261007.md'||manifest.denominator!==2||!exact(manifest.caseIds,IDS)||!exact(manifest.requiredCoverage,COVERAGE)||!array(manifest.cases)||manifest.cases.length!==2||!exact(manifest.cases.map(x=>x.id),IDS))throw Error('FIXED_SUITE_CONTRACT_REQUIRED');
  const taskIds=manifest.cases.map(x=>x.expected?.taskId),runIds=manifest.cases.map(x=>x.expected?.runId);
  if(!taskIds.every(nonempty)||!runIds.every(nonempty)||!unique(taskIds)||!unique(runIds))throw Error('DISTINCT_REAL_TASKS_REQUIRED');
  for(const sample of manifest.cases){
   try{
    if(!keys(sample,['id','scenario','expected','artifacts'])||sample.scenario!==(sample.id===IDS[0]?'NONEMPTY_RESULT':'EMPTY_RESULT')||!keys(sample.expected,['taskId','runId','inputId','goalId','query','motherSet','pageCount','allQueryTermHits','stopReason'])||!keys(sample.artifacts,ARTIFACTS))throw Error('CASE_CONTRACT_REQUIRED');
    if(!['inputId','goalId','query','stopReason'].every(k=>nonempty(sample.expected[k]))||!['motherSet','pageCount','allQueryTermHits'].every(k=>Number.isSafeInteger(sample.expected[k])&&sample.expected[k]>=0)||sample.expected.pageCount>sample.expected.motherSet||sample.expected.allQueryTermHits>sample.expected.pageCount)throw Error('INVALID_EXPECTED_COUNTS');
    const docs={};const paths=new Set();
    for(const [name,item] of Object.entries(sample.artifacts)){
     if(!keys(item,['path','sha256'])||!hex(item.sha256)||paths.has(item.path))throw Error('ARTIFACT_RECEIPT_REQUIRED');paths.add(item.path);
     const file=safeFile(workspaceRoot,item.path,'docs/evidence/'),data=readFileSync(file),sha256=hash(data);report.inputFiles.push({path:item.path,sha256});
     if(sha256!==item.sha256)throw Error('ARTIFACT_SHA_MISMATCH');docs[name]=rawJson(new TextDecoder('utf-8',{fatal:true}).decode(data).replace(/^\uFEFF/,''));
    }
    report.cases.push(evaluateCase(sample,docs));
   }catch(cause){report.cases.push(failCase(sample.id,cause.message));}
  }
 }catch(cause){report.errors.push(cause.message);report.cases=IDS.map(id=>failCase(id,cause.message));}
 report.mechanicalPassed=report.cases.length===2&&report.cases.every(x=>x.passed);
 report.mechanicalSummary={passed:report.cases.filter(x=>x.passed).length,failed:report.cases.filter(x=>!x.passed).length,denominator:2};
 report.professionalSummary={accepted:0,unassessed:2,denominator:2};
 const observed=new Set();for(const c of report.cases){if(c.passed)observed.add(c.scenario);if(c.checks.find(x=>x.id==='permissionLoss')?.passed)observed.add('PERMISSION_LOSS');if(c.checks.find(x=>x.id==='recoveryLedger')?.passed)observed.add('TERMINAL_RECOVERY');}
 report.unobservedCoverage=COVERAGE.filter(x=>!observed.has(x));return report;
}
