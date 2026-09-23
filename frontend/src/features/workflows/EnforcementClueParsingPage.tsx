import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Alert, Button, Divider, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from "antd";
import { CheckCircle2, FilePenLine, Play, RefreshCw, RotateCcw } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { ACTOR, ENFORCEMENT_REVIEWER, ORGANIZATION, RequestError, WorkbenchHeader } from "../../shared/ui/Workbench";

type Envelope<T> = { requestId: string; resultType: string; data: T; limitations: string[] };
type Lead = { leadId: string; sourceRecordId: string; sourceVersion: string; sourceAvailability: string; suspectedTypes: string[]; processingStatus: string; reviewRequired: boolean; version: number };
type LeadPage = { items: Lead[]; total: number };
type FieldValue = { kind: string; order: number; role?: string; rawValue: string; normalizedValue?: string; creditCode?: string; sourceFragment: string; fieldPath: string; sourceKind: string; referenceStatus?: string; sourceRef?: string; sourceVersion?: string; sourceDigest?: string };
type ParseIssue = { kind: string; fieldPath: string; reasonCode: string };
type ParseTask = { taskId: string; leadId: string; status: string; resultVersion: number; fieldCount: number; issueCount: number; parserVersion: string; createdAt: string; completedAt?: string };
type ParseResult = { resultId: string; leadId: string; version: number; previousResultId?: string; revisionKind: string; reviewStatus: string; parserVersion: string; modelUsage: string; priorityLevel: string; priorityReason: string; notLegalProbability: boolean; reviewRequired: boolean; fields: FieldValue[]; issues: ParseIssue[]; createdBy: string; createdAt: string; updatedBy: string; updatedAt: string };
type RevisionForm = { reason: string; fields: Array<{ kind: string; role?: string; rawValue: string; normalizedValue?: string; creditCode?: string }> };
type ReviewForm = { reason?: string };

const headers = (actor = ACTOR, idempotent = false) => ({
  "X-V0-Actor": actor,
  "X-V0-Organization": ORGANIZATION,
  ...(idempotent ? { "Idempotency-Key": crypto.randomUUID() } : {})
});
const statusLabels: Record<string, string> = { PENDING_VERIFICATION: "待核实", VERIFYING: "核实中", succeeded: "解析完成", partial_failed: "部分完成", failed: "解析失败", running: "解析中", PENDING_REVIEW: "待审核", CONFIRMED: "已确认", RETURNED: "已退回" };
const fieldLabels: Record<string, string> = { SUBJECT: "主体", FACT: "事实描述", TIME: "时间", PLACE: "地点", EVIDENCE_REFERENCE: "证据引用", CLASSIFICATION: "疑似分类" };
const issueLabels: Record<string, string> = { MISSING: "字段缺失", TYPE_ERROR: "类型错误", UNPROCESSED: "未处理", NORMALIZATION_FAILED: "标准化失败" };

