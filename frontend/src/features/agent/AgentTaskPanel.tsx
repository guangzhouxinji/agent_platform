import { useEffect, useRef, useState } from "react";
import "./agent-task.css";
export type AgentRunView={goalText:string;checkpoint:{runId:string;version:number;phase:string;task:{id:string};cursor:number;invocations:number;replans:number;plan:null|{version:string;steps:Array<{id:string;skill:string;tool:string}>};pending:null|{id:string};observations:Array<{reference:string;sha256:string}>;outcome:null|{reason:string;reference?:string;outcomeRef?:string}};events:Array<{version:number;phase:string;invocationId:string|null;stopReason:string|null}>};
export type AgentRecoveryState={runId:string;creation:{key:string;body:unknown}|null};
export type AgentRecoveryPort={load:()=>AgentRecoveryState|null;save:(state:AgentRecoveryState)=>void;clear:()=>void};
type Props={recovery?:AgentRecoveryPort;scope:string;expectedTaskId?:string;request:<T>(suffix:string,body?:unknown,key?:string)=>Promise<T>;startInput?:unknown;disabled?:boolean;onDenied:()=>void;onAdvanced?:()=>void;onView?:(view:AgentRunView|null)=>void};
const phases:Record<string,string>={OBSERVE:"观察当前输入",PLAN:"准备计划",ACT:"执行工具",EVALUATE:"检查完成条件",REPLAN:"重新规划",STOP:"本次运行已停止"};
const stops:Record<string,string>={COMPLETED:"完成条件已满足",NEED_HUMAN:"需要人工复核",INSUFFICIENT_EVIDENCE:"证据不足",PERMISSION_BLOCKED:"当前权限阻止执行",SOURCE_UNAVAILABLE:"来源暂不可用",BUDGET_EXCEEDED:"执行预算已耗尽",ERROR:"执行异常"};
/** Shared rendering only. The caller owns its domain API, JWT and exact native task recipe. */
export default function AgentTaskPanel({scope,expectedTaskId,request,startInput,disabled=false,onDenied,onAdvanced,onView,recovery}:Props){
 const [view,setView]=useState<AgentRunView|null>(null),[runId,setRunId]=useState(""),[busy,setBusy]=useState(false),[error,setError]=useState("");
 const [uncertain,setUncertain]=useState(false);const epoch=useRef(0),creation=useRef<{key:string;body:unknown}|null>(null);
 useEffect(()=>{epoch.current++;onView?.(null);setView(null);setRunId("");setError("");setBusy(false);setUncertain(false);creation.current=null;try{const restored=recovery?.load();if(restored){creation.current=restored.creation;setRunId(restored.runId);setUncertain(true);}}catch(cause){setUncertain(true);setError(cause instanceof Error?cause.message:"无法核对浏览器恢复记录，请输入已有运行编号重新读取。");}return()=>{epoch.current++}},[scope,expectedTaskId,recovery]);
 async function execute(work:()=>Promise<AgentRunView>,advanced=false,expectedRunId?:string){
  const current=++epoch.current;setBusy(true);setError("");onView?.(null);
  try{const result=await work();if(current!==epoch.current)return;
   if(!result?.checkpoint?.runId||(expectedRunId!==undefined&&result.checkpoint.runId!==expectedRunId)
     ||(expectedTaskId!==undefined&&result.checkpoint.task?.id!==expectedTaskId)){
    throw Object.assign(new Error("运行响应与当前任务或请求的运行编号不一致。已清空结果，请重新读取当前任务和运行。"),{status:409,responseMismatch:true});
   }
   recovery?.save({runId:result.checkpoint.runId,creation:null});setView(result);setRunId(result.checkpoint.runId);setUncertain(false);creation.current=null;onView?.(result);if(advanced)onAdvanced?.();}
  catch(cause){if(current!==epoch.current)return;const status=(cause as {status?:number}).status;setView(null);
   if((cause as {responseMismatch?:boolean}).responseMismatch&&creation.current){setUncertain(true);}
   else if(status===401||status===403){setView(null);setRunId("");creation.current=null;setUncertain(!!recovery);onDenied();}
   else if(status===409||status===404){
    try{recovery?.clear();creation.current=null;setUncertain(false);}
    catch(cleanupFailure){setUncertain(true);setError(cleanupFailure instanceof Error?cleanupFailure.message:"无法清除恢复记录，请重新核对任务。");return;}
   }
   else {setUncertain(true);}
   setError(cause instanceof Error?cause.message:"运行请求失败");
  }finally{if(current===epoch.current)setBusy(false);}
 }
 function start(){if(startInput===undefined)return;const packet=creation.current??{key:crypto.randomUUID(),body:structuredClone(startInput)};creation.current=packet;void execute(()=>{recovery?.save({runId:"",creation:packet});return request<AgentRunView>("",packet.body,packet.key);});}
 const checkpoint=view?.checkpoint;const stopped=checkpoint?.phase==="STOP";
 return <section className="agent-task-panel" aria-label="Agent任务运行" aria-busy={busy}>
  <header><div><h2>Agent 任务运行</h2><p>按当前授权执行领域工具；辅助结果不替代正式业务确认。</p></div></header>
  <div className="agent-task-actions">
   {startInput!==undefined&&<button disabled={disabled||busy||!!checkpoint||(uncertain&&!creation.current)} onClick={start}>{creation.current?"对账同一创建请求":"启动当前目标"}</button>}
   <label>运行编号<input value={runId} disabled={disabled||busy||!!creation.current} onChange={event=>{setRunId(event.target.value);setView(null);onView?.(null)}} autoComplete="off"/></label>
   <button disabled={disabled||busy||!runId||!!creation.current} onClick={()=>void execute(()=>request<AgentRunView>("/"+encodeURIComponent(runId)),false,runId)}>读取当前运行</button>
   <button disabled={disabled||busy||!checkpoint||stopped||uncertain} onClick={()=>void execute(()=>request<AgentRunView>("/"+encodeURIComponent(checkpoint!.runId)+"/advance",{},crypto.randomUUID()),true,checkpoint!.runId)}>执行下一步</button>
   <button disabled={disabled||busy||!checkpoint||stopped||uncertain} onClick={()=>void execute(()=>request<AgentRunView>("/"+encodeURIComponent(checkpoint!.runId)+"/execute",{},crypto.randomUUID()),true,checkpoint!.runId)}>执行至当前出口</button>
  </div>
  {busy&&<p role="status">正在处理当前授权请求。若连接中断，请读取同一运行对账后再继续。</p>}
  {error&&<p role="alert" className="agent-task-error">{error}</p>}
  {uncertain&&<p role="status">请求结果尚未确认或已有运行待重新核验。先读取运行对账；创建请求只允许重试原请求键。</p>}
  {!checkpoint?<p>尚未读取运行，不显示假定计划或执行进度。</p>:<>
   <dl className="agent-task-summary"><div><dt>当前任务</dt><dd>{checkpoint.task.id}</dd></div><div><dt>目标</dt><dd>{view!.goalText}</dd></div><div><dt>状态</dt><dd aria-live="polite">{phases[checkpoint.phase]??checkpoint.phase}</dd></div><div><dt>运行版本 / 工具调用 / 重规划</dt><dd>{checkpoint.version} / {checkpoint.invocations} / {checkpoint.replans}</dd></div></dl>
   {checkpoint.outcome&&<p role="status"><strong>{stops[checkpoint.outcome.reason]??checkpoint.outcome.reason}</strong> · {checkpoint.outcome.reference??checkpoint.outcome.outcomeRef}</p>}
   {checkpoint.outcome?.reason==="NEED_HUMAN"&&<p>请在本领域业务复核入口检查证据与报告。此面板不会自动提交、批准或作出正式决定。</p>}
   <h3>当前计划</h3>{checkpoint.plan?<ol className="agent-task-steps">{checkpoint.plan.steps.map((step,index)=><li key={step.id}><strong>{step.skill}</strong><span>工具：{step.tool}</span><span>{index<checkpoint.cursor?"已完成":index===checkpoint.cursor&&!stopped?(checkpoint.pending?"调用已保留，待结果确认":"当前步骤"):"未执行"}</span></li>)}</ol>:<p>尚未生成计划。</p>}
   <h3>证据与缺口引用</h3><ul className="agent-task-evidence">{checkpoint.observations.map((item,index)=><li key={index}><span>{item.reference}</span><details><summary>查看证据摘要</summary><code>{item.sha256}</code></details></li>)}</ul>
   <details><summary>本次运行历史（{view!.events.length}）</summary><ol>{view!.events.map(event=><li key={event.version}>版本 {event.version} · {phases[event.phase]??event.phase}{event.invocationId?" · "+event.invocationId:""}{event.stopReason?" · "+(stops[event.stopReason]??event.stopReason):""}</li>)}</ol></details>
  </>}
 </section>;
}
