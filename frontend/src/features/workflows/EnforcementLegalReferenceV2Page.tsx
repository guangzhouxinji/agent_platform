import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, Select, Table, Tag, Typography } from "antd";
import { Link2, LogIn, LogOut, RefreshCw, Search } from "lucide-react";
import { beginEnforcementLogin, completeEnforcementLogin, enforcementRequest, readEnforcementIdentity, endEnforcementLogin, EnforcementAccessDenied } from "./enforcementNextV2Api";
import "./EnforcementNextV2Page.css";

type PageOperation = { epoch: number; operation: number; token: string | null };
const staleOperation = Symbol("discarded page operation");

type Candidate = { candidateId: string; sourceId: string; sourceVersion: string; articleId: string; officialTitle: string; locator: string; verbatimText: string; verbatimSha256: string; explanation: string; effectStatus: string; transitionStatus: string; gaps: string[]; reviewStatus: string };
type SearchResult = { searchId: string; revision: number; status: string; targetType: string; targetId: string; targetRevision: number; targetAnchor: string; scope: string[]; failedSources: string[]; members: Candidate[] };
type Reference = Candidate & { referenceId: string; revision: number; organization: string; searchId: string; searchRevision: number; targetType: string; targetId: string; targetRevision: number; targetAnchor: string; reviewState: string; bindingState: string; reviewedBy?: string; reviewReason?: string; previousRevision: number };
type Receipt = { requestId: string; operation: string; resultId: string; version: number };

