import {useEffect,useRef,useState} from 'react';
import {FileText,X} from 'lucide-react';
import {ConsultationError,path,request} from './api';
import type {MatrixLineage,CarryReference} from './comparisonLineage';
import {bindParentMatrix,type ParentMatrix} from './comparisonParentContracts';
import ComparisonMatrixReport from './ComparisonMatrixReport';
export default function ComparisonParentMatrix({token,lineage,disabled,onDenied}:{token:string;lineage:MatrixLineage;disabled:boolean;onDenied:()=>void}){
 const [value,setValue]=useState<ParentMatrix|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false),[owner,setOwner]=useState('');
 const epoch=useRef(0),binding=JSON.stringify([token,lineage,disabled]);
 useEffect(()=>{epoch.current++;setValue(null);setError('');setBusy(false);return()=>{epoch.current++}},[binding]);
 useEffect(()=>{if(!value)return;let timer:ReturnType<typeof setTimeout>;const expire=()=>{const left=Date.parse(value.expiresAt)-Date.now();if(left<=0){epoch.current++;setValue(null);setBusy(false);setError('父矩阵读取已到期。')}else timer=setTimeout(expire,Math.min(left,2147483647))};expire();return()=>clearTimeout(timer)},[value]);
 function clear(){epoch.current++;setValue(null);setError('');setBusy(false)}
 async function read(ref:CarryReference){if(disabled||busy||Date.parse(lineage.expiresAt)<=Date.now())return;const op=++epoch.current;setBusy(true);setError('');setValue(null);
  try{const response=await request<unknown>(token,path(lineage.taskId)+'/patent-comparisons/'+encodeURIComponent(lineage.comparisonId)+'/parent-reports/'+encodeURIComponent(ref.reportId));if(op!==epoch.current)return;
   const result=await bindParentMatrix(lineage,ref,response);if(op!==epoch.current)return;setOwner(binding);setValue(result)}
  catch(e){if(op!==epoch.current)return;setValue(null);setError(e instanceof Error?e.message:'父矩阵读取失败');if(e instanceof ConsultationError&&[401,403,404].includes(e.status))onDenied()}
  finally{if(op===epoch.current)setBusy(false)}
 }
 const references=[...new Map(lineage.carried.map(r=>[r.reportId,r])).values()];
 return <div aria-busy={busy}>
  {references.map((ref,i)=><button type="button" key={ref.reportId} disabled={disabled||busy} onClick={()=>void read(ref)}><FileText size={16} aria-hidden="true"/>查看沿用原矩阵 {i+1}</button>)}
  {error&&<p role="alert">{error}</p>}
  {!disabled&&owner===binding&&value&&Date.parse(value.expiresAt)>Date.now()&&<><button type="button" title="关闭父矩阵" aria-label="关闭父矩阵" onClick={clear}><X size={16} aria-hidden="true"/></button><ComparisonMatrixReport report={value.report} features={value.confirmedFeatures} parent/></>}
 </div>;
}
