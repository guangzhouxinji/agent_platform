import type {MatrixSummary} from './comparisonMatrix';
export type LineageRow={candidateId:string;claimBranch:number[];featureId:string};
export type CarryReference={key:LineageRow;comparisonId:string;inputSha:string;reportId:string;reportSha:string;rowSha:string};
export type Ancestor={comparisonId:string;inputSha:string;previousComparisonId:string|null};
export type MatrixLineage={taskId:string;comparisonId:string;inputSha:string;previousComparisonId:string;parentInputSha:string;configurationVersion:string;configurationSha:string;expiresAt:string;expectedRows:LineageRow[];invalidated:LineageRow[];recompute:LineageRow[];carried:CarryReference[];ancestry:Ancestor[]};
const object=(v:unknown):v is Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const text=(v:unknown):v is string=>typeof v==='string'&&v.trim().length>0&&v.length<=200;
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
export const lineageKey=(r:LineageRow)=>JSON.stringify([r.candidateId,r.claimBranch,r.featureId]);
const fail=():never=>{throw new Error('沿用版本或行域不一致，未展示该响应。')};
function row(value:unknown):value is LineageRow {
 return object(value)&&hash(value.candidateId)&&text(value.featureId)&&Array.isArray(value.claimBranch)&&value.claimBranch.length>0&&value.claimBranch.length<=4000&&value.claimBranch.every((n,i,a)=>Number.isSafeInteger(n)&&n>0&&(i===0||n>a[i-1]));
}
function rows(value:unknown):value is LineageRow[] {return Array.isArray(value)&&value.length<=4000&&value.every(row)&&new Set(value.map(lineageKey)).size===value.length;}
export function validAncestry(value:unknown,currentId:string,currentSha:string,parentId:string,parentSha:string):value is Ancestor[] {
 if(!Array.isArray(value)||!value.length||value.length>63)return false;
 const ids=new Set([currentId]),shas=new Set([currentSha]);
 for(let i=0;i<value.length;i++){
  const v=value[i];if(!object(v)||!text(v.comparisonId)||!hash(v.inputSha)||(v.previousComparisonId!==null&&!text(v.previousComparisonId))||ids.has(v.comparisonId)||shas.has(v.inputSha))return false;
  if(i===0?(v.comparisonId!==parentId||v.inputSha!==parentSha):value[i-1].previousComparisonId!==v.comparisonId)return false;
  ids.add(v.comparisonId);shas.add(v.inputSha);
 }
 const last=value[value.length-1];return last.previousComparisonId===null||!ids.has(last.previousComparisonId);
}
/** Transport closure only. The server authenticates parent proofs and current source rights. */
export function bindMatrixLineage(summary:MatrixSummary,value:unknown,now=Date.now()):MatrixLineage {
 if(!object(value)||value.taskId!==summary.taskId||value.comparisonId!==summary.comparisonId||value.inputSha!==summary.inputSha||!text(value.previousComparisonId)||value.previousComparisonId===summary.comparisonId||!hash(value.parentInputSha)||value.parentInputSha===summary.inputSha||!text(value.configurationVersion)||!hash(value.configurationSha)||typeof value.expiresAt!=='string'||!Number.isFinite(Date.parse(value.expiresAt))||Date.parse(value.expiresAt)<=now||!rows(value.expectedRows)||!value.expectedRows.length||!rows(value.invalidated)||!rows(value.recompute)||!Array.isArray(value.carried)||value.carried.length>4000)return fail();
 if(!validAncestry(value.ancestry,summary.comparisonId,summary.inputSha,value.previousComparisonId,value.parentInputSha))return fail();
 const candidates=new Set(summary.candidates.map(c=>c.candidateId)),features=new Set(summary.confirmedFeatures.map(f=>f.featureId));
 const expected=new Set(value.expectedRows.map(lineageKey)),branches=new Map<string,Map<string,number[]>>();
 for(const r of value.expectedRows){if(!candidates.has(r.candidateId)||!features.has(r.featureId))return fail();let paths=branches.get(r.candidateId);if(!paths){paths=new Map();branches.set(r.candidateId,paths)}paths.set(JSON.stringify(r.claimBranch),r.claimBranch);}
 if(branches.size!==candidates.size)return fail();
 for(const [candidate,paths] of branches)for(const branch of paths.values())for(const feature of features)if(!expected.has(lineageKey({candidateId:candidate,claimBranch:branch,featureId:feature})))return fail();
 const carried=new Set<string>(),reports=new Map<string,string>();
 for(const ref of value.carried){
  if(!object(ref)||!row(ref.key)||!value.ancestry.some(a=>a.comparisonId===ref.comparisonId&&a.inputSha===ref.inputSha)||!text(ref.reportId)||!hash(ref.reportSha)||!hash(ref.rowSha))return fail();
  const key=lineageKey(ref.key),binding=JSON.stringify([ref.key.candidateId,ref.comparisonId,ref.inputSha,ref.reportSha]);
  if(!expected.has(key)||carried.has(key)||(reports.has(ref.reportId)&&reports.get(ref.reportId)!==binding))return fail();carried.add(key);reports.set(ref.reportId,binding);
 }
 const reportByCandidate=new Map<string,string>();for(const ref of value.carried as CarryReference[]){if(reportByCandidate.has(ref.key.candidateId)&&reportByCandidate.get(ref.key.candidateId)!==ref.reportId)return fail();reportByCandidate.set(ref.key.candidateId,ref.reportId);}
 const pending=new Set(value.recompute.map(lineageKey));
 for(const key of pending)if(!expected.has(key)||carried.has(key))return fail();
 if(pending.size+carried.size!==expected.size||value.invalidated.some(r=>carried.has(lineageKey(r))))return fail();
 return value as MatrixLineage;
}
