import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Divider, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from "antd";
import { GitMerge, Play, RefreshCw, ScanSearch, ShieldCheck, XCircle } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { ACTOR, ENFORCEMENT_REVIEWER, ORGANIZATION, RequestError, WorkbenchHeader } from "../../shared/ui/Workbench";

type Envelope<T> = { requestId: string; resultType: string; data: T; limitations: string[] };
type Source = { sourceId: string; sourceName: string };
type SyncTask = { taskId: string; status: string; processedCount: number; importedCount: number; failedCount: number; createdAt: string };
type Rule = { ruleId: string; ruleVersion: number; ruleName: string; category: string; status: string };
type TaskItem = { sequence: number; sourceRecordId?: string; ruleId?: string; status: string; reasonCode: string; retryable: boolean; leadId?: string };
type DiscoveryTask = { taskId: string; status: string; processedCount: number; matchedCount: number; leadCount: number; failedCount: number; createdAt: string; items: TaskItem[]; leadIds: string[] };
type Lead = { leadId: string; sourceId: string; sourceRecordId: string; sourceVersion: string; sourceAvailability: string; sourceExcerpt?: string; suspectedTypes: string[]; processingStatus: string; reviewRequired: boolean; usageScope: string; conclusionScope: string; version: number; updatedAt: string };
type RuleMatch = { ruleId: string; ruleVersion: number; suspectedType: string; conditionResults: Array<{ nodeId: string; operator: string; fieldPath?: string; matched: boolean; reasonCode: string }> };
type StatusHistory = { leadVersion: number; fromStatus?: string; toStatus: string; reason?: string; actor: string; occurredAt: string };
type DuplicateLink = { linkId: string; leftLeadId: string; rightLeadId: string; status: string; version: number; reason: string };
type LeadDetails = { lead: Lead; matches: RuleMatch[]; history: StatusHistory[]; duplicateLinks: DuplicateLink[]; notificationCapability: string };
type LeadPage = { items: Lead[]; total: number; page: number; pageSize: number };
type DiscoveryForm = { syncTaskIds: string[]; ruleKeys: string[] };
type ActionForm = { reason?: string };
type DuplicateForm = { rightLeadId: string; reason: string };

const headers = (actor = ACTOR, write = false) => ({
  "X-V0-Actor": actor,
  "X-V0-Organization": ORGANIZATION,
  ...(write ? { "Idempotency-Key": crypto.randomUUID() } : {})
});
const categoryLabels: Record<string, string> = { COUNTERFEIT_PATENT: "假冒专利", PATENT_INFRINGEMENT: "专利侵权", PUBLICITY_ISSUE: "宣传问题" };
const leadStatusLabels: Record<string, string> = { PENDING_VERIFICATION: "待核实", VERIFYING: "核实中", TRANSFERRED: "已转处置", EXCLUDED: "已排除", CLOSED: "已关闭" };
const taskStatusLabels: Record<string, string> = { succeeded: "发现完成", partial_failed: "部分失败", failed: "发现失败", running: "运行中", queued: "等待运行", cancelled: "已取消" };
const itemStatusLabels: Record<string, string> = { MATCHED: "已命中", NOT_MATCHED: "未命中", NOT_APPLICABLE: "不适用", FAILED: "失败", WITHDRAWN: "来源已撤回" };

