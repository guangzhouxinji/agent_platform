import {useEffect,useRef,useState} from 'react';
import {RefreshCw} from 'lucide-react';
import {path,request,ConsultationError} from './api';
import type {MatrixSummary} from './comparisonMatrix';
import {bindMatrixLineage,lineageKey,type MatrixLineage,type LineageRow} from './comparisonLineage';
import ComparisonParentMatrix from './ComparisonParentMatrix';

export default function ComparisonMatrixLineage({token,summary,disabled,onDenied}:{token:string;summary:MatrixSummary;disabled:boolean;onDenied:()=>void}){
 const [value,setValue]=useState<MatrixLineage|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 const epoch=useRef(0),binding=JSON.stringify([token,summary.taskId,summary.comparisonId,summary.inputSha,disabled]);
 const [owner,setOwner]=useState('');
 useEffect(()=>{epoch.current++;setValue(null);setError('');setBusy(false);return()=>{epoch.current++}},[binding]);
 useEffect(()=>{if(!value)return;let timer:ReturnType<typeof setTimeout>;const expire=()=>{const remaining=Date.parse(value.expiresAt)-Date.now();if(remaining<=0){epoch.current++;setValue(null);setError('沿用读取已到期。')}else timer=setTimeout(expire,Math.min(remaining,2147483647))};expire();return()=>clearTimeout(timer)},[value]);
 async function read(){if(disabled||busy)return;const op=++epoch.current;setBusy(true);setError('');setValue(null);
  try{const response=await request<unknown>(token,path(summary.taskId)+'/patent-comparisons/'+encodeURIComponent(summary.comparisonId)+'/lineage');if(op!==epoch.current)return;const result=bindMatrixLineage(summary,response);setOwner(binding);setValue(result)}
  catch(e){if(op!==epoch.current)return;setValue(null);setError(e instanceof Error?e.message:'沿用读取失败');if(e instanceof ConsultationError&&[401,403,404].includes(e.status))onDenied()}
  finally{if(op===epoch.current)setBusy(false)}
 }
 const visible=!disabled&&owner===binding&&value&&Date.parse(value.expiresAt)>Date.now();
 const status=(r:LineageRow)=>summary.candidates.find(c=>c.candidateId===r.candidateId)?.status;
 const pending=value?.recompute.filter(r=>!['COMMITTED','CANCELLED'].includes(status(r)??''))??[];
 const completed=value?.recompute.filter(r=>status(r)==='COMMITTED')??[],cancelled=value?.recompute.filter(r=>status(r)==='CANCELLED')??[];
 const label=(r:LineageRow)=>`${summary.candidates.findIndex(c=>c.candidateId===r.candidateId)+1>0?'候选 '+(summary.candidates.findIndex(c=>c.candidateId===r.candidateId)+1):'原候选'} · 权项 ${r.claimBranch.join(' → ')} · ${summary.confirmedFeatures.find(f=>f.featureId===r.featureId)?.userText??'原特征 '+r.featureId}`;
 return <div aria-busy={busy}>
  <h3>版本沿用</h3><button type="button" disabled={disabled||busy} onClick={()=>void read()}><RefreshCw size={16} aria-hidden="true"/>读取沿用关系</button>
  {error&&<p role="alert">{error}</p>}
  {visible&&<><p role="status">沿用参考 {value.carried.length} · 已失效 {value.invalidated.length} · 待重新计算 {pending.length}</p>
   <details><summary>版本依据</summary><p style={{overflowWrap:'anywhere'}}>父分析 {value.previousComparisonId} · Input SHA {value.parentInputSha}<br/>配置版本 {value.configurationVersion} · SHA {value.configurationSha}</p></details>
   <details><summary>沿用参考</summary><ul>{value.carried.map(ref=><li key={lineageKey(ref.key)}>{label(ref.key)}<details><summary>原报告引用</summary><p style={{overflowWrap:'anywhere'}}>报告 {ref.reportId} · SHA {ref.reportSha}<br/>行 SHA {ref.rowSha}</p></details></li>)}</ul></details>
   <details><summary>已失效</summary><ul>{value.invalidated.map(r=><li key={lineageKey(r)}>{label(r)}</li>)}</ul></details>
   <details><summary>待重新计算</summary><ul>{pending.map(r=><li key={lineageKey(r)}>{label(r)}</li>)}</ul></details>
   {completed.length>0&&<details open><summary>本版已提交（父版差异）</summary><ul>{completed.map(r=><li key={lineageKey(r)}>{label(r)}</li>)}</ul></details>}
   {cancelled.length>0&&<details><summary>本版已取消（父版差异）</summary><ul>{cancelled.map(r=><li key={lineageKey(r)}>{label(r)}</li>)}</ul></details>}
   <ComparisonParentMatrix token={token} lineage={value} disabled={disabled||busy} onDenied={()=>{epoch.current++;setValue(null);setBusy(false);onDenied()}}/>
  </>}
 </div>;
}
