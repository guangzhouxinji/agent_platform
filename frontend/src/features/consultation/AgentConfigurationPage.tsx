import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, Modal, Select, Space, Switch, Tag, Typography, message } from "antd";
import { ArrowDown, ArrowUp, CheckCircle2, FlaskConical, Pencil, Play, Plus, Power, Save, ShieldAlert, X } from "lucide-react";
import { ApiError, apiRequest } from "../../shared/api/client";
import { RequestError, WorkbenchHeader, requesterHeaders } from "../../shared/ui/Workbench";

type Option = { id: string; label: string };
type ModelOption = { modelId: string; provider: string; modelName: string; displayName: string; enabled: boolean; configured: boolean; dataChannel: string; availability: string };
type Catalog = { models: ModelOption[]; scenarios: Option[]; tools: Option[]; switchableFailures: string[]; limitation: string };
type Draft = { agentId: string; name: string; description: string; scenarioIds: string[]; modelChain: string[]; switchOn: string[]; toolIds: string[]; version: number; status: string; changeNote: string; updatedAt: string };
type Validation = { publishReady: boolean; draftVersion: number; issues: Array<{ code: string; message: string }>; limitation: string; validatedAt: string };
type ManagedModel = { modelId: string; displayName: string; provider: string; modelName: string; endpoint: string; enabled: boolean; priority: number; dataChannel: string; credentialConfigured: boolean; lastTestStatus?: string; lastTestedAt?: string; lastTestErrorCode?: string };
type ModelFormValues = { modelId: string; displayName: string; provider: string; modelName: string; endpoint: string; apiKey?: string; enabled: boolean; priority: number; changeNote: string };

const failureLabels: Record<string, string> = { TIMEOUT: "请求超时", RATE_LIMITED: "上游限流", UPSTREAM_UNAVAILABLE: "上游不可用" };

