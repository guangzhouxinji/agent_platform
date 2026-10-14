import {useEffect,useRef,useState} from 'react';
import {Button,Checkbox,Input,Space} from 'antd';
import {ConsultationError,path,request} from './api';
import {bindHumanRequest,humanRequestSha,type HumanRequestCommand,type HumanRequestView} from './humanRequest';
import {humanRequestRecovery} from './humanRequestRecovery';
import type {Selection,Workspace} from './types';

export type HumanReportSelection={comparisonId:string;inputSha:string;reportId:string;expiresAt:string};
type RecoveryRecord={key:string;requestSha:string;createdAt:number;expiresAt:number};
type Props={token:string;workspace:Workspace;selection:Selection|null;comparison:{comparisonId:string;inputSha:string}|null;report:HumanReportSelection|null;disabled:boolean;onLockChange:(locked:boolean)=>void;onDenied:()=>void};
const fieldNames:Record<string,string>={TITLE:'标题',ABSTRACT:'摘要',CLAIMS:'权利要求',DESCRIPTION:'说明书'};

/** A request record is not an acceptance, independent review, or legal decision. */
export default function HumanRequestPanel({token,workspace,selection,comparison,report,disabled,onLockChange,onDenied}:Props){
 const [reason,setReason]=useState(''),[materials,setMaterials]=useState<Record<string,string[]>>({});
 const [includeInput,setIncludeInput]=useState(false),[includeReport,setIncludeReport]=useState(false);
 const [record,setRecord]=useState<RecoveryRecord|null>(null),[view,setView]=useState<HumanRequestView|null>(null);
 const [ready,setReady]=useState(false),[storageFailed,setStorageFailed]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const [now,setNow]=useState(Date.now);
 const epoch=useRef(0),inFlight=useRef(false);
 const scope=JSON.stringify([token,workspace.task.id,workspace.task.version,workspace.goal?.inputId]);
 const liveScope=useRef(scope);liveScope.current=scope;
 const [shownScope,setShownScope]=useState(scope);const visible=shownScope===scope;
 const renderNow=Math.max(now,Date.now());
 const recovery=useRef<ReturnType<typeof humanRequestRecovery>|null>(null);
 const taskLive=Date.parse(workspace.task.expiresAt)>renderNow;
 const goalLive=!!workspace.goal&&(!workspace.goal.expiresAt||Date.parse(workspace.goal.expiresAt)>renderNow);
 const selectionLive=!!selection&&selection.taskId===workspace.task.id&&selection.taskVersion===workspace.task.version&&Date.parse(selection.expiresAt)>renderNow;
 const reportLive=!!report&&!!comparison&&report.comparisonId===comparison.comparisonId&&report.inputSha===comparison.inputSha&&Date.parse(report.expiresAt)>renderNow;
 const locked=!visible||!ready||storageFailed||busy||!!record&&(!view||Date.parse(view.receipt.auditUntil)<=renderNow);
 useEffect(()=>{onLockChange(locked);return()=>onLockChange(false)},[locked,onLockChange]);
 useEffect(()=>{
  epoch.current++;setReady(false);setRecord(null);setView(null);setError('');setStorageFailed(false);setBusy(false);inFlight.current=false;
  try{const port=humanRequestRecovery(token,workspace.task.id,workspace.task.expiresAt);recovery.current=port;setRecord(port.load());}
  catch{recovery.current=null;setStorageFailed(true);setError('无法安全读取请求恢复记录，已停止新提交。请保留当前浏览器记录并联系支持。');}
  setReady(true);return()=>{epoch.current++;inFlight.current=false;};
 },[token,workspace.task.id,workspace.task.expiresAt]);
 useEffect(()=>{epoch.current++;inFlight.current=false;setBusy(false);setView(null);setShownScope(scope);setReason('');setMaterials({});setIncludeInput(false);setIncludeReport(false);},[scope]);
 useEffect(()=>{setMaterials({});},[selection]);
 useEffect(()=>{setIncludeInput(false);setIncludeReport(false)},[comparison?.comparisonId,comparison?.inputSha]);
 useEffect(()=>{setIncludeReport(false)},[report?.reportId,report?.expiresAt]);
 useEffect(()=>{
  const dates=[workspace.task.expiresAt,workspace.goal?.expiresAt,selection?.expiresAt,report?.expiresAt,view?.body?.bodyAccessUntil,view?.receipt.auditUntil,...workspace.sources.map(s=>s.expiresAt)].filter(Boolean).map(v=>Date.parse(v!));
  if(record)dates.push(record.expiresAt);
  const future=dates.filter(v=>Number.isFinite(v)&&v>now);
  if(!future.length)return;
  const timer=setTimeout(()=>setNow(Date.now()),Math.min(2147483647,Math.max(1,Math.min(...future)-Date.now())));
  return()=>clearTimeout(timer);
 },[now,workspace,selection,report,view,record]);
 useEffect(()=>{
  if(view&&Date.parse(view.receipt.auditUntil)<=now)setView(null);
  else if(view?.body&&Date.parse(view.body.bodyAccessUntil)<=now)setView({...view,kind:'RECEIPT_ONLY',body:null});
  if(record&&record.expiresAt<=now){
   setReason('');setMaterials({});setIncludeInput(false);setIncludeReport(false);setRecord(null);setView(null);setError('');
   try{recovery.current?.load()}catch{setStorageFailed(true)}
  }
  if(!taskLive||!goalLive){setReason('');setMaterials({});setIncludeInput(false);setIncludeReport(false)}
 },[now,taskLive,goalLive,record,view]);
 const allowed=(sourceId:string)=>workspace.sources.find(s=>s.sourceId===sourceId&&Date.parse(s.expiresAt)>renderNow)?.fields.filter(f=>f in fieldNames)??[];
 const current=(op:number,captured:string)=>op===epoch.current&&captured===liveScope.current;
 async function perform(saved:RecoveryRecord,command?:HumanRequestCommand){
  if(!visible||disabled||saved.expiresAt<=Date.now()||Date.parse(workspace.task.expiresAt)<=Date.now())return;
  const op=++epoch.current,captured=scope;inFlight.current=true;setBusy(true);setView(null);setError('');
  try{
   const value=await request<unknown>(token,path(workspace.task.id)+'/human-requests'+(command?'':'?requestId='+encodeURIComponent(saved.key)),command,saved.key);
   if(!current(op,captured))return;
   const bound=await bindHumanRequest(value,workspace.task.id,saved.requestSha,token,saved.key);
   if(!current(op,captured))return;
   const checkedAt=Date.now();if(saved.expiresAt<=checkedAt||Date.parse(bound.receipt.auditUntil)<=checkedAt||Date.parse(workspace.task.expiresAt)<=checkedAt)throw new Error('请求查回期限已到');
   setView(bound.body&&Date.parse(bound.body.bodyAccessUntil)<=checkedAt?{...bound,kind:'RECEIPT_ONLY',body:null}:bound);setReason('');setMaterials({});setIncludeInput(false);setIncludeReport(false);
  }catch(failure){
   if(!current(op,captured))return;
   if(failure instanceof ConsultationError&&[401,403].includes(failure.status)){
    setReason('');setMaterials({});setIncludeInput(false);setIncludeReport(false);setError('当前权限不可用，已清空正文；重新登录后可查回原请求。');onDenied();
   }else if(command&&failure instanceof ConsultationError&&failure.status===400){
    try{recovery.current!.clear();setRecord(null);setError('提交条件不合法，本次输入已被拒绝。请修正后重新提交。');}
    catch{setStorageFailed(true);setError('无法更新请求恢复记录，已停止新提交。');}
   }else setError('尚未核实原请求结果。已保留原请求编号，请查回原请求；不会重复提交。');
  }finally{if(current(op,captured)){inFlight.current=false;setBusy(false)}}
 }
 async function submit(){
  if(disabled||locked||record||inFlight.current||!taskLive||!goalLive||!reason.trim()||reason.length>4000)return;
  const selected=selectionLive?selection!.members.filter(m=>(materials[m.memberId]?.length??0)>0):[];
  if(selected.some(m=>!materials[m.memberId].every(f=>allowed(m.sourceId).includes(f)))){setError('所选资料字段已不可用，请重新选择。');return;}
  const command:HumanRequestCommand={expectedTaskVersion:workspace.task.version,reason:reason.trim(),purpose:'ORDINARY_ASSISTANCE',
   materials:selected.map(m=>({sourceId:m.sourceId,documentId:m.documentId,documentVersion:m.documentVersion,fields:materials[m.memberId]})),
   comparison:includeInput&&comparison?{comparisonId:comparison.comparisonId,reportIds:includeReport&&reportLive?[report!.reportId]:[]}:null};
  const captured=scope,op=++epoch.current;inFlight.current=true;setBusy(true);setError('');
  try{
   let key:string,requestSha:string;
   try{key=crypto.randomUUID();requestSha=await humanRequestSha(token,workspace.task.id,key,command)}
   catch{if(current(op,captured))setError('输入无法核验，尚未提交。请核对内容后重试。');return;}
   if(!current(op,captured))return;
   const createdAt=Date.now();
   if(Date.parse(workspace.task.expiresAt)<=createdAt||workspace.goal?.expiresAt&&Date.parse(workspace.goal.expiresAt)<=createdAt){
    setReason('');setMaterials({});setIncludeInput(false);setIncludeReport(false);setNow(createdAt);setError('当前任务或目标已到期，尚未发送请求。');return;
   }
   const saved={key,requestSha,createdAt,expiresAt:Math.min(Date.parse(workspace.task.expiresAt),createdAt+180*86400000)};
   try{recovery.current!.save(saved)}
   catch{setStorageFailed(true);setError('无法安全保存请求恢复记录，尚未发送请求。');return;}
   setRecord(saved);setReason('');setMaterials({});setIncludeInput(false);setIncludeReport(false);
   await perform(saved,command);
  }finally{if(current(op,captured)){inFlight.current=false;setBusy(false)}}
 }
 function another(){
  if(!visible||!view||Date.parse(view.receipt.auditUntil)<=Date.now()||busy||disabled)return;
  try{recovery.current!.clear();setRecord(null);setView(null);setError('');setReason('');}
  catch{setStorageFailed(true);setError('无法更新请求恢复记录，已停止新提交。');}
 }
 const formDisabled=disabled||locked||!!record||!taskLive||!goalLive;
 return <section className="consultation-panel" aria-labelledby="human-request-heading" aria-busy={busy}>
  <h2 id="human-request-heading">普通辅助意见请求</h2>
  <p>请求记录不代表已领取或受理。普通辅助意见不具有专业复核或正式法律决定效力；本人处理不能标为独立复核。</p>
  {visible&&error&&<p role="alert">{error}</p>}
  <div role="status" aria-live="polite">{!visible?'正在核对当前任务…':busy?'正在核验请求…':record&&!view?'原请求结果待查回，已暂停其他任务写入。':''}</div>
  {visible&&record&&record.expiresAt>renderNow&&<><p>请求编号 {record.key}</p><Button disabled={disabled||busy||storageFailed||!taskLive} onClick={()=>{if(!inFlight.current)void perform(record)}}>查回原请求</Button><p>刷新、退出或开始另一请求不会撤回服务器已记录的请求。</p></>}
  {visible&&record&&record.expiresAt>renderNow&&view&&Date.parse(view.receipt.auditUntil)>renderNow&&<article>
   <h3>{view.body&&Date.parse(view.body.bodyAccessUntil)>renderNow?'请求已记录，未证明受理':'仅保留请求回执'}</h3>
   <p>人工包 {view.receipt.packageId} · 版本 {view.receipt.packageRevision} · 记录时间 {view.receipt.createdAt}</p>
   {view.body&&Date.parse(view.body.bodyAccessUntil)>renderNow&&<><p>原任务版本 {view.body.taskVersion}</p><p className="consultation-answer">请求理由：{view.body.reason}</p>
    <p>已记录资料 {view.body.materials.length} 份 · 比较输入 {view.body.comparisonId?'已附':'未附'} · 报告 {view.body.reportIds.length} 份</p>
    {!!view.body.gaps.length&&<p>仍有缺项：{view.body.gaps.join('、')}</p>}</>}
   <Button disabled={disabled||busy||storageFailed||!taskLive} onClick={another}>开始另一请求</Button>
  </article>}
  {visible&&!record&&<form onSubmit={e=>{e.preventDefault();void submit()}}>
   <p>当前任务版本 {workspace.task.version} · 已确认目标：{workspace.goal?.queryObject??'尚未确认目标'}</p>
   <label htmlFor="human-request-reason">请求理由</label><Input.TextArea id="human-request-reason" value={reason} rows={3} maxLength={4000} disabled={formDisabled} onChange={e=>setReason(e.target.value)}/>
   <fieldset disabled={formDisabled}><legend>选择已有资料及字段</legend>
    {!selectionLive&&<p>暂无当前版本的有效已保存文献；可以不附文献提出请求。</p>}
    {selectionLive&&selection!.members.map(m=><div key={m.memberId}><p>{m.documentId} · 版本 {m.documentVersion} · 来源 {m.sourceId}</p>
     <Checkbox.Group aria-label={m.documentId+'资料字段'} value={materials[m.memberId]??[]} options={allowed(m.sourceId).map(f=>({value:f,label:fieldNames[f]}))} onChange={values=>setMaterials(old=>({...old,[m.memberId]:values as string[]}))}/></div>)}
   </fieldset>
   <Checkbox disabled={formDisabled||!comparison} checked={includeInput&&!!comparison} onChange={e=>{setIncludeInput(e.target.checked);setIncludeReport(false)}}>附当前已核对照输入</Checkbox>
   <Checkbox disabled={formDisabled||!includeInput||!reportLive} checked={includeReport&&includeInput&&reportLive} onChange={e=>setIncludeReport(e.target.checked)}>附已选择的确切矩阵报告</Checkbox>
   {reportLive&&<p>已选择报告 {report!.reportId}</p>}
   <p>未附对照输入、报告或资料时，请求将保留实际缺项；来源使用和保存权限由服务器再次核验。</p>
   <Space className="consultation-actions"><Button htmlType="submit" type="primary" disabled={formDisabled||!reason.trim()||reason.length>4000}>提交普通辅助请求</Button></Space>
  </form>}
 </section>;
}