export default function EnforcementLegalReferenceV2Page() {
  const [token, setToken] = useState<string | null>(null); const [organization, setOrganization] = useState("");
  const [result, setResult] = useState<SearchResult | null>(null); const [selected, setSelected] = useState<Candidate | null>(null);
  const [reference, setReference] = useState<Reference | null>(null); const [history, setHistory] = useState<Reference[]>([]);
  const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  const [searchForm] = Form.useForm();
  const [readForm] = Form.useForm();
  const [reviewForm] = Form.useForm();
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
    setToken(null); setOrganization(""); setBusy(false);
    setResult(null); setSelected(null); setReference(null); setHistory([]);
    searchForm.resetFields(); readForm.resetFields(); reviewForm.resetFields(); 
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
        const value = await guarded(context, () => completeEnforcementLogin("legal-references"));
        assertCurrent(context);
        if (!value) return;
        live.token = value; context.token = value; setToken(value);
        const identity = await guarded(context, () => readEnforcementIdentity(value));
        assertCurrent(context);
        const organization = identity.grants.find(grant => grant.resourceType === "LEGAL_REFERENCE")?.organization;
        if (!organization) throw new Error("身份上下文不可用");
        setOrganization(organization);
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
    if (!live.mounted || live.working || live.loggingOut || live.token !== token || (live.token && !organization)) return;
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
  async function readReference(context: PageOperation, id: string, revision: number) {
    assertCurrent(context); if (!token) return;
    const value = await request<Reference>(context, token, `/legal-references/${encodeURIComponent(id)}?revision=${revision}`);
    assertCurrent(context);
    const versions = await request<Reference[]>(context, token, `/legal-references/${encodeURIComponent(id)}/history`);
    assertCurrent(context); setReference(value); setSelected(value); setHistory(versions);
  }
  async function change(context: PageOperation, path: string, body: unknown) {
    assertCurrent(context); if (!token || !reference) return;
    const receipt = await request<Receipt>(context, token, `/legal-references/${reference.referenceId}/${path}`, body, crypto.randomUUID());
    assertCurrent(context);
    await readReference(context, receipt.resultId, receipt.version); assertCurrent(context);
  }
  return <main className="enf-next" aria-busy={busy || loggingOut}><header><div><Typography.Title level={2}>法律依据追溯</Typography.Title><span>逐字原文与解释分离；候选始终需要人工复核</span></div>{token ? <Button icon={<LogOut size={16} aria-hidden="true" />} disabled={loggingOut} onClick={() => void logout()}>退出</Button> : <Button disabled={busy} type="primary" icon={<LogIn size={16} aria-hidden="true" />} onClick={() => void run(() => beginEnforcementLogin("legal-references"))}>登录</Button>}</header>
    {error && <Alert type="error" showIcon role="alert" title={error} />}
    {token && organization && <Form form={readForm} disabled={busy} layout="inline" initialValues={{ existingRevision: 1 }}
      onFinish={values => void run(context => readReference(context, values.existingReferenceId, values.existingRevision))}>
      <Form.Item name="existingReferenceId" label="已有引用 ID" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item name="existingRevision" label="已有引用版本" rules={[{ required: true }]}><InputNumber min={1} precision={0} /></Form.Item>
      <Button disabled={busy} htmlType="submit" loading={busy}>读取已有引用与历史</Button>
    </Form>}
    {!(token && organization) ? <div className="enf-next-locked">登录后检索、绑定并复核引用</div> : <div className="enf-next-grid"><section><Typography.Title level={4}>按目标位置检索</Typography.Title>
      <Form form={searchForm} disabled={busy} layout="vertical" initialValues={{ targetType: "CONDUCT_REPORT", purpose: "LEGAL_REFERENCE", sourceRefs: "CNIPA" }} onFinish={values => void run(async context => { const created = await request<Receipt>(context, token, "/legal-basis/searches", { ...values, organization, eventAt: new Date(values.eventAt).toISOString(), sourceRefs: String(values.sourceRefs).split(/[,，\n]/).map(value => value.trim()).filter(Boolean) }, crypto.randomUUID()); assertCurrent(context); const found = await request<SearchResult>(context, token, `/legal-basis/searches/${created.resultId}?revision=${created.version}`); assertCurrent(context); setResult(found); setSelected(null); })}>
        <Form.Item label="当前组织"><Input value={organization} disabled /></Form.Item><div className="enf-next-row"><Form.Item name="targetType" label="目标类型" rules={[{ required: true }]}><Select options={[{ value: "CONDUCT_REPORT", label: "行为分析报告" }, { value: "PROCEDURE_REPORT", label: "程序校验报告" }]} /></Form.Item><Form.Item name="targetId" label="目标 ID" rules={[{ required: true }]}><Input /></Form.Item></div>
        <div className="enf-next-row"><Form.Item name="targetRevision" label="目标版本" rules={[{ required: true }]}><InputNumber min={1} precision={0} style={{ width: "100%" }} /></Form.Item><Form.Item name="targetAnchor" label="目标锚点" rules={[{ required: true }]}><Input /></Form.Item></div>
        <div className="enf-next-row"><Form.Item name="caseRef" label="案件 Ref" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="region" label="地区" rules={[{ required: true }]}><Input /></Form.Item></div>
        <div className="enf-next-row"><Form.Item name="eventAt" label="事件时点" rules={[{ required: true }]}><Input type="datetime-local" /></Form.Item><Form.Item name="purpose" label="用途" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Form.Item name="query" label="检索事项" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item><Form.Item name="sourceRefs" label="来源 Ref（逗号或换行分隔）" rules={[{ required: true }]}><Input /></Form.Item><Button disabled={busy} type="primary" htmlType="submit" icon={<Search size={16} aria-hidden="true" />} loading={busy}>检索候选</Button>
      </Form>
      {result && <div className="enf-next-detail"><Alert type={result.status === "COMPLETED" ? "success" : "warning"} showIcon title={`检索 ${result.searchId} v${result.revision} · ${result.status}`} description={result.failedSources.length ? `失败来源：${result.failedSources.join("；")}` : `实际范围：${result.scope.join("；")}`} /><Table rowKey="candidateId" size="small" pagination={false} dataSource={result.members} onRow={row => ({ onClick: () => { if (!lifecycle.current.working && lifecycle.current.token) setSelected(row); } })} columns={[{ title: "法规", dataIndex: "officialTitle" }, { title: "定位", dataIndex: "locator" }, { title: "复核", dataIndex: "reviewStatus", render: value => <Tag>{String(value)}</Tag> }]} /></div>}
    </section><section><Typography.Title level={4}>逐字原文与引用</Typography.Title>{selected ? <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[
      { key: "title", label: "名称 / 定位", children: `${selected.officialTitle} · ${selected.locator}` }, { key: "source", label: "来源版", children: `${selected.sourceId} v${selected.sourceVersion}` }, { key: "sha", label: "原文 SHA", children: selected.verbatimSha256 }, { key: "effect", label: "效力 / 过渡", children: `${selected.effectStatus} · ${selected.transitionStatus}` }, { key: "gaps", label: "待核缺口", children: selected.gaps.join("；") || "无" }
    ]} /><Typography.Title level={5}>逐字原文</Typography.Title><Typography.Paragraph>{selected.verbatimText}</Typography.Paragraph><Typography.Title level={5}>匹配解释</Typography.Title><Typography.Paragraph type="secondary">{selected.explanation}</Typography.Paragraph><Button disabled={busy} type="primary" icon={<Link2 size={16} aria-hidden="true" />} onClick={() => void run(async context => { if (!result) return; const receipt = await request<Receipt>(context, token, "/legal-references", { searchId: result.searchId, searchRevision: result.revision, candidateId: selected.candidateId, targetType: result.targetType, targetId: result.targetId, targetRevision: result.targetRevision, targetAnchor: result.targetAnchor }, crypto.randomUUID()); assertCurrent(context); await readReference(context, receipt.resultId, receipt.version); assertCurrent(context); })}>绑定到确切位置</Button></div> : <Empty description="选择候选后核对逐字原文" />}
      {reference && <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[{ key: "ref", label: "引用版本", children: `${reference.referenceId} v${reference.revision}` }, { key: "state", label: "审核 / 绑定", children: `${reference.reviewState} · ${reference.bindingState}` }, { key: "target", label: "目标", children: `${reference.targetType}/${reference.targetId} v${reference.targetRevision} · ${reference.targetAnchor}` }, { key: "previous", label: "前版", children: reference.previousRevision || "无" }]} />
        <Form form={reviewForm} disabled={busy} layout="vertical" onFinish={values => void run(context => change(context, "reviews", { expectedRevision: reference.revision, ...values }))}><Form.Item name="decision" label="人工复核" rules={[{ required: true }]}><Select options={[{ value: "CONFIRMED", label: "确认引用" }, { value: "RETURNED", label: "退回" }]} /></Form.Item><Form.Item name="reason" label="具名理由" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item><Button disabled={busy} htmlType="submit" loading={busy}>提交复核版本</Button></Form>
        <div className="enf-next-actions"><Button disabled={busy} onClick={() => void run(context => change(context, "rechecks", { expectedRevision: reference.revision, reason: "来源复核" }))}>重新核对来源</Button><Button disabled={busy} danger onClick={() => void run(context => change(context, "unbind", { expectedRevision: reference.revision, reason: "人工解除绑定" }))}>解除绑定</Button><Button disabled={busy} icon={<RefreshCw size={16} aria-hidden="true" />} onClick={() => void run(context => readReference(context, reference.referenceId, reference.revision))}>刷新</Button></div>
        <Typography.Title level={5}>不可变历史</Typography.Title><Table rowKey="revision" size="small" pagination={false} dataSource={history} columns={[{ title: "版本", dataIndex: "revision" }, { title: "审核", dataIndex: "reviewState" }, { title: "绑定", dataIndex: "bindingState" }, { title: "复核人", dataIndex: "reviewedBy", render: value => value || "—" }]} />
      </div>}
    </section></div>}
  </main>;
}
