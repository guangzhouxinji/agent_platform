import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button, Checkbox, Collapse, Form, Input, Select, Space, Tag, Typography } from "antd";
import { History, Search } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { apiRequest } from "../../shared/api/client";
import { ACTOR, displayCode, EmptyState, RequestError, SnapshotSummary, StatusTag, WorkbenchHeader, requesterHeaders } from "../../shared/ui/Workbench";
import { FlowHandoff } from "../consultation/FlowHandoff";
import { loadConsultationFlow } from "../consultation/consultationFlowState";

type Task = Record<string, any> & { taskId: string; status: string; candidates: any[]; inputSnapshot: Record<string, any> };
type Kind = "patent" | "trademark";
const TERMINAL = new Set(["SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED"]);

const configs = {
  patent: {
    title: "专利相似度检索", endpoint: "/api/v0/patent-search-tasks",
    description: "先用 DeepSeek 提取可编辑特征，人工确认后创建检索任务。",
    limitation: "结果仅是当前数据源的候选文献；数据模式、实际范围和缺失能力以任务结果为准，不代表全量检索、新颖性、侵权或授权结论。"
  },
  trademark: {
    title: "商标相似度检索", endpoint: "/api/v0/trademark-search-tasks",
    description: "按名称、类别、商品服务与可用维度生成候选，不将排序结果作为近似结论。",
    limitation: "当前任务使用本地 USPTO 派生公开开发样本；不是中国商标库，图形和组合检索未配置，不代表真实商标库、注册成功率或 Recall@K。"
  }
} as const;

function CandidateCards({ kind, items, dataMode }: { kind: Kind; items: any[]; dataMode?: string }) {
  if (!items.length) return <EmptyState text="当前确认范围内没有候选；这不等于不存在相关记录。" />;
  return <div className="candidate-grid">{items.map((item, index) => <article className="candidate-card" key={`${item.sourceId}-${index}`}>
    <div className="candidate-head"><div><Typography.Title level={4}>{item.title ?? item.name}</Typography.Title><Typography.Text type="secondary">{item.sourceId} · {item.publicationNumber ?? item.registrationNumber} · {item.version ?? item.sourceVersion}</Typography.Text></div><Tag color="cyan">{item.relevanceLabel ?? item.score}</Tag></div>
    {kind === "patent" ? <><Typography.Paragraph>{item.matchingExcerpt || "来源未提供可展示摘要"}</Typography.Paragraph><Space wrap>{(item.matchedFeatures ?? []).map((v: string) => <Tag key={v}>{v}</Tag>)}</Space>
      {dataMode === "PUBLIC_LOCAL" && item.location !== "权利要求节选" && <Space wrap className="candidate-capabilities"><Tag>权利要求未提供</Tag><Button size="small" href="/app/libraries/patents">查看资料库</Button><Button size="small" disabled>暂无法进入对比</Button></Space>}</> : <>
      <Typography.Paragraph>类别：{(item.categories ?? []).join("、")} · 商品/服务：{(item.goodsServices ?? []).join("、")}</Typography.Paragraph>
      <div className="dimension-list">{(item.dimensions ?? []).map((v: any) => <Tag key={v.dimension} title={`${v.dimension} · ${v.state}`} color={v.state === "EXECUTED" ? "success" : "default"}>{displayCode(v.dimension)} · {displayCode(v.state)}：{v.basis}</Tag>)}</div>
    </>}
  </article>)}</div>;
}

