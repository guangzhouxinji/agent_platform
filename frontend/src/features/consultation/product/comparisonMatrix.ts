export type MatrixSummary={taskId:string;comparisonId:string;inputSha:string;state:string;confirmedFeatures:{featureId:string;userText:string}[];candidates:{candidateId:string;status:string;attemptId:string|null;errorCode:string|null;executionState:string|null;reportIds:string[]}[]};
export type MatrixEvidence={documentVersion:string;originalRef:string;originalSha:string;jsonPointer:string;byteStart:number;byteEnd:number;spanSha:string;text:string};
export type MatrixRow={candidateId:string;featureId:string;claimBranch:number[];relation:string;sameAspects:string[];differences:string[];reasoning:string;uncertainty:string;analyzedRange:MatrixEvidence[];citations:MatrixEvidence[]};
export type MatrixReport={result:{taskId:string;comparisonId:string;candidateId:string;inputSha:string;attemptId:string;token:number;reportId:string;resultVersion:number;methodVersion:string;workerSubject:string;configurationVersion:string;approvalSha:string;responseSha:string;createdAt:string;expiresAt:string;rows:MatrixRow[]};reportSha:string;receipt:{taskId:string;comparisonId:string;candidateId:string;inputSha:string;attemptId:string;token:number;reportId:string;reportSha:string;resultVersion:number;status:string}};
const hash=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v);
const positive=(v:unknown)=>Number.isSafeInteger(v)&&Number(v)>0;
const text=(v:unknown):v is string=>typeof v==='string'&&v.trim().length>0;
const object=(v:unknown):v is Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const strings=(v:unknown):v is string[]=>Array.isArray(v)&&v.every(text);
const nullableText=(v:unknown)=>v===null||text(v);
const fail=():never=>{throw new Error('矩阵结果绑定或摘要不一致，未展示该响应。请核对分析编号后重新读取。')};
function sorted(v:unknown):unknown {if(Array.isArray(v))return v.map(sorted);if(v!==null&&typeof v==='object')return Object.fromEntries(Object.entries(v).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([k,val])=>[k,sorted(val)]));return v;}
export async function matrixDigest(value:unknown){const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(sorted(value))));return Array.from(new Uint8Array(bytes)).map(v=>v.toString(16).padStart(2,'0')).join('');}
/** Validate the transport contract, not professional findings or current source rights. */
export function bindMatrixSummary(task:string,comparison:string,value:unknown,expectedInputSha?:string):MatrixSummary {
 if(!text(task)||!text(comparison)||!object(value)||value.taskId!==task||value.comparisonId!==comparison||!hash(value.inputSha)||!text(value.state)||!['COMMITTED','PARTIAL','IN_PROGRESS'].includes(value.state)||!Array.isArray(value.candidates)||!value.candidates.length||!Array.isArray(value.confirmedFeatures)||!value.confirmedFeatures.length)return fail();
 if(expectedInputSha!==undefined&&(!hash(expectedInputSha)||value.inputSha!==expectedInputSha))return fail();
 const features=new Set<string>();for(const f of value.confirmedFeatures){if(!object(f)||!text(f.featureId)||features.has(f.featureId)||!text(f.userText))return fail();features.add(f.featureId);}
 const ids=new Set<string>(),reports=new Set<string>();for(const c of value.candidates){
  if(!object(c)||!text(c.candidateId)||!hash(c.candidateId)||ids.has(c.candidateId)||!text(c.status)||!['QUEUED','RUNNING','COMMITTED','FAILED','CANCELLED'].includes(c.status)||!nullableText(c.attemptId)||!nullableText(c.errorCode)||!nullableText(c.executionState)||!strings(c.reportIds)||new Set(c.reportIds).size!==c.reportIds.length)return fail();
  if(c.status==='COMMITTED'&&(!text(c.attemptId)||!c.reportIds.length))return fail();
  for(const id of c.reportIds){if(reports.has(id))return fail();reports.add(id);}ids.add(c.candidateId);
 }
 const committed=value.candidates.filter(c=>c.status==='COMMITTED').length;
 const expected=committed===value.candidates.length?'COMMITTED':committed>0?'PARTIAL':'IN_PROGRESS';
 if(value.state!==expected)return fail();return value as MatrixSummary;
}
function evidence(value:unknown):value is MatrixEvidence {
 if(!object(value))return false;
 return hash(value.originalSha)&&hash(value.spanSha)&&Number.isSafeInteger(value.byteStart)&&Number.isSafeInteger(value.byteEnd)&&Number(value.byteStart)>=0&&Number(value.byteEnd)>Number(value.byteStart)&&text(value.text)&&text(value.originalRef)&&text(value.documentVersion)&&text(value.jsonPointer);
}
export async function bindMatrixReport(summary:MatrixSummary,candidate:string,reportId:string,value:unknown,originalJson?:string):Promise<MatrixReport> {
 bindMatrixSummary(summary.taskId,summary.comparisonId,summary);
 if(!object(value)||!object(value.result)||!object(value.receipt))return fail();
 const r=value.result,rec=value.receipt;
 if(!text(candidate)||!text(reportId)||!hash(value.reportSha)||r.taskId!==summary.taskId||r.comparisonId!==summary.comparisonId||r.inputSha!==summary.inputSha||r.candidateId!==candidate||r.reportId!==reportId||r.methodVersion!=='PATENT_FEATURE_MATRIX_V1'||!text(r.attemptId)||!text(r.workerSubject)||!text(r.configurationVersion)||!hash(r.approvalSha)||!hash(r.responseSha)||!positive(r.token)||!positive(r.resultVersion)||!text(r.createdAt)||!text(r.expiresAt)||!Number.isFinite(Date.parse(r.createdAt))||!Number.isFinite(Date.parse(r.expiresAt))||Date.parse(r.expiresAt)<=Date.parse(r.createdAt)||!Array.isArray(r.rows)||!r.rows.length||!summary.candidates.some(c=>c.candidateId===candidate&&c.reportIds.includes(reportId)))return fail();
 for(const key of ['taskId','comparisonId','candidateId','inputSha','attemptId','token','reportId','resultVersion'] as const)if(rec[key]!==r[key])return fail();
 if(rec.status!=='COMMITTED'||rec.reportSha!==value.reportSha)return fail();
 if(originalJson===undefined){if(await matrixDigest(r)!==value.reportSha)return fail()}
 else{
  const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(originalJson));
  const digest=Array.from(new Uint8Array(bytes)).map(v=>v.toString(16).padStart(2,'0')).join('');
  let parsed:unknown;try{parsed=JSON.parse(originalJson)}catch{return fail()}
  if(digest!==value.reportSha||await matrixDigest(parsed)!==await matrixDigest(r))return fail();
 }
 const features=new Set(summary.confirmedFeatures.map(f=>f.featureId)),rows=new Set<string>();
 for(const row of r.rows){
  if(!object(row)||row.candidateId!==candidate||!text(row.featureId)||!features.has(row.featureId)||!Array.isArray(row.claimBranch)||!row.claimBranch.length||!row.claimBranch.every(positive)||new Set(row.claimBranch).size!==row.claimBranch.length||!text(row.relation)||!['CORRESPONDING','PARTIAL','NOT_FOUND_IN_ANALYZED_RANGE','UNKNOWN'].includes(row.relation)||!strings(row.sameAspects)||!strings(row.differences)||!text(row.reasoning)||typeof row.uncertainty!=='string'||!Array.isArray(row.citations)||!Array.isArray(row.analyzedRange)||!row.citations.every(evidence)||!row.analyzedRange.every(evidence))return fail();
  const key=JSON.stringify([row.featureId,row.claimBranch]);if(rows.has(key))return fail();rows.add(key);
 }
 return value as MatrixReport;
}

