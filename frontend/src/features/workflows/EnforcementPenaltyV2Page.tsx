import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, Select, Table, Tabs, Typography } from "antd";
import { beginEnforcementLogin, completeEnforcementLogin, readEnforcementIdentity, endEnforcementLogin, EnforcementAccessDenied } from "./enforcementNextV2Api";
import { penaltyRequest as enforcementRequest, PenaltyConflict } from "./enforcementPenaltyApi";
import "./EnforcementNextV2Page.css";

type Ref = { kind: string; id: string; revision: number; sha256: string };
type Rule = { id: string; revision: number; organization: string; name: string; state: string; scope: Record<string, unknown>; conditions: unknown[]; discretionBasis: string | null; calculation: Record<string, unknown> };
type Option = { ruleId: string; ruleRevision: number; basisRef: string; basisRevision: number; range: { minimum: number; maximum: number }; semantics: { kind: string; currency: string; unit: string }; calculation: { amount: number | null; steps: unknown[]; gaps: string[] }; adjustedAmount?: number; reason?: string; adjustmentBasis?: string };
type Suggestion = { id: string; revision: number; organization: string; state: string; input: { caseId: string; caseRevision: number; dependencies: Ref[]; factors: Ref[]; amounts: unknown; confirmedFacts: unknown[] }; ruleRefs: Ref[]; options: Option[]; gaps: string[]; reviewedBy?: string; reviewedAt?: string; reviewReason?: string };
type Receipt = { requestId: string; operation: string; resourceKind: string; resourceId: string; revision: number };
const parse = <T,>(value: string): T => JSON.parse(value) as T;

