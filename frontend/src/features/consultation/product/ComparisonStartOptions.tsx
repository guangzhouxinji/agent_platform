import {useEffect,useId,useRef,useState,type ComponentProps} from 'react';
import {RefreshCw} from 'lucide-react';
import ComparisonStart from './ComparisonStart';
import {path,request,ConsultationError} from './api';
import {bindMatrixSummary} from './comparisonMatrix';
import {bindMatrixLineage,lineageKey,type MatrixLineage} from './comparisonLineage';
import type {RecomputeSelection} from './comparisonStartContracts';

export function recomputeSelection(plan:MatrixLineage):RecomputeSelection {
 const candidates=new Set(plan.expectedRows.map(r=>r.candidateId)),pending=new Set(plan.recompute.map(lineageKey));
 const selected:string[]=[];
 for(const candidate of candidates){const rows=plan.expectedRows.filter(r=>r.candidateId===candidate),count=rows.filter(r=>pending.has(lineageKey(r))).length;
  if(count!==0&&count!==rows.length)throw new Error('此候选存在混合沿用与待算，不能扩大计算范围。');
  if(count===rows.length)selected.push(candidate);
 }
 return {configurationSha:plan.configurationSha,candidateIds:selected,expiresAt:plan.expiresAt};
}
export default function ComparisonStartOptions(props:ComponentProps<typeof ComparisonStart>){
 const [mode,setMode]=useState<'normal'|'affected'>('normal'),[selection,setSelection]=useState<RecomputeSelection|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const [currentInput,setCurrentInput]=useState(props.input);
 const epoch=useRef(0),id=useId(),scope=JSON.stringify([props.token,props.input.taskId,props.input.comparisonId,props.input.inputSha,props.disabled]);
 const [owner,setOwner]=useState('');
 useEffect(()=>{epoch.current++;setSelection(null);setBusy(false);setError('');return()=>{epoch.current++}},[scope]);
 useEffect(()=>{if(!selection)return;let timer:ReturnType<typeof setTimeout>;const expire=()=>{const remaining=Date.parse(selection.expiresAt)-Date.now();if(remaining<=0){epoch.current++;setSelection(null);setError('受影响候选依据已到期。')}else timer=setTimeout(expire,Math.min(remaining,2147483647))};expire();return()=>clearTimeout(timer)},[selection]);
 function change(next:'normal'|'affected'){epoch.current++;setMode(next);setSelection(null);setBusy(false);setError('')}
 async function read(){if(props.disabled||busy)return;const op=++epoch.current;setBusy(true);setError('');setSelection(null);
  try{const root=path(props.input.taskId)+'/patent-comparisons/'+encodeURIComponent(props.input.comparisonId);
   const summary=bindMatrixSummary(props.input.taskId,props.input.comparisonId,await request(props.token,root+'/results'),props.input.inputSha);if(op!==epoch.current)return;
   if(summary.candidates.length!==props.input.candidates.length||summary.candidates.some(c=>!props.input.candidates.some(i=>i.candidateId===c.candidateId)))throw new Error('当前候选域与确认输入不同。');
   const plan=bindMatrixLineage(summary,await request(props.token,root+'/lineage'));if(op!==epoch.current)return;
   const selected=recomputeSelection(plan);
   selected.candidateIds=selected.candidateIds.filter(id=>summary.candidates.some(c=>c.candidateId===id&&!['COMMITTED','CANCELLED'].includes(c.status)));
   setCurrentInput({...props.input,candidates:props.input.candidates.map(c=>({candidateId:c.candidateId,status:summary.candidates.find(s=>s.candidateId===c.candidateId)!.status}))});
   setOwner(scope);setSelection(selected);
  }catch(e){if(op!==epoch.current)return;setError(e instanceof Error?e.message:'受影响候选读取失败');if(e instanceof ConsultationError&&[401,403,404].includes(e.status))props.onDenied()}
  finally{if(op===epoch.current)setBusy(false)}
 }
 const active=owner===scope&&!props.disabled&&selection&&Date.parse(selection.expiresAt)>Date.now();
 return <div><fieldset disabled={props.disabled}><legend>计算范围</legend>
  <label><input type="radio" name={id} checked={mode==='normal'} onChange={()=>change('normal')}/>所选候选</label>
  <label><input type="radio" name={id} checked={mode==='affected'} onChange={()=>change('affected')}/>受影响候选</label>
 </fieldset>
 {mode==='normal'?<ComparisonStart {...props}/>:<><button type="button" disabled={props.disabled||busy} onClick={()=>void read()}><RefreshCw size={16} aria-hidden="true"/>读取受影响候选</button>
  {error&&<p role="alert">{error}</p>}{active&&(selection.candidateIds.length?<ComparisonStart {...props} input={currentInput} recompute={selection}/>:<p role="status">当前没有待重新计算的候选。</p>)}</>}
 </div>;
}
