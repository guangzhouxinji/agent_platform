import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Divider, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, Typography, message } from "antd";
import { Ban, Beaker, Copy, FilePlus2, Pencil, Plus, RefreshCw, Rocket, Trash2 } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { ACTOR, ORGANIZATION, RequestError, WorkbenchHeader } from "../../shared/ui/Workbench";

type Envelope<T> = { requestId: string; resultType: string; data: T; limitations: string[] };
type Condition = { nodeId: string; operator: string; fieldPath?: string; value?: string | number; children: Condition[] };
type Rule = { ruleId: string; ruleVersion: number; revision: number; status: string; ruleName: string; category: string; description?: string; engineType: string; usageScope: string; sourceIds: string[]; condition: Condition; contentDigest: string; updatedAt: string };
type Source = { sourceId: string; sourceName: string; enabled: boolean; healthStatus: string };
type ConditionResult = { nodeId: string; operator: string; fieldPath?: string; matched: boolean; reasonCode: string; actualValue?: unknown };
type DryRun = { runId: string; sampleId: string; sampleVersion: string; resultStatus: string; matched: boolean; executedAt: string; conditionResults: ConditionResult[] };
type RuleForm = { ruleName: string; category: string; description?: string; sourceIds: string[]; groupOperator: "AND" | "OR"; conditions: Array<{ fieldPath: string; operator: string; value?: string | number }> };
type SampleForm = { sampleId: string; sampleVersion: string; content: string; score?: number; subjectName?: string };

const headers = () => ({ "X-V0-Actor": ACTOR, "X-V0-Organization": ORGANIZATION });
const categoryLabels: Record<string, string> = { COUNTERFEIT_PATENT: "假冒专利", PATENT_INFRINGEMENT: "专利侵权", PUBLICITY_ISSUE: "宣传问题" };
const statusLabels: Record<string, string> = { DRAFT: "草稿", PUBLISHED: "开发期发布", DISABLED: "已停用" };
const reasonLabels: Record<string, string> = { MATCHED: "命中", NOT_MATCHED: "未命中", FIELD_MISSING: "字段缺失", FIELD_TYPE_MISMATCH: "类型不匹配", CHILD_TYPE_ERROR: "子条件类型错误" };
const operators = [
  { value: "EXISTS", label: "字段存在" }, { value: "TEXT_CONTAINS", label: "文本包含" },
  { value: "NUMBER_GT", label: "数值大于" }, { value: "NUMBER_GTE", label: "数值大于等于" },
  { value: "NUMBER_LT", label: "数值小于" }, { value: "NUMBER_LTE", label: "数值小于等于" },
  { value: "NUMBER_EQ", label: "数值等于" }
];
const initialConditions = [{ fieldPath: "content", operator: "TEXT_CONTAINS", value: "专利" }, { fieldPath: "subject.name", operator: "EXISTS" }];

