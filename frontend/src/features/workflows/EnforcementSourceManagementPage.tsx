import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag, Typography, message } from "antd";
import { Activity, Database, History, Pencil, Play, Plus, Power, RefreshCw } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { ACTOR, ORGANIZATION, RequestError, WorkbenchHeader } from "../../shared/ui/Workbench";

type Envelope<T> = { requestId: string; resultType: string; data: T; limitations: string[] };
type Source = { sourceId: string; organizationRef: string; sourceName: string; dataType: string; accessMethod: string; controlledEndpoint: string; credentialRef: string; fieldMapping: Record<string, string>; ownerName: string; scheduleMode: string; scheduleInterval?: number; scheduleUnit?: string; enabled: boolean; healthStatus: string; lastSuccessCursor?: string; lastSuccessAt?: string; version: number; updatedAt: string };
type SyncFailure = { sequence: number; sourceRecordRef?: string; errorCode: string; message: string };
type SyncTask = { taskId: string; triggerType: string; status: string; processedCount: number; importedCount: number; failedCount: number; resultCursor?: string; createdAt: string; completedAt?: string; requestId: string; failures: SyncFailure[] };
type SourceForm = { sourceId: string; sourceName: string; dataType: string; credentialRef: string; ownerName: string; scheduleMode: string; scheduleInterval?: number; scheduleUnit?: string; enabled: boolean };

const headers = (write = false) => ({ "X-V0-Actor": ACTOR, "X-V0-Organization": ORGANIZATION, ...(write ? { "Idempotency-Key": crypto.randomUUID() } : {}) });
const mapping = { sourceRecordId: "sourceRecordId", sourceVersion: "sourceVersion", sourceUpdatedAt: "sourceUpdatedAt", content: "content" };
const statusLabel: Record<string, string> = { NOT_TESTED: "尚未测试", HEALTHY: "连接正常", UNHEALTHY: "连接异常", succeeded: "同步完成", partial_failed: "部分失败", failed: "同步失败", running: "同步中", queued: "等待同步", cancelled: "已取消" };