export default function EnforcementPenaltyV2Page() {
  const [token, setToken] = useState<string | null>(null); const [organization, setOrganization] = useState("");
  const [busy, setBusy] = useState(false); const [error, setError] = useState(""); const errorRef = useRef<HTMLDivElement>(null);
  const [rules, setRules] = useState<Rule[]>([]); const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [rule, setRule] = useState<Rule | null>(null); const [suggestion, setSuggestion] = useState<Suggestion | null>(null);
  const [ruleHistory, setRuleHistory] = useState<Rule[]>([]); const [suggestionHistory, setSuggestionHistory] = useState<Suggestion[]>([]);
  const [receipt, setReceipt] = useState<Receipt | null>(null); const [result, setResult] = useState<unknown>(null);
  const [offset, setOffset] = useState(0); const [form] = Form.useForm(); const [generateForm] = Form.useForm();
  const [adjustForm] = Form.useForm(); const [reviewForm] = Form.useForm(); const [amounts, setAmounts] = useState("");
  const authEpoch = useRef(0);
  const clear = () => { authEpoch.current++; setToken(null); setOrganization(""); setRules([]); setSuggestions([]); setRule(null); setSuggestion(null); setRuleHistory([]); setSuggestionHistory([]); setReceipt(null); setResult(null); setAmounts(""); form.resetFields(); generateForm.resetFields(); adjustForm.resetFields(); reviewForm.resetFields(); };
  async function run(work: () => Promise<void>) {
    if (busy) return; setBusy(true); setError("");
    try { await work(); } catch (failure) {
      if (failure instanceof EnforcementAccessDenied) clear();
      if (failure instanceof PenaltyConflict) {
        setReceipt(null);
        if (failure.code === "PENALTY_PUBLICATION_REJECTED") setResult({ publishable: false, gaps: failure.gaps }); else setResult(null);
      }
      setError(failure instanceof Error ? failure.message : "产品操作失败"); requestAnimationFrame(() => errorRef.current?.focus());
    } finally { setBusy(false); }
  }
  useEffect(() => { const epoch = authEpoch.current; void completeEnforcementLogin("penalty").then(async value => {
    if (!value || epoch !== authEpoch.current) return;
    const identity = await readEnforcementIdentity(value); if (epoch !== authEpoch.current) return;
    const org = identity.grants.find(g => ["PENALTY_RULE", "PENALTY_SUGGESTION"].includes(g.resourceType))?.organization;
    if (!org) throw new EnforcementAccessDenied(); setToken(value); setOrganization(org);
  }).catch(failure => { clear(); setError(failure instanceof Error ? failure.message : "产品身份不可用"); }); }, []);
  async function refresh(nextOffset = offset) {
    if (!token) return; const epoch = authEpoch.current;
    const query = "organization=" + encodeURIComponent(organization) + "&offset=" + nextOffset + "&limit=20";
    const a = await enforcementRequest<Rule[]>(token, "/penalty/rules?" + query);
    const b = await enforcementRequest<Suggestion[]>(token, "/penalty/suggestions?" + query);
    if (epoch !== authEpoch.current) return; setRules(a); setSuggestions(b); setOffset(nextOffset);
  }
  async function readRule(id: string, revision: number) {
    if (!token) return; const epoch = authEpoch.current; const path = "/penalty/rules/" + encodeURIComponent(id);
    const value = await enforcementRequest<Rule>(token, path + "?revision=" + revision);
    const history = await enforcementRequest<Rule[]>(token, path + "/history");
    if (epoch !== authEpoch.current) return; setRule(value); setRuleHistory(history); setResult(null);
    form.setFieldsValue({ id: value.id, expectedRevision: value.revision, name: value.name, scope: JSON.stringify(value.scope, null, 2), conditions: JSON.stringify(value.conditions, null, 2), discretionBasis: value.discretionBasis, calculation: JSON.stringify(value.calculation, null, 2) });
  }
  async function readSuggestion(id: string, revision: number) {
    if (!token) return; const epoch = authEpoch.current; const path = "/penalty/suggestions/" + encodeURIComponent(id);
    const value = await enforcementRequest<Suggestion>(token, path + "?revision=" + revision);
    const history = await enforcementRequest<Suggestion[]>(token, path + "/history");
    if (epoch !== authEpoch.current) return; setSuggestion(value); setSuggestionHistory(history); reviewForm.resetFields(); adjustForm.resetFields();
    generateForm.setFieldsValue({ id: value.id, expectedRevision: value.revision, input: JSON.stringify(value.input, null, 2), rules: JSON.stringify(value.ruleRefs, null, 2) });
  }
  async function command(path: string, body: unknown) {
    if (!token) return; const epoch = authEpoch.current;
    const value = await enforcementRequest<Receipt>(token, path, body, crypto.randomUUID()); if (epoch !== authEpoch.current) return;
    setReceipt(value); if (value.resourceKind === "PENALTY_RULE") await readRule(value.resourceId, value.revision); else await readSuggestion(value.resourceId, value.revision);
    await refresh();
  }
  const historyColumns = [{ title: "版本", dataIndex: "revision" }, { title: "状态", dataIndex: "state" }];
  return <main className="enf-next" aria-busy={busy}>
    <header><div><Typography.Title level={2}>处罚规则与建议</Typography.Title><span>确定性计算、并列方案与具名复核；不产生正式处罚决定</span></div>
      {token ? <Button disabled={busy} onClick={() => void run(async () => { await endEnforcementLogin(token); clear(); })}>退出</Button> : <Button loading={busy} onClick={() => void run(() => beginEnforcementLogin("penalty"))}>登录</Button>}</header>
    {error && <div ref={errorRef} tabIndex={-1} role="alert"><Alert type="error" showIcon title={error} description="失权后数据已清除。版本冲突请刷新并选取最新修订，源不可用时不会写入成功回执。" /></div>}
    {receipt && <Alert type="success" title={"回执 " + receipt.requestId} description={receipt.operation + " · " + receipt.resourceId + " v" + receipt.revision} />}
    {!token ? <Empty description="使用现有产品身份登录后读取当前授权范围" /> : <>
      <div className="enf-next-actions"><Typography.Text>组织：{organization}</Typography.Text><Button loading={busy} onClick={() => void run(() => refresh())}>刷新当前列表</Button><Button disabled={busy || offset === 0} onClick={() => void run(() => refresh(Math.max(0, offset - 20)))}>上一页</Button><Button disabled={busy || (rules.length < 20 && suggestions.length < 20)} onClick={() => void run(() => refresh(offset + 20))}>下一页</Button></div>
      <Tabs items={[{ key: "rules", label: "ENF-15 规则", children: <div className="enf-next-grid"><section>
        <Table rowKey="id" pagination={false} dataSource={rules} columns={[{ title: "名称", dataIndex: "name" }, ...historyColumns, { title: "详情", render: (_: unknown, value: Rule) => <Button disabled={busy} onClick={() => void run(() => readRule(value.id, value.revision))}>打开规则</Button> }]} />
        <Typography.Title level={4}>维护草稿</Typography.Title>
        <Form name="penalty-rule" form={form} layout="vertical" initialValues={{ expectedRevision: 0 }} onFinish={value => void run(async () => {
          await command("/penalty/rules", { expectedRevision: value.expectedRevision, rule: { id: value.id, revision: value.expectedRevision + 1, organization, name: value.name, state: "DRAFT", scope: parse(value.scope), conditions: parse(value.conditions), discretionBasis: value.discretionBasis || null, calculation: { ...parse<Record<string, unknown>>(value.calculation), organization } } });
        })}>
          <Form.Item name="id" label="规则编号" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="name" label="规则名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="expectedRevision" label="基于版本（新建 0）" rules={[{ required: true }]}><InputNumber min={0} precision={0} /></Form.Item>
          <Form.Item name="scope" label="行为、职权、程序、地区、有效期（类型化 JSON）" rules={[{ required: true }]}><Input.TextArea rows={5} /></Form.Item>
          <Form.Item name="discretionBasis" label="裁量基准依据（无适用基准时留空）"><Input /></Form.Item>
          <Form.Item name="conditions" label="裁量因素及从轻/减轻/不罚条件和证据（JSON 数组）" rules={[{ required: true }]}><Input.TextArea rows={5} /></Form.Item>
          <Form.Item name="calculation" label="依据版本、类型化公式、金额口径、法定范围、边界样例及优先关系（JSON）" rules={[{ required: true }]} extra="只解析类型化表达式，不执行脚本；不得自行补造处罚标准。"><Input.TextArea rows={10} /></Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>保存草稿版本</Button>
        </Form>
      </section><section>{rule ? <>
        <Descriptions bordered column={1} items={[{ key: "id", label: "确切规则", children: rule.id + " v" + rule.revision }, { key: "state", label: "状态", children: rule.state }, { key: "scope", label: "适用范围", children: <pre>{JSON.stringify(rule.scope, null, 2)}</pre> }, { key: "calculation", label: "计算与依据输入", children: <pre>{JSON.stringify(rule.calculation, null, 2)}</pre> }]} />
        <div className="enf-next-actions"><Button loading={busy} onClick={() => void run(async () => { if (token) setResult(await enforcementRequest(token, "/penalty/rules/" + encodeURIComponent(rule.id) + "/validate", { expectedRevision: rule.revision }, crypto.randomUUID())); })}>发布前核验</Button>
          <Button loading={busy} disabled={rule.state !== "DRAFT"} onClick={() => void run(() => command("/penalty/rules/" + encodeURIComponent(rule.id) + "/publish", { expectedRevision: rule.revision }))}>发布</Button><Button danger loading={busy} disabled={rule.state !== "ACTIVE"} onClick={() => void run(() => command("/penalty/rules/" + encodeURIComponent(rule.id) + "/disable", { expectedRevision: rule.revision }))}>停用</Button></div>
        <label htmlFor="penalty-trial-amounts">逐项金额输入及确认来源（JSON）</label><Input.TextArea id="penalty-trial-amounts" rows={6} value={amounts} onChange={event => setAmounts(event.target.value)} /><Button loading={busy} onClick={() => void run(async () => { if (token) setResult(await enforcementRequest(token, "/penalty/rules/" + encodeURIComponent(rule.id) + "/trial", { revision: rule.revision, amounts: parse(amounts) }, crypto.randomUUID())); })}>无副作用试算</Button>
        {result !== null && <pre aria-label="实际核验或试算结果">{JSON.stringify(result, null, 2)}</pre>}
        <Typography.Title level={5}>不可变规则历史</Typography.Title><Table rowKey="revision" pagination={false} dataSource={ruleHistory} columns={[...historyColumns, { title: "读取", render: (_: unknown, value: Rule) => <Button disabled={busy} onClick={() => void run(() => readRule(value.id, value.revision))}>版本 {value.revision}</Button> }]} />
      </> : <Empty description="选取实际规则查看详情、核验、试算及历史" />}</section></div> },
      { key: "suggestions", label: "ENF-16 建议", children: <div className="enf-next-grid"><section>
        <Table rowKey="id" pagination={false} dataSource={suggestions} columns={[{ title: "编号", dataIndex: "id" }, ...historyColumns, { title: "详情", render: (_: unknown, value: Suggestion) => <Button disabled={busy} onClick={() => void run(() => readSuggestion(value.id, value.revision))}>打开建议</Button> }]} />
        <Typography.Title level={4}>生成或重跑</Typography.Title><Form name="penalty-suggestion" form={generateForm} layout="vertical" initialValues={{ expectedRevision: 0 }} onFinish={value => void run(() => command("/penalty/suggestions", { id: value.id, expectedRevision: value.expectedRevision, input: { ...parse<Record<string, unknown>>(value.input), organization }, rules: parse(value.rules) }))}>
          <Form.Item name="id" label="建议编号" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="expectedRevision" label="基于版本（新建 0）" rules={[{ required: true }]}><InputNumber min={0} precision={0} /></Form.Item>
          <Form.Item name="input" label="案件、具名事实、因素、金额来源及上游确切版本/SHA（JSON）" rules={[{ required: true }]} extra="浏览器中的 confirmed 标记不能替代源记录或现时授权。"><Input.TextArea rows={10} /></Form.Item><Form.Item name="rules" label="ACTIVE 候选规则确切版本（JSON 数组）" rules={[{ required: true }]}><Input.TextArea rows={4} /></Form.Item><Button type="primary" htmlType="submit" loading={busy}>生成 / 重跑建议</Button>
        </Form>
      </section><section>{suggestion ? <>
        <Descriptions bordered column={1} items={[{ key: "id", label: "确切建议", children: suggestion.id + " v" + suggestion.revision }, { key: "state", label: "状态", children: suggestion.state }, { key: "case", label: "案件版", children: suggestion.input.caseId + " v" + suggestion.input.caseRevision }, { key: "deps", label: "来源版本与 SHA", children: <pre>{JSON.stringify(suggestion.input, null, 2)}</pre> }, { key: "review", label: "复核", children: [suggestion.reviewedBy, suggestion.reviewedAt, suggestion.reviewReason].filter(Boolean).join(" · ") || "尚未复核" }]} />
        {suggestion.gaps.length > 0 && <Alert type="warning" title="待补充 / 冲突" description={suggestion.gaps.join("；")} />}
        <Typography.Title level={5}>全部并列方案（不会自动挑选）</Typography.Title>{suggestion.options.map(option => <section key={option.ruleId}><Typography.Text strong>{option.ruleId} v{option.ruleRevision} · 依据 {option.basisRef} v{option.basisRevision}</Typography.Text><pre>{JSON.stringify(option, null, 2)}</pre></section>)}
        <Form name="penalty-adjustment" form={adjustForm} layout="vertical" onFinish={value => void run(() => command("/penalty/suggestions/" + encodeURIComponent(suggestion.id) + "/adjust", { ...value, expectedRevision: suggestion.revision }))}>
          <Form.Item name="ruleId" label="调整方案" rules={[{ required: true }]}><Select options={suggestion.options.map(option => ({ value: option.ruleId, label: option.ruleId }))} /></Form.Item><Form.Item name="amount" label="调整金额（按该方案口径）" rules={[{ required: true }]}><InputNumber stringMode /></Form.Item><Form.Item name="reason" label="调整理由" rules={[{ required: true }]}><Input.TextArea /></Form.Item><Form.Item name="basis" label="调整依据" rules={[{ required: true }]}><Input /></Form.Item><Button htmlType="submit" loading={busy} disabled={suggestion.state === "WAITING_INPUT"}>保存人工修订</Button>
        </Form>
        <Form name="penalty-review" form={reviewForm} layout="vertical" onFinish={value => void run(() => command("/penalty/suggestions/" + encodeURIComponent(suggestion.id) + "/review", { ...value, expectedRevision: suggestion.revision }))}>
          <Form.Item name="accepted" label="具名复核结果" rules={[{ required: true }]}><Select options={[{ value: true, label: "确认建议（非处罚决定）" }, { value: false, label: "退回" }]} /></Form.Item><Form.Item name="reason" label="复核理由" rules={[{ required: true }]}><Input.TextArea /></Form.Item><Button htmlType="submit" loading={busy} disabled={suggestion.state !== "PENDING_REVIEW"}>提交复核</Button>
        </Form><Typography.Title level={5}>建议与调整历史</Typography.Title><Table rowKey="revision" pagination={false} dataSource={suggestionHistory} columns={[...historyColumns, { title: "读取", render: (_: unknown, value: Suggestion) => <Button disabled={busy} onClick={() => void run(() => readSuggestion(value.id, value.revision))}>版本 {value.revision}</Button> }]} />
      </> : <Empty description="选择建议查看来源、计算、缺口及复核历史" />}</section></div> }]} />
    </>}
  </main>;
}
