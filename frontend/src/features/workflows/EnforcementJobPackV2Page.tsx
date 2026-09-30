import { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Descriptions, Form, Input, Progress, Select, Steps, Tag, Typography } from "antd";
import { CheckCircle2, Circle, FileCheck2, LogIn, LogOut, RefreshCw, Send, ShieldCheck } from "lucide-react";
import { beginEnforcementLogin, completeEnforcementLogin, endEnforcementLogin, enforcementRequest, enforcementUpload,
  readEnforcementIdentity, EnforcementAccessDenied, type EnforcementIdentity } from "./enforcementNextV2Api";
import EnforcementCollectionTaskQueue, { type CollectionTask } from "./EnforcementCollectionTaskQueue";
import { displayTime, findingLabel, sectionsFor, sectionFindings, stateIndex, stateLabel, verificationLabel,
  type FactType, type Receipt, type Snapshot } from "./EnforcementJobPackV2Model";
import "./EnforcementJobPackV2Page.css";

export default function EnforcementJobPackV2Page() {
  const [token, setToken] = useState<string | null>(null);
  const [identity, setIdentity] = useState<EnforcementIdentity | null>(null);
  const [pack, setPack] = useState<Snapshot | null>(null);
  const [packId, setPackId] = useState("");
  const [activeType, setActiveType] = useState<FactType>("TASK");
  const [tasks, setTasks] = useState<CollectionTask[]>([]);
  const [organizations, setOrganizations] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const pending = useRef<{ fingerprint: string; key: string } | null>(null);
  useEffect(() => { void completeEnforcementLogin("job-pack").then(value => value && setToken(value)).catch(show); }, []);
  useEffect(() => { if (token) void run(async () => {
    const current = await readEnforcementIdentity(token); setIdentity(current);
    setOrganizations(Object.fromEntries(current.grants.filter(grant => grant.domain === "ENFORCEMENT"
      && grant.resourceType === "ENFORCEMENT_JOB_PACK" && grant.purpose === "JOB_PACK")
      .map(grant => [grant.resourceId, grant.organization])));
    await loadTasks();
  }); }, [token]);
  const activeSections = useMemo(() => sectionsFor(pack), [pack]);
  const submitted = useMemo(() => (pack?.publicInput ? 1 : 0) + (pack?.facts.length ?? 0), [pack]);
  const completed = useMemo(() => (pack?.publicInput ? 1 : 0) + (pack?.facts.filter(f =>
    f.verification === "VERIFIED" && f.verifiedBy !== f.submittedBy).length ?? 0), [pack]);
  const activeSection = activeSections.find(item => item.type === activeType) ?? activeSections[0];
  const activeFact = pack?.facts.find(item => item.type === activeType);
  useEffect(() => {
    if (!pack) return;
    const current = pack.facts.find(item => item.type === activeType);
    if (current?.verification !== "VERIFIED") return;
    const next = activeSections.find(section => pack.facts.find(item => item.type === section.type)?.verification !== "VERIFIED");
    if (next) setActiveType(next.type);
  }, [pack?.revision]);

  function show(failure: unknown) { setError(failure instanceof Error ? failure.message : "请求失败"); }
  function clear() { setToken(null); setIdentity(null); setPack(null); setTasks([]); setOrganizations({}); pending.current = null; }
  async function request<T>(path: string, body?: unknown): Promise<T> {
    if (!token) throw new Error("请先登录");
    const fingerprint = `${path}\0${JSON.stringify(body)}`;
    if (body !== undefined && pending.current?.fingerprint !== fingerprint) pending.current = { fingerprint, key: crypto.randomUUID() };
    setBusy(true); setError("");
    try { const result = await enforcementRequest<T>(token, path, body, pending.current?.key); if (body !== undefined) pending.current = null; return result; }
    catch (failure) { if (failure instanceof EnforcementAccessDenied) clear(); throw failure; }
    finally { setBusy(false); }
  }
  async function load(id = packId) { const value = await request<Snapshot>(`/job-packs/${encodeURIComponent(id)}`); setPack(value); setPackId(value.id); }
  async function loadTasks() { const value = await request<CollectionTask[]>("/job-packs/collection-tasks"); setTasks(value); }
  async function openTask(task: CollectionTask) { setPackId(task.packageId); await load(task.packageId); }
  async function claim(task: CollectionTask) {
    const result = await request<{ task: CollectionTask; jobPack: Snapshot }>(`/job-packs/collection-tasks/${encodeURIComponent(task.taskId)}/claim`, { organization: organizations[task.packageId] });
    setPack(result.jobPack); setPackId(result.jobPack.id); setActiveType("ACTOR_POSITION"); await loadTasks();
  }
  async function run(action: () => Promise<void>) { try { await action(); } catch (failure) { show(failure); } }
  async function submit(type: FactType, values: Record<string, unknown>) {
    const { evidenceRef, evidenceFile, ...fields } = values;
    let evidence: { ref: string; sha256: string; fileKey: string; originalName: string }[] = [];
    if (typeof evidenceRef === "string" && evidenceFile instanceof File) {
      const body = new FormData(); body.append("evidenceRef", evidenceRef); body.append("file", evidenceFile);
      evidence = [await enforcementUpload(token!, `/job-packs/${encodeURIComponent(pack!.id)}/evidence`, body)];
    }
    await request<Receipt>(`/job-packs/${encodeURIComponent(pack!.id)}/facts/${type}`, { fields,
      evidence });
    await load(pack!.id);
  }
  async function verify(type: FactType, accepted: boolean, reason: string) {
    await request<Receipt>(`/job-packs/${encodeURIComponent(pack!.id)}/facts/${type}/verification`,
      { expectedRevision: pack!.revision, accepted, reason }); await load(pack!.id);
  }
  return <main className="enf-job-pack">
    <header><div><Typography.Title level={2}>执法任务包</Typography.Title><span>执法 V2 · 功能验证与真实业务采集</span></div>
      {token ? <Button icon={<LogOut size={16} aria-hidden="true" />} onClick={() => void run(async () => {
        await endEnforcementLogin(token); clear();
      })}>退出</Button>
        : <Button type="primary" icon={<LogIn size={16} aria-hidden="true" />} onClick={() => void run(() => beginEnforcementLogin("job-pack"))}>登录</Button>}</header>
    {error && <Alert type="error" showIcon closable role="alert" title={error} onClose={() => setError("")} />}
    {!token ? <div className="enf-job-pack-locked">当前没有产品会话</div> : <>
      {identity && <Descriptions className="enf-job-pack-operator" size="small" bordered column={{ xs: 1, md: 2 }} items={[
        { key: "subject", label: "当前登录身份", children: identity.subject },
        { key: "organizations", label: "当前授权组织", children: [...new Set(identity.grants.map(grant => grant.organization))].join("、") || "无" }
      ]} />}
      <EnforcementCollectionTaskQueue tasks={tasks} busy={busy} organizations={organizations}
        onClaim={task => void run(() => claim(task))} onOpen={task => void run(() => openTask(task))} />
      <section className="enf-job-pack-toolbar" aria-label="任务包读取">
        <Input aria-label="任务包 ID" value={packId} onChange={event => setPackId(event.target.value)} placeholder="输入任务包 ID" />
        <Button icon={<RefreshCw size={16} aria-hidden="true" />} disabled={!packId} loading={busy} onClick={() => void run(() => load())}>读取</Button>
      </section>
      {!pack ? <section className="enf-job-pack-create"><Typography.Title level={4}>建立任务包</Typography.Title>
        <Alert type="info" showIcon title="身份和组织由产品授权自动带入" description="输入任务包 ID 后，系统使用当前会话中与该任务包精确匹配的 CREATE Grant；页面不要求手工填写 Subject、岗位或组织 Ref。" />
        <Form layout="vertical" onFinish={values => void run(async () => {
          const id = String(values.id ?? "");
          const matching = [...new Set(identity?.grants.filter(grant => grant.domain === "ENFORCEMENT"
            && grant.resourceType === "ENFORCEMENT_JOB_PACK" && grant.resourceId === id
            && grant.action === "CREATE" && grant.purpose === "JOB_PACK").map(grant => grant.organization) ?? [])];
          if (matching.length !== 1) throw new Error(matching.length === 0
            ? "当前身份没有该任务包的 CREATE 授权" : "该任务包存在多个授权组织，无法自动确定");
          const receipt = await request<Receipt>("/job-packs", { ...values, organization: matching[0] });
          await load(receipt.packageId);
        })}>
          <Form.Item name="id" label="任务包 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="title" label="任务名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="purpose" label="验证用途" initialValue="FUNCTIONAL_VALIDATION" rules={[{ required: true }]}><Select options={[
            { value: "FUNCTIONAL_VALIDATION", label: "功能验证（测试，不是正式业务验收）" },
            { value: "REAL_BUSINESS_COLLECTION", label: "正式业务采集" }
          ]} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>开始采集</Button>
        </Form></section> : <>
        <section className="enf-job-pack-status"><div><Tag color={pack.state.startsWith("WAITING_") ? "gold" : pack.state === "FAILED" ? "red" : "blue"}>{stateLabel[pack.state] ?? pack.state}</Tag>
          <strong>{pack.title}</strong><span>v{pack.revision} · 内容版 {pack.contentRevision}</span></div>
          <div className="enf-job-pack-counts"><strong>{submitted}/{activeSections.length + (pack.publicInput ? 1 : 0)} 已采集</strong><strong>{completed}/{activeSections.length + (pack.publicInput ? 1 : 0)} 已完成复核</strong></div>
          <Progress percent={Math.round(completed / (activeSections.length + (pack.publicInput ? 1 : 0)) * 100)} format={() => `${completed}/${activeSections.length + (pack.publicInput ? 1 : 0)}`} />
          <Steps size="small" current={stateIndex[pack.state] ?? 0} status={pack.state === "FAILED" ? "error" : "process"}
            items={["设计完成", "采集中", pack.publicInput ? "等待真人操作" : "等待数据", "已就绪",
              pack.purpose === "FUNCTIONAL_VALIDATION" ? "功能联调" : "真实集成", "结果"].map(title => ({ title }))} />
        </section>
        {pack.purpose === "FUNCTIONAL_VALIDATION" && <Alert type="info" showIcon title="功能验证任务包" description="使用测试身份和测试输入验证同一产品链；结果不代表正式业务验收。" />}
        {pack.findings.length > 0 && <Alert type="warning" showIcon title="尚未满足 READY" description={pack.purpose === "FUNCTIONAL_VALIDATION"
          ? "以下向导会显示尚未提交或复核的功能验证输入。" : "以下向导会逐项显示缺失、失效或尚未核验的真实事实。系统不会自动补齐真实性内容。"} />}
        {pack.publicInput && <Descriptions className="enf-job-pack-operator" size="small" bordered column={{ xs: 1, md: 2 }} items={[
          { key: "operator", label: "当前操作者", children: pack.publicInput.claimedBy },
          { key: "organization", label: "授权组织", children: pack.organization },
          { key: "source", label: "公开来源", children: pack.publicInput.sourceName },
          { key: "sha", label: "原件 SHA-256", children: pack.publicInput.rawSha256 }
        ]} />}
        <nav className="enf-job-pack-wizard" aria-label="真实事实采集向导"><ol>{activeSections.map((section, index) => {
          const fact = pack.facts.find(item => item.type === section.type);
          const verified = fact?.verification === "VERIFIED" && fact.verifiedBy !== fact.submittedBy;
          return <li key={section.type}><button type="button" aria-current={activeType === section.type ? "step" : undefined}
            aria-label={`打开${section.title}采集步骤`} onClick={() => setActiveType(section.type)}>
            <span className="enf-job-pack-wizard-index">{verified ? <CheckCircle2 size={18} aria-hidden="true" /> : <Circle size={18} aria-hidden="true" />}{index + 1}</span>
            <span><strong>{section.title}</strong><small>{fact ? verificationLabel[fact.verification] ?? fact.verification : "未提交"}</small></span>
          </button></li>;
        })}</ol></nav>
        <section className="enf-job-pack-step" aria-labelledby={`job-pack-step-${activeSection.type}`}>
          <div className="enf-job-pack-section-title"><div><span className="enf-job-pack-eyebrow">当前步骤</span>
            <Typography.Title id={`job-pack-step-${activeSection.type}`} level={3}>{activeSection.title}</Typography.Title></div>
            <Tag color={activeFact?.verification === "VERIFIED" ? "green" : activeFact?.verification === "REJECTED" ? "red" : "default"}>
              {activeFact ? verificationLabel[activeFact.verification] ?? activeFact.verification : "未提交"}</Tag></div>
            {(activeType === "ACTOR_POSITION" || activeType === "HUMAN_RECEIPT") && identity && <Alert type="info" showIcon
              title="身份信息由产品会话自动记录" description={`${identity.subject} · ${pack.organization}`} />}
          <Descriptions size="small" bordered column={{ xs: 1, md: 2 }} items={[
            { key: "required", label: "必填内容", span: "filled", children: activeSection.fields.map(field => field.label).join("、") },
            { key: "evidence", label: "对应证据", span: "filled", children: activeSection.evidenceHelp },
            { key: "submitter", label: "提交人", children: activeFact?.submittedBy ?? "尚未提交" },
            { key: "submittedAt", label: "提交时间", children: displayTime(activeFact?.submittedAt) },
            { key: "verifier", label: "核验人", children: activeFact?.verifiedBy ?? "尚未核验" },
            { key: "verifiedAt", label: "核验时间", children: displayTime(activeFact?.verifiedAt) },
            { key: "independent", label: "异人核验", children: activeFact?.verification === "VERIFIED" && activeFact.verifiedBy !== activeFact.submittedBy ? "已通过" : "未通过" },
            { key: "reason", label: "核验说明", children: activeFact?.verificationReason ?? "-" }
          ]} />
          {activeFact?.evidence?.length ? <div className="enf-job-pack-evidence"><strong><FileCheck2 size={17} aria-hidden="true" /> 已保存原件 / 凭证</strong>
            {activeFact.evidence.map(item => <dl key={`${item.ref}-${item.sha256}`}><div><dt>证据 Ref</dt><dd>{item.ref}</dd></div>
              <div><dt>原件</dt><dd>{item.originalName}</dd></div><div><dt>SHA-256</dt><dd>{item.sha256}</dd></div></dl>)}</div> : null}
          {sectionFindings(pack, activeType).length > 0 && <Alert type="warning" showIcon title="本项尚未完成"
            description={sectionFindings(pack, activeType).map(findingLabel).join("；")} />}
          <Form key={`${pack.revision}-${activeType}`} layout="vertical" onFinish={values => void run(() => submit(activeType, values))} initialValues={activeFact?.fields}>
              <div className="enf-job-pack-row">{activeSection.fields.map(field => <Form.Item key={field.name} name={field.name} label={field.label} rules={[{ required: true, message: `请填写${field.label}` }]}>
                {field.kind === "textarea" ? <Input.TextArea rows={2} /> : field.kind === "decision" ? <Select options={[{ value: "APPROVED", label: "批准" }, { value: "RETURNED", label: "退回" }, { value: "MODIFIED", label: "要求修改" }]} />
                  : field.kind === "score" ? <Select options={[1, 2, 3, 4, 5].map(value => ({ value: String(value), label: String(value) }))} /> : <Input type={field.kind === "date" ? "date" : "text"} />}
              </Form.Item>)}</div>
              {activeSection.evidence && <fieldset><legend>原件 / 凭证定位</legend><div className="enf-job-pack-row">
                <Form.Item name="evidenceRef" label="证据 Ref" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item name="evidenceFile" label="原件文件" rules={[{ required: true }]}
                  getValueFromEvent={event => event.target.files?.[0]} getValueProps={() => ({})}>
                  <input className="enf-job-pack-file" type="file" /></Form.Item></div></fieldset>}
              <Button htmlType="submit" loading={busy} icon={<Send size={16} aria-hidden="true" />}>提交新版本</Button>
            </Form>
            {activeFact && activeFact.verification !== "VERIFIED" && <Form className="enf-job-pack-review" layout="vertical" onFinish={values => void run(() => verify(activeType, values.accepted, values.reason))}>
              <Form.Item name="reason" label="异人核验说明" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name="accepted" initialValue={true} hidden><Input /></Form.Item>
              <Button icon={<ShieldCheck size={16} aria-hidden="true" />} htmlType="submit">核验通过</Button>
              <Button danger onClick={() => void run(() => verify(activeType, false, "证据或真实性核验未通过"))}>拒绝</Button>
            </Form>}
        </section>
        {pack.trigger && <Alert type="success" showIcon role="status"
          title={pack.purpose === "FUNCTIONAL_VALIDATION" ? "已自动触发功能联调" : "已自动触发真实集成"}
          description={`${pack.trigger.taskId} · ${pack.trigger.requestId}`} />}
        <Descriptions size="small" bordered column={1} items={[{ key: "id", label: "任务包", children: `${pack.id} v${pack.revision}` },
          { key: "org", label: "组织 / 目的", children: `${pack.organization} · ${pack.purpose}` }, { key: "sha", label: "组包 SHA-256", children: pack.sha256 }]} />
      </>}</>}
  </main>;
}
