import AgentTaskPanel, { type AgentRunView } from "../../agent/AgentTaskPanel";
import { consultationAgentRecovery } from "./agentRecovery";
import { bindConsultationResult } from "./agentResultHandoff";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Checkbox, Input, Space, Spin } from "antd";
import { completeLogin, ConsultationError, ConsultationLoginError, forgetLogin, login, path, request, readConsultationDestination, consultationLocation, type ConsultationDestination } from "./api";
import ConsultationResourcePage, {ConsultationFrame,ConsultationTaskAccess} from "./ConsultationResourcePage";
import { Citations, Results } from "./Results";
import SavedSelection, {SavedSelectionAccess} from "./SavedSelection";
import ComparisonMatrixResults from "./ComparisonMatrixResults";
import ComparisonInputWorkbench from "./ComparisonInputWorkbench";
import HumanRequestPanel, {type HumanReportSelection} from "./HumanRequestPanel";
import { assertSelection, type SelectionWrite } from "./selection";
import type { ResultPage, Selection, Turn, Workspace } from "./types";
import "./consultation.css";
export default function ConsultationProductPage(){
  const [initialRoute]=useState(()=>{try{return {destination:new URLSearchParams(location.search).has("code")||new URLSearchParams(location.search).has("error")?undefined:readConsultationDestination(location.search),error:""};}catch{return {destination:undefined,error:"页面地址不合法，请使用完整的咨询任务或提示词链接。"};}});
  const [destination,setDestination]=useState<ConsultationDestination|undefined>(initialRoute.destination);
  const [taskId,setTaskId]=useState(()=>new URLSearchParams(location.search).get("task")??"");
  const [token,setToken]=useState<string|null>(null),[workspace,setWorkspace]=useState<Workspace|null>(null);
  const [message,setMessage]=useState(""),[query,setQuery]=useState("");
  const [sources,setSources]=useState<string[]>([]),[fields,setFields]=useState<string[]>([]);
  const [turns,setTurns]=useState<Turn[]>([]),[page,setPage]=useState<ResultPage|null>(null);
  const [selected,setSelected]=useState<string[]>([]),[selectionRevision,setSelectionRevision]=useState(0);
  const [pending,setPending]=useState(""),[error,setError]=useState(initialRoute.error),[notice,setNotice]=useState("");
  const [agentView,setAgentView]=useState<AgentRunView|null>(null);
  const [savedSelection,setSavedSelection]=useState<Selection|null>(null),[savedId,setSavedId]=useState(""),[savedResultId,setSavedResultId]=useState("");
  const [selectionUncertain,setSelectionUncertain]=useState(false);
  const [comparisonLocked,setComparisonLocked]=useState(false);
  const [humanLocked,setHumanLocked]=useState(false),[humanReport,setHumanReport]=useState<HumanReportSelection|null>(null);
  const [comparisonInput,setComparisonInput]=useState<{comparisonId:string;inputSha:string}|null>(null);
  const selectComparisonInput=useCallback((id:string,inputSha?:string)=>{setComparisonInput(id&&inputSha?{comparisonId:id,inputSha}:null);setHumanReport(null)},[]);
  const selectionWrite=useRef<SelectionWrite|null>(null),selectionEpoch=useRef(0);
  function clearSelectionView(){selectionEpoch.current++;setSavedSelection(null);}
  function releaseSelectionWrite(){selectionWrite.current=null;setSelectionUncertain(false);}
  const agentRecovery=useMemo(()=>token&&workspace?.goal?consultationAgentRecovery(token,workspace):undefined,[token,workspace?.task.id,workspace?.task.version,workspace?.goal?.inputId,workspace?.goal?.expiresAt,workspace?.task.expiresAt]);
  const handoffEpoch=useRef(0);
  const epoch=useRef(0),errorRef=useRef<HTMLDivElement>(null);
  const renderedEpoch=epoch.current;
  const clear=()=>{setHumanReport(null);setComparisonInput(null);clearSelectionView();handoffEpoch.current++;setAgentView(null);setWorkspace(null);setPage(null);setTurns([]);setSelected([]);setSources([]);setFields([]);setMessage("");setQuery("");setNotice("");};
  const apply=(data:Workspace,expectedTaskId:string)=>{
    if(data.task.id!==expectedTaskId)throw new Error("任务响应与当前任务编号不一致。已清空结果，请重新读取并核对任务。");
    clearSelectionView();setWorkspace(data);setPage(data.result);setTurns([...data.dialogue].reverse());setSelectionRevision(data.selectionRevision);
    setQuery(data.goal?.queryObject??"");setMessage(data.goal?.messageText??"");
    setSources(data.goal?.sourceIds??data.sources.map(s=>s.sourceId));
    setFields(data.goal?.requestedFields??(data.sources.every(s=>s.fields.includes("TITLE"))?["TITLE"]:[]));
  };
  useEffect(()=>{
    let active=true;const current=epoch.current;
    completeLogin().then(async session=>{
      if(!active||current!==epoch.current||!session)return;
      setToken(session.token);setTaskId(session.taskId);setDestination(session.destination);
      if(session.destination)return;
      setPending("读取当前任务");
      const data=await request<Workspace>(session.token,path(session.taskId)+"/retrieval-workspace");
      if(active&&current===epoch.current)apply(data,session.taskId);
    }).catch(failure=>{if(active&&current===epoch.current){clear();if(failure instanceof ConsultationLoginError){setTaskId(failure.taskId);setDestination(failure.destination);}setError(failure instanceof Error?failure.message:"登录未完成");}})
      .finally(()=>{if(active&&current===epoch.current)setPending("");});
    return ()=>{active=false;epoch.current++;handoffEpoch.current++;selectionEpoch.current++;selectionWrite.current=null;};
  },[]);
  useEffect(()=>{if(error)errorRef.current?.focus();},[error]);
  async function run(label:string,action:()=>Promise<void>){
    if(pending)return;
    const current=epoch.current;setPending(label);setError("");setNotice("");
    try{await action();}catch(failure){
      if(current===epoch.current){clear();if(failure instanceof ConsultationError&&[401,403].includes(failure.status))releaseSelectionWrite();setError(failure instanceof Error?failure.message:"操作未完成");
        if(failure instanceof ConsultationError&&failure.status===401){setToken(null);forgetLogin();}}
    }finally{if(current===epoch.current)setPending("");}
  }
  async function refresh(current=epoch.current){
    if(current!==epoch.current)return;
    if(!token)throw new Error("请先登录。");
    clearSelectionView();handoffEpoch.current++;setAgentView(null);setPage(null);setTurns([]);
    const data=await request<Workspace>(token,path(taskId)+"/retrieval-workspace");
    if(current===epoch.current)apply(data,taskId);
  }
  function onAgentView(view:AgentRunView|null){
    if(renderedEpoch!==epoch.current)return;
    clearSelectionView();handoffEpoch.current++;setAgentView(view);
    if(view||agentView){setPage(null);setTurns([]);setSelected([]);setNotice("");}
  }
  function invalidateEditedHandoff(){
    if(agentView){handoffEpoch.current++;setAgentView(null);setPage(null);setTurns([]);setSelected([]);setNotice("");}
  }
  async function agentRequest<T>(suffix:string,body?:unknown,key?:string):Promise<T>{
    if(!token)throw new Error("请先登录。");
    setPage(null);setTurns([]);setSelected([]);setNotice("");
    const current=epoch.current;
    try{const value=await request<T>(token,path(taskId)+"/agent-runs"+suffix,body,key);if(current!==epoch.current)throw new Error("当前会话已切换，未采用旧响应。");return value;}
    catch(failure){
      if(current===epoch.current&&failure instanceof ConsultationError&&failure.status===401){setToken(null);forgetLogin();}
      throw failure;
    }
  }
  async function readAgentResult(){
    if(!token||!agentView||!workspace?.goal)return;
    const current=epoch.current, marker=++handoffEpoch.current, previous=agentView.checkpoint;
    setPage(null);setTurns([]);setSelected([]);
    const live=await request<AgentRunView>(token,path(taskId)+"/agent-runs/"+encodeURIComponent(previous.runId));
    if(current!==epoch.current||marker!==handoffEpoch.current)return;
    const data=await request<Workspace>(token,path(taskId)+"/retrieval-workspace");
    if(current!==epoch.current||marker!==handoffEpoch.current)return;
    const turn=bindConsultationResult(live,data);
    if(live.checkpoint.task.id!==taskId||data.task.id!==taskId||live.checkpoint.runId!==previous.runId||live.checkpoint.version!==previous.version||!turn){
      setAgentView(null);throw new Error("运行、目标或结果已变化，请重新读取当前任务和运行后核对。");
    }
    apply(data,taskId);setTurns([turn]);setAgentView(live);
    setNotice(live.checkpoint.outcome?.reason==="NEED_HUMAN"?"已读取本次运行的回答与来源；来源覆盖不完整，仍需人工核查。":"已读取本次运行的回答、检索结果与来源；不构成专业或法律结论。");
  }
  async function propose(){
    if(!workspace||!token)return;const current=epoch.current;
    const turn=await request<Turn>(token,path(taskId)+"/dialogue/proposals",{expectedTaskVersion:workspace.task.version,messageText:message});
    if(current!==epoch.current)return;
    setTurns(old=>[...old,turn]);setQuery(turn.output.query??"");
  }
  async function confirm(){
    if(!workspace||!token)return;const current=epoch.current;
    await request(token,path(taskId)+"/goals/confirm",{expectedTaskVersion:workspace.task.version,
      messageText:message,purpose:"QUERY",queryObject:query,sourceIds:sources,requestedFields:fields,
      requiredFeatures:[],confirmed:true,retentionRef:workspace.retentionRef,retentionVersion:workspace.retentionVersion,
      filters:{publicationFrom:null,publicationTo:null,ipcPrefixes:[],cpcPrefixes:[]}});
    if(current!==epoch.current)return;
    setSelected([]);setTurns([]);await refresh(current);if(current!==epoch.current)return;setNotice("查询条件已正式确认。请执行本轮检索。");
  }
  async function execute(){
    if(!workspace||!token)return;const current=epoch.current;
    const result=await request<ResultPage>(token,path(taskId)+"/retrievals/execute",{expectedTaskVersion:workspace.task.version});
    if(current!==epoch.current)return;
    setPage(result);setNotice("已读取本轮正式提交的真实检索结果。");
  }
  async function explain(){
    if(!workspace||!token||!page)return;const current=epoch.current;
    const turn=await request<Turn>(token,path(taskId)+"/dialogue/explanations",{expectedTaskVersion:workspace.task.version,resultSetId:page.resultSetId});
    if(current!==epoch.current)return;
    setTurns(old=>[...old,turn]);
  }
  async function next(){if(!token||!page?.nextCursor)return;const current=epoch.current;const result=await request<ResultPage>(token,path(taskId)+"/patent-searches/"+encodeURIComponent(page.resultSetId)+"/results?pageSize=20&cursor="+encodeURIComponent(page.nextCursor));if(current===epoch.current)setPage(result);}
  async function saveSelection(packet:SelectionWrite){
    if(!token||packet.taskId!==taskId)return;
    const op=++selectionEpoch.current,current=epoch.current;
    setSavedSelection(null);setSelectionUncertain(false);
    try{
      const value=await request<Selection>(token,path(packet.taskId)+"/patent-searches/"+encodeURIComponent(packet.resultSetId)+"/selections",packet.body,packet.key);
      if(op!==selectionEpoch.current||current!==epoch.current)return;
      const receipt=assertSelection(value,packet.taskId,packet.resultSetId,undefined,packet);
      releaseSelectionWrite();setSavedSelection(receipt);setSelectionRevision(receipt.revision);
      setSavedId(receipt.selectionId);setSavedResultId(receipt.resultSetId);
      setNotice("文献选择已保存，版本 "+receipt.revision+"；文献版本和结果摘要已绑定。");
    }catch(failure){
      if(op!==selectionEpoch.current||current!==epoch.current)return;
      if(failure instanceof ConsultationError&&failure.status>=400&&failure.status<500&&failure.status!==408)releaseSelectionWrite();
      else setSelectionUncertain(true);
      throw failure;
    }
  }
  async function select(){
    if(!token||!page||selectionWrite.current||!selected.length)return;
    const packet:SelectionWrite={taskId,resultSetId:page.resultSetId,key:crypto.randomUUID(),body:{expectedRevision:selectionRevision,resultSha:page.resultSha,memberIds:[...selected]}};
    selectionWrite.current=packet;await saveSelection(packet);
  }
  async function readSelection(){
    if(!token||!savedId.trim()||!savedResultId.trim())return;
    const op=++selectionEpoch.current,current=epoch.current,expectedTask=taskId,expectedResult=savedResultId.trim(),expectedId=savedId.trim();setSavedSelection(null);
    const value=await request<Selection>(token,path(expectedTask)+"/patent-searches/"+encodeURIComponent(expectedResult)+"/selections/"+encodeURIComponent(expectedId));
    if(op!==selectionEpoch.current||current!==epoch.current)return;
    setSavedSelection(assertSelection(value,expectedTask,expectedResult,expectedId));
    // A historical selection read must not advance the current Task's write-revision baseline.
  }
  const allowedFields=workspace?Array.from(new Set(workspace.sources.filter(s=>sources.includes(s.sourceId)).flatMap(s=>s.fields)))
    .filter(field=>workspace.sources.filter(s=>sources.includes(s.sourceId)).every(s=>s.fields.includes(field))):[];
  const busy=!!pending||comparisonLocked||humanLocked;
  function endSession(){epoch.current++;forgetLogin();setToken(null);setPending("");releaseSelectionWrite();clear();}
  function returnToTask(){
    epoch.current++;setPending("");clear();setDestination(undefined);history.replaceState(null,"",consultationLocation(taskId));
    if(token&&taskId)void run("读取当前任务",refresh);
  }
  function openResource(next:ConsultationDestination){
    const url=consultationLocation(taskId,next);
    epoch.current++;setPending("");setError("");clear();setDestination(next);history.replaceState(null,"",url);
  }
  if(destination)return <ConsultationResourcePage destination={destination} taskId={taskId} token={token} pending={pending} error={error} errorRef={errorRef} onLogout={endSession} onBack={returnToTask} onLogin={()=>void run("登录",()=>login(taskId,destination))} onDestination={openResource}/>;
  return <ConsultationFrame title="咨询检索工作台" token={token} onLogout={endSession}>
      <p>提出公开非保密检索目标，核对并确认查询条件，再依据真实候选继续咨询。检索与模型说明不构成专业或法律结论。</p>
      {token&&taskId&&<nav aria-label="咨询资料"><Button disabled={busy||selectionUncertain} onClick={()=>{epoch.current++;clear();setDestination({view:"trademarks"});history.replaceState(null,"",consultationLocation(taskId,{view:"trademarks"}));}}>查询商标资料</Button></nav>}
      {error&&<div ref={errorRef} tabIndex={-1}><Alert type="error" showIcon title={error}/></div>}
      {notice&&<Alert type="success" showIcon title={notice}/>}<div role="status" aria-live="polite" className="consultation-status">{pending&&<><Spin size="small"/> {pending}…</>}</div>
      <ConsultationTaskAccess taskId={taskId} token={token} busy={busy} inputLocked={busy||!!workspace||selectionUncertain} version={workspace?.task.version} state={workspace?.task.state} onTaskChange={value=>{epoch.current++;clearSelectionView();setSavedId("");setSavedResultId("");setTaskId(value);}} onLogin={()=>void run("登录",()=>login(taskId))} onRefresh={()=>void run("核对任务现时状态",refresh)}/>
      {token&&selectionUncertain&&selectionWrite.current&&<section className="consultation-panel" aria-label="文献保存请求对账">
        <Alert type="warning" showIcon title="文献保存结果尚未确认。已保留原请求，不会换键再次保存。"/>
        <p>任务 {selectionWrite.current.taskId} · 结果集 {selectionWrite.current.resultSetId} · 请求编号 {selectionWrite.current.key}</p>
        <p>请先对账原请求。刷新或退出不会取消服务器已提交的选择，也不代表保存失败。</p>
        <Button disabled={busy} onClick={()=>{const packet=selectionWrite.current;if(packet)void run("对账原文献保存请求",()=>saveSelection(packet));}}>对账同一文献保存请求</Button>
      </section>}
      {token&&<SavedSelectionAccess resultId={savedResultId} selectionId={savedId} busy={busy} onResultChange={value=>{clearSelectionView();setSavedResultId(value);}} onSelectionChange={value=>{clearSelectionView();setSavedId(value);}} onRead={()=>void run("核验已保存文献选择",readSelection)}/>}
      {token&&savedSelection&&<SavedSelection value={savedSelection}/>}
      {token&&workspace&&<ComparisonInputWorkbench token={token} workspace={workspace} selection={savedSelection} disabled={!!pending||selectionUncertain||humanLocked} onLockChange={setComparisonLocked} onInput={selectComparisonInput} onDenied={()=>{releaseSelectionWrite();clear();setError("当前对照权限不可用，已清空结果。");}}/>}
      {token&&workspace&&<ComparisonMatrixResults key={token+":"+workspace.task.id+":"+workspace.task.version} token={token} taskId={workspace.task.id} defaultComparisonId={comparisonInput?.comparisonId??""} defaultInputSha={comparisonInput?.inputSha??""} onReport={setHumanReport} disabled={busy||selectionUncertain} onDenied={()=>{releaseSelectionWrite();clear();setError("当前矩阵读取权限不可用，已清空结果。");}}/>}
      {token&&workspace&&<HumanRequestPanel token={token} workspace={workspace} selection={savedSelection} comparison={comparisonInput} report={humanReport} disabled={!!pending||comparisonLocked||selectionUncertain} onLockChange={setHumanLocked} onDenied={()=>{releaseSelectionWrite();clear();setError("当前人工请求权限不可用，已清空结果。原请求可在同一主体重新登录后查回。");}}/>}
      {token&&workspace?.goal&&<AgentTaskPanel recovery={agentRecovery} expectedTaskId={taskId} scope={token+":"+taskId+":"+workspace.goal.inputId} disabled={busy||selectionUncertain} startInput={{expectedTaskVersion:workspace.task.version}} request={agentRequest} onView={onAgentView} onDenied={()=>{releaseSelectionWrite();clear();setError("当前会话或任务权限已不可用，已清空结果。请重新读取任务并核对。");}}/>}
      {agentView?.checkpoint.phase==="STOP"&&["COMPLETED","NEED_HUMAN"].includes(agentView.checkpoint.outcome?.reason??"")&&<Button disabled={busy} onClick={()=>run("核对并读取本次咨询结果",readAgentResult)}>查看本次咨询结果与来源</Button>}
      {workspace&&<><section className="consultation-panel" aria-labelledby="goal-heading"><h2 id="goal-heading">目标与必要补问</h2>
        <label htmlFor="consultation-goal">公开非保密检索目标／补充条件</label>
        <Input.TextArea id="consultation-goal" value={message} rows={4} disabled={busy} onChange={e=>{invalidateEditedHandoff();setMessage(e.target.value);}}/>
        <Button className="consultation-actions" disabled={busy||selectionUncertain||!message.trim()} onClick={()=>run("模型理解目标",propose)}>请模型理解／继续补问</Button>
        <div aria-live="polite">{turns.map(turn=><article className="consultation-turn" key={turn.turnId}><h3>{turn.kind==="PROPOSAL"?"待确认检索建议":"依据实际结果的说明"}</h3>
          {turn.output.query&&<p>建议查询：{turn.output.query}</p>}{turn.output.questions?.length? <ul>{turn.output.questions.map((question,i)=><li key={i}>{question}</li>)}</ul>:turn.kind==="PROPOSAL"&&<p>无补问。仍需您核对并明确确认条件。</p>}
          {turn.output.answer&&<p className="consultation-answer">{turn.output.answer}</p>}{!!turn.citations.length&&<Citations citations={turn.citations}/>}</article>)}</div>
      </section>
      <section className="consultation-panel" aria-labelledby="confirm-heading"><h2 id="confirm-heading">核对并确认检索条件</h2>
        <label htmlFor="consultation-query">查询表达式（可修改）</label><Input.TextArea id="consultation-query" value={query} rows={2} disabled={busy} onChange={e=>{invalidateEditedHandoff();setQuery(e.target.value);}}/>
        <fieldset disabled={busy}><legend>当前获准来源</legend><Checkbox.Group value={sources} options={workspace.sources.map(s=>({label:s.sourceId,value:s.sourceId}))} onChange={values=>{invalidateEditedHandoff();setSources(values as string[]);setFields([]);}}/></fieldset>
        <fieldset disabled={busy}><legend>检索字段</legend><Checkbox.Group value={fields} options={allowedFields.map(field=>({label:field==="TITLE"?"标题":field==="CLAIMS"?"权利要求":field,value:field}))} onChange={values=>{invalidateEditedHandoff();setFields(values as string[]);}}/></fieldset>
        <Space wrap><Button type="primary" disabled={busy||selectionUncertain||!message.trim()||!query.trim()||!sources.length||!fields.length||!fields.every(f=>allowedFields.includes(f))} onClick={()=>run("正式确认查询条件",confirm)}>确认条件{workspace.goal?"并开始同任务新一轮":""}</Button>
          <Button disabled={busy||selectionUncertain||!workspace.goal} onClick={()=>run("正式Worker执行检索",execute)}>执行已确认的本轮检索</Button></Space>
      </section></>}
      {page&&<Results page={page} selected={selected} onSelect={setSelected} busy={busy||selectionUncertain} onNext={()=>run("读取下一页",next)} onExplain={()=>run("模型核查真实结果",explain)} onSave={()=>run("保存文献选择",select)}/>}
  </ConsultationFrame>;
}
