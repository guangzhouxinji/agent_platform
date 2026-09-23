import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Alert, Button, Collapse, DatePicker, Form, Input, Select, Space, Tag, Typography } from "antd";
import { BookOpen, History, Search } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { apiRequest } from "../../shared/api/client";
import { EmptyState, RequestError, SnapshotSummary, StatusTag, WorkbenchHeader, requesterHeaders } from "../../shared/ui/Workbench";
import { FlowHandoff } from "../consultation/FlowHandoff";
import { loadConsultationFlow } from "../consultation/consultationFlowState";

type Preparation = {
  question: string;
  suggestedKeywords: string[];
  requiresClarification: boolean;
  issues: string[];
  limitation: string;
};

type Candidate = {
  sourceId: string;
  sourceVersion: string;
  caseNumber?: string;
  docketNumber?: string;
  title: string;
  caseType?: string;
  intellectualPropertyType?: string;
  institution?: string;
  procedure?: string;
  decisionDate?: string;
  outcomeExcerpt?: string;
  relevanceScore: number;
  relevanceLabel: string;
  matchedKeywords: string[];
  rankingBasis: string[];
  evidence: { text?: string; sourceLocation?: string; systemLocator?: string; state: string };
};

type CaseSearchTask = {
  taskId: string;
  status: string;
  dataMode: string;
  sourceVersion: string;
  rankingVersion: string;
  actualScope: string;
  totalCandidates: number;
  candidates: Candidate[];
  limitation: string;
  traceId: string;
  createdAt: string;
  inputSnapshot: { question: string; confirmedKeywords: string[] };
};

const ENDPOINT = "/api/v0/case-search-tasks";

function CaseCandidate({ item }: { item: Candidate }) {
  const libraryHref = `/app/libraries/cases?caseNumber=${encodeURIComponent(item.caseNumber ?? "")}`;
  return <article className="candidate-card case-search-candidate">
    <div className="candidate-head">
      <div>
        <Typography.Title level={4}>{item.title}</Typography.Title>
        <Typography.Text type="secondary">{item.docketNumber || item.caseNumber || item.sourceId} · {item.institution || "审理机构未提供"}</Typography.Text>
      </div>
      <Tag color="cyan">{item.relevanceScore} · {item.relevanceLabel}</Tag>
    </div>
    <Space wrap className="case-search-meta">
      {[item.intellectualPropertyType, item.caseType, item.procedure, item.decisionDate].filter(Boolean).map((value) => <Tag key={value}>{value}</Tag>)}
    </Space>
    <Typography.Paragraph>{item.outcomeExcerpt || "来源未提供裁判结果节选"}</Typography.Paragraph>
    <div className="case-search-evidence">
      <Typography.Text strong>原文证据</Typography.Text>
      <Typography.Paragraph>{item.evidence.text || "当前来源没有可展示的原文片段"}</Typography.Paragraph>
      <Typography.Text type="secondary">{item.evidence.sourceLocation || "位置未提供"} · {item.evidence.systemLocator || item.evidence.state}</Typography.Text>
    </div>
    <div className="case-search-basis">
      <Typography.Text strong>排序依据</Typography.Text>
      <Space wrap>{item.rankingBasis.map((basis) => <Tag key={basis}>{basis}</Tag>)}</Space>
    </div>
    <Space wrap className="candidate-capabilities">
      <Typography.Text type="secondary">命中：{item.matchedKeywords.join("、") || "无"}</Typography.Text>
      <Button size="small" href={libraryHref} icon={<BookOpen size={14} aria-hidden="true" />}>在案例库核对</Button>
    </Space>
  </article>;
}

