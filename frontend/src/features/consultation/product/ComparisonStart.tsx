import {useEffect,useRef,useState} from 'react';
import {request,ConsultationError} from './api';
import {candidateLabel,startStatus,failureReason} from './comparisonPresentation';
import type {InputReceipt} from './comparisonInputContracts';
import {bindStart,startPacket,startPath,type StartPacket,type StartReceipt,type RecomputeSelection} from './comparisonStartContracts';
/** Explicit single-candidate handoff. Unknown submission is only reconciled by GET. */
export default function ComparisonStart({token,input,disabled,onDenied,onResult,candidateDocuments={},recompute}:{token:string;input:InputReceipt;disabled:boolean;onDenied:()=>void;onResult:()=>void;candidateDocuments?:Record<string,{documentId:string;documentVersion:string}>;recompute?:RecomputeSelection}) {
 const scope=token+':'+input.taskId+':'+input.taskVersion+':'+input.comparisonId+':'+input.inputSha+':'+(recompute?.configurationSha??'normal');
 const retryProof=useRef<StartReceipt|null>(null);
 const owner=useRef(scope),availability=useRef(disabled),epoch=useRef(0),pending=useRef<StartPacket|null>(null),inFlight=useRef(false),denied=useRef(false);
 const [shown,setShown]=useState(scope),[candidate,setCandidate]=useState(''),[packet,setPacket]=useState<StartPacket|null>(null),[receipt,setReceipt]=useState<StartReceipt|null>(null);
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[committed,setCommitted]=useState<string[]>([]),[accessDenied,setAccessDenied]=useState(false),[lookupReadOnly,setLookupReadOnly]=useState(false);
 if(owner.current!==scope){owner.current=scope;retryProof.current=null;epoch.current++;pending.current=null;inFlight.current=false;denied.current=false;}
 // Invalidate late responses in render, without waiting for the pause effect.
 if(availability.current!==disabled){availability.current=disabled;epoch.current++;inFlight.current=false;}
 useEffect(()=>{epoch.current++;pending.current=null;retryProof.current=null;denied.current=false;setShown(scope);setCandidate('');setPacket(null);setReceipt(null);setError('');setCommitted([]);setBusy(false);setAccessDenied(false);setLookupReadOnly(false);inFlight.current=false;return()=>{epoch.current++;pending.current=null}},[scope]);
 useEffect(()=>{if(disabled){retryProof.current=null;epoch.current++;inFlight.current=false;setReceipt(null);setBusy(false);setError('')}},[disabled]);
 const visible=shown===scope&&!disabled,unavailable=!visible||accessDenied;
 async function perform(read:boolean) {
  if(unavailable||denied.current||inFlight.current||(!read&&lookupReadOnly))return;
  let original=pending.current;
  if(!read&&original)return;
  const op=++epoch.current;inFlight.current=true;setBusy(true);setError('');setReceipt(null);
  try {
   if(!original){
    if(!candidate)return;
    const root=startPath(input,candidate);
    setLookupReadOnly(true);
    const saved=await request<{receipt:StartReceipt|null}>(token,root+'/latest?inputSha='+encodeURIComponent(input.inputSha));
    if(op!==epoch.current)return;
    if(!saved||!Object.prototype.hasOwnProperty.call(saved,'receipt'))throw new Error('候选回执查询返回不合法，未发起计算。');
    // Only explicit null is a proven absence; false/undefined must never authorize a fresh POST.
    if(saved.receipt!==null){
     original={path:root,key:saved.receipt.requestId,body:{expectedTaskVersion:input.taskVersion,inputSha:input.inputSha},candidateId:candidate};
     const bound=bindStart(input,original,saved.receipt);
     const freshExplicit=!read&&['FAILED','ADMISSION_REQUIRED'].includes(bound.status)
      &&retryProof.current?.status===bound.status&&retryProof.current.commandId===bound.commandId&&retryProof.current.requestId===bound.requestId
      &&retryProof.current.attemptId===bound.attemptId&&retryProof.current.errorCode===bound.errorCode;
     if(!freshExplicit){pending.current=original;setPacket(original);setReceipt(bound);setLookupReadOnly(false);
     if(bound.status==='COMMITTED'){setCommitted(values=>values.includes(bound.candidateId)?values:[...values,bound.candidateId]);onResult();}
     return;
     }
     retryProof.current=null;original=null;
    }
    setLookupReadOnly(false);
    if(read){setError('此候选尚无本会话主体的启动回执；未发起计算。');return;}
    original=startPacket(input,candidate,recompute);pending.current=original;setPacket(original);
   }
   const current=original;
   const raw=await request<StartReceipt>(token,read?current.path+'?requestId='+encodeURIComponent(current.key):current.submitPath??current.path,read?undefined:current.body,read?undefined:current.key);
   if(op!==epoch.current)return;
   const bound=bindStart(input,current,raw);setReceipt(bound);
   if(bound.status==='COMMITTED'){setCommitted(values=>values.includes(bound.candidateId)?values:[...values,bound.candidateId]);onResult();}
  } catch(e) {
   if(op!==epoch.current)return;
   setError(e instanceof Error?e.message:'启动结果未知，请查询原请求。');
   if(e instanceof ConsultationError&&[401,403,404].includes(e.status)){
    denied.current=true;pending.current=null;setAccessDenied(true);setPacket(null);setReceipt(null);setCommitted([]);setCandidate('');onDenied();
   }
  } finally {if(op===epoch.current){inFlight.current=false;setBusy(false)}}
 }
 function another(){
  if(unavailable||busy||!receipt||!['COMMITTED','FAILED','ADMISSION_REQUIRED'].includes(receipt.status))return;
  retryProof.current=['FAILED','ADMISSION_REQUIRED'].includes(receipt.status)?receipt:null;pending.current=null;setPacket(null);setReceipt(null);setCandidate('');setError('');setLookupReadOnly(false);
 }
 return <section aria-label="显式发起单候选对照" aria-busy={busy}>
  <h4>发起所选候选计算</h4><p>一次明确请求仅交接一个候选。断线不是取消；未知结果仅查原回执，不会自动重发。</p>
  <label>计算候选<select aria-label="计算候选" value={candidate} disabled={unavailable||busy||!!packet||lookupReadOnly} onChange={e=>setCandidate(e.target.value)}><option value="">请选择一个候选</option>{input.candidates.filter(c=>c.status!=='COMMITTED'&&!committed.includes(c.candidateId)&&(!recompute||recompute.candidateIds.includes(c.candidateId))).map(c=><option key={c.candidateId} value={c.candidateId}>{candidateLabel(input.candidates.indexOf(c),candidateDocuments[c.candidateId])}</option>)}</select></label>
  <button disabled={unavailable||busy||!!packet||lookupReadOnly||!candidate} onClick={()=>void perform(false)}>{busy?'正在处理原请求':'明确发起此候选计算'}</button>
  {visible&&!packet&&<button disabled={unavailable||busy||!candidate} onClick={()=>void perform(true)}>恢复此候选已有回执</button>}
  {visible&&packet&&<><p role="status">{startStatus(receipt?.status)}</p><button disabled={unavailable||busy} onClick={()=>void perform(true)}>查询原启动回执</button></>}
  {visible&&receipt&&['COMMITTED','FAILED','ADMISSION_REQUIRED'].includes(receipt.status)&&<button disabled={unavailable||busy} onClick={another}>返回候选选择</button>}
  {visible&&receipt?.status==='ADMISSION_REQUIRED'&&<p role="status">受管服务已接收此候选交接，但当前计算准入未满足；尚未开始计算，也没有提交对照结果。查询原回执不会自动执行。</p>}
  {visible&&receipt?.status==='FAILED'&&<p role="status">{failureReason(receipt.errorCode)}仅对账不会重新计算其他候选。</p>}
  {visible&&receipt?.status==='CANCELLED'&&<p>任务已取消；仅可查询原回执，不在此输入上重新发起。请重新读取当前任务。</p>}
  {visible&&error&&<p role="alert">{error}</p>}
 </section>;
}
