import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, Select, Table, Typography } from "antd";
import { beginEnforcementLogin, completeEnforcementLogin, enforcementRequest, readEnforcementIdentity, endEnforcementLogin, EnforcementAccessDenied } from "./enforcementNextV2Api";
import "./EnforcementNextV2Page.css";

type PageOperation = { epoch: number; operation: number; token: string | null };
const staleOperation = Symbol("discarded page operation");
type Mapping = { fieldId: string; sourcePath: string; schemaVersion: string; mode: string; transform?: string; missing: string };
type Rule = { id: string; revision: number; organization: string; state: string; templateId: string; templateRevision: number; templateSha256: string; schemaId: string; schemaVersion: string; mappings: Mapping[] };
type Receipt = { requestId: string; ruleId: string; revision: number; operation: string };
type Diagnostic = { fieldId: string; sourcePath: string; code: string; blocking: boolean };
type Trial = { ruleRevision?:number; complete?: boolean; valid?: boolean; reviewRequired: boolean; diagnostics: Diagnostic[]; fields?: Array<{ fieldId: string; value: unknown; sourceRef: string; sourceVersion: string; locator: string; reviewRequired: boolean }> };
type Attempt = { id:string; revision:number; action:string; outcome:string; failureCode?:string; completedAt:string };
export default function EnforcementDocumentRuleV2Page() {
  const [token,setToken]=useState<string|null>(null);const [org,setOrg]=useState("");
  const [rules,setRules]=useState<Rule[]>([]);const [current,setCurrent]=useState<Rule|null>(null);
  const [history,setHistory]=useState<Rule[]>([]);const [result,setResult]=useState<Trial|null>(null);
  const [attempts,setAttempts]=useState<Attempt[]>([]);
  const [receipt,setReceipt]=useState<Receipt|null>(null);const [busy,setBusy]=useState(false);const [error,setError]=useState("");
  const [form]=Form.useForm();
  const [readForm] = Form.useForm();
  const lifecycle = useRef({ mounted: false, epoch: 0, operation: 0, token: null as string | null, working: false, loggingOut: false });
  const [loggingOut, setLoggingOut] = useState(false);

  function isCurrent(context: PageOperation) {
    const live = lifecycle.current;
    return live.mounted && live.epoch === context.epoch && live.operation === context.operation && live.token === context.token;
  }
  function assertCurrent(context: PageOperation) { if (!isCurrent(context)) throw staleOperation; }
  // Invalidate synchronously; pending transports and form validation cannot revive this page.
  function clearSession() {
    const live = lifecycle.current;
    live.epoch++; live.operation++; live.token = null; live.working = false;
    setToken(null); setOrg(""); setBusy(false);
    setRules([]); setCurrent(null); setHistory([]); setResult(null); setAttempts([]); setReceipt(null);
    form.resetFields(); readForm.resetFields(); 
  }
  async function guarded<T>(context: PageOperation, action: () => Promise<T>): Promise<T> {
    assertCurrent(context);
    try { const value = await action(); assertCurrent(context); return value; }
    catch (failure) { assertCurrent(context); throw failure; }
  }
  function request<T>(context: PageOperation, sessionToken: string, path: string, body?: unknown, key?: string) {
    assertCurrent(context);
    if (sessionToken !== context.token) throw staleOperation;
    return guarded(context, () => enforcementRequest<T>(sessionToken, path, body, key));
  }
  useEffect(() => {
    const live = lifecycle.current;
    live.mounted = true; live.epoch++; live.operation++;
    const context: PageOperation = { epoch: live.epoch, operation: live.operation, token: null };
    void (async () => {
      try {
        const value = await guarded(context, () => completeEnforcementLogin("document-rules"));
        assertCurrent(context);
        if (!value) return;
        live.token = value; context.token = value; setToken(value);
        const identity = await guarded(context, () => readEnforcementIdentity(value));
        assertCurrent(context);
        const organization = identity.grants.find(grant => grant.resourceType === "DOCUMENT_RULE")?.organization;
        if (!organization) throw new Error("身份上下文不可用");
        setOrg(organization);
      } catch (failure) {
        if (!isCurrent(context)) return;
        clearSession();
        setError(failure instanceof Error ? failure.message : "登录失败");
      }
    })();
    return () => { live.mounted = false; live.epoch++; live.operation++; live.token = null; live.working = false; };
  }, []);

  // Include operation identity: an old catch/finally cannot clear a later login error/busy.
  async function run(action: (context: PageOperation) => Promise<void>) {
    const live = lifecycle.current;
    if (!live.mounted || live.working || live.loggingOut || live.token !== token || (live.token && !org)) return;
    live.working = true; live.operation++;
    const context = { epoch: live.epoch, operation: live.operation, token: live.token };
    setBusy(true); setError("");
    try { await action(context); assertCurrent(context); }
    catch (failure) {
      if (!isCurrent(context)) return;
      if (failure instanceof EnforcementAccessDenied) clearSession();
      setError(failure instanceof Error ? failure.message : "请求失败");
    } finally {
      if (isCurrent(context)) { live.working = false; setBusy(false); }
    }
  }
  async function logout() {
    const live = lifecycle.current;
    if (!live.mounted || live.loggingOut || !live.token) return;
    const capturedToken = live.token;
    clearSession(); setError(""); live.loggingOut = true; setLoggingOut(true); setBusy(true);
    const context = { epoch: live.epoch, operation: live.operation, token: null };
    try { await endEnforcementLogin(capturedToken); assertCurrent(context); }
    catch (failure) { if (isCurrent(context)) setError(failure instanceof Error ? failure.message : "退出失败"); }
    finally { if (isCurrent(context)) { live.loggingOut = false; setLoggingOut(false); setBusy(false); } }
  }
  async function read(context: PageOperation, id: string, revision: number) {
    assertCurrent(context); if (!token) return;
    const rule = await request<Rule>(context, token, `/document-rules/${encodeURIComponent(id)}?revision=${revision}`);
    assertCurrent(context);
    const versions = await request<Rule[]>(context, token, `/document-rules/${encodeURIComponent(id)}/history`);
    assertCurrent(context);
    const executions = await request<Attempt[]>(context, token, `/document-rules/${encodeURIComponent(id)}/attempts`);
    assertCurrent(context);
    setAttempts(executions); setCurrent(rule); setHistory(versions); setResult(null);
    form.setFieldsValue({ ...rule, expectedRevision: rule.revision });
  }
  async function refresh(context: PageOperation) {
    assertCurrent(context); if (!token) return;
    const value = await request<Rule[]>(context, token, `/document-rules?organization=${encodeURIComponent(org)}`);
    assertCurrent(context); setRules(value);
  }
  async function change(context: PageOperation, action: string) {
    assertCurrent(context); if (!token || !current) return;
    const value = await request<Receipt>(context, token, `/document-rules/${encodeURIComponent(current.id)}/${action}`,
      { expectedRevision: current.revision, purpose: "DOCUMENT_MAPPING" }, crypto.randomUUID());
    assertCurrent(context); setReceipt(value);
    await read(context, value.ruleId, value.revision); assertCurrent(context);
    await refresh(context); assertCurrent(context);
  }
  async function evaluate(context: PageOperation, action: string) {
    assertCurrent(context); if (!token || !current) return;
    const input = current; setResult(null);
    try {
      const value = await request<Trial>(context, token, `/document-rules/${encodeURIComponent(input.id)}/${action}`,
        { expectedRevision: input.revision, purpose: "DOCUMENT_MAPPING" }, crypto.randomUUID());
      assertCurrent(context);
      if (value.ruleRevision && value.ruleRevision !== input.revision) {
        await read(context, input.id, value.ruleRevision); assertCurrent(context);
      }
      const versions = await request<Rule[]>(context, token, `/document-rules/${encodeURIComponent(input.id)}/history`);
      assertCurrent(context); setHistory(versions); setCurrent(versions.at(-1) ?? input);
      const executions = await request<Attempt[]>(context, token, `/document-rules/${encodeURIComponent(input.id)}/attempts`);
      assertCurrent(context); setAttempts(executions);
      await refresh(context); assertCurrent(context); setResult(value);
    } catch (failure) {
      assertCurrent(context);
      if (!(failure instanceof EnforcementAccessDenied)) {
        try {
          const executions = await request<Attempt[]>(context, token, `/document-rules/${encodeURIComponent(input.id)}/attempts`);
          assertCurrent(context); setAttempts(executions);
        } catch (reload) {
          assertCurrent(context);
          if (reload instanceof EnforcementAccessDenied) throw reload;
          setAttempts([]);
        }
      }
      throw failure;
    }
  }
  return <main className="enf-next" aria-busy={busy || loggingOut}><header><div><Typography.Title level={2}>文书字段映射</Typography.Title><span>精确模板与来源版本；试生成不改写业务事实</span></div>{token?<Button disabled={loggingOut} onClick={()=>void logout()}>退出</Button>:<Button disabled={busy} type="primary" onClick={()=>void run(() => beginEnforcementLogin("document-rules"))}>登录</Button>}</header>
  {error&&<Alert role="alert" type="error" showIcon title={error}/>} {!(token && org)?<Empty description="登录后配置、校验和试生成"/>:<div className="enf-next-grid"><section>
    <Typography.Title level={4}>规则列表</Typography.Title><Button disabled={busy} loading={busy} onClick={()=>void run(refresh)}>刷新列表</Button>
    <Table rowKey="id" size="small" dataSource={rules} onRow={r=>({onClick:()=>void run(context =>read(context, r.id,r.revision))})} columns={[{title:"规则",dataIndex:"id"},{title:"版本",dataIndex:"revision"},{title:"状态",dataIndex:"state"}]}/>
    <Form form={readForm} disabled={busy} layout="inline" onFinish={v=>void run(context =>read(context, v.existingId,v.existingRevision))}><Form.Item name="existingId" label="已有规则 ID" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="existingRevision" label="版本" rules={[{required:true}]}><InputNumber min={1} precision={0}/></Form.Item><Button disabled={busy} htmlType="submit" loading={busy}>读取规则</Button></Form>
    <Typography.Title level={4}>映射维护</Typography.Title>
    <Form disabled={busy} form={form} layout="vertical" initialValues={{expectedRevision:0,mappings:[{mode:"DIRECT",missing:"BLOCK"}]}} onFinish={v=>void run(async context =>{if(!token)return;
      const r=await request<Receipt>(context, token,"/document-rules",{expectedRevision:v.expectedRevision,rule:{...v,organization:org,revision:v.expectedRevision+1,state:"DRAFT"}},crypto.randomUUID());assertCurrent(context);setReceipt(r);await read(context, r.ruleId,r.revision);await refresh(context);})}>
    <Form.Item name="id" label="规则 ID" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="expectedRevision" label="基于版本（新建为 0）" rules={[{required:true}]}><InputNumber min={0} precision={0}/></Form.Item>
    <Form.Item name="templateId" label="模板 ID" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="templateRevision" label="模板版本" rules={[{required:true}]}><InputNumber min={1} precision={0}/></Form.Item><Form.Item name="templateSha256" label="模板 SHA" rules={[{required:true},{pattern:/^[a-f0-9]{64}$/,message:"请输入模板返回的完整 SHA"}]}><Input/></Form.Item>
    <Form.Item name="schemaId" label="来源 Schema ID" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="schemaVersion" label="来源 Schema 版本" rules={[{required:true}]}><Input/></Form.Item>
    <Form.List name="mappings">{(fields,{add,remove})=><>{fields.map(field=><div className="enf-next-detail" key={field.key}>
      <Form.Item name={[field.name,"fieldId"]} label="模板字段" rules={[{required:true}]}><Input/></Form.Item><Form.Item name={[field.name,"sourcePath"]} label="来源路径" rules={[{required:true}]}><Input/></Form.Item><Form.Item name={[field.name,"schemaVersion"]} label="路径 Schema 版本" rules={[{required:true}]}><Input/></Form.Item>
      <Form.Item name={[field.name,"mode"]} label="映射方式" rules={[{required:true}]}><Select options={["DIRECT","DETERMINISTIC","AUXILIARY_DRAFT"].map(value=>({value,label:value}))}/></Form.Item><Form.Item name={[field.name,"transform"]} label="确定性转换"><Input placeholder="仅日期转换可填 ISO_DATE"/></Form.Item><Form.Item name={[field.name,"missing"]} label="缺失策略" rules={[{required:true}]}><Select options={["BLOCK","BLANK_WITH_WARNING","OMIT_OPTIONAL"].map(value=>({value,label:value}))}/></Form.Item><Button disabled={busy} onClick={()=>remove(field.name)}>移除此映射</Button></div>)}<Button disabled={busy} onClick={()=>add({mode:"DIRECT",missing:"BLOCK"})}>添加映射</Button></>}</Form.List><Button disabled={busy} type="primary" loading={busy} htmlType="submit">保存草稿版本</Button></Form>
    </section><section><Typography.Title level={4}>版本、诊断与试生成</Typography.Title>
    {receipt&&<Alert type="success" showIcon title={`回执 ${receipt.requestId}`} description={`${receipt.operation} · ${receipt.ruleId} v${receipt.revision}`}/>}
    {current?<><Descriptions bordered column={1} items={[{key:"id",label:"规则版本",children:`${current.id} v${current.revision}`},{key:"state",label:"状态",children:current.state},{key:"template",label:"模板版本",children:`${current.templateId} v${current.templateRevision}`},{key:"schema",label:"Schema",children:`${current.schemaId} @${current.schemaVersion}`}]}/>
    <div className="enf-next-actions">{["validate","trial"].map(action=><Button disabled={busy} key={action} loading={busy} onClick={()=>void run(context =>evaluate(context, action))}>{action==="validate"?"校验映射":"试生成"}</Button>)}<Button disabled={busy || (current.state!=="DRAFT")} loading={busy} onClick={()=>void run(context =>change(context, "publish"))}>发布</Button><Button danger disabled={busy || (!["PUBLISHED","REVIEW_REQUIRED"].includes(current.state))} loading={busy} onClick={()=>void run(context =>change(context, "disable"))}>停用</Button></div>
    {result&&<><Alert type={result.valid===false||result.complete===false?"warning":"success"} showIcon title={`校验 ${String(result.valid??result.complete)} · 待复核 ${String(result.reviewRequired)}`}/><Table rowKey={r=>r.fieldId+r.sourcePath+r.code} size="small" pagination={false} dataSource={result.diagnostics} columns={[{title:"字段",dataIndex:"fieldId"},{title:"来源路径",dataIndex:"sourcePath"},{title:"诊断",dataIndex:"code"},{title:"阻断",dataIndex:"blocking",render:v=>String(v)}]}/>{result.fields&&<Table rowKey="fieldId" size="small" pagination={false} dataSource={result.fields} columns={[{title:"字段",dataIndex:"fieldId"},{title:"实际值",dataIndex:"value",render:v=>JSON.stringify(v)},{title:"来源",dataIndex:"sourceRef"},{title:"来源版",dataIndex:"sourceVersion"},{title:"定位",dataIndex:"locator"}]}/>}</>}
    <Typography.Title level={5}>校验与试生成执行历史</Typography.Title><Table rowKey="id" size="small" dataSource={attempts} columns={[{title:"执行编号",dataIndex:"id"},{title:"规则版",dataIndex:"revision"},{title:"操作",dataIndex:"action"},{title:"结果",dataIndex:"outcome"},{title:"失败原因",dataIndex:"failureCode"},{title:"时间",dataIndex:"completedAt"}]}/><Typography.Title level={5}>不可变历史</Typography.Title><Table rowKey="revision" size="small" pagination={false} dataSource={history} columns={[{title:"版本",dataIndex:"revision"},{title:"状态",dataIndex:"state"},{title:"Schema 版",dataIndex:"schemaVersion"}]}/></>:<Empty description="选择或读取规则查看实际版本"/>}
    </section></div>}</main>;
}
