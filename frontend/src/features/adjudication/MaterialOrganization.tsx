import { useEffect, useRef, useState } from "react";
import { RefreshCw } from "lucide-react";
import { AdjudicationError, casePath, request } from "./api";
import type { CaseView } from "./contract";
import { Panel } from "./common";
import MaterialOrganizationRead, { DecisionHistory } from "./MaterialOrganizationRead";
import { asCandidate, bindConfirmed, bindRun, bindView, locate, MaterialBindingError, readHistory } from "./materialOrganizationBinding";
import type { Action, Candidate, DecisionView, Location, Run } from "./materialOrganizationBinding";
import type { MaterialOrganizationSession, Packet } from "./materialOrganizationSession";
type Snapshot={run:Run;locations:Record<string,Location>;versions:DecisionView[];head:number};
type Editor={candidate:Candidate;action:Action;baseVersionId:string|null;humanText:string;reason:string};
type Props={token:string;view:CaseView;historical:boolean;session:MaterialOrganizationSession;disabled:boolean;isCurrent:()=>boolean;onBusy:(busy:boolean)=>void;onDirty:(dirty:boolean)=>void;onDenied:(error:AdjudicationError)=>void;onConfirmed:(value:DecisionView)=>Promise<boolean>;onRefresh:()=>Promise<boolean>};
export default function MaterialOrganization({token,view,historical,session,disabled,isCurrent,onBusy,onDirty,onDenied,onConfirmed,onRefresh}:Props) {
 const id=view.caseInput.id,root=casePath(id)+"/material-organization";
 const ids=[...new Set(view.audits.filter(a=>a.action==="ORGANIZE_MATERIALS").map(a=>a.objectId))];
 const initial=session.state(id);const [selected,setSelected]=useState(initial.packet?.binding.runId??initial.unknown?.runId??initial.confirmed?.version.runId??ids.at(-1)??"");
 const [snapshot,setSnapshot]=useState<Snapshot|null>(null),[loading,setLoading]=useState(false),[error,setError]=useState<Error|null>(null),[editor,setEditor]=useState<Editor|null>(null);
 const epoch=useRef(0),alive=useRef(true),context=useRef("");context.current=JSON.stringify([token,id,view.version,view.inputVersion,historical]);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;epoch.current++;onBusy(false);onDirty(false);};},[]);
 useEffect(()=>{void load(selected);return()=>{epoch.current++;};},[token,id,view.version,view.inputVersion,historical,selected]);
 const state=session.state(id),writeDisabled=disabled||loading||session.blocked(id)||historical||!snapshot||snapshot.run.stale;
 function current(op:number,key:string){return alive.current&&op===epoch.current&&key===context.current&&isCurrent();}
 async function load(runId:string) {
  const op=++epoch.current,key=context.current;setSnapshot(null);setError(null);
  if(!runId&&!session.state(id).confirmedId){setLoading(false);onBusy(false);return;}setLoading(true);onBusy(true);
  try{
   let exact:DecisionView|undefined;const pending=session.state(id);
   if(pending.confirmedId){exact=bindView(await request(token,root+"/versions/"+encodeURIComponent(pending.confirmedId)),id);if(!current(op,key))return;if(exact.version.versionId!==pending.confirmedId||(pending.confirmed&&JSON.stringify(exact.version)!==JSON.stringify(pending.confirmed.version)))throw new MaterialBindingError();runId=exact.version.runId;}
   if(!ids.includes(runId))throw new MaterialBindingError("当前案件没有该整理回合的审计引用。");
   const run=bindRun(await request(token,root+"/runs/"+encodeURIComponent(runId)),runId,view);if(!current(op,key))return;
   const history=await readHistory(after=>request(token,root+"/versions?afterOrganizationVersion="+after+"&limit=20"),view,()=>current(op,key));
   for(const v of history.versions.filter(v=>v.version.runId===run.runId)){const original=run.candidates.find(c=>c.id===v.version.candidateId),saved=asCandidate(v.version);if(!original||Object.keys(saved).some(k=>saved[k as keyof Candidate]!==original[k as keyof Candidate]))throw new MaterialBindingError();}
   const locations:Record<string,Location>={};for(const c of run.candidates){locations[c.id]=await locate(c,view);if(!current(op,key))return;}
   if(exact){const saved=history.versions.find(v=>v.version.versionId===exact!.version.versionId);if(!saved||JSON.stringify(saved.version)!==JSON.stringify(exact.version)||exact.currentOrganizationVersion!==history.head)throw new MaterialBindingError();session.clearConfirmed(id);}
   if(!current(op,key))return;setSelected(runId);setSnapshot({run,locations,versions:history.versions,head:history.head});
  }catch(cause){if(!current(op,key))return;const failure=cause instanceof Error?cause:new Error("材料整理读取失败。");setSnapshot(null);setError(failure);
   if(failure instanceof AdjudicationError&&(failure.status===401||failure.status===403)){setEditor(null);onDirty(false);session.scrub();onDenied(failure);}
  }finally{if(current(op,key)){setLoading(false);onBusy(false);}}
 }
 function edit(candidate:Candidate,action:Action,base?:DecisionView) {
  if(writeDisabled||snapshot?.locations[candidate.id]?.error)return;
  if(editor&&!window.confirm("放弃未提交的人工整理内容？"))return;
  setEditor({candidate,action,baseVersionId:base?.version.versionId??null,humanText:base?.version.humanText??"",reason:""});onDirty(true);setError(null);
 }
 function canSubmit() {
  if(writeDisabled||!editor||snapshot?.locations[editor.candidate.id]?.error)return false;
  const current=snapshot!.versions.find(v=>v.version.runId===snapshot!.run.runId&&v.version.candidateId===editor.candidate.id&&v.currentCandidateDecision);
  return editor.baseVersionId?current?.version.versionId===editor.baseVersionId&&!current.stale&&Date.parse(current.version.expiresAt)>Date.now():!current;
 }
 function confirm(raw:unknown,p:Packet){return bindConfirmed(raw,p.binding,view);}
 async function finish(value:DecisionView|undefined,key:string) {
  if(!alive.current||key!==context.current||!isCurrent())return;
  const failure=session.state(id).error;
  if(failure instanceof AdjudicationError&&(failure.status===401||failure.status===403)){setSnapshot(null);setEditor(null);onDirty(false);onDenied(failure);return;}
  if(value){setSnapshot(null);setEditor(null);onDirty(false);await onConfirmed(value);}
  else if(session.state(id).packet||session.state(id).unknown)setSnapshot(null);
 }
 async function submit() {
  if(!canSubmit()||!snapshot||!editor)return;const key=context.current;setError(null);
  const body={expectedWorkVersion:view.version,expectedOrganizationVersion:snapshot.head,action:editor.action,...(editor.action==="EDIT"?{humanText:editor.humanText}:{}),...(editor.reason!==""?{reason:editor.reason}:{})};
  const suffix=editor.baseVersionId?"/versions/"+encodeURIComponent(editor.baseVersionId)+"/revisions":"/runs/"+encodeURIComponent(snapshot.run.runId)+"/candidates/"+encodeURIComponent(editor.candidate.id)+"/decisions";
  try{const value=await session.begin(token,root+suffix,{caseId:id,runId:snapshot.run.runId,candidate:editor.candidate,baseVersionId:editor.baseVersionId,inputVersion:view.inputVersion,body},confirm,()=>alive.current&&key===context.current&&isCurrent());await finish(value,key);}catch(cause){if(alive.current&&key===context.current)setError(cause as Error);}
 }
 async function retry(){if(disabled||loading||!state.packet||state.busy)return;const key=context.current;await finish(await session.retry(token,id,confirm,()=>alive.current&&key===context.current&&isCurrent()),key);}
 async function refresh(){const key=context.current;const ok=await onRefresh();if(ok&&alive.current&&key===context.current)void load(selected);}
 const failure=error??state.error;
 return <Panel title="材料整理"><div className="adj-case-entry"><label>已有整理回合<select aria-label="已有整理回合" value={selected} disabled={disabled||loading||state.busy||!!state.packet||!!state.unknown||!!editor} onChange={e=>setSelected(e.target.value)}><option value="">请选择</option>{ids.map(runId=><option key={runId} value={runId}>{runId}</option>)}</select></label><button title="刷新材料整理" aria-label="刷新材料整理" disabled={disabled||loading||state.busy||!!state.packet} onClick={()=>void refresh()}><RefreshCw size={16} aria-hidden/></button></div>
 {!ids.length&&<p>尚无已有材料整理回合。</p>}{loading&&<p role="status">正在读取材料整理…</p>}
 {failure&&<p role="alert" className="adj-error">{failure.message}{failure instanceof AdjudicationError?" · "+failure.code:""}</p>}
 {state.packet&&<div role="status" className="adj-state warning"><p>整理结果未确认。原请求键：{state.packet.key}</p><button disabled={disabled||state.busy||loading} onClick={()=>void retry()}>对账同一整理请求</button></div>}
 {state.unknown&&<p role="status" className="adj-state warning">整理结果未确认，敏感请求已清空。此案件仅可读取。</p>}
 {state.confirmedId&&!snapshot&&<p role="status" className="adj-receipt">整理写入已确认，后续只需读取版本。</p>}
 {snapshot&&<><p>输入版本 {snapshot.run.inputVersion} · 整理头 {snapshot.head} · {snapshot.run.model}{snapshot.run.stale&&<strong className="adj-danger"> · 回合已失效</strong>}</p>
 {snapshot.run.candidates.map(c=><MaterialOrganizationRead key={c.id} candidate={c} location={snapshot.locations[c.id]} view={view} decision={snapshot.versions.find(v=>v.version.runId===snapshot.run.runId&&v.version.candidateId===c.id&&v.currentCandidateDecision)} disabled={writeDisabled||!!editor} onAction={action=>edit(c,action)}/>)}
 <DecisionHistory versions={snapshot.versions.filter(v=>v.version.runId===snapshot.run.runId)} view={view} disabled={writeDisabled||!!editor} onRevise={v=>edit(asCandidate(v.version),"EDIT",v)}/></>}
 {editor&&<form aria-label="人工整理决定" onSubmit={e=>{e.preventDefault();void submit();}}><h3>{editor.baseVersionId?"更正人工版本":editor.action==="ACCEPT"?"接受候选":editor.action==="EDIT"?"修改候选":"拒绝候选"} · {editor.candidate.id}</h3>
 {editor.action==="EDIT"&&<label>人工陈述<textarea required value={editor.humanText} disabled={state.busy||!!state.packet||!!state.unknown||disabled} onChange={e=>setEditor({...editor,humanText:e.target.value})}/></label>}
 <label>{editor.action==="ACCEPT"?"接受理由（可选）":"理由"}<textarea required={editor.action!=="ACCEPT"} value={editor.reason} disabled={state.busy||!!state.packet||!!state.unknown||disabled} onChange={e=>setEditor({...editor,reason:e.target.value})}/></label>
 <button type="submit" disabled={!canSubmit()}>确认{editor.action==="ACCEPT"?"接受":editor.action==="EDIT"?"修改":"拒绝"}</button><button type="button" disabled={state.busy||!!state.packet||!!state.unknown} onClick={()=>{setEditor(null);onDirty(false);}}>放弃编辑</button></form>}
 </Panel>;
}
