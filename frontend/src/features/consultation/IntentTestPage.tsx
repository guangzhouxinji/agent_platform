import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Alert, Button, Form, Input, Space, Tag, Typography } from "antd";
import { ArrowRight, ScanSearch } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { EmptyState, RequestError, SnapshotSummary, StatusTag, WorkbenchHeader, requesterHeaders } from "../../shared/ui/Workbench";

type IntentResult = {
  status: string; parserVersion: string; question: string; primaryIntentId?: string;
  intents: Array<{ intentId: string; label: string; category: string; order: number; matchedSignals: string[];
    slots: Array<{ name: string; value: string; sourceRound: number; sourceType: string }>;
    missingSlots: string[]; plannedRoute?: string; routeState: string; reason: string }>;
  clarificationQuestions: string[]; limitation: string; traceId: string; parsedAt: string;
};

const slotLabels: Record<string, string> = {
  patentNumber: "专利号", registrationNumber: "注册号", category: "类别", namedSubject: "明确名称",
  trademarkName: "商标名称", analysisPurpose: "分析目的", targetTrademark: "目标商标",
  candidateTrademark: "候选商标", targetPatent: "目标专利", candidatePatent: "候选专利",
  categoryOrGoodsServices: "类别/商品服务"
};
const statusText: Record<string, string> = {
  RESOLVED: "已形成路由建议", MULTI_INTENT: "识别到多个诉求", NEEDS_CLARIFICATION: "需要补充信息",
  UNKNOWN: "暂时无法识别", ROUTE_UNAVAILABLE: "目标能力尚不可用", AVAILABLE: "可进入工作台",
  NOT_READY: "等待补充", UNAVAILABLE: "当前无可用工具"
};
const parseContext = (value?: string) => String(value ?? "").split("\n")
  .map((content, index) => ({ round: index + 1, content: content.trim() })).filter((turn) => turn.content);

export default function IntentTestPage() {
  const [result, setResult] = useState<IntentResult>();
  const parse = useMutation({
    mutationFn: (value: any) => apiRequest<IntentResult>("/api/v0/consultation-intents/parse", {
      method: "POST", headers: requesterHeaders(), body: JSON.stringify({ question: value.question, context: parseContext(value.context) })
    }),
    onSuccess: setResult
  });
  return <section className="workbench" id="intent-test-workspace">
    <WorkbenchHeader eyebrow="咨询能力" title="咨询意图测试" description="检查问题会被建议路由到哪个现有能力，以及还缺少哪些用户信息。" tags={["LOCAL-INTENT-V1", "不自动执行"]} />
    <div className="workbench-grid"><aside className="control-panel">
      <Form layout="vertical" initialValues={{ question: "查询这个专利的状态；同时找相关侵权案例", context: "专利号是 CN116234567A" }} onFinish={(value) => parse.mutate(value)}>
        <Form.Item name="question" label="当前问题" rules={[{ required: true, message: "请输入需要解析的问题" }]}><Input.TextArea rows={5} placeholder="描述需要查询、检索、比对或参考案例的诉求" /></Form.Item>
        <Form.Item name="context" label="允许使用的上下文" extra="每行视为一轮较早输入；留空表示不使用上下文。"><Input.TextArea rows={4} placeholder="例如：专利号是 CN116234567A" /></Form.Item>
        <RequestError error={parse.error} />
        <Button block type="primary" htmlType="submit" loading={parse.isPending} icon={<ScanSearch size={16} aria-hidden="true" />}>解析咨询意图</Button>
      </Form>
    </aside><main className="result-panel" aria-busy={parse.isPending}>
      <RequestError error={parse.error} />
      {!result ? <EmptyState text="提交左侧问题后，这里将并列展示意图、槽位、缺口和路由建议。" /> : <>
        <div className="section-title"><div><Typography.Title level={3}>解析结果</Typography.Title><Typography.Text type="secondary">{statusText[result.status] ?? result.status}</Typography.Text></div><StatusTag value={result.status} /></div>
        <SnapshotSummary items={[["解析版本", result.parserVersion], ["主意图", result.primaryIntentId || "未确定"], ["意图数量", result.intents.length], ["解析时间", new Date(result.parsedAt).toLocaleString("zh-CN")], ["追踪号", result.traceId]]} />
        {!!result.clarificationQuestions.length && <Alert className="intent-clarification" type="warning" showIcon title="需要向用户补充确认" description={result.clarificationQuestions.join("；")} />}
        <div className="intent-result-list">{result.intents.map((intent) => <article className="candidate-card intent-result-card" key={intent.intentId}>
          <div className="candidate-head"><div><Typography.Title level={4}>{intent.order}. {intent.label}</Typography.Title><Typography.Text type="secondary">{intent.intentId} · {intent.category}</Typography.Text></div><Tag>{statusText[intent.routeState] ?? intent.routeState}</Tag></div>
          <Typography.Paragraph>{intent.reason}</Typography.Paragraph>
          <div className="intent-detail-row"><Typography.Text strong>识别依据</Typography.Text><Space wrap>{intent.matchedSignals.length ? intent.matchedSignals.map((value) => <Tag key={value}>{value}</Tag>) : <Typography.Text type="secondary">无明确依据</Typography.Text>}</Space></div>
          <div className="intent-detail-row"><Typography.Text strong>用户明示信息</Typography.Text><div className="intent-slot-list">{intent.slots.length ? intent.slots.map((slot, index) => <div key={`${slot.name}-${index}`}><span>{slotLabels[slot.name] ?? slot.name}</span><strong>{slot.value}</strong><small>{slot.sourceRound === 0 ? "当前输入" : `上下文第 ${slot.sourceRound} 轮`}</small></div>) : <Typography.Text type="secondary">未提取到可确认信息</Typography.Text>}</div></div>
          {!!intent.missingSlots.length && <div className="intent-detail-row"><Typography.Text strong>仍缺少</Typography.Text><Space wrap>{intent.missingSlots.map((value) => <Tag color="warning" key={value}>{value}</Tag>)}</Space></div>}
          {intent.plannedRoute && <Button className="intent-route-button" href={`/app${intent.plannedRoute}`} icon={<ArrowRight size={15} aria-hidden="true" />}>进入建议工作台</Button>}
        </article>)}</div>
        <Alert className="case-search-result-limit" type="info" showIcon title="能力边界" description={result.limitation} />
      </>}
    </main></div>
  </section>;
}