export default function EnforcementSourceManagementPage() {
  const [form] = Form.useForm<SourceForm>();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Source>();
  const [selectedId, setSelectedId] = useState<string>();
  const [messageApi, messageContext] = message.useMessage();
  const sources = useQuery({ queryKey: ["enforcement-source-configurations"], queryFn: async () => (await apiRequest<Envelope<Source[]>>("/api/v0/enforcement/source-configurations", { headers: headers() })).data });
  useEffect(() => { if (!selectedId && sources.data?.length) setSelectedId(sources.data[0].sourceId); }, [selectedId, sources.data]);
  const selected = useMemo(() => sources.data?.find(item => item.sourceId === selectedId), [selectedId, sources.data]);
  const tasks = useQuery({ queryKey: ["enforcement-source-tasks", selectedId], enabled: Boolean(selectedId), queryFn: async () => (await apiRequest<Envelope<SyncTask[]>>(`/api/v0/enforcement/source-configurations/${encodeURIComponent(selectedId!)}/sync-tasks`, { headers: headers() })).data });
  const refresh = () => { sources.refetch(); tasks.refetch(); };
  const closeDialog = () => { setDialogOpen(false); setEditing(undefined); form.resetFields(); };
  const openCreate = () => { setEditing(undefined); form.resetFields(); form.setFieldsValue({ dataType: "COMPLAINT_REPORT", scheduleMode: "MANUAL", enabled: true, credentialRef: "NONE_REQUIRED" }); setDialogOpen(true); };
  const openEdit = (source: Source) => { setEditing(source); form.setFieldsValue(source); setDialogOpen(true); };
  const save = useMutation({ mutationFn: (values: SourceForm) => apiRequest<Envelope<Source>>(`/api/v0/enforcement/source-configurations/${encodeURIComponent(values.sourceId)}`, { method: "PUT", headers: headers(true), body: JSON.stringify({ ...values, accessMethod: "DEVELOPMENT_FIXED_API", controlledEndpoint: "local://enforcement/development-feed", fieldMapping: mapping, expectedVersion: editing?.version }) }), onSuccess: response => { messageApi.success(editing ? "数据源配置已更新" : "数据源配置已创建"); setSelectedId(response.data.sourceId); closeDialog(); refresh(); } });
  const toggle = useMutation({ mutationFn: (source: Source) => apiRequest<Envelope<Source>>(`/api/v0/enforcement/source-configurations/${encodeURIComponent(source.sourceId)}/enabled`, { method: "POST", headers: headers(true), body: JSON.stringify({ expectedVersion: source.version, enabled: !source.enabled }) }), onSuccess: response => { messageApi.success(response.data.enabled ? "数据源已启用" : "数据源已停用"); refresh(); } });
  const testConnection = useMutation({ mutationFn: (sourceId: string) => apiRequest<Envelope<any>>(`/api/v0/enforcement/source-configurations/${encodeURIComponent(sourceId)}/connection-tests`, { method: "POST", headers: headers(true) }), onSuccess: response => { messageApi[response.data.result === "SUCCEEDED" ? "success" : "warning"](response.data.result === "SUCCEEDED" ? "连接检查通过" : "连接检查未通过"); refresh(); } });
  const synchronize = useMutation({ mutationFn: (sourceId: string) => apiRequest<Envelope<SyncTask>>(`/api/v0/enforcement/source-configurations/${encodeURIComponent(sourceId)}/sync-tasks`, { method: "POST", headers: headers(true) }), onSuccess: response => { messageApi[response.data.status === "succeeded" ? "success" : "warning"](statusLabel[response.data.status] ?? response.data.status); refresh(); } });
  const error = sources.error || tasks.error || save.error || toggle.error || testConnection.error || synchronize.error;

  return <section className="workbench source-admin-workspace">
    {messageContext}
    <WorkbenchHeader eyebrow="执法配置" title="执法数据源" description="管理本地数据源配置、连接状态和同步记录。" tags={["LOCAL_DM8", "NOT_OFFICIAL_DECISION"]} />
    <div className="source-admin-toolbar"><div><Typography.Title level={3}>执法数据源</Typography.Title><Typography.Text type="secondary">本地 DM8 配置与同步，不代表真实中心联调或违法认定</Typography.Text></div><Space wrap><Button icon={<RefreshCw size={16} />} onClick={refresh}>刷新</Button><Button type="primary" icon={<Plus size={16} />} onClick={openCreate}>新增数据源</Button></Space></div>
    <RequestError error={error} retry={refresh} />
    <div className="source-admin-layout">
      <section className="source-list-panel" aria-label="数据源列表">
        <Table rowKey="sourceId" size="small" loading={sources.isLoading} dataSource={sources.data ?? []} pagination={false} onRow={record => ({ onClick: () => setSelectedId(record.sourceId) })} rowClassName={record => record.sourceId === selectedId ? "selected-row" : ""} columns={[
          { title: "数据源", dataIndex: "sourceName", render: (_, row: Source) => <div className="source-name-cell"><strong>{row.sourceName}</strong><span>{row.sourceId}</span></div> },
          { title: "类型", dataIndex: "dataType", responsive: ["md"] },
          { title: "状态", render: (_, row: Source) => <Space wrap size={4}><Tag color={row.enabled ? "success" : "default"}>{row.enabled ? "已启用" : "已停用"}</Tag><Tag color={row.healthStatus === "HEALTHY" ? "success" : row.healthStatus === "UNHEALTHY" ? "error" : "default"}>{statusLabel[row.healthStatus] ?? row.healthStatus}</Tag></Space> },
          { title: "操作", render: (_, row: Source) => <Button aria-label={`编辑 ${row.sourceName}`} icon={<Pencil size={15} />} onClick={event => { event.stopPropagation(); openEdit(row); }}>编辑</Button> }
        ]} />
      </section>
      <aside className="source-detail-panel" aria-label="当前数据源详情">
        {!selected ? <Alert type="info" showIcon title="选择一个数据源查看详情" /> : <>
          <div className="source-detail-heading"><div><Typography.Title level={4}>{selected.sourceName}</Typography.Title><Typography.Text type="secondary">配置版本 {selected.version}</Typography.Text></div><Tag color={selected.enabled ? "success" : "default"}>{selected.enabled ? "运行中" : "已停用"}</Tag></div>
          <Descriptions size="small" column={1} items={[
            { key: "owner", label: "负责人", children: selected.ownerName }, { key: "endpoint", label: "受控地址", children: selected.controlledEndpoint }, { key: "credential", label: "凭证引用", children: selected.credentialRef },
            { key: "schedule", label: "同步方式", children: selected.scheduleMode === "SCHEDULED" ? `每 ${selected.scheduleInterval} ${selected.scheduleUnit === "HOURS" ? "小时" : "分钟"}` : "手动同步" }, { key: "cursor", label: "成功游标", children: selected.lastSuccessCursor ?? "尚未同步" }, { key: "time", label: "最近成功", children: selected.lastSuccessAt ?? "尚未同步" }
          ]} />
          <Space wrap className="source-detail-actions"><Button icon={<Power size={16} />} loading={toggle.isPending} onClick={() => toggle.mutate(selected)}>{selected.enabled ? "停用" : "启用"}</Button><Button icon={<Activity size={16} />} loading={testConnection.isPending} onClick={() => testConnection.mutate(selected.sourceId)}>检查连接</Button><Button type="primary" icon={<Play size={16} />} disabled={!selected.enabled} loading={synchronize.isPending} onClick={() => synchronize.mutate(selected.sourceId)}>立即同步</Button></Space>
          <div className="source-task-heading"><div><History size={16} /><strong>同步记录</strong></div><Typography.Text type="secondary">{tasks.data?.length ?? 0} 条</Typography.Text></div>
          <div className="source-task-list">{tasks.data?.length ? tasks.data.map(task => <article key={task.taskId}><div><strong>{statusLabel[task.status] ?? task.status}</strong><span>{task.triggerType === "SCHEDULED" ? "定时" : "手动"} · {task.createdAt}</span></div><Space wrap><Tag>{task.processedCount} 条处理</Tag><Tag color={task.failedCount ? "error" : "success"}>{task.failedCount ? `${task.failedCount} 条失败` : `${task.importedCount} 条新增`}</Tag></Space>{task.failures.map(item => <Alert key={`${task.taskId}-${item.sequence}`} type="error" showIcon title={item.errorCode} description={item.message} />)}</article>) : <Typography.Text type="secondary">暂无同步记录</Typography.Text>}</div>
        </>}
      </aside>
    </div>
    <Modal title={editing ? "编辑数据源" : "新增数据源"} open={dialogOpen} onCancel={closeDialog} footer={null} destroyOnHidden forceRender width={720}>
      <Alert type="info" showIcon title="首期仅支持受控本地开发适配器" description="不会访问任意网址，也不会自动生成违法线索。" />
      <Form form={form} layout="vertical" onFinish={values => save.mutate(values)} className="source-config-form">
        <div className="source-form-grid"><Form.Item name="sourceId" label="数据源编号" rules={[{ required: true, message: "请填写数据源编号" }]}><Input disabled={Boolean(editing)} placeholder="local-enforcement-feed" /></Form.Item><Form.Item name="sourceName" label="数据源名称" rules={[{ required: true, message: "请填写数据源名称" }]}><Input /></Form.Item><Form.Item name="dataType" label="数据类型" rules={[{ required: true }]}><Select options={[{ value: "COMPLAINT_REPORT", label: "投诉举报" }, { value: "REGULATORY_MONITORING", label: "监管监测" }]} /></Form.Item><Form.Item name="ownerName" label="负责人" rules={[{ required: true, message: "请填写负责人" }]}><Input /></Form.Item><Form.Item name="credentialRef" label="凭证引用" rules={[{ required: true, message: "请填写凭证引用" }]}><Input placeholder="NONE_REQUIRED" /></Form.Item><Form.Item name="scheduleMode" label="同步方式" rules={[{ required: true }]}><Select options={[{ value: "MANUAL", label: "手动同步" }, { value: "SCHEDULED", label: "定时同步" }]} /></Form.Item></div>
        <Form.Item noStyle shouldUpdate={(previous, current) => previous.scheduleMode !== current.scheduleMode}>{({ getFieldValue }) => getFieldValue("scheduleMode") === "SCHEDULED" && <div className="source-form-grid"><Form.Item name="scheduleInterval" label="同步周期" rules={[{ required: true, type: "number", min: 1 }]}><InputNumber min={1} precision={0} /></Form.Item><Form.Item name="scheduleUnit" label="周期单位" rules={[{ required: true }]}><Select options={[{ value: "MINUTES", label: "分钟" }, { value: "HOURS", label: "小时" }]} /></Form.Item></div>}</Form.Item>
        <Form.Item name="enabled" label="启用数据源" valuePropName="checked"><Switch /></Form.Item>
        <Space wrap><Button type="primary" htmlType="submit" icon={<Database size={16} />} loading={save.isPending}>保存配置</Button><Button onClick={closeDialog}>取消</Button></Space>
      </Form>
    </Modal>
  </section>;
}
