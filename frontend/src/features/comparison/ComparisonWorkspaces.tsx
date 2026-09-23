import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Checkbox, Form, Input, Select, Space, Tag, Typography } from "antd";
import { GitCompareArrows, History } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { apiRequest } from "../../shared/api/client";
import { ACTOR, displayCode, EmptyState, RequestError, SnapshotSummary, StatusTag, WorkbenchHeader, writeHeaders } from "../../shared/ui/Workbench";
import { FlowHandoff } from "../consultation/FlowHandoff";

type Kind = "patent" | "trademark";
type Ref = { sourceId: string; version: string };
type Task = Record<string, any> & { taskId: string; status: string; pairs: any[] };
type Catalog = { items: any[]; limitation: string; catalogVersion: string };

const config = {
  patent: { title: "专利技术对照", endpoint: "/api/v0/patent-comparisons", catalog: "/api/v0/patent-library?pageSize=50", purpose: "TECHNICAL_SIMILARITY", description: "先确认目标、候选和技术特征，再生成逐特征辅助对照。" },
  trademark: { title: "商标多维对照", endpoint: "/api/v0/trademark-comparisons", catalog: "/api/v0/trademark-library?pageSize=50", purpose: "AUXILIARY_COMPARISON", description: "对已确认类别和商品服务执行文字、读音、含义等可用维度。" }
} as const;

function itemId(item: any) { return item.sourceRecordId ?? item.sourceId; }
function ref(item: any): Ref { return { sourceId: itemId(item), version: item.sourceVersion ?? item.version }; }
function label(kind: Kind, item: any) { return `${item.title ?? item.name} · ${item.publicationNumber ?? item.registrationNumber ?? "号码未提供"} · ${itemId(item)}`; }
function shortId(value?: string) { return value && value.length > 25 ? `${value.slice(0, 11)}...${value.slice(-8)}` : value; }
function readableState(value?: string) {
  const states: Record<string, string> = { COMPLETED: "已完成", SUCCEEDED: "已完成", NOT_FOUND: "未找到对应依据", PARTIAL: "部分完成", FAILED: "处理失败", ANALYZED: "已分析", NOT_ANALYZED: "未分析" };
  return states[value ?? ""] ?? String(displayCode(value) ?? "等待处理");
}

function PairCards({ kind, pairs }: { kind: Kind; pairs: any[] }) {
  if (!pairs?.length) return <EmptyState text="当前任务没有可展示的对照结果。" />;
  return <div className="candidate-grid comparison-pair-grid">{pairs.map((pair, index) => <article className="candidate-card comparison-pair" key={`${pair.candidate?.sourceId}-${index}`}>
    <div className="candidate-head"><div><span className="pair-kicker">候选记录 {index + 1}</span><Typography.Title level={4}>{pair.candidate?.sourceId}</Typography.Title><Typography.Text type="secondary">版本 {pair.candidate?.version ?? "未提供"}</Typography.Text></div><div className="pair-status"><span>处理状态</span><StatusTag value={pair.status} /></div></div>
    {kind === "patent" ? <>
      {(pair.mappings ?? []).map((item: any) => <div className="comparison-row" key={item.featureId}><strong>特征 {item.featureId} · {readableState(item.relation)}</strong><span><b>目标</b>{item.targetText}</span><span><b>候选</b>{item.candidateEvidence}</span><small>{item.reason}</small></div>)}
      <Typography.Paragraph>{pair.auxiliaryOpinion}</Typography.Paragraph>
    </> : <>
      {(pair.dimensions ?? []).map((item: any) => <div className="comparison-row" key={item.dimension}><strong title={`${item.dimension} · ${item.state}`}>{displayCode(item.dimension)} · {readableState(item.state)}</strong><span>{item.observation}</span><small>{item.reason}</small></div>)}
      <Typography.Paragraph>{pair.auxiliaryOpinion}</Typography.Paragraph>
    </>}
  </article>)}</div>;
}