function SearchForm({ kind, pending, onSubmit, initialInput, initialTrademark }: { kind: Kind; pending: boolean; onSubmit: (value: any) => void; initialInput?: string; initialTrademark?: { name?: string; category?: string } }) {
  const [form] = Form.useForm();
  const [prepared, setPrepared] = useState<any>();
  const prepare = useMutation({ mutationFn: (body: any) => apiRequest<any>("/api/v0/patent-search-tasks/preparations", { method: "POST", headers: requesterHeaders(true), body: JSON.stringify(body) }), onSuccess: (value) => { setPrepared(value); form.setFieldValue("features", value.suggestedFeatures.join("，")); } });
  const patentBody = (v: any) => ({
    inputMode: v.inputMode, input: v.input, purpose: v.purpose,
    confirmedFeatures: String(v.features ?? "").split(/[，,]/).map((x) => x.trim()).filter(Boolean),
    scope: { patentTypes: v.patentTypes ?? [], regions: v.region ? [v.region] : [], classifications: v.classification ? [v.classification] : [], applicationFrom: null, applicationTo: null, publicationFrom: null, publicationTo: null }
  });
  if (kind === "patent") return <Form className="search-query-form" form={form} layout="vertical" initialValues={{ inputMode: "TECHNICAL_SOLUTION", input: initialInput || "水性粘结剂 双区烘干 在线测厚", purpose: "TECHNICAL_REFERENCE", patentTypes: [], region: "CN" }} onValuesChange={(changed) => { if ("input" in changed || "inputMode" in changed || "purpose" in changed) setPrepared(undefined); }} onFinish={(v) => prepared ? onSubmit(patentBody(v)) : prepare.mutate(patentBody(v))}>
    <div className="search-query-primary">
      <Form.Item name="inputMode" label="输入方式"><Select options={[{ value: "TECHNICAL_SOLUTION", label: "技术方案" }, { value: "CORE_FEATURES", label: "核心特征" }, { value: "PATENT_NUMBER", label: "专利号" }]} /></Form.Item>
      <Form.Item className="search-query-input" name="input" label="检索内容" rules={[{ required: true }]}><Input placeholder="输入技术方案、核心特征或专利号" /></Form.Item>
      <Button type="primary" htmlType="submit" loading={pending || prepare.isPending} icon={<Search size={16} />}>{prepared ? "开始检索" : "提取特征"}</Button>
    </div>
    {prepared && <div className="search-confirmation-shelf"><div><Typography.Text strong>已提取 {prepared.suggestedFeatures.length} 项检索特征</Typography.Text><Typography.Text type="secondary">可直接编辑后开始检索</Typography.Text></div><Form.Item name="features" rules={[{ required: true }]}><Input placeholder="用逗号分隔检索特征" /></Form.Item></div>}
    <Collapse className="search-advanced-filters" ghost items={[{ key: "filters", label: "更多筛选条件", children: <>
      <Form.Item name="patentTypes" label="专利类型"><Checkbox.Group options={["发明", "实用新型", "外观设计"].map((value) => ({ value, label: value }))} /></Form.Item>
      <div className="form-grid"><Form.Item name="region" label="地区"><Select allowClear options={[{ value: "CN", label: "中国" }]} /></Form.Item><Form.Item name="classification" label="分类"><Input placeholder="例如 G01B" /></Form.Item></div>
      <Form.Item name="purpose" label="检索目的"><Select options={[{ value: "TECHNICAL_REFERENCE", label: "技术参考" }]} /></Form.Item>
    </> }]} />
    <RequestError error={prepare.error} />
  </Form>;

  return <Form className="search-query-form" form={form} layout="vertical" initialValues={{ name: initialTrademark?.name || "清源", categories: initialTrademark?.category || "3", goodsServices: "化妆品", dimensions: ["TEXT", "PHONETIC", "SEMANTIC"] }} onFinish={(v) => onSubmit({
    inputMode: "TEXT", name: v.name,
    categories: String(v.categories).split(/[，,]/).map((x) => x.trim()).filter(Boolean),
    goodsServices: String(v.goodsServices).split(/[，,]/).map((x) => x.trim()).filter(Boolean), dimensions: v.dimensions,
    scope: { statuses: v.status ? [v.status] : [], regions: v.region ? [v.region] : [], dataSources: [] }, page: 1, pageSize: 20
  })}>
    <div className="search-query-primary trademark-query-primary">
      <Form.Item className="search-query-input" name="name" label="商标名称" rules={[{ required: true }]}><Input placeholder="输入商标名称" /></Form.Item>
      <Form.Item name="categories" label="类别" rules={[{ required: true }]}><Input placeholder="例如 3" /></Form.Item>
      <Form.Item className="search-query-input" name="goodsServices" label="商品/服务" rules={[{ required: true }]}><Input placeholder="例如 化妆品" /></Form.Item>
      <Button type="primary" htmlType="submit" loading={pending} icon={<Search size={16} />}>开始检索</Button>
    </div>
    <Collapse className="search-advanced-filters" ghost items={[{ key: "filters", label: "更多筛选条件", children: <>
      <Form.Item name="dimensions" label="分析维度" rules={[{ required: true }]}><Checkbox.Group options={["TEXT", "PHONETIC", "SEMANTIC"].map((value) => ({ value, label: displayCode(value) as string }))} /></Form.Item>
      <div className="form-grid"><Form.Item name="status" label="展示状态"><Input /></Form.Item><Form.Item name="region" label="地区"><Select allowClear options={[{ value: "CN", label: "中国" }]} /></Form.Item></div>
    </> }]} />
  </Form>;
}

