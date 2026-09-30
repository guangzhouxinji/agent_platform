import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, Select, Table, Tabs, Tag, Typography } from "antd";
import { LogIn, LogOut, RefreshCw } from "lucide-react";
import { beginEnforcementLogin, completeEnforcementLogin, enforcementRequest, EnforcementAccessDenied } from "./enforcementNextV2Api";
import type { Rule, Trial, Report, Receipt } from "./EnforcementProcedureV2Types";
import "./EnforcementNextV2Page.css";

function list(value?: string[]) { return value?.length ? value.join("；") : "—"; }

export default function EnforcementProcedureV2Page() {
  const [token, setToken] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [rule, setRule] = useState<Rule | null>(null);
  const [trial, setTrial] = useState<Trial | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [ruleId, setRuleId] = useState("");
  const [ruleVersion, setRuleVersion] = useState<number | null>(null);
  const [reportId, setReportId] = useState("");
  const [reportVersion, setReportVersion] = useState<number | null>(null);
  const pending = useRef<{ fingerprint: string; key: string } | null>(null);

  useEffect(() => {
    void completeEnforcementLogin("procedure").then(setToken)
      .catch(failure => setError(failure instanceof Error ? failure.message : "登录失败"));
  }, []);

  function clear() {
    setToken(null); setRule(null); setTrial(null); setReport(null); setReceipt(null);
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
    setRule(await request<Rule>(`/procedure-rules/${encodeURIComponent(id)}/versions/${version}`));
    setTrial(null);
  }
  async function readReport(id: string, version: number) {
    setReport(await request<Report>(`/procedure-checks/${encodeURIComponent(id)}/versions/${version}`));
  }
  async function ruleCommand(operation: "publish" | "disable", body: unknown = {}) {
    if (!rule) return;
    const value = await request<Receipt>(`/procedure-rules/${encodeURIComponent(rule.id)}/versions/${rule.version}/${operation}`, body);
    setReceipt(value); await readRule(rule.id, value.version);
  }

  return <main className="enf-next">
    <header><div><Typography.Title level={2}>程序逐项校验</Typography.Title><span>执法 V2 · 辅助检查，不变更官方流程状态</span></div>
      {token ? <Button icon={<LogOut size={16} aria-hidden="true" />} onClick={clear}>退出</Button>
        : <Button type="primary" icon={<LogIn size={16} aria-hidden="true" />} onClick={() => void run(() => beginEnforcementLogin("procedure"))}>登录</Button>}</header>
    {error && <Alert type="error" showIcon role="alert" title={error} closable onClose={() => setError("")} />}
    {!token ? <div className="enf-next-locked">当前没有产品会话</div> : <Tabs items={[
      { key: "rules", label: "规则与试算", children: <div className="enf-next-grid"><section>
        <Typography.Title level={4}>规则草稿</Typography.Title>
        <Form layout="vertical" onFinish={values => void run(async () => {
          const created = await request<Receipt>("/procedure-rules", {
            ...values, requiredEvents: values.requiredEvents?.split(/[,，\n]/).map((text: string) => text.trim()).filter(Boolean) ?? [],
            requiredMaterials: values.requiredMaterials?.split(/[,，\n]/).map((text: string) => text.trim()).filter(Boolean) ?? [] });
          setReceipt(created); await readRule(values.id, created.version);
        })}>
          <div className="enf-next-row"><Form.Item name="id" label="规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="organization" label="组织" rules={[{ required: true }]}><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name="region" label="地区" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="procedure" label="程序" rules={[{ required: true }]}><Input /></Form.Item></div>
          <Form.Item name="stage" label="环节" rules={[{ required: true }]}><Input /></Form.Item>
          <Typography.Title level={5}>依据与效力</Typography.Title>
          <div className="enf-next-row"><Form.Item name={["basis", "ref"]} label="依据 Ref"><Input /></Form.Item>
            <Form.Item name={["basis", "version"]} label="依据版本"><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={["basis", "locator"]} label="条文定位"><Input /></Form.Item>
            <Form.Item name={["basis", "effectStatus"]} label="效力核验状态"><Input /></Form.Item></div>
          <Form.Item name={["basis", "scope"]} label="适用范围"><Input /></Form.Item>
          <div className="enf-next-row"><Form.Item name={["basis", "effectiveFrom"]} label="效力开始"><Input type="date" /></Form.Item>
            <Form.Item name={["basis", "effectiveTo"]} label="效力结束"><Input type="date" /></Form.Item></div>
          <Typography.Title level={5}>期限表达</Typography.Title>
          <div className="enf-next-row"><Form.Item name={["clock", "unit"]} label="计时单位" rules={[{ required: true }]}><Select options={[{ value: "CALENDAR_DAY", label: "自然日" }, { value: "WORKDAY", label: "工作日" }]} /></Form.Item>
            <Form.Item name={["clock", "days"]} label="天数" rules={[{ required: true }]}><InputNumber min={1} precision={0} style={{ width: "100%" }} /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={["clock", "startEvent"]} label="起算事件"><Input /></Form.Item>
            <Form.Item name={["clock", "includeStartDay"]} label="包含起算日" initialValue={false}><Select options={[{ value: true, label: "包含" }, { value: false, label: "不包含" }]} /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={["clock", "deadlineAdjustment"]} label="截止调整"><Input /></Form.Item>
            <Form.Item name={["clock", "extensionDays"]} label="延期天数" initialValue={0}><InputNumber min={0} precision={0} style={{ width: "100%" }} /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={["clock", "calendarRef"]} label="日历 Ref"><Input /></Form.Item>
            <Form.Item name={["clock", "calendarVersion"]} label="日历版本"><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={["clock", "pausePolicy"]} label="暂停 / 恢复规则"><Input /></Form.Item>
            <Form.Item name={["clock", "interruptionPolicy"]} label="中断规则"><Input /></Form.Item></div>
          <Form.Item name="requiredEvents" label="必需事件（逗号或换行分隔）"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="requiredMaterials" label="必需材料（逗号或换行分隔）"><Input.TextArea rows={2} /></Form.Item>
          <div className="enf-next-row"><Form.Item name="author" label="作者"><Input /></Form.Item>
            <Form.Item name="reviewer" label="复核者"><Input /></Form.Item></div>
          <Form.Item name="reviewRef" label="复核 Ref"><Input /></Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>保存草稿</Button>
        </Form>
      </section><section><Typography.Title level={4}>确切规则版</Typography.Title>
        <div className="enf-next-lookup"><Input aria-label="规则 ID" placeholder="规则 ID" value={ruleId} onChange={event => setRuleId(event.target.value)} />
          <InputNumber aria-label="规则版本" placeholder="版本" min={1} precision={0} value={ruleVersion} onChange={setRuleVersion} />
          <Button icon={<RefreshCw size={16} aria-hidden="true" />} disabled={!ruleId || !ruleVersion} loading={busy}
            onClick={() => void run(() => readRule(ruleId, ruleVersion!))}>读取</Button></div>
        {rule ? <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[
          { key: "id", label: "规则版", children: `${rule.id} v${rule.version}` },
          { key: "state", label: "状态", children: rule.state },
          { key: "scope", label: "地区 / 程序 / 环节", children: `${rule.region} · ${rule.procedure} · ${rule.stage}` },
          { key: "basis", label: "依据与定位", children: `${rule.basis?.ref || "未提供"} v${rule.basis?.version || "?"} · ${rule.basis?.locator || "未定位"}` },
          { key: "clock", label: "起算与日历", children: `${rule.clock?.startEvent || "未提供"} · ${rule.clock?.days ?? "?"} ${rule.clock?.unit || "?"} · ${rule.clock?.calendarRef || "未提供"} v${rule.clock?.calendarVersion || "?"}` },
          { key: "inputs", label: "必需输入", children: `事件：${list(rule.requiredEvents)}；材料：${list(rule.requiredMaterials)}` }
        ]} /><div className="enf-next-actions">
          {rule.state === "ACTIVE" && <Button danger loading={busy} onClick={() => void run(() => ruleCommand("disable"))}>停用</Button>}</div>
          {rule.state === "DRAFT" && <Form layout="vertical" onFinish={values => void run(async () => {
            const positive = JSON.parse(values.positive) as unknown;
            const negative = JSON.parse(values.negative) as unknown;
            if (!Array.isArray(positive) || !Array.isArray(negative)) throw new Error("正反例必须是 JSON 数组");
            await ruleCommand("publish", { reviewRef: values.reviewRef, reviewer: values.reviewer, positive, negative });
          })}><Typography.Title level={5}>发布复核</Typography.Title>
            <div className="enf-next-row"><Form.Item name="reviewRef" label="复核 Ref" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name="reviewer" label="独立具名复核者" rules={[{ required: true }]}><Input /></Form.Item></div>
            <Form.Item name="positive" label="正例输入数组（JSON）" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
            <Form.Item name="negative" label="反例输入数组（JSON）" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>核验并发布</Button></Form>}
          <Form layout="vertical" className="enf-next-detail" onFinish={values => void run(async () => {
            setTrial(await request<Trial>(`/procedure-rules/${encodeURIComponent(rule.id)}/versions/${rule.version}/trials`, values));
          })}><Typography.Title level={5}>无写入试算</Typography.Title>
            <div className="enf-next-row"><Form.Item name="caseRef" label="案件 Ref" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name="caseVersion" label="案件版本" rules={[{ required: true }]}><Input /></Form.Item></div>
            <div className="enf-next-row"><Form.Item name="calendarRef" label="日历 Ref" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name="calendarVersion" label="日历版本" rules={[{ required: true }]}><Input /></Form.Item></div>
            <Button htmlType="submit" loading={busy}>试算</Button></Form>
          {trial && <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[
            { key: "state", label: "试算状态", children: <Tag>{trial.state}</Tag> },
            { key: "due", label: "候选截止日", children: trial.dueDate || "无法判断" },
            { key: "refs", label: "固定输入", children: `${trial.caseRef} v${trial.caseVersion} · ${trial.calendarRef} v${trial.calendarVersion}` },
            { key: "trace", label: "逐步轨迹", children: list(trial.trace) },
            { key: "gaps", label: "缺口", children: list(trial.gaps) }
          ]} /></div>}</div> : <Empty description="读取规则版后查看依据与试算" />}
      </section></div> },
      { key: "checks", label: "案件逐项报告", children: <div className="enf-next-grid"><section>
        <Typography.Title level={4}>发起校验</Typography.Title>
        <Form layout="vertical" onFinish={values => void run(async () => {
          const created = await request<Receipt>("/procedure-checks", values);
          setReceipt(created); await readReport(created.resultId, created.version);
        })}>
          <div className="enf-next-row"><Form.Item name="id" label="校验 ID" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="caseRef" label="案件 Ref" rules={[{ required: true }]}><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name="caseVersion" label="案件版本" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="ruleId" label="适用规则 ID" rules={[{ required: true }]}><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name="ruleVersion" label="规则版本" rules={[{ required: true }]}><InputNumber min={1} precision={0} style={{ width: "100%" }} /></Form.Item>
            <Form.Item name="calendarRef" label="日历 Ref" rules={[{ required: true }]}><Input /></Form.Item></div>
          <Form.Item name="calendarVersion" label="日历版本" rules={[{ required: true }]}><Input /></Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>生成逐项报告</Button>
        </Form>
      </section><section><Typography.Title level={4}>报告与整改版本</Typography.Title>
        <div className="enf-next-lookup"><Input aria-label="报告 ID" placeholder="报告 ID" value={reportId} onChange={event => setReportId(event.target.value)} />
          <InputNumber aria-label="报告版本" placeholder="版本" min={1} precision={0} value={reportVersion} onChange={setReportVersion} />
          <Button icon={<RefreshCw size={16} aria-hidden="true" />} disabled={!reportId || !reportVersion} loading={busy}
            onClick={() => void run(() => readReport(reportId, reportVersion!))}>读取</Button></div>
        {report ? <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[
          { key: "version", label: "报告版", children: `${report.id} v${report.version}` },
          { key: "case", label: "案件 / 规则 / 日历", children: `${report.caseRef} v${report.caseVersion} · ${report.ruleId} v${report.ruleVersion} · ${report.calendarRef} v${report.calendarVersion}` },
          { key: "execution", label: "执行状态", children: <Tag>{report.executionState}</Tag> },
          { key: "unprocessed", label: "未处理项", children: list(report.unprocessed) },
          { key: "rectification", label: "整改", children: report.rectification ? `${report.rectification} · ${report.rectifiedBy}` : "无" },
          { key: "previous", label: "前版", children: report.previousVersion || "无" }
        ]} /><Typography.Title level={5}>逐项四态</Typography.Title>
          <div className="enf-next-table"><Table size="small" rowKey={(item, index) => `${item.requirement}-${index}`}
            dataSource={report.items} locale={{ emptyText: "暂无逐项结果" }} pagination={false} columns={[
              { key: "requirement", title: "检查项", dataIndex: "requirement" },
              { key: "state", title: "结果", dataIndex: "state", render: (value: string) => <Tag>{value}</Tag> },
              { key: "input", title: "输入引用", render: (_, item) => list(item.inputRefs) },
              { key: "due", title: "候选截止", render: (_, item) => item.dueDate || "—" },
              { key: "gaps", title: "缺口 / 轨迹", render: (_, item) => <>{list(item.gaps)}<br />{list(item.trace)}</> }
            ]} /></div>
          <Form layout="vertical" className="enf-next-detail" onFinish={values => void run(async () => {
            const value = await request<Receipt>(`/procedure-checks/${encodeURIComponent(report.id)}/rectifications`,
              { expectedVersion: report.version, reason: values.reason,
                evidenceRefs: values.evidenceRefs?.split(/[,，\n]/).map((text: string) => text.trim()).filter(Boolean) ?? [] });
            setReceipt(value); await readReport(report.id, value.version);
          })}><Form.Item name="reason" label="具名整改说明" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item>
            <Form.Item name="evidenceRefs" label="新增证据 Ref（逗号分隔）"><Input /></Form.Item>
            <Button htmlType="submit" loading={busy}>追加整改版</Button></Form>
          <Form layout="vertical" className="enf-next-detail" initialValues={{ caseRef: report.caseRef,
            caseVersion: report.caseVersion, ruleId: report.ruleId, ruleVersion: report.ruleVersion,
            calendarRef: report.calendarRef, calendarVersion: report.calendarVersion }}
            onFinish={values => void run(async () => {
              const value = await request<Receipt>(`/procedure-checks/${encodeURIComponent(report.id)}/rechecks`,
                { expectedVersion: report.version, ...values });
              setReceipt(value); await readReport(report.id, value.version);
            })}>
            <Typography.Title level={5}>新输入重检</Typography.Title>
            <div className="enf-next-row"><Form.Item name="caseRef" label="案件 Ref" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name="caseVersion" label="案件版本" rules={[{ required: true }]}><Input /></Form.Item></div>
            <div className="enf-next-row"><Form.Item name="ruleId" label="规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name="ruleVersion" label="规则版本" rules={[{ required: true }]}><InputNumber min={1} precision={0} style={{ width: "100%" }} /></Form.Item></div>
            <div className="enf-next-row"><Form.Item name="calendarRef" label="日历 Ref" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item name="calendarVersion" label="日历版本" rules={[{ required: true }]}><Input /></Form.Item></div>
            <Button htmlType="submit" loading={busy}>生成新报告版</Button>
          </Form>
        </div> : <Empty description="读取确切报告版后查看四态与整改历史" />}
      </section></div> }
    ]} />}
    {receipt && token && <Alert type="success" showIcon role="status" title={`${receipt.operation} · ${receipt.resultId} v${receipt.version}`} />}
  </main>;
}
