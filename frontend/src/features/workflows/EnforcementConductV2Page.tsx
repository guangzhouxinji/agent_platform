import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, Select, Tabs, Tag, Typography } from "antd";
import { LogIn, LogOut, Plus, RefreshCw } from "lucide-react";
import { beginEnforcementLogin, completeEnforcementLogin, enforcementRequest, EnforcementAccessDenied } from "./enforcementNextV2Api";
import { ConductFacts, ConductFindings, type Rule, type Report, type Receipt, type Trial } from "./EnforcementConductV2Types";
import "./EnforcementNextV2Page.css";
const behaviorOptions = [
  { value: "PATENT_INFRINGEMENT", label: "专利侵权候选" },
  { value: "COUNTERFEIT_PATENT", label: "假冒专利候选" },
  { value: "TRADEMARK_INFRINGEMENT", label: "商标侵权候选" }
];
export default function EnforcementConductV2Page() {
  const [token, setToken] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [rule, setRule] = useState<Rule | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [trial, setTrial] = useState<Trial | null>(null);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [lookupRule, setLookupRule] = useState("");
  const [lookupRuleVersion, setLookupRuleVersion] = useState<number | null>(null);
  const [lookupReport, setLookupReport] = useState("");
  const [lookupReportVersion, setLookupReportVersion] = useState<number | null>(null);
  const [analysisForm] = Form.useForm();
  const pending = useRef<{ fingerprint: string; key: string } | null>(null);
  useEffect(() => {
    void completeEnforcementLogin("conduct").then(setToken)
      .catch(failure => setError(failure instanceof Error ? failure.message : "登录失败"));
  }, []);
  function clear() {
    setToken(null); setRule(null); setReport(null); setTrial(null); setReceipt(null);
    pending.current = null;
  }
  async function request<T>(path: string, body?: unknown): Promise<T> {
    if (!token) throw new Error("请先登录");
    const fingerprint = `${path}\u0000${JSON.stringify(body)}`;
    if (body !== undefined && pending.current?.fingerprint !== fingerprint)
      pending.current = { fingerprint, key: crypto.randomUUID() };
    setBusy(true); setError("");
    try {
      const value = await enforcementRequest<T>(token, path, body, pending.current?.key);
      if (body !== undefined) pending.current = null;
      return value;
    } catch (failure) {
      if (failure instanceof EnforcementAccessDenied) clear();
      throw failure;
    } finally { setBusy(false); }
  }
  async function run(action: () => Promise<void>) {
    try { await action(); } catch (failure) { setError(failure instanceof Error ? failure.message : "请求失败"); }
  }
  async function readRule(id: string, version: number) {
    setRule(await request<Rule>(`/conduct-rules/${encodeURIComponent(id)}/versions/${version}`));
    setTrial(null);
  }
  async function readReport(id: string, version: number) {
    setReport(await request<Report>(`/conduct-analyses/${encodeURIComponent(id)}/versions/${version}`));
  }
  async function ruleCommand(operation: "publish" | "disable", body: unknown = {}) {
    if (!rule) return;
    const value = await request<Receipt>(`/conduct-rules/${encodeURIComponent(rule.id)}/versions/${rule.version}/${operation}`, body);
    setReceipt(value); await readRule(rule.id, value.version);
  }

  return <main className="enf-next">
    <header><div><Typography.Title level={2}>行为逐要件分析</Typography.Title><span>执法 V2 · 辅助候选，不作侵权认定</span></div>
      {token ? <Button icon={<LogOut size={16} aria-hidden="true" />} onClick={clear}>退出</Button>
        : <Button type="primary" icon={<LogIn size={16} aria-hidden="true" />} onClick={() => void run(() => beginEnforcementLogin("conduct"))}>登录</Button>}</header>
    {error && <Alert type="error" showIcon role="alert" title={error} closable onClose={() => setError("")} />}
    {!token ? <div className="enf-next-locked">当前没有产品会话</div> : <Tabs items={[
      { key: "rules", label: "行为规则", children: <div className="enf-next-grid"><section>
        <Typography.Title level={4}>规则草稿</Typography.Title>
        <Form layout="vertical" onFinish={values => void run(async () => {
          const created = await request<Receipt>("/conduct-rules", {
            ...values, defenses: values.defenses?.split(/[,，\n]/).map((text: string) => text.trim()).filter(Boolean) ?? [],
            noDefensesConfirmed: values.noDefensesConfirmed === "true" });
          setReceipt(created); await readRule(values.id, created.version);
        })}>
          <div className="enf-next-row"><Form.Item name="id" label="规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="organization" label="组织" rules={[{ required: true }]}><Input /></Form.Item></div>
          <Form.Item name="behavior" label="行为类型" rules={[{ required: true }]}><Select options={behaviorOptions} /></Form.Item>
          <div className="enf-next-row"><Form.Item name={["basis", "ref"]} label="依据 Ref" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name={["basis", "version"]} label="依据版本" rules={[{ required: true }]}><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={["basis", "locator"]} label="原文定位"><Input /></Form.Item>
            <Form.Item name={["basis", "effectStatus"]} label="效力核验状态"><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={["basis", "scope"]} label="适用范围"><Input /></Form.Item>
            <Form.Item name={["basis", "jurisdiction"]} label="职权地区"><Input /></Form.Item></div>
          <Form.Item name={["basis", "procedure"]} label="程序"><Input /></Form.Item>
          <Typography.Title level={5}>逐行为要件</Typography.Title>
          <Form.List name="elements" initialValue={[{ required: true }]}>{(fields, { add, remove }) => <>
            {fields.map(field => <div key={field.key} className="enf-next-form-list"><div className="enf-next-row">
              <Form.Item name={[field.name, "id"]} label="要件 ID" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name={[field.name, "description"]} label="要件说明" rules={[{ required: true }]}><Input /></Form.Item></div>
              <Form.Item name={[field.name, "required"]} label="是否必需" initialValue={true}><Select options={[{ value: true, label: "必需" }, { value: false, label: "非必需" }]} /></Form.Item>
              <Button onClick={() => remove(field.name)} disabled={fields.length === 1}>移除此项</Button></div>)}
            <Button icon={<Plus size={16} aria-hidden="true" />} onClick={() => add({ required: true })}>添加要件</Button></>}</Form.List>
          <Form.Item name="defenses" label="抗辩 Ref（逗号或换行分隔）"><Input.TextArea rows={2} /></Form.Item>
          <div className="enf-next-row"><Form.Item name="noDefensesConfirmed" label="无抗辩项确认" initialValue="false"><Select options={[{ value: "false", label: "未确认" }, { value: "true", label: "已确认" }]} /></Form.Item>
            <Form.Item name="noDefensesSource" label="确认来源"><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name="reviewRef" label="复核 Ref"><Input /></Form.Item>
            <Form.Item name="author" label="作者"><Input /></Form.Item></div>
          <Button type="primary" htmlType="submit" loading={busy}>保存草稿</Button>
        </Form>
      </section><section><Typography.Title level={4}>确切规则版</Typography.Title>
        <div className="enf-next-lookup"><Input aria-label="规则 ID" placeholder="规则 ID" value={lookupRule} onChange={event => setLookupRule(event.target.value)} />
          <InputNumber aria-label="规则版本" placeholder="版本" min={1} precision={0} value={lookupRuleVersion} onChange={setLookupRuleVersion} />
          <Button icon={<RefreshCw size={16} aria-hidden="true" />} disabled={!lookupRule || !lookupRuleVersion} loading={busy}
            onClick={() => void run(() => readRule(lookupRule, lookupRuleVersion!))}>读取</Button></div>
        {rule ? <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[
          { key: "id", label: "规则版", children: `${rule.id} v${rule.version}` },
          { key: "state", label: "状态", children: rule.state },
          { key: "behavior", label: "行为分支", children: behaviorOptions.find(option => option.value === rule.behavior)?.label || rule.behavior },
          { key: "basis", label: "依据与定位", children: `${rule.basis.ref} v${rule.basis.version} · ${rule.basis.locator || "未定位"}` },
          { key: "effect", label: "效力 / 职权 / 程序", children: `${rule.basis.effectStatus || "待核"} · ${rule.basis.jurisdiction || "待核"} · ${rule.basis.procedure || "待核"}` },
          { key: "defenses", label: "抗辩", children: rule.defenses?.length ? rule.defenses.join("、") : rule.noDefensesConfirmed ? `无抗辩项已确认：${rule.noDefensesSource}` : "未评估" }
        ]} /><div className="enf-next-detail">{rule.elements?.map(element => <Tag key={element.id}>{element.id} · {element.description}</Tag>)}</div>
          <div className="enf-next-actions">
            {rule.state === "ACTIVE" && <Button danger onClick={() => void run(() => ruleCommand("disable"))} loading={busy}>停用</Button>}</div>
          {rule.state === "DRAFT" && <Form layout="vertical" onFinish={values => void run(async () => {
            const positive = JSON.parse(values.positive) as unknown;
            const negative = JSON.parse(values.negative) as unknown;
            if (!Array.isArray(positive) || !Array.isArray(negative)) throw new Error("正反例必须是 JSON 数组");
            await ruleCommand("publish", { reviewRef: values.reviewRef, positive, negative });
          })}><Typography.Title level={5}>发布复核</Typography.Title>
            <Form.Item name="reviewRef" label="具名复核 Ref" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="positive" label="正例数组（JSON）" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
            <Form.Item name="negative" label="反例数组（JSON）" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>核验并发布</Button></Form>}</div>
          : <Empty description="读取规则版后查看依据和要件" />}
      </section></div> },
      { key: "analyses", label: "案件辅助分析", children: <div className="enf-next-grid"><section>
        <Typography.Title level={4}>新分析</Typography.Title>
        <Form form={analysisForm} layout="vertical" onFinish={values => void run(async () => {
          const created = await request<Receipt>("/conduct-analyses", { ...values,
            defenseRefs: values.defenseRefs?.split(/[,，\n]/).map((text: string) => text.trim()).filter(Boolean) ?? [] });
          setReceipt(created); await readReport(values.id, created.version);
        })}>
          <div className="enf-next-row"><Form.Item name="id" label="分析 ID" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="organization" label="组织" rules={[{ required: true }]}><Input /></Form.Item></div>
          <Form.Item name="behavior" label="行为分支" rules={[{ required: true }]}><Select options={behaviorOptions} /></Form.Item>
          <div className="enf-next-row"><Form.Item name="caseRef" label="案件 Ref" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="caseVersion" label="案件版本" rules={[{ required: true }]}><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name="evidenceOrganizationId" label="证据整理 ID" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="evidenceOrganizationVersion" label="证据整理版本" rules={[{ required: true }]}><InputNumber min={1} precision={0} style={{ width: "100%" }} /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name="ruleId" label="适用规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="ruleVersion" label="规则版本" rules={[{ required: true }]}><InputNumber min={1} precision={0} style={{ width: "100%" }} /></Form.Item></div>
          <Form.Item name="behaviorAt" label="行为发生时点（含时区）" rules={[{ required: true }]}><Input placeholder="ISO 8601" /></Form.Item>
          <Typography.Title level={5}>发生时点权利</Typography.Title>
          <div className="enf-next-row"><Form.Item name={["right", "ref"]} label="权利 Ref"><Input /></Form.Item>
            <Form.Item name={["right", "version"]} label="权利版本"><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={["right", "status"]} label="发生时状态"><Input /></Form.Item>
            <Form.Item name={["right", "sourceRef"]} label="状态来源 Ref"><Input /></Form.Item></div>
          <Form.Item name={["right", "behaviorAt"]} label="状态核对时点（含时区）"><Input placeholder="ISO 8601" /></Form.Item>
          <Typography.Title level={5}>事实与来源</Typography.Title>
          <Form.List name="facts" initialValue={[{}]}>{(fields, { add, remove }) => <>{fields.map(field => <div className="enf-next-form-list" key={field.key}>
            <div className="enf-next-row"><Form.Item name={[field.name, "ref"]} label="事实 Ref" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name={[field.name, "kind"]} label="事实性质" rules={[{ required: true }]}><Select options={[
                { value: "CONFIRMED", label: "已确认事实" }, { value: "PARTY_STATEMENT", label: "当事人陈述" },
                { value: "MACHINE_CANDIDATE", label: "机器候选" }]} /></Form.Item></div>
            <div className="enf-next-row"><Form.Item name={[field.name, "sourceRef"]} label="来源 Ref" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name={[field.name, "locator"]} label="定位" rules={[{ required: true }]}><Input /></Form.Item></div>
            <Button disabled={fields.length === 1} onClick={() => remove(field.name)}>移除事实</Button></div>)}
            <Button icon={<Plus size={16} aria-hidden="true" />} onClick={() => add()}>添加事实</Button></>}</Form.List>
          <Typography.Title level={5}>逐要件证据关系</Typography.Title>
          <Form.List name="links" initialValue={[{}]}>{(fields, { add, remove }) => <>{fields.map(field => <div className="enf-next-form-list" key={field.key}>
            <div className="enf-next-row"><Form.Item name={[field.name, "elementId"]} label="要件 ID" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name={[field.name, "factRef"]} label="事实 Ref" rules={[{ required: true }]}><Input /></Form.Item></div>
            <div className="enf-next-row"><Form.Item name={[field.name, "evidenceRef"]} label="证据 Ref" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name={[field.name, "locator"]} label="定位" rules={[{ required: true }]}><Input /></Form.Item></div>
            <Form.Item name={[field.name, "relation"]} label="关系" rules={[{ required: true }]}><Select options={[{ value: "SUPPORT", label: "支持" }, { value: "CONTRADICT", label: "反证" }]} /></Form.Item>
            <Button disabled={fields.length === 1} onClick={() => remove(field.name)}>移除关系</Button></div>)}
            <Button icon={<Plus size={16} aria-hidden="true" />} onClick={() => add()}>添加关系</Button></>}</Form.List>
          <Form.Item name="defenseRefs" label="抗辩 Ref（逗号或换行分隔）"><Input.TextArea rows={2} /></Form.Item>
          <div className="enf-next-actions"><Button type="primary" htmlType="submit" loading={busy}>生成辅助分析</Button>
            <Button loading={busy} onClick={() => void run(async () => {
              const values = await analysisForm.validateFields();
              const input = { ...values, defenseRefs: values.defenseRefs?.split(/[,，\n]/).map((text: string) => text.trim()).filter(Boolean) ?? [] };
              setTrial(await request<Trial>(`/conduct-rules/${encodeURIComponent(input.ruleId)}/versions/${input.ruleVersion}/trials`, input));
            })}>按当前输入试跑</Button></div>
        </Form>
        {trial && <div className="enf-next-detail"><Typography.Title level={5}>无写入试跑 · {trial.outcome}</Typography.Title>
          <ConductFindings findings={trial.elements} /><div className="enf-next-muted">缺口：{trial.gaps?.join("；") || "无"}</div></div>}
      </section><section><Typography.Title level={4}>报告与人工版</Typography.Title>
        <div className="enf-next-lookup"><Input aria-label="分析 ID" placeholder="分析 ID" value={lookupReport} onChange={event => setLookupReport(event.target.value)} />
          <InputNumber aria-label="分析版本" placeholder="版本" min={1} precision={0} value={lookupReportVersion} onChange={setLookupReportVersion} />
          <Button icon={<RefreshCw size={16} aria-hidden="true" />} disabled={!lookupReport || !lookupReportVersion} loading={busy}
            onClick={() => void run(() => readReport(lookupReport, lookupReportVersion!))}>读取</Button></div>
        {report ? <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[
          { key: "version", label: "分析版", children: `${report.id} v${report.version}` },
          { key: "case", label: "案件", children: `${report.caseRef} v${report.caseVersion}` },
          { key: "rule", label: "规则版 / 行为", children: `${report.ruleId} v${report.ruleVersion} · ${report.behavior}` },
          { key: "right", label: "历史权利", children: report.rightRef ? `${report.rightRef} v${report.rightVersion} · ${report.rightStatus}` : "待核" },
          { key: "outcome", label: "辅助候选", children: <Tag>{report.outcome}</Tag> },
          { key: "review", label: "人工复核", children: `${report.reviewState}${report.reviewedBy ? ` · ${report.reviewedBy}` : ""}` },
          { key: "previous", label: "前版", children: report.previousVersion || "无" }
          ]} /><Typography.Title level={5}>事实及其性质</Typography.Title><ConductFacts facts={report.facts} />
          <Typography.Title level={5}>逐要件</Typography.Title><ConductFindings findings={report.elements} />
          <div className="enf-next-muted">缺口：{report.gaps?.join("；") || "无"}</div>
          <Form layout="vertical" className="enf-next-detail" onFinish={values => void run(async () => {
            const value = await request<Receipt>(`/conduct-analyses/${encodeURIComponent(report.id)}/versions/${report.version}/reviews`,
              { expectedVersion: report.version, ...values });
            setReceipt(value); await readReport(report.id, value.version);
          })}><Form.Item name="decision" label="人工处理" rules={[{ required: true }]}><Select options={[
            { value: "REVIEWED", label: "复核" }, { value: "RETURNED", label: "退回" }]} /></Form.Item>
            <Form.Item name="reason" label="具名理由" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item>
            <Button htmlType="submit" loading={busy}>追加人工版本</Button></Form>
          <Button className="enf-next-detail" onClick={() => void run(async () => {
            const values = await analysisForm.validateFields();
            const value = await request<Receipt>(`/conduct-analyses/${encodeURIComponent(report.id)}/versions/${report.version}/reruns`,
              { expectedVersion: report.version, input: { ...values,
                defenseRefs: values.defenseRefs?.split(/[,，\n]/).map((text: string) => text.trim()).filter(Boolean) ?? [] } });
            setReceipt(value); await readReport(report.id, value.version);
          })} loading={busy}>按确切输入重跑</Button>
        </div> : <Empty description="读取确切分析版后查看逐要件与人工历史" />}
      </section></div> }
    ]} />}
    {receipt && token && <Alert type="success" showIcon role="status" title={`${receipt.operation} · ${receipt.resultId} v${receipt.version}`} />}
  </main>;
}