function ComparisonWorkspace({ kind }: { kind: Kind }) {
  const [params] = useSearchParams();
  const flowId = params.get("flowId");
  const c = config[kind];
  const queryClient = useQueryClient();
  const [active, setActive] = useState<Task>();
  const [prepared, setPrepared] = useState<any>();
  const catalog = useQuery({ queryKey: [kind, "comparison-catalog"], queryFn: () => apiRequest<Catalog>(c.catalog, { actor: ACTOR }) });
  const history = useQuery({ queryKey: [kind, "comparison-history"], queryFn: () => apiRequest<Task[]>(`${c.endpoint}/tasks`, { actor: ACTOR }) });
  const options = useMemo(() => (catalog.data?.items ?? []).slice(0, 40).map((item) => ({ value: itemId(item), label: label(kind, item) })), [catalog.data, kind]);
  const prepare = useMutation({ mutationFn: async (values: any) => {
    const items = catalog.data!.items;
    const target = ref(items.find((item) => itemId(item) === values.target));
    const candidates = values.candidates.map((id: string) => ref(items.find((item) => itemId(item) === id)));
    const prepBody = kind === "patent" ? { purpose: c.purpose, target, candidates } : { purpose: c.purpose, target, candidates, dimensions: values.dimensions };
    const preparation = await apiRequest<any>(`${c.endpoint}/preparations`, { method: "POST", actor: ACTOR, body: JSON.stringify(prepBody) });
    return { preparation, target, candidates };
  }, onSuccess: setPrepared });
  const create = useMutation({ mutationFn: async (values: any) => {
    const { preparation, target, candidates } = prepared;
    const body = kind === "patent"
      ? { purpose: c.purpose, target, candidates, confirmedFeatures: preparation.suggestedFeatures.map((feature: any, index: number) => ({ ...feature, text: values.features[index] })) }
      : { purpose: c.purpose, target, candidates, confirmedCategories: values.categories.split(/[，,]/).map((v: string) => v.trim()).filter(Boolean), confirmedGoodsServices: values.goodsServices.split(/[，,]/).map((v: string) => v.trim()).filter(Boolean), dimensions: preparation.selectedDimensions };
    return apiRequest<Task>(`${c.endpoint}/tasks`, { method: "POST", headers: writeHeaders(), body: JSON.stringify(body) });
  }, onSuccess: (task) => { setActive(task); setPrepared(undefined); queryClient.invalidateQueries({ queryKey: [kind, "comparison-history"] }); } });
  const initialTarget = options[0]?.value;
  const initialCandidate = options[1]?.value;
  const preparationBlocked = prepared && (kind === "patent"
    ? !prepared.preparation.suggestedFeatures?.length
    : !prepared.preparation.suggestedCategories?.length || !prepared.preparation.suggestedGoodsServices?.length);
  const blockedDescription = kind === "patent"
    ? "目标公开记录未提供可确认的权利要求技术特征。请补充合法来源的权利要求文本后再继续。"
    : "目标公开记录未同时提供可确认的类别和商品/服务。请补充合法来源材料后再继续。";
  return <section className="workbench search-workbench comparison-workbench">
    <WorkbenchHeader eyebrow="辅助对照" title={c.title} description={c.description} tags={["PUBLIC_LOCAL", "NOT_LEGAL_CONCLUSION"]} />
    <FlowHandoff flowId={flowId} step={kind === "patent" ? "patent-comparison" : "trademark-comparison"} status={active?.status} reference={active?.taskId} />
    <div className="search-workbench-content"><div className="search-query-desk"><div className="search-query-heading"><div><Typography.Text className="search-query-title">{c.title}</Typography.Text><Typography.Text type="secondary">{c.description}</Typography.Text></div></div>
      {options.length ? <Form className="search-query-form comparison-query-form" key={initialTarget} layout="vertical" initialValues={{ target: initialTarget, candidates: initialCandidate ? [initialCandidate] : [], dimensions: ["TEXT", "PHONETIC", "SEMANTIC", "GOODS_SERVICES"] }} onFinish={(v) => prepare.mutate(v)}>
        <div className="search-query-primary comparison-query-primary"><Form.Item name="target" label="目标记录" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={options} /></Form.Item><Form.Item name="candidates" label="候选记录" rules={[{ required: true }]}><Select mode="multiple" maxCount={5} showSearch optionFilterProp="label" options={options} /></Form.Item><Button type="primary" htmlType="submit" loading={prepare.isPending} icon={<GitCompareArrows size={16} />}>准备对照</Button></div>
        {kind === "trademark" && <Form.Item className="comparison-dimensions" name="dimensions" label="执行维度"><Checkbox.Group options={["TEXT", "PHONETIC", "SEMANTIC", "GOODS_SERVICES"].map((value) => ({ value, label: displayCode(value) as string }))} /></Form.Item>}
      </Form> : <Typography.Text type="secondary">正在加载本地公开目录…</Typography.Text>}
      {prepared && <div className="comparison-confirmation-shelf"><div className="comparison-confirmation-heading"><Typography.Text strong>确认对照范围</Typography.Text><Typography.Text type="secondary">确认后创建一次对照任务</Typography.Text></div><SnapshotSummary items={[["数据模式", prepared.preparation.dataMode], ["来源版本", prepared.preparation.sourceVersion], ["规则版本", prepared.preparation.ruleVersion]]} />
        {preparationBlocked ? <Alert type="warning" showIcon title="资料不完整，无法创建对照任务" description={blockedDescription} /> : kind === "patent" ? <Form className="comparison-confirmation-form" layout="vertical" initialValues={{ features: prepared.preparation.suggestedFeatures.map((item: any) => item.text) }} onFinish={(v) => create.mutate(v)}><Form.List name="features">{(fields) => <>{fields.map((field, index) => <Form.Item {...field} key={field.key} label={`技术特征 ${index + 1}`} rules={[{ required: true }]}><Input /></Form.Item>)}</>}</Form.List><Button type="primary" htmlType="submit" loading={create.isPending}>创建对照任务</Button></Form> : <Form className="comparison-confirmation-form comparison-trademark-confirmation" layout="vertical" initialValues={{ categories: prepared.preparation.suggestedCategories.join("，"), goodsServices: prepared.preparation.suggestedGoodsServices.join("，") }} onFinish={(v) => create.mutate(v)}><Form.Item name="categories" label="确认类别" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="goodsServices" label="确认商品/服务" rules={[{ required: true }]}><Input /></Form.Item><Space wrap>{prepared.preparation.selectedDimensions.map((item: string) => <Tag key={item} title={item}>{displayCode(item)}</Tag>)}</Space><Button className="confirm-create" type="primary" htmlType="submit" loading={create.isPending}>创建对照任务</Button></Form>}
      </div>}</div>
    <div className="search-results-layout"><aside className="search-history-rail"><div className="section-title"><Typography.Title level={4}>任务记录</Typography.Title><Button type="text" aria-label="刷新任务记录" icon={<History size={16} />} onClick={() => history.refetch()} /></div><div className="history-list">
        {history.isLoading ? <Typography.Text type="secondary">正在读取历史任务…</Typography.Text> : history.data?.length ? history.data.slice(0, 8).map((task) => <Button className="history-item" key={task.taskId} title={task.taskId} aria-label={`查看历史任务 ${task.taskId}`} onClick={() => setActive(task)}><span>{shortId(task.taskId)}</span><StatusTag value={task.status} /></Button>) : <Typography.Text type="secondary">暂无历史任务</Typography.Text>}
      </div></aside><main className="search-result-stage">
      <RequestError error={catalog.error || history.error || prepare.error || create.error} retry={() => { catalog.refetch(); history.refetch(); }} />
      {!active ? <EmptyState text="选择目标和候选记录后，对照结果会显示在这里。" /> : <><div className="comparison-result-header"><div><span className="result-kicker">本次任务</span><Typography.Title level={3}>对照结果</Typography.Title><Typography.Text type="secondary" title={active.taskId}>{shortId(active.taskId)}</Typography.Text></div><div className="comparison-task-status"><div><span>任务状态</span><StatusTag value={active.status} /></div><div><span>审核状态</span><Tag title={active.reviewStatus}>{displayCode(active.reviewStatus ?? "未审核")}</Tag></div></div></div>
        <SnapshotSummary items={[["来源版本", active.sourceVersion], ["规则版本", active.ruleVersion], ["能力", active.scoreCapability ?? active.reportCapability]]} />
        <section className="comparison-evidence"><div className="section-title"><div><span className="result-kicker">逐项核对</span><Typography.Title level={3}>候选对照</Typography.Title></div><Typography.Text type="secondary">{active.pairs?.length ?? 0} 条候选</Typography.Text></div><PairCards kind={kind} pairs={active.pairs} /></section>
      </>}
    </main></div></div>
  </section>;
}

export const PatentComparisonPage = () => <ComparisonWorkspace kind="patent" />;
export const TrademarkComparisonPage = () => <ComparisonWorkspace kind="trademark" />;