export default function CaseSearchWorkspace() {
  const [params] = useSearchParams();
  const flowId = params.get("flowId");
  const flow = loadConsultationFlow(flowId);
  const [form] = Form.useForm();
  const [preparation, setPreparation] = useState<Preparation>();
  const [active, setActive] = useState<CaseSearchTask>();
  const queryClient = useQueryClient();
  const history = useQuery({ queryKey: ["case-search-history"], queryFn: () => apiRequest<CaseSearchTask[]>(ENDPOINT, { headers: requesterHeaders() }) });
  const prepare = useMutation({
    mutationFn: (question: string) => apiRequest<Preparation>(`${ENDPOINT}/preparations`, { method: "POST", headers: requesterHeaders(), body: JSON.stringify({ question }) }),
    onSuccess: (value) => {
      setPreparation(value);
      form.setFieldValue("keywords", value.suggestedKeywords);
    }
  });
  const create = useMutation({
    mutationFn: (value: any) => {
      const dates = value.decisionDates ?? [];
      return apiRequest<CaseSearchTask>(ENDPOINT, {
        method: "POST",
        headers: requesterHeaders(true),
        body: JSON.stringify({
          question: value.question,
          confirmedKeywords: value.keywords,
          caseTypes: value.caseTypes ?? [],
          intellectualPropertyTypes: value.ipTypes ?? [],
          institutions: value.institutions ?? [],
          procedures: value.procedures ?? [],
          decisionFrom: dates[0]?.format("YYYY-MM-DD") ?? null,
          decisionTo: dates[1]?.format("YYYY-MM-DD") ?? null,
          resultLimit: value.resultLimit
        })
      });
    },
    onSuccess: (value) => {
      setActive(value);
      queryClient.invalidateQueries({ queryKey: ["case-search-history"] });
    }
  });

  const submit = (value: any) => preparation ? create.mutate(value) : prepare.mutate(value.question);
  const error = prepare.error || create.error || history.error;

  return <section className="workbench search-workbench" id="case-search-workspace">
    <WorkbenchHeader eyebrow="检索分析" title="相似案例检索" description="从咨询问题提取可编辑关键词，按确认范围检索案例并回到原文核对。" tags={["PUBLIC_LOCAL", "人工确认", "原文证据"]} />
    <FlowHandoff flowId={flowId} step="case-search" status={active?.status} reference={active?.taskId} />
    <div className="search-workbench-content">
      <div className="search-query-desk">
        <div className="search-query-heading"><div><Typography.Text className="search-query-title">相似案例检索</Typography.Text><Typography.Text type="secondary">从问题中提取关键词，确认范围后查看可回溯的案例候选。</Typography.Text></div></div>
        <Form className="search-query-form" form={form} layout="vertical" initialValues={{ question: flow?.question || "商标侵权二审有哪些可参考案例", resultLimit: 10 }}
          onValuesChange={(changed) => { if ("question" in changed) setPreparation(undefined); }} onFinish={submit}>
          <div className="search-query-primary case-query-primary"><Form.Item className="search-query-input" name="question" label="检索问题" rules={[{ required: true, message: "请描述需要参考的争议或处理问题" }]}><Input placeholder="例如：商标侵权二审有哪些可参考案例" /></Form.Item><Button type="primary" htmlType="submit" loading={prepare.isPending || create.isPending} icon={<Search size={16} aria-hidden="true" />}>{preparation ? "开始检索" : "提取要素"}</Button></div>
          {preparation?.requiresClarification && <Alert className="search-inline-notice" type="warning" showIcon title="问题范围较宽" description={preparation.issues.join("；")} />}
          {preparation && <div className="search-confirmation-shelf"><div><Typography.Text strong>确认检索关键词</Typography.Text><Typography.Text type="secondary">可删除、修改或继续添加</Typography.Text></div><Form.Item name="keywords" extra={preparation.limitation} rules={[{ required: true, message: "至少确认一个检索关键词" }]}><Select mode="tags" tokenSeparators={[",", "，", "、"]} placeholder="输入后回车，可删除或修改" /></Form.Item></div>}
          {preparation && <Collapse className="search-advanced-filters" ghost items={[{ key: "filters", label: "更多筛选条件", children: <>
            <Form.Item name="ipTypes" label="知识产权类型"><Select mode="tags" options={["商标", "专利", "著作权"].map((value) => ({ value }))} /></Form.Item>
            <div className="form-grid">
              <Form.Item name="caseTypes" label="案件类型"><Select mode="tags" options={["民事案件", "行政案件"].map((value) => ({ value }))} /></Form.Item>
              <Form.Item name="procedures" label="程序"><Select mode="tags" options={["一审", "二审", "行政裁决", "行政复议"].map((value) => ({ value }))} /></Form.Item>
            </div>
            <Form.Item name="institutions" label="审理机构"><Select mode="tags" placeholder="可输入机构名称后回车" /></Form.Item>
            <Form.Item name="decisionDates" label="审理时间"><DatePicker.RangePicker style={{ width: "100%" }} /></Form.Item>
            <Form.Item name="resultLimit" label="返回数量"><Select options={[5, 10, 20, 50].map((value) => ({ value, label: `${value} 条` }))} /></Form.Item>
          </> }]} />}
          <RequestError error={prepare.error || create.error} />
        </Form>
      </div>
      <div className="search-results-layout"><aside className="search-history-rail">
          <div className="section-title"><Typography.Title level={4}>任务记录</Typography.Title><Button type="text" aria-label="刷新任务记录" icon={<History size={16} aria-hidden="true" />} onClick={() => history.refetch()} /></div>
          <div className="history-list">{history.isLoading ? <Typography.Text type="secondary">正在读取历史任务...</Typography.Text> : history.data?.length ? history.data.slice(0, 8).map((task) =>
            <Button key={task.taskId} className="history-item" onClick={() => setActive(task)}><span>{task.inputSnapshot.question}</span><StatusTag value={task.status} /></Button>
          ) : <Typography.Text type="secondary">暂无历史任务</Typography.Text>}</div>
      </aside>

      <main className="search-result-stage" aria-busy={create.isPending}>
        <RequestError error={error} retry={() => history.refetch()} />
        {!active ? <EmptyState text="填写检索问题后，候选案例会显示在这里。" /> : <>
          <div className="section-title"><div><Typography.Title level={3}>任务结果</Typography.Title><Typography.Text type="secondary">{active.taskId} · {new Date(active.createdAt).toLocaleString("zh-CN")}</Typography.Text></div><StatusTag value={active.status} /></div>
          <SnapshotSummary items={[["候选数量", active.totalCandidates], ["实际范围", active.actualScope], ["排序版本", active.rankingVersion]]} />
          <Alert className="case-search-result-limit" type="info" showIcon title="结果说明" description={active.limitation} />
          <div className="search-candidates-heading"><Typography.Title level={3}>候选案例</Typography.Title><Typography.Text type="secondary">按相关性排序</Typography.Text></div>
          {active.candidates.length ? <div className="candidate-grid">{active.candidates.map((item) => <CaseCandidate key={item.sourceId} item={item} />)}</div>
            : <EmptyState text="当前确认范围内没有命中案例；这不等于不存在相关案例，可调整关键词或范围后重试。" />}
        </>}
      </main></div>
    </div>
  </section>;
}