export default function AgentConfigurationPage() {
  const [form] = Form.useForm();
  const [modelForm] = Form.useForm();
  const [draft, setDraft] = useState<Draft>();
  const [validation, setValidation] = useState<Validation>();
  const [editingId, setEditingId] = useState<string>();
  const [modelDialogOpen, setModelDialogOpen] = useState(false);
  const [messageApi, messageContext] = message.useMessage();
  const catalog = useQuery({ queryKey: ["agent-draft-catalog"], queryFn: () => apiRequest<Catalog>("/api/v0/consultation-agent-draft/catalog", { headers: requesterHeaders() }) });
  const current = useQuery({ queryKey: ["agent-draft"], queryFn: () => apiRequest<Draft>("/api/v0/consultation-agent-draft", { headers: requesterHeaders() }) });
  const models = useQuery({ queryKey: ["managed-models"], queryFn: () => apiRequest<ManagedModel[]>("/api/v0/model-configurations", { headers: requesterHeaders() }) });
  const savedModels = models.data ?? [];
  const expectedOrder = draft ? [...draft.modelChain.filter(modelId => savedModels.some(model => model.modelId === modelId)), ...savedModels.map(model => model.modelId).filter(modelId => !draft.modelChain.includes(modelId))] : [];
  const draftCanApply = Boolean(draft && draft.modelChain.length > 0 && draft.modelChain.every(modelId => savedModels.some(model => model.modelId === modelId)));
  const draftApplied = draftCanApply && expectedOrder.every((modelId, index) => savedModels[index]?.modelId === modelId);
  useEffect(() => { if (current.data) setDraft(current.data); }, [current.data]);
  useEffect(() => { if (draft && catalog.data) form.setFieldsValue(draft); }, [catalog.data, draft, form]);
  const save = useMutation({
    mutationFn: (values: any) => apiRequest<Draft>("/api/v0/consultation-agent-draft", { method: "PUT", headers: requesterHeaders(), body: JSON.stringify({ ...values, agentId: draft!.agentId, expectedVersion: draft!.version }) }),
    onSuccess: value => { setDraft(value); setValidation(undefined); form.setFieldsValue(value); messageApi.success(`草稿已保存，版本 ${value.version}`); }
  });
  const validate = useMutation({ mutationFn: () => apiRequest<Validation>("/api/v0/consultation-agent-draft/validate", { method: "POST", headers: requesterHeaders() }), onSuccess: setValidation });
  const refreshModels = () => { models.refetch(); catalog.refetch(); };
  const resetModelForm = () => { setEditingId(undefined); setModelDialogOpen(false); modelForm.resetFields(); };
  const openCreateModel = () => { setEditingId(undefined); modelForm.resetFields(); modelForm.setFieldsValue({ provider: "DeepSeek", enabled: true, priority: (models.data?.length ?? 0) + 1, changeNote: "新增模型配置" }); setModelDialogOpen(true); };
  const saveModel = useMutation({ mutationFn: (values: ModelFormValues) => apiRequest<ManagedModel>(`/api/v0/model-configurations/${encodeURIComponent(values.modelId)}`, { method: "PUT", headers: requesterHeaders(), body: JSON.stringify(values) }), onSuccess: () => { messageApi.success(editingId ? "模型配置已更新" : "模型配置已加密保存"); resetModelForm(); refreshModels(); } });
  const testModel = useMutation({ mutationFn: (modelId: string) => apiRequest<ManagedModel>(`/api/v0/model-configurations/${encodeURIComponent(modelId)}/test`, { method: "POST", headers: requesterHeaders() }), onSuccess: value => { messageApi[value.lastTestStatus === "SUCCEEDED" ? "success" : "warning"](value.lastTestStatus === "SUCCEEDED" ? "连接测试通过" : `连接测试未通过：${value.lastTestErrorCode ?? "未知错误"}`); refreshModels(); } });
  const reorderModels = useMutation({ mutationFn: ({ modelIds, changeNote }: { modelIds: string[]; changeNote: string }) => apiRequest<ManagedModel[]>("/api/v0/model-configurations/order", { method: "PUT", headers: requesterHeaders(), body: JSON.stringify({ modelIds, changeNote }) }), onSuccess: () => { messageApi.success("调用顺序已更新"); refreshModels(); } });
  const applyDraft = useMutation({ mutationFn: () => apiRequest<ManagedModel[]>("/api/v0/model-configurations/apply-consultation-agent-draft", { method: "POST", headers: requesterHeaders() }), onSuccess: () => { messageApi.success("已应用草稿模型链到实际运行顺序"); refreshModels(); } });
  const updateModel = (model: ManagedModel, enabled: boolean) => saveModel.mutate({ ...model, apiKey: "", enabled, changeNote: enabled ? "启用模型" : "停用模型" });
  const editModel = (model: ManagedModel) => { setEditingId(model.modelId); modelForm.setFieldsValue({ ...model, apiKey: "", changeNote: "更新模型配置" }); setModelDialogOpen(true); };
  const moveModel = (index: number, offset: number) => { const ordered = [...(models.data ?? [])]; const target = index + offset; if (target < 0 || target >= ordered.length) return; [ordered[index], ordered[target]] = [ordered[target], ordered[index]]; reorderModels.mutate({ modelIds: ordered.map(item => item.modelId), changeNote: "调整模型调用顺序" }); };
  const loadingError = catalog.error || current.error || save.error || validate.error || models.error || testModel.error || reorderModels.error || applyDraft.error;

  return <section className="workbench agent-config-workspace">
    {messageContext}
    <WorkbenchHeader eyebrow="内部配置" title="咨询智能体配置草稿" description="先保存场景、工具与模型故障链；授权配置人员可将已保存草稿的模型顺序显式应用到本地运行服务。" tags={["DEVELOPMENT_DRAFT", `版本 ${draft?.version ?? "-"}`]} />
    <RequestError error={loadingError} retry={() => { catalog.refetch(); current.refetch(); }} />
    {catalog.data && draft && <div className="agent-config-grid">
      <main className="agent-config-form-panel">
        <div className="panel-kicker"><Save size={15} /><span>草稿配置</span></div>
        <Form form={form} layout="vertical" onFinish={values => save.mutate(values)}>
          <Descriptions size="small" column={1} items={[{ key: "id", label: "智能体编号", children: draft.agentId }, { key: "state", label: "配置状态", children: <Tag color="gold">开发草稿</Tag> }, { key: "runtime", label: "模型顺序", children: draftApplied ? <Tag color="success">已应用到运行服务</Tag> : <Tag color="processing">待显式应用</Tag> }]} />
          <Form.Item name="name" label="智能体名称" rules={[{ required: true, message: "请填写名称" }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述" rules={[{ required: true, message: "请填写描述" }]}><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="scenarioIds" label="咨询场景" rules={[{ required: true, message: "至少选择一个场景" }]}><Select mode="multiple" options={catalog.data.scenarios.map(item => ({ value: item.id, label: item.label }))} /></Form.Item>
          <Form.Item name="modelChain" label="模型故障链（按选择顺序）" rules={[{ required: true, message: "至少选择一个模型" }]}><Select mode="multiple" optionLabelProp="label" options={savedModels.map(item => ({ value: item.modelId, label: `${item.displayName}${item.enabled && item.credentialConfigured ? "" : "（不可用）"}`, disabled: !item.enabled || !item.credentialConfigured }))} /></Form.Item>
          <Typography.Paragraph type="secondary" className="field-hint">第一个模型为主模型；发生允许的技术故障时，按顺序尝试后续模型，每个模型最多一次。</Typography.Paragraph>
          <Form.Item name="switchOn" label="允许自动切换的故障" rules={[{ required: true, message: "至少选择一个技术故障" }]}><Select mode="multiple" options={catalog.data.switchableFailures.map(value => ({ value, label: failureLabels[value] ?? value }))} /></Form.Item>
          <Form.Item name="toolIds" label="工具能力" rules={[{ required: true, message: "至少选择一个工具" }]}><Select mode="multiple" options={catalog.data.tools.map(item => ({ value: item.id, label: item.label }))} /></Form.Item>
          <Form.Item name="changeNote" label="变更说明" rules={[{ required: true, message: "请填写变更说明" }]}><Input placeholder="说明本次草稿修改内容" /></Form.Item>
          <Space wrap><Button type="primary" htmlType="submit" icon={<Save size={16} />} loading={save.isPending}>保存草稿</Button><Button icon={<Play size={16} />} disabled={!draftCanApply || draftApplied} loading={applyDraft.isPending} onClick={() => applyDraft.mutate()}>应用模型顺序</Button><Button icon={<CheckCircle2 size={16} />} loading={validate.isPending} onClick={() => validate.mutate()}>校验当前草稿</Button></Space>
          {!draftCanApply && <Typography.Paragraph type="secondary" className="field-hint">草稿中的模型尚未全部保存为实际运行模型，暂不能应用。保存模型配置并刷新后可继续。</Typography.Paragraph>}
        </Form>
      </main>
      <aside className="agent-config-summary">
        <div className="panel-kicker"><ShieldAlert size={15} /><span>模型与校验</span></div>
        <div className="model-directory-heading"><Typography.Title level={4}>已保存运行模型</Typography.Title><Button size="small" type="primary" icon={<Plus size={15} />} onClick={openCreateModel}>新增模型</Button></div>
        {savedModels.length > 0 ? <div className="model-catalog-list">{savedModels.map(item => <article key={item.modelId}><div><strong>{item.displayName}</strong><span>{item.provider} / {item.modelName} · 优先级 {item.priority}</span></div><Tag color={item.enabled ? "success" : "default"}>{item.enabled ? "已启用" : "已停用"}</Tag></article>)}</div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未保存模型配置" />}
        <Typography.Paragraph type="secondary">仅展示本地数据库中已保存的模型配置。</Typography.Paragraph>
        {validation ? <Alert type={validation.publishReady ? "success" : "warning"} showIcon title={validation.publishReady ? "校验通过" : "仍不能正式发布"} description={<ul className="validation-issues">{validation.issues.map(item => <li key={item.code}>{item.message}</li>)}</ul>} />
          : <Alert type="info" showIcon title="等待校验" description="保存草稿后可检查模型配置和仍缺少的正式依赖。" />}
      </aside>
    </div>}
    <section className="model-management-panel">
      <div className="panel-kicker"><Plus size={15} /><span>实际运行模型</span></div>
      <Typography.Title level={4}>管理已保存模型</Typography.Title>
      <div className="model-catalog-list managed-model-list">{models.data?.map((item, index) => <article key={item.modelId}><div className="managed-model-copy"><strong>{item.displayName}</strong><span>{item.provider} / {item.modelName} · 优先级 {item.priority}</span><span>{item.lastTestStatus === "SUCCEEDED" ? "最近连接通过" : item.lastTestStatus === "FAILED" ? `最近连接失败：${item.lastTestErrorCode ?? "未知错误"}` : "尚未测试连接"}</span></div><div className="managed-model-actions"><Tag color={item.enabled ? "success" : "default"}>{item.enabled ? "已启用" : "已停用"}</Tag><Button aria-label={`编辑 ${item.displayName}`} icon={<Pencil size={15} />} onClick={() => editModel(item)}>编辑</Button><Button aria-label={`${item.enabled ? "停用" : "启用"} ${item.displayName}`} icon={<Power size={15} />} loading={saveModel.isPending} onClick={() => updateModel(item, !item.enabled)}>{item.enabled ? "停用" : "启用"}</Button><Button aria-label={`测试 ${item.displayName} 连接`} icon={<FlaskConical size={15} />} disabled={!item.enabled} loading={testModel.isPending} onClick={() => testModel.mutate(item.modelId)}>测试</Button><Button aria-label={`上移 ${item.displayName}`} icon={<ArrowUp size={15} />} disabled={index === 0 || reorderModels.isPending} onClick={() => moveModel(index, -1)} /><Button aria-label={`下移 ${item.displayName}`} icon={<ArrowDown size={15} />} disabled={index === (models.data?.length ?? 0) - 1 || reorderModels.isPending} onClick={() => moveModel(index, 1)} /></div></article>)}</div>
    </section>
    <Modal title={editingId ? "编辑模型" : "新增模型"} open={modelDialogOpen} onCancel={resetModelForm} destroyOnHidden forceRender footer={null} width={760}>
      <Typography.Paragraph type="secondary" className="model-dialog-note">API Key 仅在服务端加密保存且不会回显；编辑时留空可保留现有密钥。</Typography.Paragraph>
      {saveModel.error && <Alert className="model-save-error" type="error" showIcon title="保存失败" description={saveModel.error instanceof ApiError && saveModel.error.status === 404 ? "当前后端未部署模型配置接口。请刷新到包含模型配置功能的后端服务后重试。" : saveModel.error instanceof Error ? saveModel.error.message : "请检查模型服务和配置后重试"} />}
      <Form form={modelForm} layout="vertical" onFinish={values => saveModel.mutate(values)}>
        <div className="model-form-grid"><Form.Item name="modelId" label="配置编号" rules={[{ required: true, message: "请填写配置编号" }]}><Input disabled={Boolean(editingId)} placeholder="deepseek-chat" /></Form.Item><Form.Item name="displayName" label="显示名称" rules={[{ required: true, message: "请填写显示名称" }]}><Input placeholder="DeepSeek Chat" /></Form.Item><Form.Item name="provider" label="供应商" rules={[{ required: true, message: "请填写供应商" }]}><Input /></Form.Item><Form.Item name="modelName" label="模型名称" rules={[{ required: true, message: "请填写模型名称" }]}><Input placeholder="deepseek-chat" /></Form.Item><Form.Item name="endpoint" label="接口地址" rules={[{ required: true, type: "url", message: "请填写 HTTPS 或本地 HTTP 接口地址" }]}><Input placeholder="https://api.deepseek.com/chat/completions" /></Form.Item><Form.Item name="apiKey" label="API Key" rules={[{ required: !editingId, message: "新增模型时请填写 API Key" }]}><Input.Password autoComplete="new-password" placeholder={editingId ? "留空则保留现有密钥" : undefined} /></Form.Item><Form.Item name="priority" label="调用优先级" rules={[{ required: true, type: "number", min: 1 }]}><InputNumber min={1} precision={0} disabled={Boolean(editingId)} /></Form.Item><Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item></div>
        <Form.Item name="changeNote" label="变更说明" rules={[{ required: true, message: "请填写变更说明" }]}><Input /></Form.Item><Space wrap><Button type="primary" htmlType="submit" icon={<Save size={16} />} loading={saveModel.isPending}>加密保存模型</Button><Button icon={<X size={16} />} onClick={resetModelForm}>取消</Button></Space>
      </Form>
    </Modal>
  </section>;
}
