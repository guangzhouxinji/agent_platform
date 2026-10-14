import {useEffect,useRef,useState} from 'react';
import {path,request} from './api';
import {candidateLabel,candidateStatus,failureReason} from './comparisonPresentation';
import {bindMatrixSummary,bindMatrixReport,type MatrixSummary,type MatrixReport} from './comparisonMatrix';
import ComparisonMatrixLineage from './ComparisonMatrixLineage';
import ComparisonMatrixReport from './ComparisonMatrixReport';
import type {HumanReportSelection} from './HumanRequestPanel';
/** Read-only actual product results. No browser advance, Worker credentials, or model dispatch. */
export default function ComparisonMatrixResults({token,taskId,disabled,onDenied,defaultComparisonId="",defaultInputSha="",onReport}:{token:string;taskId:string;disabled:boolean;onDenied:()=>void;defaultComparisonId?:string;defaultInputSha?:string;onReport?:(report:HumanReportSelection|null)=>void}){
 const [comparison,setComparison]=useState(''),[summary,setSummary]=useState<MatrixSummary|null>(null),[report,setReport]=useState<MatrixReport|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');const epoch=useRef(0);const [owner,setOwner]=useState<{token:string;taskId:string;comparisonId:string;inputSha:string}|null>(null);
 const reportCallback=useRef(onReport);reportCallback.current=onReport;
 const [now,setNow]=useState(Date.now);
 useEffect(()=>{reportCallback.current?.(null);return()=>reportCallback.current?.(null)},[token,taskId,disabled,defaultComparisonId,defaultInputSha]);
 useEffect(()=>{if(!report)return;const delay=Date.parse(report.result.expiresAt)-Date.now();if(delay<=0){setReport(null);reportCallback.current?.(null);return;}const timer=setTimeout(()=>{setNow(Date.now());if(Date.parse(report.result.expiresAt)<=Date.now()){setReport(null);reportCallback.current?.(null)}},Math.min(2147483647,delay));return()=>clearTimeout(timer)},[report,now]);
 const visible=!disabled&&owner?.token===token&&owner.taskId===taskId&&owner.comparisonId===defaultComparisonId&&owner.inputSha===defaultInputSha;
 useEffect(()=>{epoch.current++;setSummary(null);setReport(null);setComparison('');setError('');setBusy(false);return()=>{epoch.current++}},[token,taskId]);
 useEffect(()=>{epoch.current++;setSummary(null);setReport(null);setError('');setBusy(false)},[disabled]);
 useEffect(()=>{epoch.current++;setComparison(defaultComparisonId);setSummary(null);setReport(null);setError('');setBusy(false)},[defaultComparisonId,defaultInputSha,token,taskId]);
 function edited(value:string){reportCallback.current?.(null);epoch.current++;setComparison(value);setSummary(null);setReport(null);setError('');setBusy(false)}
 async function read(candidate?:string,reportId?:string){if(disabled||busy||!comparison.trim())return;reportCallback.current?.(null);const op=++epoch.current;setBusy(true);setError('');setReport(null);if(!reportId)setSummary(null);
  try{if(defaultInputSha&&comparison!==defaultComparisonId)throw new Error("分析编号与已确认输入不一致，请重新关联当前输入。");const root=path(taskId)+'/patent-comparisons/'+encodeURIComponent(comparison);if(reportId&&candidate&&summary){const response=await request<MatrixReport>(token,root+'/reports/'+encodeURIComponent(reportId));if(op!==epoch.current)return;const value=await bindMatrixReport(summary,candidate,reportId,response);if(op!==epoch.current)return;setOwner({token,taskId,comparisonId:defaultComparisonId,inputSha:defaultInputSha});setReport(value)}
   else{const response=await request<MatrixSummary>(token,root+'/results');if(op!==epoch.current)return;const value=bindMatrixSummary(taskId,comparison,response,defaultInputSha||undefined);setOwner({token,taskId,comparisonId:defaultComparisonId,inputSha:defaultInputSha});setSummary(value)}}
  catch(e){if(op!==epoch.current)return;setReport(null);setSummary(null);setError(e instanceof Error?e.message:'矩阵读取失败');if(e instanceof Error&&'status' in e&&(e.status===401||e.status===403||e.status===404))onDenied()}
  finally{if(op===epoch.current)setBusy(false)}}
 return <section className="consultation-panel" aria-labelledby="comparison-result-heading" aria-busy={busy}>
  <h2 id="comparison-result-heading">产品逐项对照结果</h2><p>读取已保存分析，不新建任务或调用模型。已提交不等于专业验收或正式业务办结；尚无经标定数值评分。</p>
  <details open={!defaultInputSha}><summary>{defaultInputSha?'已关联输入的分析编号':'读取已有分析'}</summary><label htmlFor="comparison-id">分析编号</label><input id="comparison-id" value={comparison} readOnly={!!defaultInputSha} disabled={disabled||busy} onChange={e=>edited(e.target.value)}/></details>
  <button disabled={disabled||busy||!comparison.trim()} onClick={()=>void read()}>读取对照结果</button>{error&&<p role="alert">{error}</p>}
  {visible&&summary&&<><p role="status">{summary.state==='PARTIAL'?'部分完成：成功候选保留，其他候选未完成':summary.state==='COMMITTED'?'本批候选已提交':'尚未全部提交'}</p><details><summary>输入版本与追溯信息</summary><p>分析编号 {summary.comparisonId} · Input SHA {summary.inputSha}</p></details>
   <ul>{summary.confirmedFeatures.map(f=><li key={f.featureId}>{f.userText}</li>)}</ul>
   <ComparisonMatrixLineage key={JSON.stringify([token,taskId,summary.comparisonId,summary.inputSha])} token={token} summary={summary} disabled={disabled||busy} onDenied={()=>{reportCallback.current?.(null);epoch.current++;setSummary(null);setReport(null);setBusy(false);onDenied()}}/>
   {summary.candidates.map((c,i)=><article key={c.candidateId}><h3>{candidateLabel(i)}</h3><p>{candidateStatus(c.status)} {c.errorCode?failureReason(c.errorCode):''} {c.executionState==='RECONCILE_REQUIRED'?'未知执行结果需对账，不能盲重发':''}</p>{c.reportIds.map((id,version)=><button key={id} disabled={disabled||busy} onClick={()=>void read(c.candidateId,id)}>查看第{version+1}份矩阵</button>)}</article>)}</>}
  {visible&&report&&Date.parse(report.result.expiresAt)>Math.max(now,Date.now())&&<><ComparisonMatrixReport report={report} features={summary?.confirmedFeatures??[]}/>{onReport&&defaultInputSha&&report.result.comparisonId===defaultComparisonId&&report.result.inputSha===defaultInputSha&&<button disabled={disabled||busy} onClick={()=>{if(Date.parse(report.result.expiresAt)>Date.now())onReport({comparisonId:report.result.comparisonId,inputSha:report.result.inputSha,reportId:report.result.reportId,expiresAt:report.result.expiresAt})}}>选择此矩阵用于人工请求</button>}</>}
 </section>;
}


