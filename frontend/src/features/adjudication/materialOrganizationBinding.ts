import { digest } from "./api";
import type { CaseView, Material, Source } from "./contract";
export type Action = "ACCEPT" | "EDIT" | "REJECT";
export type Candidate = { id:string; kind:string; providerSide:string; statement:string; exactQuote:string; start:number; end:number; sourceId:string; sourceVersion:string; sourceSha256:string; originalLocator:string; sourceTag:string };
export type Run = { runId:string; workVersion:number; inputVersion:number; stale:boolean; model:string; promptVersion:string; candidates:Candidate[] };
export type Decision = { versionId:string; baseVersionId:string|null; caseId:string; runId:string; candidateId:string; workVersion:number; organizationVersion:number; inputVersion:number; action:Action; semantic:string; kind:string; providerSide:string; machineStatement:string; humanText:string|null; reason:string|null; source:Source; exactQuote:string; start:number; end:number; sourceTag:string; scope:string; actor:string; createdAt:string; expiresAt:string };
export type DecisionView = { version:Decision; currentOrganizationVersion:number; currentInputVersion:number; stale:boolean; currentCandidateDecision:boolean };
export type Page = { versions:DecisionView[]; nextAfterOrganizationVersion:number; currentOrganizationVersion:number };
export type Body = { expectedWorkVersion:number; expectedOrganizationVersion:number; action:Action; humanText?:string; reason?:string };
export type Binding = { caseId:string; runId:string; candidate:Candidate; baseVersionId:string|null; inputVersion:number; body:Body };
export type Location = { materials:Material[]; text?:string; source?:Source; error?:string };
export class MaterialBindingError extends Error { constructor(message="材料整理响应或来源绑定不一致，不能继续操作。") { super(message); } }
const kinds=["PARTY_ASSERTION","RECORDED_STATEMENT","DISPUTE_CLUE","MISSING_ITEM"], sides=["APPLICANT","RESPONDENT"];
function check(value:unknown):asserts value { if(!value)throw new MaterialBindingError(); }
function object(value:unknown,keys:string[]):Record<string,unknown> { check(value&&typeof value==="object"&&!Array.isArray(value));const v=value as Record<string,unknown>;check(Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)));return v; }
function text(v:unknown):asserts v is string { check(typeof v==="string"&&v.trim().length>0&&validUtf16(v)); }
function integer(v:unknown,min=1):asserts v is number { check(Number.isSafeInteger(v)&&(v as number)>=min); }
function bool(v:unknown):asserts v is boolean { check(typeof v==="boolean"); }
function uuid(v:unknown):asserts v is string { check(typeof v==="string"&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(v)); }
export function validUtf16(value:string) { for(let i=0;i<value.length;i++){const n=value.charCodeAt(i);if(n>=0xd800&&n<=0xdbff){const next=value.charCodeAt(++i);if(!(next>=0xdc00&&next<=0xdfff))return false;}else if(n>=0xdc00&&n<=0xdfff)return false;}return true; }
function source(raw:unknown):Source { const s=object(raw,["id","version","locator","sha256","kind"]);[s.id,s.version,s.locator].forEach(text);check(typeof s.sha256==="string"&&/^[a-f0-9]{64}$/.test(s.sha256));check(["PUBLIC","DEIDENTIFIED","USER_PROVIDED","PROJECT_AUTHORED"].includes(s.kind as string));return {id:s.id,version:s.version,locator:s.locator,sha256:s.sha256,kind:s.kind} as Source; }
function candidate(raw:unknown):Candidate {
 const c=object(raw,["id","kind","providerSide","statement","exactQuote","start","end","sourceId","sourceVersion","sourceSha256","originalLocator","sourceTag"]);
 check(typeof c.id==="string"&&/^c[1-9][0-9]{0,3}$/.test(c.id));check(kinds.includes(c.kind as string)&&sides.includes(c.providerSide as string));
 [c.statement,c.exactQuote,c.sourceId,c.sourceVersion,c.originalLocator,c.sourceTag].forEach(text);check(typeof c.sourceSha256==="string"&&/^[a-f0-9]{64}$/.test(c.sourceSha256));integer(c.start,0);integer(c.end);check(c.end>c.start);return c as Candidate;
}
export function bindRun(raw:unknown,id:string,view:CaseView):Run {
 const r=object(raw,["runId","workVersion","inputVersion","stale","model","promptVersion","candidates"]);uuid(r.runId);check(r.runId===id);integer(r.workVersion);integer(r.inputVersion);bool(r.stale);text(r.model);text(r.promptVersion);check(r.workVersion<=view.version&&r.inputVersion<=r.workVersion);check(r.stale===true||r.inputVersion===view.inputVersion);check(Array.isArray(r.candidates));
 check(r.candidates.length>0&&r.candidates.length<=100);const candidates=r.candidates.map(candidate);check(new Set(candidates.map(c=>c.id)).size===candidates.length);return {...r,candidates} as Run;
}
export async function locate(c:Candidate,view:CaseView):Promise<Location> {
 const materials=view.materials.filter(m=>m.source.id===c.sourceId&&m.source.version===c.sourceVersion&&m.source.sha256===c.sourceSha256&&m.source.locator===c.originalLocator);
 const bad=(error:string):Location=>({materials,error});if(!materials.length)return bad("当前案件没有可绑定的引用材料。");
 if(materials.some(m=>!m.readable||m.content===null))return bad("匹配材料原文不可读。");
 const first=materials[0],content=first.content!;
 if(materials.some(m=>m.source.kind!==first.source.kind||m.content!==content)||!validUtf16(content)||await digest(content)!==c.sourceSha256)return bad("材料原文或摘要不一致。");
 if(c.start<0||c.end>content.length||c.end<=c.start||content.slice(c.start,c.end)!==c.exactQuote||!validUtf16(content.slice(0,c.start))||!validUtf16(content.slice(0,c.end)))return bad("引用位置与确切原文不一致。");
 if(!materials.some(m=>m.side===c.providerSide))return bad("没有可绑定的提供方材料。");
 return {materials,text:content,source:first.source};
}
export function validateBody(body:Body) {
 integer(body.expectedWorkVersion);integer(body.expectedOrganizationVersion,0);check(["ACCEPT","EDIT","REJECT"].includes(body.action));
 if(body.action==="EDIT"){text(body.humanText);text(body.reason);}else {check(body.humanText===undefined);if(body.action==="REJECT")text(body.reason);else if(body.reason!==undefined)text(body.reason);}
}
function decision(raw:unknown):Decision {
 const keys=["versionId","baseVersionId","caseId","runId","candidateId","workVersion","organizationVersion","inputVersion","action","semantic","kind","providerSide","machineStatement","humanText","reason","source","exactQuote","start","end","sourceTag","scope","actor","createdAt","expiresAt"],d=object(raw,keys);
 uuid(d.versionId);uuid(d.runId);if(d.baseVersionId!==null)uuid(d.baseVersionId);text(d.caseId);check(["ACCEPT","EDIT","REJECT"].includes(d.action as string));
 integer(d.workVersion);integer(d.organizationVersion);integer(d.inputVersion);check(d.inputVersion<=d.workVersion);text(d.actor);text(d.createdAt);text(d.expiresAt);
 const created=Date.parse(d.createdAt),expires=Date.parse(d.expiresAt);check(Number.isFinite(created)&&Number.isFinite(expires)&&expires>created&&expires-created<=30*86400000);
 const s=source(d.source);candidate({id:d.candidateId,kind:d.kind,providerSide:d.providerSide,statement:d.machineStatement,exactQuote:d.exactQuote,start:d.start,end:d.end,sourceId:s.id,sourceVersion:s.version,sourceSha256:s.sha256,originalLocator:s.locator,sourceTag:d.sourceTag});
 check(d.scope==="SELECTED_MATERIALS"&&d.semantic===(d.action==="REJECT"?"REJECTED_CANDIDATE":"HUMAN_REVIEWED_MATERIAL_STATEMENT"));
 if(d.reason!==null)text(d.reason);if(d.action==="REJECT"){check(d.humanText===null);text(d.reason);}else {text(d.humanText);if(d.action==="ACCEPT")check(d.humanText===d.machineStatement);else text(d.reason);}return {...Object.fromEntries(keys.map(k=>[k,d[k]])),source:s} as Decision;
}
export function asCandidate(d:Decision):Candidate {return {id:d.candidateId,kind:d.kind,providerSide:d.providerSide,statement:d.machineStatement,exactQuote:d.exactQuote,start:d.start,end:d.end,sourceId:d.source.id,sourceVersion:d.source.version,sourceSha256:d.source.sha256,originalLocator:d.source.locator,sourceTag:d.sourceTag};}
export function bindView(raw:unknown,caseId:string):DecisionView {
 const v=object(raw,["version","currentOrganizationVersion","currentInputVersion","stale","currentCandidateDecision"]),d=decision(v.version);check(d.caseId===caseId);integer(v.currentOrganizationVersion,0);integer(v.currentInputVersion);bool(v.stale);bool(v.currentCandidateDecision);check(v.currentOrganizationVersion>=d.organizationVersion&&v.stale===(v.currentInputVersion!==d.inputVersion));return {...v,version:d} as DecisionView;
}
export async function bindConfirmed(raw:unknown,b:Binding,view:CaseView,previous?:Decision):Promise<DecisionView> {
 const result=bindView(raw,b.caseId),d=result.version,c=b.candidate;
 check(d.runId===b.runId&&d.candidateId===c.id&&d.baseVersionId===b.baseVersionId&&d.inputVersion===b.inputVersion&&d.action===b.body.action);
 const bound=asCandidate(d);check(Object.keys(bound).every(k=>bound[k as keyof Candidate]===c[k as keyof Candidate]));check(d.workVersion===b.body.expectedWorkVersion+1&&d.organizationVersion===b.body.expectedOrganizationVersion+1);
 check(d.humanText===(b.body.action==="REJECT"?null:b.body.action==="EDIT"?b.body.humanText:c.statement)&&d.reason===(b.body.reason??null));
 if(previous)check(JSON.stringify(d)===JSON.stringify(decision(previous)));const location=await locate(c,view);check(!location.error&&location.source?.kind===d.source.kind);return result;
}
export async function bindPage(raw:unknown,view:CaseView,after:number,head?:number):Promise<Page> {
 const p=object(raw,["versions","nextAfterOrganizationVersion","currentOrganizationVersion"]);integer(p.currentOrganizationVersion,0);integer(p.nextAfterOrganizationVersion,0);check(head===undefined||p.currentOrganizationVersion===head);check(Array.isArray(p.versions)&&p.versions.length<=20);
 let cursor=after;const versions:DecisionView[]=[];
 for(const rawView of p.versions){const v=bindView(rawView,view.caseInput.id),d=v.version;check(d.organizationVersion>cursor&&v.currentOrganizationVersion===p.currentOrganizationVersion&&v.currentInputVersion===view.inputVersion&&d.workVersion<=view.version);const location=await locate(asCandidate(d),view);check(!location.error&&location.source?.kind===d.source.kind);cursor=d.organizationVersion;versions.push(v);}
 check(p.nextAfterOrganizationVersion===cursor);return {...p,versions} as Page;
}
export async function readHistory(get:(after:number)=>Promise<unknown>,view:CaseView,isCurrent:()=>boolean) {
 let after=0,head:number|undefined;const versions:DecisionView[]=[];
 while(true){const raw=await get(after);if(!isCurrent())throw new MaterialBindingError("读取上下文已变化。");const page=await bindPage(raw,view,after,head);if(!isCurrent())throw new MaterialBindingError("读取上下文已变化。");head=page.currentOrganizationVersion;versions.push(...page.versions);if(!page.versions.length)break;after=page.nextAfterOrganizationVersion;}
 check(after===head);const latest=new Map<string,DecisionView>();for(const v of versions)latest.set(v.version.runId+":"+v.version.candidateId,v);
 for(const v of versions)check(v.currentCandidateDecision===(latest.get(v.version.runId+":"+v.version.candidateId)===v));return {versions,head:head!};
}