export default function EnforcementClueDiscoveryPage() {
  const [discoveryForm] = Form.useForm<DiscoveryForm>();
  const [actionForm] = Form.useForm<ActionForm>();
  const [duplicateForm] = Form.useForm<DuplicateForm>();
  const [selectedLeadId, setSelectedLeadId] = useState<string>();
  const [selectedTaskId, setSelectedTaskId] = useState<string>();
  const [statusFilter, setStatusFilter] = useState<string>();
  const [action, setAction] = useState<"start-verification" | "transfer" | "exclude" | "close">();
  const [duplicateOpen, setDuplicateOpen] = useState(false);
  const [messageApi, messageContext] = message.useMessage();

  const sources = useQuery({ queryKey: ["enforcement-discovery-sources"], queryFn: async () => (await apiRequest<Envelope<Source[]>>("/api/v0/enforcement/source-configurations", { headers: headers() })).data });
  const rules = useQuery({ queryKey: ["enforcement-discovery-published-rules"], queryFn: async () => (await apiRequest<Envelope<Rule[]>>("/api/v0/enforcement/discovery-rules?status=PUBLISHED", { headers: headers() })).data });
  const sourceTasks = useQuery({ queryKey: ["enforcement-discovery-source-tasks", sources.data?.map(item => item.sourceId)], enabled: Boolean(sources.data), queryFn: async () => {
    const rows = await Promise.all((sources.data ?? []).map(async source => ({ source, tasks: (await apiRequest<Envelope<SyncTask[]>>(`/api/v0/enforcement/source-configurations/${encodeURIComponent(source.sourceId)}/sync-tasks`, { headers: headers() })).data })));
    return rows.flatMap(row => row.tasks.filter(task => ["succeeded", "partial_failed"].includes(task.status)).map(task => ({ ...task, sourceId: row.source.sourceId, sourceName: row.source.sourceName })));
  } });
  const tasks = useQuery({ queryKey: ["enforcement-discovery-tasks"], queryFn: async () => (await apiRequest<Envelope<DiscoveryTask[]>>("/api/v0/enforcement/discovery-tasks", { headers: headers() })).data });
  const leads = useQuery({ queryKey: ["enforcement-discovered-leads", statusFilter], queryFn: async () => (await apiRequest<Envelope<LeadPage>>(`/api/v0/enforcement/leads?page=1&pageSize=100${statusFilter ? `&status=${statusFilter}` : ""}`, { headers: headers() })).data });
  const leadDetails = useQuery({ queryKey: ["enforcement-discovered-lead", selectedLeadId], enabled: Boolean(selectedLeadId), queryFn: async () => (await apiRequest<Envelope<LeadDetails>>(`/api/v0/enforcement/leads/${encodeURIComponent(selectedLeadId!)}`, { headers: headers() })).data });
  const selectedTask = useMemo(() => tasks.data?.find(item => item.taskId === selectedTaskId), [selectedTaskId, tasks.data]);
  useEffect(() => { if (!selectedLeadId && leads.data?.items.length) setSelectedLeadId(leads.data.items[0].leadId); }, [leads.data, selectedLeadId]);
  useEffect(() => { if (!selectedTaskId && tasks.data?.length) setSelectedTaskId(tasks.data[0].taskId); }, [selectedTaskId, tasks.data]);

  const refresh = () => { sources.refetch(); rules.refetch(); sourceTasks.refetch(); tasks.refetch(); leads.refetch(); leadDetails.refetch(); };
  const discover = useMutation({ mutationFn: (values: DiscoveryForm) => apiRequest<Envelope<DiscoveryTask>>("/api/v0/enforcement/discovery-tasks", { method: "POST", headers: headers(ACTOR, true), body: JSON.stringify({ syncTaskIds: values.syncTaskIds, ruleVersions: values.ruleKeys.map(value => { const [ruleId, ruleVersion] = value.split("::"); return { ruleId, ruleVersion: Number(ruleVersion) }; }) }) }), onSuccess: response => { messageApi[response.data.status === "succeeded" ? "success" : "warning"](taskStatusLabels[response.data.status] ?? response.data.status); setSelectedTaskId(response.data.taskId); discoveryForm.resetFields(); refresh(); } });
  const transition = useMutation({ mutationFn: ({ path, version, reason }: { path: string; version: number; reason?: string }) => apiRequest<Envelope<Lead>>(`/api/v0/enforcement/leads/${encodeURIComponent(selectedLeadId!)}/${path}`, { method: "POST", headers: headers(path === "start-verification" ? ACTOR : ENFORCEMENT_REVIEWER), body: JSON.stringify({ expectedVersion: version, reason }) }), onSuccess: response => { messageApi.success(`状态已更新为${leadStatusLabels[response.data.processingStatus]}`); setAction(undefined); actionForm.resetFields(); refresh(); } });
  const markDuplicate = useMutation({ mutationFn: (values: DuplicateForm) => apiRequest<Envelope<DuplicateLink>>("/api/v0/enforcement/leads/duplicate-links", { method: "POST", headers: headers(), body: JSON.stringify({ leftLeadId: selectedLeadId, rightLeadId: values.rightLeadId, reason: values.reason }) }), onSuccess: () => { messageApi.success("已标记疑似重复，等待复核"); setDuplicateOpen(false); duplicateForm.resetFields(); refresh(); } });
  const error = sources.error || rules.error || sourceTasks.error || tasks.error || leads.error || leadDetails.error || discover.error || transition.error || markDuplicate.error;
  const current = leadDetails.data?.lead;

  return <section className="workbench clue-discovery-workspace">
    {messageContext}
    <WorkbenchHeader eyebrow="执法辅助" title="线索发现" description="从本地同步快照运行确定性规则，形成待人工核实的开发候选。" tags={["LOCAL_DM8", "PENDING_VERIFICATION"]} />
    <div className="clue-discovery-toolbar"><div><Typography.Title level={3}>规则驱动线索发现</Typography.Title><Typography.Text type="secondary">候选仅供核实，不构成违法认定、立案或处罚决定</Typography.Text></div><Button icon={<RefreshCw size={16} />} onClick={refresh}>刷新</Button></div>
    <RequestError error={error} retry={refresh} />

    <div className="clue-discovery-band">
      <div><Typography.Title level={4}>发起发现任务</Typography.Title><Typography.Text type="secondary">选择已完成的本地同步批次和已发布开发规则</Typography.Text></div>
      <Form form={discoveryForm} layout="vertical" onFinish={values => discover.mutate(values)} className="clue-discovery-form">
        <Form.Item name="syncTaskIds" label="同步批次" rules={[{ required: true, message: "请选择同步批次" }]}><Select mode="multiple" placeholder="选择已完成批次" options={(sourceTasks.data ?? []).map(task => ({ value: task.taskId, label: `${task.sourceName} · ${task.taskId.slice(0, 8)} · ${taskStatusLabels[task.status]}` }))} /></Form.Item>
        <Form.Item name="ruleKeys" label="发现规则" rules={[{ required: true, message: "请选择规则" }]}><Select mode="multiple" placeholder="选择已发布规则" options={(rules.data ?? []).map(rule => ({ value: `${rule.ruleId}::${rule.ruleVersion}`, label: `${rule.ruleName} · V${rule.ruleVersion}` }))} /></Form.Item>
        <Button type="primary" htmlType="submit" icon={<Play size={16} />} loading={discover.isPending}>运行发现</Button>
      </Form>
    </div>

    <div className="clue-discovery-grid">
      <section className="clue-task-panel" aria-label="发现任务">
        <div className="panel-heading"><strong>发现任务</strong><Tag>{tasks.data?.length ?? 0} 个</Tag></div>
        <Table rowKey="taskId" size="small" loading={tasks.isLoading} dataSource={tasks.data ?? []} pagination={false} locale={{ emptyText: "暂无发现任务" }} onRow={row => ({ onClick: () => setSelectedTaskId(row.taskId) })} rowClassName={row => row.taskId === selectedTaskId ? "selected-row" : ""} columns={[
          { title: "任务", render: (_, row: DiscoveryTask) => <div className="clue-primary-cell"><strong>{taskStatusLabels[row.status] ?? row.status}</strong><span>{row.taskId.slice(0, 12)}</span></div> },
          { title: "处理", dataIndex: "processedCount" }, { title: "候选", dataIndex: "leadCount" }, { title: "失败", dataIndex: "failedCount" }
        ]} />
        {selectedTask && <div className="task-result-detail"><Typography.Text type="secondary">任务明细</Typography.Text>{selectedTask.items.length ? selectedTask.items.map(item => <div key={item.sequence}><Tag color={item.status === "MATCHED" ? "success" : item.status === "FAILED" ? "error" : "default"}>{itemStatusLabels[item.status] ?? item.status}</Tag><span>{item.sourceRecordId ?? "上游任务"} · {item.reasonCode}</span></div>) : <Typography.Text type="secondary">没有处理明细</Typography.Text>}</div>}
      </section>

      <section className="clue-lead-panel" aria-label="待核实候选">
        <div className="panel-heading"><strong>待核实候选</strong><Select allowClear placeholder="全部状态" value={statusFilter} onChange={setStatusFilter} options={Object.entries(leadStatusLabels).map(([value, label]) => ({ value, label }))} /></div>
        <Table rowKey="leadId" size="small" loading={leads.isLoading} dataSource={leads.data?.items ?? []} pagination={false} locale={{ emptyText: "暂无候选" }} onRow={row => ({ onClick: () => setSelectedLeadId(row.leadId) })} rowClassName={row => row.leadId === selectedLeadId ? "selected-row" : ""} columns={[
          { title: "来源记录", render: (_, row: Lead) => <div className="clue-primary-cell"><strong>{row.sourceRecordId}</strong><span>{row.leadId} · {row.sourceId} · {row.sourceVersion}</span></div> },
          { title: "疑似类型", responsive: ["md"], render: (_, row: Lead) => <Space wrap size={4}>{row.suspectedTypes.map(type => <Tag key={type}>{categoryLabels[type] ?? type}</Tag>)}</Space> },
          { title: "状态", render: (_, row: Lead) => <Tag color={row.processingStatus === "PENDING_VERIFICATION" ? "warning" : row.processingStatus === "VERIFYING" ? "processing" : "default"}>{leadStatusLabels[row.processingStatus] ?? row.processingStatus}</Tag> }
        ]} />
      </section>
    </div>

    <section className="clue-detail-band" aria-label="候选详情">
      {!current ? <Alert type="info" showIcon title="选择候选查看命中依据" /> : <>
        <div className="panel-heading"><div><Typography.Title level={4}>{current.sourceRecordId}</Typography.Title><Typography.Text type="secondary">{current.leadId} · 版本 {current.version}</Typography.Text></div><Space wrap><Tag color={current.sourceAvailability === "WITHDRAWN" ? "error" : "success"}>{current.sourceAvailability === "WITHDRAWN" ? "来源已撤回" : "来源可用"}</Tag><Tag>{leadStatusLabels[current.processingStatus]}</Tag></Space></div>
        {current.reviewRequired && <Alert type="warning" showIcon title="来源已发生变化，需要重新核对" />}
        <Descriptions size="small" column={{ xs: 1, md: 2 }} items={[{ key: "source", label: "本地来源", children: current.sourceId }, { key: "version", label: "来源版本", children: current.sourceVersion }, { key: "scope", label: "用途", children: "仅限开发期人工核实" }, { key: "notice", label: "提醒方式", children: "本地授权列表查询" }, { key: "excerpt", label: "来源片段", span: "filled", children: current.sourceExcerpt ?? "未提供文本片段" }]} />
        <Space wrap className="clue-actions">
          {current.processingStatus === "PENDING_VERIFICATION" && <Button type="primary" icon={<ShieldCheck size={16} />} onClick={() => setAction("start-verification")}>开始核实</Button>}
          {current.processingStatus === "VERIFYING" && <Button type="primary" icon={<ScanSearch size={16} />} onClick={() => setAction("transfer")}>转处置</Button>}
          {["PENDING_VERIFICATION", "VERIFYING"].includes(current.processingStatus) && <Button danger icon={<XCircle size={16} />} onClick={() => setAction("exclude")}>排除</Button>}
          {["PENDING_VERIFICATION", "VERIFYING", "TRANSFERRED"].includes(current.processingStatus) && <Button onClick={() => setAction("close")}>关闭</Button>}
          <Button icon={<GitMerge size={16} />} onClick={() => setDuplicateOpen(true)}>标记疑似重复</Button>
        </Space>
        <Divider titlePlacement="start">命中依据</Divider>
        <div className="clue-evidence-list">{leadDetails.data?.matches.map(match => <article key={`${match.ruleId}-${match.ruleVersion}`}><div><strong>{categoryLabels[match.suspectedType] ?? match.suspectedType}</strong><span>{match.ruleId} · V{match.ruleVersion}</span></div>{match.conditionResults.map(result => <p key={result.nodeId}><Tag color={result.matched ? "success" : "default"}>{result.matched ? "满足" : "未满足"}</Tag>{result.fieldPath ?? result.operator} · {result.reasonCode}</p>)}</article>)}</div>
        <Divider titlePlacement="start">处理历史</Divider>
        <div className="clue-history-list">{leadDetails.data?.history.map(item => <div key={`${item.leadVersion}-${item.occurredAt}`}><Tag>{leadStatusLabels[item.toStatus] ?? item.toStatus}</Tag><span>{item.actor} · {item.occurredAt}{item.reason ? ` · ${item.reason}` : ""}</span></div>)}</div>
      </>}
    </section>

    <Modal title={action === "start-verification" ? "开始核实" : action === "transfer" ? "转处置" : action === "exclude" ? "排除候选" : "关闭候选"} open={Boolean(action)} onCancel={() => setAction(undefined)} footer={null} destroyOnHidden>
      <Form form={actionForm} layout="vertical" onFinish={values => transition.mutate({ path: action!, version: current!.version, reason: values.reason })}>
        {(action === "exclude" || action === "close") && <Form.Item name="reason" label="处理原因" rules={[{ required: true, whitespace: true, message: "请填写处理原因" }]}><Input.TextArea rows={3} maxLength={1000} showCount /></Form.Item>}
        <Button type="primary" htmlType="submit" loading={transition.isPending}>确认操作</Button>
      </Form>
    </Modal>
    <Modal title="标记疑似重复" open={duplicateOpen} onCancel={() => setDuplicateOpen(false)} footer={null} destroyOnHidden>
      <Form form={duplicateForm} layout="vertical" onFinish={values => markDuplicate.mutate(values)}>
        <Form.Item name="rightLeadId" label="关联候选" rules={[{ required: true, message: "请选择另一个候选" }]}><Select options={(leads.data?.items ?? []).filter(item => item.leadId !== selectedLeadId).map(item => ({ value: item.leadId, label: `${item.sourceRecordId} · ${item.sourceVersion}` }))} /></Form.Item>
        <Form.Item name="reason" label="标记原因" rules={[{ required: true, whitespace: true, message: "请填写标记原因" }]}><Input.TextArea rows={3} maxLength={1000} showCount /></Form.Item>
        <Button type="primary" htmlType="submit" loading={markDuplicate.isPending}>保存疑似关联</Button>
      </Form>
    </Modal>
  </section>;
}