export default function EnforcementClueParsingPage() {
  const [selectedLeadId, setSelectedLeadId] = useState<string>();
  const [selectedVersion, setSelectedVersion] = useState<number>();
  const [revisionOpen, setRevisionOpen] = useState(false);
  const [reviewAction, setReviewAction] = useState<"confirm" | "return">();
  const [revisionForm] = Form.useForm<RevisionForm>();
  const [reviewForm] = Form.useForm<ReviewForm>();
  const [messageApi, messageContext] = message.useMessage();

  const leads = useQuery({ queryKey: ["parse-leads"], queryFn: async () => (await apiRequest<Envelope<LeadPage>>("/api/v0/enforcement/leads?page=1&pageSize=100", { headers: headers() })).data });
  const tasks = useQuery({ queryKey: ["parse-tasks", selectedLeadId], enabled: Boolean(selectedLeadId), queryFn: async () => (await apiRequest<Envelope<ParseTask[]>>(`/api/v0/enforcement/leads/${encodeURIComponent(selectedLeadId!)}/parse-tasks`, { headers: headers() })).data });
  const results = useQuery({ queryKey: ["parse-results", selectedLeadId], enabled: Boolean(selectedLeadId), queryFn: async () => (await apiRequest<Envelope<ParseResult[]>>(`/api/v0/enforcement/leads/${encodeURIComponent(selectedLeadId!)}/parses`, { headers: headers() })).data });
  const current = useMemo(() => results.data?.find(item => item.version === selectedVersion) ?? results.data?.[0], [results.data, selectedVersion]);
  const selectedLead = leads.data?.items.find(item => item.leadId === selectedLeadId);
  useEffect(() => { if (!selectedLeadId && leads.data?.items.length) setSelectedLeadId(leads.data.items[0].leadId); }, [leads.data, selectedLeadId]);
  useEffect(() => { if (results.data?.length && selectedVersion === undefined) setSelectedVersion(results.data[0].version); }, [results.data, selectedVersion]);

  const refresh = () => { leads.refetch(); tasks.refetch(); results.refetch(); };
  const parse = useMutation({ mutationFn: () => apiRequest<Envelope<ParseTask>>(`/api/v0/enforcement/leads/${encodeURIComponent(selectedLeadId!)}/parse-tasks`, { method: "POST", headers: headers(ACTOR, true) }), onSuccess: response => { messageApi[response.data.status === "succeeded" ? "success" : "warning"](statusLabels[response.data.status] ?? response.data.status); refresh(); } });
  const revise = useMutation({ mutationFn: (values: RevisionForm) => apiRequest<Envelope<ParseResult>>(`/api/v0/enforcement/leads/${encodeURIComponent(selectedLeadId!)}/parses/${current!.version}/revisions`, { method: "POST", headers: headers(), body: JSON.stringify({ expectedVersion: current!.version, reason: values.reason, fields: values.fields }) }), onSuccess: response => { messageApi.success(`已生成修订版本 V${response.data.version}`); setRevisionOpen(false); setSelectedVersion(response.data.version); refresh(); } });
  const review = useMutation({ mutationFn: ({ action, reason }: { action: "confirm" | "return"; reason?: string }) => apiRequest<Envelope<ParseResult>>(`/api/v0/enforcement/leads/${encodeURIComponent(selectedLeadId!)}/parses/${current!.version}/${action}`, { method: "POST", headers: headers(ENFORCEMENT_REVIEWER), body: JSON.stringify({ expectedVersion: current!.version, reason }) }), onSuccess: response => { messageApi.success(response.data.reviewStatus === "CONFIRMED" ? "解析版本已确认" : "解析版本已退回"); setReviewAction(undefined); reviewForm.resetFields(); refresh(); } });
  const error = leads.error || tasks.error || results.error || parse.error || revise.error || review.error;

  const openRevision = () => {
    revisionForm.setFieldsValue({ reason: "", fields: current?.fields.map(field => ({ kind: field.kind, role: field.role, rawValue: field.rawValue, normalizedValue: field.normalizedValue, creditCode: field.creditCode })) ?? [] });
    setRevisionOpen(true);
  };

  return <section className="workbench clue-parsing-workspace">
    {messageContext}
    <WorkbenchHeader eyebrow="执法辅助" title="线索解析" description="将待核实候选中的明确结构字段形成可追溯解析草稿。" tags={["LOCAL_DM8", "NO_MODEL_USED"]} />
    <div className="clue-parsing-toolbar"><div><Typography.Title level={3}>线索结构化解析</Typography.Title><Typography.Text type="secondary">解析结果须经人工审核，不代表违法事实、证据采信或处置决定</Typography.Text></div><Button icon={<RefreshCw size={16} aria-hidden="true" />} onClick={refresh}>刷新</Button></div>
    <RequestError error={error} retry={refresh} />

    <div className="clue-parsing-grid">
      <section className="parse-lead-panel" aria-label="可解析候选">
        <div className="parse-heading"><strong>待核实候选</strong><Tag>{leads.data?.total ?? 0} 条</Tag></div>
        <Table rowKey="leadId" size="small" loading={leads.isLoading} dataSource={leads.data?.items ?? []} pagination={false} locale={{ emptyText: "暂无可解析候选" }} onRow={row => ({ onClick: () => { setSelectedLeadId(row.leadId); setSelectedVersion(undefined); } })} rowClassName={row => row.leadId === selectedLeadId ? "selected-row" : ""} columns={[
          { title: "来源记录", render: (_, row: Lead) => <div className="parse-primary-cell"><strong>{row.sourceRecordId}</strong><span>{row.leadId}</span></div> },
          { title: "状态", render: (_, row: Lead) => <Tag color={row.sourceAvailability === "WITHDRAWN" ? "error" : "warning"}>{row.sourceAvailability === "WITHDRAWN" ? "来源已撤回" : statusLabels[row.processingStatus] ?? row.processingStatus}</Tag> }
        ]} />
      </section>

      <section className="parse-result-panel" aria-label="解析结果">
        <div className="parse-heading"><div><strong>{selectedLead?.sourceRecordId ?? "解析工作区"}</strong>{selectedLead && <span>候选版本 {selectedLead.version} · 来源 {selectedLead.sourceVersion}</span>}</div><Space wrap>
          <Button type="primary" icon={<Play size={16} aria-hidden="true" />} disabled={!selectedLead || selectedLead.sourceAvailability === "WITHDRAWN" || !["PENDING_VERIFICATION", "VERIFYING"].includes(selectedLead.processingStatus)} loading={parse.isPending} onClick={() => parse.mutate()}>运行解析</Button>
        </Space></div>
        {selectedLead?.sourceAvailability === "WITHDRAWN" && <Alert type="warning" showIcon title="来源已撤回，禁止发起新解析；历史版本保留并标记需复核。" />}
        {!current ? <Alert type="info" showIcon title={selectedLead ? "尚无解析版本，可运行一次结构化解析。" : "请选择待核实候选。"} /> : <>
          <div className="parse-version-bar"><Select aria-label="解析版本" value={current.version} onChange={setSelectedVersion} options={(results.data ?? []).map(item => ({ value: item.version, label: `V${item.version} · ${statusLabels[item.reviewStatus] ?? item.reviewStatus}` }))} /><Space wrap><Tag color={current.reviewStatus === "CONFIRMED" ? "success" : current.reviewStatus === "RETURNED" ? "error" : "processing"}>{statusLabels[current.reviewStatus]}</Tag><Tag>{current.revisionKind === "MACHINE" ? "机器结构化" : "人工修订"}</Tag><Tag>{current.modelUsage === "NO_MODEL_USED" ? "未使用模型" : current.modelUsage}</Tag></Space></div>
          {current.reviewRequired && <Alert type="warning" showIcon title="该版本需要重新核对来源状态" />}
          <div className="parse-summary"><div><span>字段</span><strong>{current.fields.length}</strong></div><div><span>问题</span><strong>{current.issues.length}</strong></div><div><span>优先级</span><strong>待评估</strong></div><div><span>结论性质</span><strong>非法律概率</strong></div></div>
          <Space wrap className="parse-actions">
            {current.reviewStatus !== "CONFIRMED" && <Button icon={<FilePenLine size={16} aria-hidden="true" />} onClick={openRevision}>人工修订</Button>}
            {current.reviewStatus === "PENDING_REVIEW" && <Button type="primary" icon={<CheckCircle2 size={16} aria-hidden="true" />} onClick={() => setReviewAction("confirm")}>确认版本</Button>}
            {current.reviewStatus === "PENDING_REVIEW" && <Button danger icon={<RotateCcw size={16} aria-hidden="true" />} onClick={() => setReviewAction("return")}>退回修订</Button>}
          </Space>
        </>}
      </section>
    </div>

    {current && <section className="parse-detail-band" aria-label="解析字段明细">
      <Divider titlePlacement="start">字段与来源</Divider>
      <Table rowKey={field => `${field.kind}-${field.order}`} size="small" dataSource={current.fields} pagination={false} scroll={{ x: 760 }} columns={[
        { title: "字段", width: 110, render: (_, field: FieldValue) => fieldLabels[field.kind] ?? field.kind },
        { title: "角色", width: 100, dataIndex: "role", render: value => value || "-" },
        { title: "原始值", dataIndex: "rawValue" },
        { title: "标准值", dataIndex: "normalizedValue", render: value => value || "-" },
        { title: "信用代码", width: 150, dataIndex: "creditCode", render: value => value || "未提供" },
        { title: "来源位置", width: 220, render: (_, field: FieldValue) => <div className="parse-source-cell"><span>{field.fieldPath}</span>{field.sourceRef && <small title={field.sourceDigest}>{field.sourceRef}{field.sourceVersion ? ` · ${field.sourceVersion}` : ""}</small>}</div> },
        { title: "引用状态", width: 120, dataIndex: "referenceStatus", render: value => value === "PENDING_CONNECTION" ? <Tag>待接入</Tag> : "-" }
      ]} />
      <Divider titlePlacement="start">解析问题</Divider>
      {current.issues.length ? <div className="parse-issue-list">{current.issues.map((issue, index) => <div key={`${issue.fieldPath}-${index}`}><Tag color="warning">{issueLabels[issue.kind] ?? issue.kind}</Tag><strong>{issue.fieldPath}</strong><span>{issue.reasonCode}</span></div>)}</div> : <Alert type="success" showIcon title="结构字段解析未发现缺失或格式问题" />}
      <Divider titlePlacement="start">版本历史</Divider>
      <div className="parse-history">{(results.data ?? []).map(result => <button type="button" key={result.version} className={result.version === current.version ? "active" : ""} onClick={() => setSelectedVersion(result.version)}><strong>V{result.version}</strong><span>{statusLabels[result.reviewStatus]} · {result.createdBy}</span></button>)}</div>
      {!!tasks.data?.length && <Typography.Text type="secondary">最近任务：{statusLabels[tasks.data[0].status] ?? tasks.data[0].status} · {tasks.data[0].fieldCount} 个字段 · {tasks.data[0].issueCount} 个问题</Typography.Text>}
    </section>}

    <Modal title="人工修订解析字段" open={revisionOpen} onCancel={() => setRevisionOpen(false)} footer={null} width={860} destroyOnHidden forceRender>
      <Alert type="info" showIcon title="修订会追加新版本，原版本和修改前后值均保留。" />
      <Form form={revisionForm} layout="vertical" onFinish={values => revise.mutate(values)}>
        <Form.Item name="reason" label="修订原因" rules={[{ required: true, whitespace: true, message: "请填写修订原因" }]}><Input.TextArea rows={2} maxLength={1000} showCount /></Form.Item>
        <Form.List name="fields">{fields => <div className="revision-fields">{fields.map(({ key, name }) => <div className="revision-row" key={key}>
          <Form.Item name={[name, "kind"]} label="字段" rules={[{ required: true }]}><Select options={Object.entries(fieldLabels).map(([value, label]) => ({ value, label }))} /></Form.Item>
          <Form.Item name={[name, "role"]} label="角色"><Input /></Form.Item>
          <Form.Item name={[name, "rawValue"]} label="值" rules={[{ required: true, whitespace: true }]}><Input /></Form.Item>
          <Form.Item name={[name, "normalizedValue"]} label="标准值"><Input /></Form.Item>
          <Form.Item name={[name, "creditCode"]} label="信用代码"><Input /></Form.Item>
        </div>)}</div>}</Form.List>
        <Button type="primary" htmlType="submit" loading={revise.isPending}>生成修订版本</Button>
      </Form>
    </Modal>
    <Modal title={reviewAction === "confirm" ? "确认解析版本" : "退回解析版本"} open={Boolean(reviewAction)} onCancel={() => setReviewAction(undefined)} footer={null} destroyOnHidden forceRender>
      <Form form={reviewForm} layout="vertical" onFinish={values => review.mutate({ action: reviewAction!, reason: values.reason })}>
        <Alert type={reviewAction === "confirm" ? "warning" : "info"} showIcon title={reviewAction === "confirm" ? "确认后该版本不可修改，但不会形成违法认定或正式状态。" : "退回后可继续人工修订并形成新版本。"} />
        <Form.Item name="reason" label={reviewAction === "return" ? "退回原因" : "审核备注"} rules={reviewAction === "return" ? [{ required: true, whitespace: true, message: "请填写退回原因" }] : []}><Input.TextArea rows={3} maxLength={1000} showCount /></Form.Item>
        <Button type="primary" htmlType="submit" loading={review.isPending}>{reviewAction === "confirm" ? "确认版本" : "确认退回"}</Button>
      </Form>
    </Modal>
  </section>;
}
