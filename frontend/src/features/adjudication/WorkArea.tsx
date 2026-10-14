import { useEffect,useRef,useState } from "react";
import AgentTaskPanel from "../agent/AgentTaskPanel";
import type { AgentRunView } from "../agent/AgentTaskPanel";
import { AdjudicationError,casePath,request } from "./api";
import type { CaseView,Version } from "./contract";
import { bindReportHandoff } from "./reportHandoff";
import type { ReportHandoff } from "./reportHandoff";

export type WorkAreaProps={
 token:string; view:CaseView; disabled?:boolean; historical?:boolean;
 onDenied:()=>void;
 /** Parent navigates to its existing Reports editor/review, never submits a command.
  * Return false when the existing unsaved-edit guard cancels navigation. */
 onOpenReport:(target:ReportHandoff,current:CaseView,versions:Version[])=>void|boolean|Promise<void|boolean>;
};
type Binding={scope:string;run:AgentRunView;target:ReportHandoff};
/** Domain-specific result bridge. JWT stays in the existing domain request adapter. */
export default function WorkArea({token,view,disabled=false,historical=false,onDenied,onOpenReport}:WorkAreaProps){
 const scope=token+":"+view.caseInput.id+":"+view.caseInput.organization+":"+view.caseInput.dataScope;
 const context=scope+":"+view.version+":"+view.inputVersion+":"+historical+":"+disabled;
 const [panelReset,setPanelReset]=useState(0);
 const [binding,setBinding]=useState<Binding|null>(null),[busy,setBusy]=useState(false),[message,setMessage]=useState("");
 const epoch=useRef(0),currentContext=useRef(context);currentContext.current=context;
 useEffect(()=>{epoch.current++;setBinding(null);setBusy(false);setMessage("");return()=>{epoch.current++}},[context]);
 const root=casePath(view.caseInput.id),visible=binding?.scope===context&&!historical&&!disabled?binding:null;
 async function agentRequest<T>(suffix:string,body?:unknown,key?:string):Promise<T>{
  const result=await request<T>(token,root+"/agent-runs"+suffix,body,key);
  const actual=result as AgentRunView;
  // Do not let even the shared renderer receive a cross-case or wrong-run response.
  if(actual.checkpoint?.task?.id!==view.caseInput.id)throw new AdjudicationError(409,"AGENT_CASE_INPUT_STALE");
  const selected=suffix.split("/")[1];
  if(selected&&actual.checkpoint.runId!==decodeURIComponent(selected))throw new AdjudicationError(409,"AGENT_RUN_MISMATCH");
  return result;
 }
 function denied(){epoch.current++;setPanelReset(v=>v+1);setBinding(null);setBusy(false);setMessage("");onDenied()}
 async function reconcile(run:AgentRunView,opening=false){
  const op=++epoch.current,captured=context;setBinding(null);setMessage("");setBusy(true);
  const active=()=>op===epoch.current&&currentContext.current===captured;
  try{
   // Recheck the selected Run under current authorization, then current report/head.
   const actual=await request<AgentRunView>(token,root+"/agent-runs/"+encodeURIComponent(run.checkpoint.runId));
   if(!active())return;
   if(actual.checkpoint.runId!==run.checkpoint.runId||actual.checkpoint.version!==run.checkpoint.version)throw new Error("RUN_CHANGED");
   const [fresh,versions]=await Promise.all([request<CaseView>(token,root),request<Version[]>(token,root+"/versions")]);
   if(!active())return;
   if(fresh.inputVersion!==view.inputVersion||fresh.caseInput.id!==view.caseInput.id||fresh.caseInput.organization!==view.caseInput.organization||fresh.caseInput.dataScope!==view.caseInput.dataScope)throw new Error("CASE_CHANGED");
   const target=await bindReportHandoff(actual,fresh,versions);
   if(!active())return;
   if(!target){setMessage("运行结果尚未关联当前有效报告。请重新读取运行和案件；不会展示旧稿或推定专业结论。");return}
   if(opening){
    // All reads above are repeated on click; no cached authorization or report wins.
    const accepted=await onOpenReport(target,fresh,versions);
    if(!active())return;
    if(accepted===false)return;
   }
   setBinding({scope:captured,run:actual,target});
  }catch(error){
   if(!active())return;
   const status=(error as {status?:number}).status;
   if(status===401||status===403){denied();return}
   setPanelReset(v=>v+1);
   setMessage("当前运行、权限或报告版本未能核实，已隐藏交接结果。请重新读取，不复用缓存报告。");
  }finally{if(active())setBusy(false)}
 }
 function onView(value:AgentRunView|null){
  epoch.current++;setBinding(null);setMessage("");setBusy(false);
  if(value&&!historical&&!disabled)void reconcile(value);
 }
 if(historical||!token)return null;
 return <section aria-label="裁决任务与报告交接" aria-busy={busy}>
  <AgentTaskPanel key={context+":"+panelReset} scope={context} disabled={disabled||busy}
   startInput={{expectedVersion:view.version,modelMaterialIds:[]}}
   request={agentRequest}
   onDenied={denied} onView={onView}/>
  <p>任务仅整理已登记材料、争点、引用与人工要素对应。官方专利状态、有效性及等同等专业分析仍须核实；读取记录或识别缺口不等于自主专业分析。未选择模型材料，不发送模型材料。</p>
  {message&&<p role="status">{message}</p>}
  {visible&&<div className="adj-panel" role="status">
   <h3>当前报告人工交接</h3>
   <p>运行 {visible.target.runId} · 运行版本 {visible.target.runVersion} · 报告 v{visible.target.revision} · 当前工作版本 {visible.target.workVersion} · 生成工作版本 {visible.target.generatedWorkVersion}</p>
   <p className="adj-hash">报告 SHA-256：{visible.target.reportSha256}</p>
   <button disabled={disabled||busy} onClick={()=>void reconcile(visible.run,true)}>定位报告编辑 / 复核区</button>
   <p>定位不等于采信、提交、复核通过或正式裁决。报告缺项及确切版本操作仍由现有报告区和服务端校验。</p>
  </div>}
 </section>;
}