function SearchWorkspace({ kind }: { kind: Kind }) {
  const [params] = useSearchParams();
  const flowId = params.get("flowId");
  const flow = loadConsultationFlow(flowId);
  const slots = flow?.intent.intents.flatMap((intent) => intent.slots) ?? [];
  const slot = (name: string) => slots.find((item) => item.name === name)?.value;
  const config = configs[kind];
  const queryClient = useQueryClient();
  const [active, setActive] = useState<Task>();
  const history = useQuery({ queryKey: [kind, "search-history"], queryFn: () => apiRequest<Task[]>(config.endpoint, { headers: requesterHeaders() }) });
  const create = useMutation({ mutationFn: (body: any) => apiRequest<Task>(config.endpoint, { method: "POST", headers: requesterHeaders(true), body: JSON.stringify(body) }), onSuccess: setActive });
  const detail = useQuery({ queryKey: [kind, "search-task", active?.taskId], queryFn: () => apiRequest<Task>(`${config.endpoint}/${active!.taskId}`, { headers: requesterHeaders() }), enabled: Boolean(active?.taskId) && !TERMINAL.has(active?.status ?? ""), refetchInterval: (query) => TERMINAL.has((query.state.data as Task | undefined)?.status ?? "") ? false : 300 });
  useEffect(() => { if (detail.data) setActive(detail.data); if (detail.data && TERMINAL.has(detail.data.status)) queryClient.invalidateQueries({ queryKey: [kind, "search-history"] }); }, [detail.data, kind, queryClient]);
  const taskAction = useMutation({ mutationFn: (action: "cancel" | "retry") => apiRequest<Task>(`${config.endpoint}/${active!.taskId}/${action}`, { method: "POST", headers: requesterHeaders() }), onSuccess: setActive });
  const busy = create.isPending || taskAction.isPending;
  return <section className="workbench search-workbench" id={`${kind}-search-workspace`}>
    <WorkbenchHeader eyebrow="检索分析" title={config.title} description={config.description} tags={kind === "patent" ? ["人工确认", "候选结果"] : ["PUBLIC_LOCAL", "候选结果"]} />
    <FlowHandoff flowId={flowId} step={kind === "patent" ? "patent-search" : "trademark-search"} status={active?.status} reference={active?.taskId} />
    <div className="search-workbench-content"><div className="search-query-desk"><div className="search-query-heading"><div><Typography.Text className="search-query-title">{config.title}</Typography.Text><Typography.Text type="secondary">{config.description}</Typography.Text></div></div><SearchForm kind={kind} pending={busy} onSubmit={(value) => create.mutate(value)} initialInput={flow?.question} initialTrademark={{ name: slot("trademarkName") ?? slot("namedSubject"), category: slot("category") }} /></div>
    <div className="search-results-layout"><aside className="search-history-rail"><div className="section-title"><Typography.Title level={4}>任务记录</Typography.Title><Button type="text" aria-label="刷新任务记录" icon={<History size={16} />} onClick={() => history.refetch()} /></div>
        <div className="history-list">{history.isLoading ? <Typography.Text type="secondary">正在读取历史任务…</Typography.Text> : history.data?.length ? history.data.slice(0, 8).map((task) => <Button key={task.taskId} className="history-item" onClick={() => setActive(task)}><span>{task.inputSnapshot.input ?? task.inputSnapshot.originalName}</span><StatusTag value={task.status} /></Button>) : <Typography.Text type="secondary">暂无历史任务</Typography.Text>}</div>
      </aside><main className="search-result-stage">
      <RequestError error={create.error || taskAction.error || detail.error || history.error} />
      {!active ? <EmptyState text="填写检索条件后，候选结果会显示在这里。" /> : <><div className="section-title"><div><Typography.Title level={3}>任务结果</Typography.Title><Typography.Text type="secondary">{active.taskId} · 第 {active.attempt} 次执行</Typography.Text></div><Space><StatusTag value={active.status} />{["QUEUED", "PROCESSING"].includes(active.status) && <Button onClick={() => taskAction.mutate("cancel")}>取消</Button>}{active.status === "FAILED" && <Button onClick={() => taskAction.mutate("retry")}>重试</Button>}</Space></div>
        <SnapshotSummary items={[["候选数量", active.totalCandidates ?? active.candidates?.length], ["实际范围", active.actualScope], ["版本", active.scoringVersion ?? active.rankingVersion]]} />
        <div className="search-candidates-heading"><Typography.Title level={3}>候选记录</Typography.Title><Typography.Text type="secondary">按相关性排序</Typography.Text></div><CandidateCards kind={kind} items={active.candidates ?? []} dataMode={active.dataMode} /></>}
    </main></div></div>
  </section>;
}

export const PatentSearchPage = () => <SearchWorkspace kind="patent" />;
export const TrademarkSearchPage = () => <SearchWorkspace kind="trademark" />;
