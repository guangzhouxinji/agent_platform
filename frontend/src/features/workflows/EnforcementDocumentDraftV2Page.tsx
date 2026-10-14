import type { Manifest, InputAudit, Content, Draft, Prepared, Receipt, Candidate, Review, Baseline } from './enforcementDraftTypes';
import { draftReadRequest, loadBoundDraftRead } from './enforcementDraftReadBinding';
import { assertExportWriteReceipt, exportReadTarget, loadBoundDraftExport, deliverDraftExport, type ExportReceipt, type ExportReadTarget } from './enforcementDraftExportBinding';
import { assertDraftWriteReceipt } from './enforcementDraftReceipt';
import { EnforcementDraftSession } from "./enforcementDraftSessionHelper";
import { EnforcementDraftRecovery, type DraftWrite } from "./enforcementDraftRecovery";
import AgentTaskPanel, { type AgentRunView } from "../agent/AgentTaskPanel";
import { assertEnforcementRunResponse, buildEnforcementAgentStart, emptyEnforcementProcedure, enforcementDraftResult } from "./enforcementAgentStart";
import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, Select, Table, Typography } from "antd";
import { beginEnforcementLogin, endEnforcementLogin, enforcementRequest, enforcementDownload, EnforcementAccessDenied } from "./enforcementNextV2Api";
import "./EnforcementNextV2Page.css";
const display=(value:unknown)=>value===null||value===undefined?"缺项":typeof value==="string"?value:JSON.stringify(value);
export default function EnforcementDocumentDraftV2Page(){
  const [token,setToken]=useState<string|null>(null);const [organization,setOrganization]=useState("");
  const [inputs,setInputs]=useState<Manifest[]>([]);const [selection,setSelection]=useState<number>();const [id,setId]=useState("");
  const [inputAudit,setInputAudit]=useState<InputAudit|null>(null);
  const [prepared,setPrepared]=useState<Prepared|null>(null);const [current,setCurrent]=useState<Draft|null>(null);
  const [history,setHistory]=useState<Draft[]>([]);const [candidates,setCandidates]=useState<Candidate[]>([]);const [reviews,setReviews]=useState<Review[]>([]);
  const [receipt,setReceipt]=useState<Receipt|null>(null);const [busy,setBusy]=useState(false);const [error,setError]=useState("");
  const [edits,setEdits]=useState<Record<string,string>>({});const [reason,setReason]=useState("");
  const [baselines,setBaselines]=useState<Baseline[]>([]);const [baselineId,setBaselineId]=useState<string>();const [baselineResult,setBaselineResult]=useState("");
  const [procedure,setProcedure]=useState(emptyEnforcementProcedure);
  const [agentBusy,setAgentBusy]=useState(false);const [agentUncertain,setAgentUncertain]=useState(false);
  const [agentResult,setAgentResult]=useState<{id:string;revision:number;reference:string;manifest:Manifest}|null>(null);
  const [exportTarget,setExportTarget]=useState<ExportReadTarget|null>(null);const exportSequence=useRef(0);
  const contextEpoch=useRef(0);const readSequence=useRef(0);const session=useRef(new EnforcementDraftSession());
  const [readFeedback,setReadFeedback]=useState("");
  const recovery=useRef(new EnforcementDraftRecovery());
  const [pendingWrite,setPendingWrite]=useState(false);const writeContinuation=useRef<((value: unknown)=>Promise<void>)|null>(null);
  useEffect(()=>()=>{session.current.invalidate();contextEpoch.current++;readSequence.current++;recovery.current.clear();writeContinuation.current=null;},[]);
  async function submitWrite(request:DraftWrite){
    if(!token)throw new EnforcementAccessDenied();
    const epoch=contextEpoch.current;
    let value:unknown;
    try{
      value=await enforcementRequest<unknown>(token,request.path,JSON.parse(request.bodyJson),request.key);
      assertDraftWriteReceipt(request,value);if(request.path.endsWith("/exports"))assertExportWriteReceipt(request,value);
    }
    catch(cause){
      if(epoch!==contextEpoch.current)throw cause;
      recovery.current.fail(request,cause);
      if(epoch===contextEpoch.current){setPendingWrite(!!recovery.current.request);if(!recovery.current.request)writeContinuation.current=null;}
      throw cause;
    }
    if(epoch!==contextEpoch.current||!recovery.current.confirm(request))return;
    const continuation=writeContinuation.current;
    writeContinuation.current=null;setPendingWrite(false);
    await continuation?.(value);
  }
  async function write<T>(path:string,body:unknown,onConfirmed:(value:T)=>Promise<void>,key:string=crypto.randomUUID()){
    if(agentBusy||agentUncertain)throw new Error("请先对账当前 Agent 运行");
    const request=recovery.current.begin(path,body,key);
    writeContinuation.current=value=>onConfirmed(value as T);
    setPendingWrite(true);
    await submitWrite(request);
  }
  function resetAgentInput(){contextEpoch.current++;exportSequence.current++;setExportTarget(null);setProcedure(emptyEnforcementProcedure());setAgentResult(null);setAgentUncertain(false);setAgentBusy(false);}
  function clearDocument(){readSequence.current++;setReadFeedback("");setPrepared(null);setCurrent(null);setHistory([]);setCandidates([]);setReviews([]);setReceipt(null);setEdits({});setReason("");}
  useEffect(()=>{void session.current.login({token:setToken,organization:setOrganization,fail});},[]);
  function clear(){session.current.invalidate();setBusy(false);setError("");recovery.current.clear();writeContinuation.current=null;setPendingWrite(false);setId("");resetAgentInput();readSequence.current++;setReadFeedback("");setInputs([]);setInputAudit(null);setSelection(undefined);setPrepared(null);setCurrent(null);setHistory([]);setCandidates([]);setReviews([]);setReceipt(null);setEdits({});setReason("");setBaselines([]);setBaselineId(undefined);setBaselineResult("");}
  function fail(e:unknown){if(e instanceof EnforcementAccessDenied){clear();setToken(null);setOrganization("");}setError(e instanceof Error?e.message:"产品请求失败");}
  function run(action:()=>Promise<void>){return session.current.run(action,{start:()=>{setBusy(true);setError("");},fail,finish:()=>setBusy(false)});}
  function logout(){const previous=token;clear();setToken(null);setOrganization("");void run(async()=>{if(previous)await endEnforcementLogin(previous);});}
  async function loadBaselines(){if(!token)return;const active=session.current.capture();const result=await enforcementRequest<Baseline[]>(token,"/document-drafts/baseline-profiles?organization="+encodeURIComponent(organization));if(active())setBaselines(result);}
  async function refresh(){if(!token||!organization)return;resetAgentInput();clearDocument();setInputAudit(null);setInputs([]);setSelection(undefined);
    const epoch=contextEpoch.current;
    const result=await enforcementRequest<InputAudit>(token,"/document-drafts/input-diagnostics?organization="+encodeURIComponent(organization));
    if(epoch!==contextEpoch.current)return;
    setInputAudit(result);setInputs(result.inputs);}
  async function read(revision?:number,expectedManifest?:Manifest,confirmedReceipt?:Receipt){if(!token||!id)return;
    const epoch=contextEpoch.current;const sequence=++readSequence.current;
    const active=()=>epoch===contextEpoch.current&&sequence===readSequence.current;
    const target=draftReadRequest(id,revision,expectedManifest??(selection===undefined?undefined:inputs[selection]),receipt,confirmedReceipt);
    // Withdraw the previous document before any async read: it is not the new command result.
    setCurrent(null);setHistory([]);setCandidates([]);setReviews([]);setEdits({});setReason("");
    setReadFeedback("正在核验确切文书版本；旧稿已撤下，尚未交接人工复核");
    try{
      const result=await loadBoundDraftRead(path=>enforcementRequest(token,path),target,active);
      if(!result||!active())return;
      setHistory(result.history);setCurrent(result.current);setCandidates(result.candidates);setReviews(result.reviews);setPrepared(null);setReadFeedback("");
    }catch(cause){
      if(!active())return;
      setReadFeedback(target.receipt?"写入已确认，但结果读取失败或绑定失配；旧稿已撤下，未交接人工复核。可查询文书重读该回执版本，不要重新提交写入。":"读取失败或绑定失配；旧稿已撤下，未交接人工复核。请核对文书标识与输入后重新查询。");
      throw cause;
    }
  }
  async function prepare(){if(!token||selection===undefined||!id)return;
    setPrepared(null);
    await write<Prepared>("/document-drafts/"+encodeURIComponent(id)+"/prepare",inputs[selection],async value=>{setPrepared(value);});}
  async function command(action:string,body:unknown){if(!token||!id)return;
    const expectedManifest=action==="generate"?(selection===undefined?undefined:inputs[selection]):current?.manifest;
    await write<Receipt>("/document-drafts/"+encodeURIComponent(id)+"/"+action,body,async result=>{setReceipt(result);await read(result.revision,expectedManifest,result);});}
  async function readExport(target:ExportReadTarget){if(!token)return;
    const epoch=contextEpoch.current,sequence=++exportSequence.current;const active=()=>epoch===contextEpoch.current&&sequence===exportSequence.current;
    try{const blob=await loadBoundDraftExport(target,path=>enforcementRequest(token,path),path=>enforcementDownload(token,path),active);
      if(blob&&active()){deliverDraftExport(blob,target.receipt.exportId);setExportTarget(null);}
    }catch(cause){if(active())throw cause;}
  }
  async function exportDocx(){if(!token||!current||exportTarget)return;const original=current;
    await write<ExportReceipt>("/document-drafts/"+encodeURIComponent(id)+"/exports",
      {revision:original.revision,contentSha:original.contentSha256},async result=>{
        setReceipt(result);const target=exportReadTarget(result,original);setExportTarget(target);await readExport(target);
      });
  }
  const selectedManifest=selection===undefined?undefined:inputs[selection];
  const startInput=buildEnforcementAgentStart(selectedManifest,organization,procedure);
  const inputLocked=busy||agentBusy||agentUncertain||pendingWrite;
  async function agentRequest<T>(suffix:string,body?:unknown,key?:string):Promise<T>{
    if(!token)throw new EnforcementAccessDenied();
    if(recovery.current.request)throw new Error("请先对账同一文书请求");
    const epoch=contextEpoch.current;
    setAgentBusy(true);setAgentResult(null);
    try{
      const result=await enforcementRequest<T>(token,"/document-drafts/"+encodeURIComponent(id)+"/agent-runs"+suffix+(suffix?"?organization="+encodeURIComponent(organization):""),body,key);
      assertEnforcementRunResponse(result,id,suffix);
      if(epoch===contextEpoch.current)setAgentUncertain(false);
      return result;
    }catch(cause){
      if(epoch===contextEpoch.current){
        const status=(cause as {status?:number}).status;
        setAgentUncertain(status!==401&&status!==403&&status!==404&&status!==409);
        if(status===401||status===403){clear();setToken(null);setOrganization("");}
      }
      throw cause;
    }finally{if(epoch===contextEpoch.current)setAgentBusy(false);}
  }
  function onAgentView(view:AgentRunView|null){
    setAgentResult(null);
    if(!view||!selectedManifest||view.checkpoint.task.id!==id)return;
    const draft=enforcementDraftResult(view,id);
    if(draft)setAgentResult({...draft,manifest:structuredClone(selectedManifest)});
  }
  const content=current?JSON.parse(current.contentJson) as Content:null;
  const fields=content?.sections.filter(s=>s.visibility!=="HIDDEN").flatMap(s=>s.fields.map(f=>({...f,visibility:s.visibility})))??[];
  const isHead=!!current&&history.at(-1)?.revision===current.revision;
  return <main className="enf-next" aria-busy={busy}>
    <header><div><Typography.Title level={2}>文书草稿</Typography.Title><span>确切版本与来源可追溯  人工修改与候选采用分别记录</span></div>
      {token?<Button disabled={busy} onClick={logout}>退出</Button>:<Button type="primary" disabled={busy} onClick={()=>void run(()=>beginEnforcementLogin("document-drafts"))}>登录</Button>}</header>
    <Alert type="info" title="辅助文书草稿" description="项目内置模板标记 NON_OFFICIAL，不生成正式文号、签名、签章、签发或送达。技术审核不等于正式业务批准。" />
    {token&&organization&&id&&<>
      <section aria-label="执法 Agent 原生程序输入"><Typography.Title level={3}>Agent 程序检查输入</Typography.Title>
        <p>案件坐标绑定当前可消费输入；程序规则和日历须来自已批准版本，不从文书规则推导。当前权限和来源仍由服务端核验。</p>
        <Form layout="vertical">
          <Form.Item label="程序检查案件"><Input aria-label="程序检查案件" readOnly value={selectedManifest?.caseInput.id??""}/></Form.Item>
          <Form.Item label="程序检查案件版本"><Input aria-label="程序检查案件版本" readOnly value={selectedManifest?String(selectedManifest.caseInput.revision):""}/></Form.Item>
          {([["id","程序检查标识"],["ruleId","程序规则标识"],["ruleVersion","程序规则版本"],["calendarRef","日历标识"],["calendarVersion","日历版本"]] as const).map(([field,label])=><Form.Item key={field} label={label} required>
            <Input aria-label={label} disabled={inputLocked} value={procedure[field]} inputMode={field==="ruleVersion"?"numeric":undefined} onChange={event=>{contextEpoch.current++;setProcedure({...procedure,[field]:event.target.value});setAgentResult(null);}}/>
          </Form.Item>)}
        </Form>
        {!startInput&&<p role="status">请选择当前组织的可消费输入并显式填写全部程序坐标；规则版本须为正整数，不提供默认或自动复核。</p>}
        {agentUncertain&&<p role="status">运行请求尚未确认，输入已锁定。请在运行面板对账原请求或读取原运行后再更换输入。</p>}
      </section>
      <AgentTaskPanel scope={token+":"+organization+":"+id+":"+JSON.stringify(selectedManifest??null)+":"+JSON.stringify(procedure)} startInput={startInput} disabled={busy||pendingWrite} request={agentRequest} onView={onAgentView} onDenied={()=>{clear();setToken(null);setOrganization("");}}/>
      {agentResult&&<section aria-label="Agent 文书结果交接"><p>服务端文书结果：{agentResult.reference}。读取确切版本后，沿用下方人工修订、审核和导出流程。</p>
        <Button disabled={inputLocked} onClick={()=>void run(async()=>{await read(agentResult.revision,agentResult.manifest);})}>读取 Agent 文书结果以人工复核</Button>
      </section>}
    </>}
    {pendingWrite&&<section aria-label="文书请求对账"><Alert type="warning" title="文书请求结果尚未确认" description="保留原请求及幂等键。请对账同一文书请求后再更换输入或发起新写入；退出仅清除本地内存，不表示服务器命令已取消。"/>
      <Button disabled={busy} onClick={()=>void run(async()=>{const request=recovery.current.request;if(request)await submitWrite(request);})}>对账同一文书请求</Button>
    </section>}
    {error&&<Alert type="error" title="操作未完成" description={error} role="alert" />}
    {!token?<Empty description="请通过产品身份入口登录"/>:<>
      <section aria-label="文书与输入选择"><Form layout="vertical">
        <Form.Item label="当前组织"><Input value={organization} readOnly/></Form.Item>
        <Button disabled={inputLocked||!organization} onClick={()=>void run(loadBaselines)}>读取项目内置模板基线</Button>
        <Form.Item label="内置模板版本"><Select aria-label="内置模板版本" disabled={inputLocked} value={baselineId} onChange={setBaselineId} options={baselines.map(p=>({value:p.id,label:p.title+"@"+p.revision+" · "+p.classification}))}/></Form.Item>
        <Button disabled={inputLocked||!baselineId} onClick={()=>void run(async()=>{
          const p=baselines.find(v=>v.id===baselineId);if(!token||!p)return;
          await write<{templateId:string;revision:number;state:string;fileSha256:string}>("/document-drafts/baseline-templates",{profileId:p.id,profileRevision:p.revision,organization,region:"*",procedure:p.documentType,evidenceRef:"USER_PROJECT_ENF14_BASELINE_20261002_NON_OFFICIAL"},async result=>{setBaselineResult(result.templateId+"@"+result.revision+" · "+result.state+" · "+result.fileSha256);},"baseline-"+p.id+"-v"+p.revision+"-"+organization);
        })}>导入并发布所选项目基线模板</Button>
        {baselineResult&&<Alert type="success" title="模板发布回执" description={baselineResult}/>}
        <Form.Item label="文书标识"><Input aria-label="文书标识" disabled={inputLocked} value={id} onChange={e=>{setId(e.target.value);resetAgentInput();clearDocument();}} maxLength={120}/></Form.Item>
        <Button disabled={inputLocked||!id} onClick={()=>void run(()=>read())}>查询文书</Button>
        <Button disabled={inputLocked||!organization} onClick={()=>void run(refresh)}>读取可消费输入</Button>
        <Form.Item label="运行库输入组合"><Select aria-label="运行库输入组合" disabled={inputLocked} value={selection} onChange={v=>{setSelection(v);resetAgentInput();clearDocument();}}
          options={inputs.map((input,index)=>({value:index,label:input.type+" · "+input.caseInput.id+" · 模板 "+input.template.id+"@"+input.template.revision+" · 规则 "+input.rule.id+"@"+input.rule.revision}))}/></Form.Item>
        {!inputs.length&&<Empty description="未取得当前可消费输入；请核对已发布模板、规则、案件材料、确认引用和当前权限，不以 D 包替代运行对象"/>}
        {inputAudit&&<section aria-label="可消费输入诊断">
          <Typography.Title level={3}>可消费输入核验</Typography.Title>
          <p role="status">当前组织 {inputAudit.organization} · 可消费组合 {inputAudit.inputs.length} · 核验时间 {inputAudit.checkedAt}</p>
          <Alert type={inputAudit.inputs.length?"info":"warning"} title={inputAudit.inputs.length?"已有可选运行输入":"尚无可消费组合，逐项原因如下"} description="下列结果来自实际运行对象及当前权限核验。未授权对象不披露坐标；不适用项不是新增前置条件。"/>
          <Table rowKey={(r,i)=>r.category+r.objectId+r.code+i} dataSource={inputAudit.checks} pagination={{pageSize:20}} scroll={{x:true}} columns={[
            {title:"条件",dataIndex:"category"},{title:"对象／版本",render:(_,r)=>r.objectId?(r.objectId+(r.revision?"@"+r.revision:"")):"—"},
            {title:"结果",dataIndex:"status"},{title:"诊断码",dataIndex:"code"},{title:"未入选原因／核验依据",dataIndex:"reason"},{title:"原件 SHA",dataIndex:"sha256"}
          ]}/>
        </section>}
        <Button disabled={inputLocked||selection===undefined||!id} onClick={()=>void run(prepare)}>准备</Button>
        <Button type="primary" disabled={inputLocked||!prepared||selection===undefined||!!current||!!receipt} onClick={()=>void run(()=>command("generate",{input:inputs[selection!],preparedManifestHash:prepared!.inputManifestHash}))}>生成草稿</Button>
        <Button disabled={inputLocked||!isHead||!prepared||selection===undefined} onClick={()=>void run(()=>command("candidates",{expectedRevision:current!.revision,expectedContentSha:current!.contentSha256,input:inputs[selection!],preparedManifestHash:prepared!.inputManifestHash}))}>重生成候选</Button>
      </Form></section>
      {prepared&&<section><Typography.Title level={3}>准备结果</Typography.Title><p>{prepared.ready?"输入完整":"存在缺项或待处理项"} · {prepared.inputManifestHash}</p><Table rowKey={(r,i)=>r.field+r.code+i} dataSource={prepared.diagnostics} pagination={false} columns={[{title:"字段",dataIndex:"field"},{title:"诊断",dataIndex:"code"},{title:"阻塞",dataIndex:"blocking",render:v=>v?"是":"否"}]}/></section>}
      {readFeedback&&<Alert type="warning" role="status" title="文书结果尚未核实" description={readFeedback}/>}
      {current&&content&&<>
        <section><Typography.Title level={3}>{content.profile.title}</Typography.Title>
          <Select aria-label="文书版本" disabled={inputLocked} value={current.revision} onChange={v=>void run(()=>read(v))} options={history.map(v=>({value:v.revision,label:"版本 "+v.revision+" · "+v.createdAt}))}/>
          {!isHead&&<Alert type="warning" title="当前查看历史版本，仅供读取；修改、采用和审核必须针对当前版本"/>}
          <Descriptions column={1} bordered items={[{key:"sha",label:"内容 SHA",children:current.contentSha256},{key:"template",label:"模板版本",children:current.manifest.template.id+"@"+current.manifest.template.revision},{key:"rule",label:"规则版本",children:current.manifest.rule.id+"@"+current.manifest.rule.revision},{key:"state",label:"审核状态",children:isHead?(reviews.at(-1)?.outcome??current.reviewState):"历史审核记录不适用于当前文书"}]}/>
          <Table rowKey={r=>r.field+r.code} dataSource={current.missingItems} pagination={false} columns={[{title:"字段",dataIndex:"field"},{title:"缺项／诊断",dataIndex:"code"}]}/>
          <Table rowKey={r=>r.definition.id} dataSource={fields} pagination={false} scroll={{x:true}} columns={[
            {title:"字段",render:(_,r)=>r.definition.label},{title:"内容",render:(_,r)=>display(r.value)},
            {title:"原始来源",render:(_,r)=>r.source?r.source.sourceRef+"@"+r.source.sourceVersion+" · "+r.source.locator:"缺来源"},
            {title:"来源 SHA",render:(_,r)=>r.source?.sourceSha256??"缺项"},
            {title:"修订文本",render:(_,r)=>r.definition.editableText&&!r.definition.repeated&&r.visibility==="VISIBLE"?<Input.TextArea aria-label={r.definition.label+"修订文本"} disabled={!isHead||inputLocked} value={edits[r.definition.id]??""} onChange={e=>setEdits({...edits,[r.definition.id]:e.target.value})}/>:"受保护字段"}
          ]}/>
          <Form layout="vertical"><Form.Item label="修订或审核理由"><Input.TextArea disabled={inputLocked} value={reason} onChange={e=>setReason(e.target.value)}/></Form.Item></Form>
          <Button disabled={inputLocked||!isHead||!reason.trim()||!Object.values(edits).some(v=>v.trim())} onClick={()=>void run(()=>command("revisions",{expectedRevision:current.revision,expectedContentSha:current.contentSha256,changes:Object.fromEntries(Object.entries(edits).filter(([,v])=>v.trim())),reason}))}>保存新修订</Button>
          <Button disabled={inputLocked||!isHead||current.missingItems.some(i=>i.blocking)} onClick={()=>void run(()=>command("submit-review",{revision:current.revision,contentSha:current.contentSha256}))}>提交本版审核</Button>
          <Button disabled={inputLocked||!isHead||!reason.trim()||reviews.at(-1)?.outcome!=="SUBMITTED"} onClick={()=>void run(()=>command("reviews",{revision:current.revision,contentSha:current.contentSha256,outcome:"APPROVED",reason}))}>通过本版审核</Button>
          <Button disabled={inputLocked||!isHead||!reason.trim()||reviews.at(-1)?.outcome!=="SUBMITTED"} onClick={()=>void run(()=>command("reviews",{revision:current.revision,contentSha:current.contentSha256,outcome:"RETURNED",reason}))}>退回本版</Button>
          <Button disabled={inputLocked||!!exportTarget||!isHead||reviews.at(-1)?.outcome!=="APPROVED"||current.missingItems.some(i=>i.blocking)} onClick={()=>void run(exportDocx)}>导出本审核版 DOCX</Button>
        </section>
        <section><Typography.Title level={3}>独立候选差异</Typography.Title>{candidates.map(c=><div key={c.id}><p>候选 {c.id} · 基于版本 {c.baseRevision} · {c.contentSha256}</p><Table rowKey="field" dataSource={c.differences} pagination={false} columns={[{title:"字段／配置",dataIndex:"field"},{title:"原版",dataIndex:"beforeJson"},{title:"候选",dataIndex:"afterJson"}]}/><Button disabled={inputLocked||!isHead||c.baseRevision!==current.revision||c.baseContentSha256!==current.contentSha256} onClick={()=>void run(()=>command("adopt",{expectedRevision:current.revision,expectedContentSha:current.contentSha256,candidateId:c.id}))}>显式采用此候选</Button></div>)}</section>
        <section><Typography.Title level={3}>本版审核记录</Typography.Title><Table rowKey={(r,i)=>r.createdAt+r.actor+i} dataSource={reviews} pagination={false} columns={[{title:"版本",dataIndex:"revision"},{title:"结果",dataIndex:"outcome"},{title:"操作者",dataIndex:"actor"},{title:"理由",dataIndex:"reason"},{title:"时间",dataIndex:"createdAt"}]}/></section>
      </>}
      {exportTarget&&<Alert type="warning" title="导出写入已确认，文件尚未交付" description={<Button disabled={inputLocked} onClick={()=>void run(()=>readExport(exportTarget))}>仅重读原导出 {exportTarget.receipt.exportId}</Button>}/>}
      {receipt&&<Alert type="success" title={receipt.operation+" 写入已确认回执（不代表结果读取成功）"} description={receipt.requestId+" · 版本 "+receipt.revision+" · "+receipt.contentSha256}/>}
    </>}</main>;
}
