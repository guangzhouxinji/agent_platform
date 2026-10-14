import { useEffect,useRef,useState } from "react";
import { AdjudicationError,beginLogin,casePath,completeLogin,forgetLogin,request } from "./api";
import type { CaseView,Original,Receipt,Version } from "./contract";
import { reviewLabels } from "./contract";
import { CaseForm,CitationForm,ClaimForm,FindingForm,MaterialForm } from "./Forms";
import { CaseRead,CitationsRead,ClaimsRead,FindingsRead,HistoryRead,MaterialsRead } from "./Readers";
import Reports from "./Reports";
import "./workspace.css";
import WorkArea from "./WorkArea";
import type { ReportHandoff } from "./reportHandoff";
import { bindCaseReceipt,CaseBindingError } from "./caseReceiptBinding";
import { bindWorkspaceRead,bindAgentHandoffRead,matchesReportHandoff } from "./workspaceReadBinding";
import type { CasePacket } from "./caseReceiptBinding";
import MaterialOrganization from "./MaterialOrganization";
import { MaterialOrganizationSession } from "./materialOrganizationSession";
import type { DecisionView } from "./materialOrganizationBinding";
const tabs=["案件","材料","权项","引用","特征","报告","历史"] as const;
type Packet=CasePacket;
export default function AdjudicationWorkspace(){
 const [token,setToken]=useState<string|null>(null),[id,setId]=useState(new URLSearchParams(location.search).get("case")??"");
 const [view,setView]=useState<CaseView|null>(null),[versions,setVersions]=useState<Version[]>([]),[originals,setOriginals]=useState<Original[]>([]);
 const [head,setHead]=useState(0),[historical,setHistorical]=useState(false),[tab,setTab]=useState<typeof tabs[number]>("案件"),[create,setCreate]=useState(false);
 const [busy,setBusy]=useState(false),[error,setError]=useState<Error|null>(null),[receipt,setReceipt]=useState<Receipt|null>(null),[pending,setPending]=useState<Packet|null>(null);
 const [reportTarget,setReportTarget]=useState<ReportHandoff|null>(null);
 const [reportDirty,setReportDirty]=useState(false),[editorReset,setEditorReset]=useState(0);
 const [materialSession]=useState(()=>new MaterialOrganizationSession()),[,materialTick]=useState(0),[materialDirty,setMaterialDirty]=useState(false),[materialReadBusy,setMaterialReadBusy]=useState(false);
 useEffect(()=>materialSession.subscribe(()=>materialTick(value=>value+1)),[materialSession]);
 useEffect(()=>{materialSession.activate(token);},[materialSession,token]);
 useEffect(()=>()=>materialSession.scrub(),[materialSession]);
 const materialCase=view?.caseInput.id??id,materialState=materialSession.state(materialCase),materialFence=materialSession.blocked(materialCase),materialActive=materialSession.anyActive();
 function navigate(action:()=>void){if(materialReadBusy||materialSession.anyActive())return false;if(materialDirty&&!window.confirm("离开或刷新将放弃未提交的人工整理，是否继续？"))return false;if(reportDirty&&!window.confirm("离开或刷新将放弃未保存的报告修订，是否继续？"))return false;setMaterialDirty(false);setReportDirty(false);setReportTarget(null);setEditorReset(value=>value+1);action();return true}
 const epoch=useRef(0),packet=useRef<Packet|null>(null);
 const reportArea=useRef<HTMLDivElement|null>(null),[reportFocus,setReportFocus]=useState(0);
 useEffect(()=>{if(reportFocus&&tab==="报告")reportArea.current?.focus()},[reportFocus,tab]);
 useEffect(()=>{let active=true;void completeLogin().then(t=>{if(active){setToken(t);setId(new URLSearchParams(location.search).get("case")??"")}}).catch(e=>{if(active)setError(e)});return()=>{active=false;epoch.current++}},[]);
 function clear(preserveReceipt=false){setMaterialDirty(false);setMaterialReadBusy(false);setReportTarget(null);setReportDirty(false);setView(null);setVersions([]);setOriginals([]);setHead(0);setHistorical(false);setCreate(false);if(!preserveReceipt)setReceipt(null);}
 async function run(work:(op:number)=>Promise<void>){const op=++epoch.current;setBusy(true);setError(null);try{await work(op);return op===epoch.current}catch(e){if(op!==epoch.current)return false;const failure=e instanceof Error?e:new Error("请求失败");setError(failure);if(failure instanceof AdjudicationError&&failure.clearContent){materialSession.scrub();clear(failure.status!==401&&failure.status!==403);if(failure.status===401){setToken(null);forgetLogin()}if(failure.status===401||failure.status===403){packet.current=null;setPending(null);}}return false;}finally{if(op===epoch.current)setBusy(false)}}
 async function load(target:string,op:number,version?:number,confirmed?:Receipt){if(!token)throw new Error("请先登录");if(view&&view.caseInput.id!==target)clear(!!confirmed);const root=casePath(target),exactVersion=version??confirmed?.workVersion;const [value,history,orig]=await Promise.all([request<CaseView>(token,root+(exactVersion!==undefined?"/versions/"+exactVersion:"")),request<Version[]>(token,root+"/versions"),request<Original[]>(token,root+"/originals")]);if(op!==epoch.current)return;
  let bound;try{bound=await bindWorkspaceRead(target,value,history,orig,exactVersion,confirmed)}catch(e){if(op!==epoch.current)return;setReportTarget(null);setReportDirty(false);setView(null);setVersions([]);setOriginals([]);setHead(0);setHistorical(false);throw e}if(op!==epoch.current)return;setReportTarget(null);setView(bound.view);setVersions(bound.versions);setOriginals(bound.originals);setHead(bound.head);setHistorical(confirmed?bound.view.version!==bound.head:version!==undefined);setCreate(false);setId(target);historyUrl(target);
 }
 function historyUrl(target:string){history.replaceState(null,"","/app/adjudication?case="+encodeURIComponent(target))}
 async function write(path:string,body:unknown){if(!token)return false;if(packet.current){setError(new Error("有结果未确认的命令，请先重试同一请求对账，禁止另发新命令。"));return false;}
  if(materialSession.blocked(view?.caseInput.id??id)||materialSession.anyActive()||materialReadBusy||materialDirty){setError(new Error("人工整理尚未结束，此案件暂不可另发写入。"));return false;}
  const p={path,body,key:crypto.randomUUID()};setReceipt(null);packet.current=p;setPending(p);return send(p);
 }
 async function send(p:Packet){if(materialSession.blocked(view?.caseInput.id??id)||materialSession.anyActive()||materialReadBusy)return false;return run(async op=>{if(!token)throw new Error("请先登录");let result:Receipt;
  try{const response=await request<Receipt>(token,p.path,p.body,p.key);if(op!==epoch.current)return;result=bindCaseReceipt(p,response);}catch(e){if(op===epoch.current&&e instanceof AdjudicationError&&e.status>=400&&e.status<500){packet.current=null;setPending(null)}throw e;}
  if(op!==epoch.current)return;packet.current=null;setPending(null);setReceipt(result);
  // A confirmed write supersedes the old actionable view, even while its exact GET is pending.
  clear(true);
  // The write is confirmed. A subsequent GET failure cannot restore or resend this Packet.
  try{await load(result.caseId,op,undefined,result)}catch(e){
   // Keep the original HTTP type/status so run still applies existing denial/content clearing.
   if(op===epoch.current&&e instanceof Error&&!(e instanceof AdjudicationError&&(e.status===401||e.status===403))){e.message=e instanceof AdjudicationError&&e.status===0?"写入已确认，后续读取连接中断或响应不可读。请仅刷新案件。":"写入已确认，后续读取失败："+e.message}throw e;
  }
 })}
 async function command(suffix:string,body:Record<string,unknown>){if(!view)return false;return write(casePath(view.caseInput.id)+suffix,{expectedVersion:view.version,...body})}
 const current=view?.reports.at(-1),readonly=historical;
 const agentScope=token&&view?token+":"+view.caseInput.id:"";
 const readContext=JSON.stringify([token,view?.caseInput.id,view?.version,view?.inputVersion,view?.caseInput.organization,view?.caseInput.dataScope,historical]),currentReadContext=useRef(readContext);currentReadContext.current=readContext;
 function materialDenied(failure:AdjudicationError){materialSession.scrub();epoch.current++;clear();packet.current=null;setPending(null);setBusy(false);setError(failure);if(failure.status===401){setToken(null);forgetLogin();}}
 async function materialConfirmed(value:DecisionView){if(!token||value.version.caseId!==view?.caseInput.id)return false;clear();return run(op=>load(value.version.caseId,op));}
 return <main className="adj-workspace" aria-busy={busy||materialReadBusy||materialActive}><header className="adj-heading"><div><small>裁决辅助工作 · 单案工作台</small><h1>裁决工作台</h1><p>材料、权项、对应与辅助报告可追溯；不代替正式裁决。</p></div>{token?<button disabled={busy} onClick={()=>{materialSession.scrub();epoch.current++;clear();packet.current=null;setPending(null);setToken(null);setBusy(false);forgetLogin();setError(null)}}>锁定工作台</button>:<button disabled={busy} onClick={()=>void run(async()=>beginLogin())}>可信身份登录</button>}</header>
 {token&&view&&!historical&&<WorkArea key={agentScope} token={token} view={view} disabled={busy||!!pending||reportDirty||materialReadBusy||materialFence||materialDirty} onDenied={()=>{materialSession.scrub();epoch.current++;clear();packet.current=null;setPending(null);setBusy(false)}} onOpenReport={(target,fresh,history)=>{
   if(materialSession.blocked(view.caseInput.id)||materialSession.anyActive()||materialReadBusy||materialDirty)return false;
   if(!token||readContext!==currentReadContext.current||!matchesReportHandoff(view,target,fresh,history))return false;
   let accepted=false;return run(async op=>{
    let bound;try{bound=await bindAgentHandoffRead(token,target,fresh,history)}catch(e){if(op===epoch.current&&readContext===currentReadContext.current)clear(true);throw e}
    if(op!==epoch.current||readContext!==currentReadContext.current)return;
    accepted=navigate(()=>{setView(bound.view);setVersions(bound.versions);setOriginals(bound.originals);setHead(bound.head);setHistorical(false);setReceipt(null);setReportTarget(target);setTab("报告");setReportFocus(value=>value+1)});
   }).then(completed=>completed&&accepted);
  }}/>}
 {error&&<div role="alert" className="adj-error"><strong>{error.message}</strong>{error instanceof AdjudicationError&&<p>HTTP {error.status||"网络"} · {error.code}{error.reason?" · "+error.reason:""}</p>}</div>}
 {receipt&&error&&!pending&&<p role="status" className="adj-state warning">写入已确认，但后续案件读取未完成。请仅刷新案件：读取已确认版本，不要重发该写入。<button disabled={busy} onClick={()=>navigate(()=>void run(op=>load(receipt.caseId,op,undefined,receipt)))}>读取已确认版本</button></p>}
 {pending&&(!busy||error)&&<aside className="adj-state warning"><p>命令结果尚未确认。保留原请求键用于对账，不会自动换键提交。</p><p>原请求键：{pending.key}</p>{error instanceof CaseBindingError&&<p>收到的回执不符合原请求，未确认成功，也未确认拒绝；不会导航到回执中的其他案件。</p>}<button disabled={busy} onClick={()=>void send(pending)}>重试同一请求</button></aside>}
 {materialState.unknown&&<p role="status" className="adj-state warning">人工整理结果未确认，此案件仅可读取。原请求键：{materialState.unknown.requestKey}</p>}
 {token&&materialState.confirmedId&&!view&&<p role="status" className="adj-receipt">人工整理写入已确认，后续案件读取未完成。<button disabled={busy} onClick={()=>void run(op=>load(materialCase,op))}>读取已确认整理后的案件</button></p>}
 {!token?<section className="adj-panel"><h2>使用当前授权进入案件</h2><p>请通过已部署的身份服务登录。工作台不提供开发身份、粘贴令牌或默认案例。锁定仅清除本地会话和正文，不代表撤销身份服务会话。</p></section>:<><div className="adj-case-entry"><label>案件ID<input value={id} onChange={e=>setId(e.target.value)} disabled={busy||materialReadBusy||materialActive} pattern="[A-Za-z0-9_-]{1,120}"/></label><button disabled={busy||!id||materialReadBusy||materialActive} onClick={()=>navigate(()=>void run(op=>load(id,op)))}>进入 / 刷新案件</button><button disabled={busy||!!pending||materialReadBusy||materialFence} onClick={()=>navigate(()=>{clear();setError(null);setCreate(true);setTab("案件")})}>新建案件</button></div><p className="adj-muted">报告有未保存修订时，离开或刷新前会要求确认；其他未提交表单不会自动保存。冲突不会自动改版本重试。所有权限、来源和状态以服务器当前校验为准。</p>
 {receipt&&<div role="status" className="adj-receipt">服务器已确认：{receipt.action} · 工作版本 {receipt.workVersion}{receipt.reportRevision?" · 报告 v"+receipt.reportRevision:""} · 请求 {receipt.requestId}</div>}
 {create&&<fieldset disabled={busy||!!pending||materialFence||materialReadBusy}><CaseForm create={async body=>{await write("/v2/adjudication/case-works",body)}}/></fieldset>}
 {view&&<><section className="adj-summary"><div><span>工作版本</span><strong>{view.version}{historical?`（历史 / 当前头${head}）`:""}</strong></div><div><span>输入版本</span><strong>{view.inputVersion}</strong></div><div><span>辅助报告状态</span><strong>{current?(current.policyStale?"规则版本待更新":current.stale?"已失效":reviewLabels[current.report.reviewState]):"尚未生成"}</strong></div><div><span>材料 / 权项版</span><strong>{view.materials.length} / {view.claims.length}</strong></div></section>
 {historical&&<div className="adj-state warning">历史快照只读：这里的报告状态属于当时工作版本，不表示当前案件的有效结果。<button disabled={busy} onClick={()=>navigate(()=>void run(op=>load(view.caseInput.id,op)))}>返回当前案件版本</button></div>}
 <nav className="adj-tabs" aria-label="案件工作区">{tabs.map(t=><button key={t} aria-current={tab===t?"page":undefined} disabled={busy||materialReadBusy||materialActive} onClick={()=>{if(t!==tab)navigate(()=>setTab(t))}}>{t}</button>)}</nav>
 <fieldset disabled={busy||!!pending||materialFence||materialReadBusy||materialDirty} className="adj-content" key={view.caseInput.id+"/"+view.version+"/"+historical+"/"+editorReset}>
 {tab==="案件"&&<><CaseRead view={view} originals={originals}/>{!readonly&&<CaseForm current={view.caseInput} command={command}/>}</>}
 {tab==="材料"&&<><MaterialsRead view={view}/>{!readonly&&<MaterialForm view={view} command={command}/>}</>}
 {tab==="权项"&&<><ClaimsRead view={view}/>{!readonly&&<ClaimForm view={view} command={command}/>}</>}
 {tab==="引用"&&<><CitationsRead view={view}/>{!readonly&&<CitationForm view={view} command={command}/>}</>}
 {tab==="特征"&&<><FindingsRead view={view}/>{!readonly&&<FindingForm view={view} command={command}/>}</>}
 {tab==="报告"&&<div ref={reportArea} tabIndex={-1} aria-label="报告编辑与复核"><Reports reports={view.reports} command={command} readOnly={readonly} onDirty={setReportDirty} target={reportTarget}/></div>}
 {tab==="历史"&&<HistoryRead view={view} versions={versions} onOpen={v=>navigate(()=>void run(op=>load(view.caseInput.id,op,v)))}/>}
 </fieldset>{tab==="材料"&&token&&<MaterialOrganization key={view.caseInput.id} token={token} view={view} historical={historical} session={materialSession} disabled={busy||!!pending||reportDirty} isCurrent={()=>readContext===currentReadContext.current} onBusy={setMaterialReadBusy} onDirty={setMaterialDirty} onDenied={materialDenied} onConfirmed={materialConfirmed} onRefresh={()=>run(op=>load(view.caseInput.id,op))}/>}</>}
 {!view&&!create&&<section className="adj-panel"><h2>选择一个案件开始工作</h2><p>输入获授权的案件ID进入，或创建新案件。来源用途未准入、缺证和版本冲突将明确显示，不生成替代结论。</p></section>}</>}
 </main>;
}