export default function EnforcementDiscoveryRulesPage() {
  const [ruleForm] = Form.useForm<RuleForm>();
  const [sampleForm] = Form.useForm<SampleForm>();
  const [selectedKey, setSelectedKey] = useState<string>();
  const [editing, setEditing] = useState<Rule>();
  const [editorOpen, setEditorOpen] = useState(false);
  const [runOpen, setRunOpen] = useState(false);
  const [lastRun, setLastRun] = useState<DryRun>();
  const [messageApi, messageContext] = message.useMessage();
  const rules = useQuery({ queryKey: ["enforcement-discovery-rules"], queryFn: async () => (await apiRequest<Envelope<Rule[]>>("/api/v0/enforcement/discovery-rules", { headers: headers() })).data });
  const sources = useQuery({ queryKey: ["enforcement-rule-sources"], queryFn: async () => (await apiRequest<Envelope<Source[]>>("/api/v0/enforcement/source-configurations", { headers: headers() })).data });
  useEffect(() => { if (!selectedKey && rules.data?.length) setSelectedKey(key(rules.data[0])); }, [rules.data, selectedKey]);
  const selected = useMemo(() => rules.data?.find(item => key(item) === selectedKey), [rules.data, selectedKey]);
  const runHistory = useQuery({ queryKey: ["enforcement-rule-runs", selected?.ruleId, selected?.ruleVersion], enabled: Boolean(selected), queryFn: async () => (await apiRequest<Envelope<DryRun[]>>(`/api/v0/enforcement/discovery-rules/${selected!.ruleId}/versions/${selected!.ruleVersion}/dry-runs`, { headers: headers() })).data });
  const refresh = () => { rules.refetch(); sources.refetch(); runHistory.refetch(); };
  const closeEditor = () => { setEditorOpen(false); setEditing(undefined); ruleForm.resetFields(); };
  const openCreate = () => { setEditing(undefined); ruleForm.setFieldsValue({ category: "COUNTERFEIT_PATENT", groupOperator: "AND", sourceIds: [], conditions: initialConditions }); setEditorOpen(true); };
  const openEdit = (rule: Rule) => { setEditing(rule); ruleForm.setFieldsValue({ ruleName: rule.ruleName, category: rule.category, description: rule.description, sourceIds: rule.sourceIds, groupOperator: rule.condition.operator as "AND" | "OR", conditions: rule.condition.children.map(item => ({ fieldPath: item.fieldPath!, operator: item.operator, value: item.value })) }); setEditorOpen(true); };
  const save = useMutation({ mutationFn: (values: RuleForm) => {
    const body = { ruleId: editing?.ruleId, ruleName: values.ruleName, category: values.category, description: values.description, sourceIds: values.sourceIds, expectedRevision: editing?.revision, condition: toCondition(values) };
    return apiRequest<Envelope<Rule>>(editing ? `/api/v0/enforcement/discovery-rules/${editing.ruleId}/versions/${editing.ruleVersion}` : "/api/v0/enforcement/discovery-rules", { method: editing ? "PUT" : "POST", headers: headers(), body: JSON.stringify(body) });
  }, onSuccess: response => { messageApi.success(editing ? "规则草稿已更新" : "规则草稿已创建"); setSelectedKey(key(response.data)); closeEditor(); refresh(); } });
  const copyRule = useMutation({ mutationFn: (rule: Rule) => apiRequest<Envelope<Rule>>(`/api/v0/enforcement/discovery-rules/${rule.ruleId}/versions/${rule.ruleVersion}/copies`, { method: "POST", headers: headers() }), onSuccess: response => { messageApi.success("已复制为新草稿版本"); setSelectedKey(key(response.data)); refresh(); } });
  const runRule = useMutation({ mutationFn: (values: SampleForm) => apiRequest<Envelope<DryRun>>(`/api/v0/enforcement/discovery-rules/${selected!.ruleId}/versions/${selected!.ruleVersion}/dry-runs`, { method: "POST", headers: headers(), body: JSON.stringify({ sampleId: values.sampleId, sampleVersion: values.sampleVersion, sampleOrigin: "SYNTHETIC_LOCAL", payload: { content: values.content, score: values.score, subject: values.subjectName ? { name: values.subjectName } : undefined } }) }), onSuccess: response => { setLastRun(response.data); messageApi[response.data.resultStatus === "SUCCEEDED" ? "success" : "warning"](response.data.matched ? "试运行命中" : "试运行完成，未命中"); refresh(); } });
  const transition = useMutation({ mutationFn: ({ rule, action }: { rule: Rule; action: "publish" | "disable" }) => apiRequest<Envelope<Rule>>(`/api/v0/enforcement/discovery-rules/${rule.ruleId}/versions/${rule.ruleVersion}/${action}`, { method: "POST", headers: headers(), body: JSON.stringify({ expectedRevision: rule.revision }) }), onSuccess: response => { messageApi.success(response.data.status === "PUBLISHED" ? "已完成开发期发布" : "规则已停用"); refresh(); } });
  const error = rules.error || sources.error || runHistory.error || save.error || copyRule.error || runRule.error || transition.error;

  return <section className="workbench rule-admin-workspace">
    {messageContext}
    <WorkbenchHeader eyebrow="执法配置" title="线索规则" description="管理本地确定性规则、合成样例试运行和开发期版本。" tags={["DETERMINISTIC_V1", "DEVELOPMENT_ONLY"]} />
    <div className="rule-admin-toolbar"><div><Typography.Title level={3}>确定性规则</Typography.Title><Typography.Text type="secondary">规则结果仅供开发验证，不构成违法认定或正式线索</Typography.Text></div><Space wrap><Button icon={<RefreshCw size={16} />} onClick={refresh}>刷新</Button><Button type="primary" icon={<Plus size={16} />} onClick={openCreate}>新建规则</Button></Space></div>
    <RequestError error={error} retry={refresh} />
    <div className="rule-admin-layout">
      <section className="rule-list-panel" aria-label="规则列表">
        <Table rowKey={key} size="small" loading={rules.isLoading} dataSource={rules.data ?? []} pagination={false} locale={{ emptyText: "暂无规则，请新建草稿" }} onRow={record => ({ onClick: () => setSelectedKey(key(record)) })} rowClassName={record => key(record) === selectedKey ? "selected-row" : ""} columns={[
          { title: "规则", render: (_, row: Rule) => <div className="rule-name-cell"><strong>{row.ruleName}</strong><span>{row.ruleId} · V{row.ruleVersion}</span></div> },
          { title: "分类", dataIndex: "category", responsive: ["md"], render: value => categoryLabels[value] ?? value },
          { title: "状态", dataIndex: "status", render: value => <Tag color={value === "PUBLISHED" ? "success" : value === "DISABLED" ? "default" : "processing"}>{statusLabels[value] ?? value}</Tag> }
        ]} />
      </section>
      <aside className="rule-detail-panel" aria-label="当前规则详情">
        {!selected ? <Alert type="info" showIcon title="选择规则查看详情" description="规则可先使用合成样例完成功能试运行。" /> : <>
          <div className="rule-detail-heading"><div><Typography.Title level={4}>{selected.ruleName}</Typography.Title><Typography.Text type="secondary">V{selected.ruleVersion} · 修订 {selected.revision}</Typography.Text></div><Tag color={selected.status === "PUBLISHED" ? "success" : "processing"}>{statusLabels[selected.status]}</Tag></div>
          <Descriptions size="small" column={1} items={[{ key: "category", label: "业务分类", children: categoryLabels[selected.category] }, { key: "sources", label: "本地来源", children: selected.sourceIds.join("、") }, { key: "engine", label: "解释器", children: selected.engineType }, { key: "digest", label: "内容摘要", children: <Typography.Text copyable ellipsis>{selected.contentDigest}</Typography.Text> }]} />
          <div className="rule-condition-summary"><strong>{selected.condition.operator === "AND" ? "全部满足" : "任一满足"}</strong>{selected.condition.children.map(item => <div key={item.nodeId}><Tag>{item.operator}</Tag><span>{item.fieldPath}{item.value !== undefined ? ` = ${item.value}` : ""}</span></div>)}</div>
          <Space wrap className="rule-detail-actions">
            {selected.status === "DRAFT" && <Button icon={<Pencil size={16} />} onClick={() => openEdit(selected)}>编辑</Button>}
            <Button icon={<Copy size={16} />} loading={copyRule.isPending} onClick={() => copyRule.mutate(selected)}>复制版本</Button>
            {selected.status === "DRAFT" && <Button icon={<Beaker size={16} />} onClick={() => { setLastRun(undefined); sampleForm.setFieldsValue({ sampleId: "synthetic-sample-001", sampleVersion: "v1", content: "" }); setRunOpen(true); }}>试运行</Button>}
            {selected.status === "DRAFT" && <Button type="primary" icon={<Rocket size={16} />} loading={transition.isPending} onClick={() => transition.mutate({ rule: selected, action: "publish" })}>开发期发布</Button>}
            {selected.status === "PUBLISHED" && <Button danger icon={<Ban size={16} />} loading={transition.isPending} onClick={() => transition.mutate({ rule: selected, action: "disable" })}>停用</Button>}
          </Space>
          <Divider titlePlacement="start">试运行历史</Divider>
          <div className="rule-run-list">{runHistory.data?.length ? runHistory.data.map(run => <article key={run.runId}><div><strong>{run.matched ? "命中" : "未命中"}</strong><span>{run.sampleId} · {run.sampleVersion}</span></div><Tag color={run.resultStatus === "SUCCEEDED" ? "success" : "error"}>{run.resultStatus}</Tag></article>) : <Typography.Text type="secondary">当前版本暂无试运行</Typography.Text>}</div>
        </>}
      </aside>
    </div>

    <Modal title={editing ? "编辑规则草稿" : "新建规则草稿"} open={editorOpen} onCancel={closeEditor} footer={null} width={820} destroyOnHidden forceRender>
      <Alert type="info" showIcon title="只支持结构化确定性条件" description="不执行脚本、SQL、网址、正则表达式或工具调用。" />
      <Form form={ruleForm} layout="vertical" onFinish={values => save.mutate(values)} className="rule-editor-form">
        <div className="rule-form-grid"><Form.Item name="ruleName" label="规则名称" rules={[{ required: true, message: "请填写规则名称" }]}><Input /></Form.Item><Form.Item name="category" label="业务分类" rules={[{ required: true }]}><Select options={Object.entries(categoryLabels).map(([value, label]) => ({ value, label }))} /></Form.Item></div>
        <Form.Item name="description" label="规则说明"><Input.TextArea rows={2} maxLength={2000} showCount /></Form.Item>
        <Form.Item name="sourceIds" label="本地数据源" rules={[{ required: true, message: "请选择至少一个数据源" }]}><Select mode="multiple" options={(sources.data ?? []).map(source => ({ value: source.sourceId, label: `${source.sourceName} · ${source.healthStatus === "HEALTHY" ? "健康" : "待检查"}` }))} /></Form.Item>
        <Form.Item name="groupOperator" label="条件组合" rules={[{ required: true }]}><Select options={[{ value: "AND", label: "全部满足" }, { value: "OR", label: "任一满足" }]} /></Form.Item>
        <Form.List name="conditions">{(fields, { add, remove }) => <div className="rule-condition-editor"><div className="rule-editor-heading"><strong>条件</strong><Button icon={<FilePlus2 size={15} />} onClick={() => add({ operator: "EXISTS" })}>添加条件</Button></div>{fields.map(({ key: fieldKey, name, ...field }) => <div className="rule-condition-row" key={fieldKey}><Form.Item {...field} name={[name, "fieldPath"]} label="字段路径" rules={[{ required: true, pattern: /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*$/, message: "请输入点分隔字段路径" }]}><Input placeholder="content" /></Form.Item><Form.Item {...field} name={[name, "operator"]} label="运算符" rules={[{ required: true }]}><Select options={operators} /></Form.Item><Form.Item noStyle shouldUpdate>{({ getFieldValue }) => { const operator = getFieldValue(["conditions", name, "operator"]); return operator !== "EXISTS" ? <Form.Item {...field} name={[name, "value"]} label="比较值" rules={[{ required: true, message: "请填写比较值" }]}>{operator?.startsWith("NUMBER_") ? <InputNumber /> : <Input />}</Form.Item> : <div className="rule-value-empty"><span>无需比较值</span></div>; }}</Form.Item><Button className="rule-remove-condition" aria-label="删除条件" icon={<Trash2 size={16} />} disabled={fields.length <= 2} onClick={() => remove(name)} /></div>)}</div>}</Form.List>
        <Space wrap><Button type="primary" htmlType="submit" loading={save.isPending}>保存草稿</Button><Button onClick={closeEditor}>取消</Button></Space>
      </Form>
    </Modal>

    <Modal title="合成样例试运行" open={runOpen} onCancel={() => setRunOpen(false)} footer={null} width={720} destroyOnHidden forceRender>
      <Alert type="warning" showIcon title="开发期合成样例" description="试运行只验证解释器和规则配置，不证明专业规则正确。" />
      <Form form={sampleForm} layout="vertical" onFinish={values => runRule.mutate(values)} className="rule-sample-form">
        <div className="rule-form-grid"><Form.Item name="sampleId" label="样例编号" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="sampleVersion" label="样例版本" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Form.Item name="content" label="内容字段" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
        <div className="rule-form-grid"><Form.Item name="subjectName" label="主体名称"><Input /></Form.Item><Form.Item name="score" label="数值字段"><InputNumber /></Form.Item></div>
        <Button type="primary" htmlType="submit" icon={<Beaker size={16} />} loading={runRule.isPending}>执行试运行</Button>
      </Form>
      {lastRun && <div className="rule-run-result"><div className="rule-run-result-heading"><strong>{lastRun.matched ? "规则命中" : "规则未命中"}</strong><Tag color={lastRun.resultStatus === "SUCCEEDED" ? "success" : "error"}>{lastRun.resultStatus}</Tag></div><Table rowKey="nodeId" size="small" pagination={false} dataSource={lastRun.conditionResults} columns={[{ title: "条件", render: (_, row: ConditionResult) => row.fieldPath ?? row.operator }, { title: "结果", render: (_, row: ConditionResult) => <Tag color={row.matched ? "success" : "default"}>{reasonLabels[row.reasonCode] ?? row.reasonCode}</Tag> }]} /></div>}
    </Modal>
  </section>;
}

function key(rule: Rule) { return `${rule.ruleId}:${rule.ruleVersion}`; }
function toCondition(values: RuleForm): Condition {
  return { nodeId: "root", operator: values.groupOperator, children: values.conditions.map((item, index) => ({ nodeId: `condition-${index + 1}`, operator: item.operator, fieldPath: item.fieldPath, value: item.operator === "EXISTS" ? undefined : item.value, children: [] })) };
}
