import { useEffect, useMemo, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Alert, Button, Form, Input, Space, Steps, Tag, Typography } from "antd";
import { ArrowRight, CheckCircle2, GitBranch, RotateCcw } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { apiRequest } from "../../shared/api/client";
import { RequestError, StatusTag, WorkbenchHeader, requesterHeaders } from "../../shared/ui/Workbench";
import {
  consultationFlowHref,
  clearCurrentConsultationFlow,
  createConsultationFlow,
  isSuccessfulFlowStatus,
  loadConsultationFlow,
  type ConsultationFlowSession,
  type IntentRoute
} from "./consultationFlowState";

type IntentResult = ConsultationFlowSession["intent"] & { question: string; parsedAt: string };

const routeLabels: Record<string, string> = {
  "/search/patents": "专利检索",
  "/search/trademarks": "商标检索",
  "/search/cases": "案例检索",
  "/compare/patents": "专利对照",
  "/compare/trademarks": "商标比对",
  "/libraries/patents": "专利资料库",
  "/libraries/trademarks": "商标资料库",
  "/libraries/cases": "案例资料库"
};

const routeSteps: Record<string, keyof ConsultationFlowSession["results"]> = {
  "/search/patents": "patent-search",
  "/search/trademarks": "trademark-search",
  "/search/cases": "case-search",
  "/compare/patents": "patent-comparison",
  "/compare/trademarks": "trademark-comparison"
};

const intakeExamples = [
  { label: "专利检索", question: "请检索水性粘结剂双区烘干技术方案，用于技术参考" },
  { label: "商标检索", question: "检索“清源”商标，第3类化妆品" },
  { label: "案例参考", question: "商标侵权二审有哪些可参考案例" }
];

function parseContext(value?: string) {
  return String(value ?? "").split("\n").map((content, index) => ({ round: index + 1, content: content.trim() })).filter((turn) => turn.content);
}

function IntentCard({ intent, flow, onEdit }: { intent: IntentRoute; flow: ConsultationFlowSession; onEdit: () => void }) {
  const result = intent.plannedRoute ? flow.results[routeSteps[intent.plannedRoute]] : undefined;
  const available = intent.routeState === "AVAILABLE" && Boolean(intent.plannedRoute);
  return <article className="flow-route-card">
    <div className="flow-route-heading"><div><span>建议 {intent.order}</span><Typography.Title level={4}>{intent.label}</Typography.Title></div><StatusTag value={intent.routeState} /></div>
    <Typography.Paragraph>{intent.reason}</Typography.Paragraph>
    <Space wrap>{intent.matchedSignals.map((value) => <Tag key={value}>{value}</Tag>)}</Space>
    {!!intent.missingSlots.length && <Alert type="warning" showIcon title="仍需补充" description={intent.missingSlots.join("、")} />}
    {result && <div className="flow-result-evidence"><CheckCircle2 size={16} aria-hidden="true" /><span>{isSuccessfulFlowStatus(result.status) ? "任务已完成" : `任务状态：${result.status}`}</span>{result.reference && <small>{result.reference}</small>}</div>}
    {available && <Button type="primary" href={consultationFlowHref(intent.plannedRoute!, flow.flowId)} icon={<ArrowRight size={15} aria-hidden="true" />}>
      进入{routeLabels[intent.plannedRoute!] ?? "建议工作台"}
    </Button>}
    {!available && !!intent.missingSlots.length && <Button onClick={onEdit}>补充信息后重新识别</Button>}
  </article>;
}

export default function ConsultationFlowPage() {
  const [params, setParams] = useSearchParams();
  const [flow, setFlow] = useState<ConsultationFlowSession>();
  const [form] = Form.useForm();
  const parse = useMutation({
    mutationFn: (value: any) => apiRequest<IntentResult>("/api/v0/consultation-intents/parse", {
      method: "POST", headers: requesterHeaders(), body: JSON.stringify({ question: value.question, context: parseContext(value.context) })
    }),
    onSuccess: (intent, value) => {
      const next = createConsultationFlow({ question: value.question.trim(), context: value.context?.trim() ?? "", purpose: value.purpose, intent });
      setFlow(next);
      setParams({ flowId: next.flowId }, { replace: true });
    }
  });

  useEffect(() => {
    const restore = () => setFlow(loadConsultationFlow(params.get("flowId")));
    restore();
    window.addEventListener("consultation-flow-updated", restore);
    return () => window.removeEventListener("consultation-flow-updated", restore);
  }, [params]);

  useEffect(() => {
    if (flow) form.setFieldsValue({ question: flow.question, context: flow.context, purpose: flow.purpose });
  }, [flow, form]);

  const completed = useMemo(() => flow ? Object.values(flow.results).filter((item) => isSuccessfulFlowStatus(item?.status)).length : 0, [flow]);
  const canAnswer = flow?.intent.intents.some((item) => item.category === "PATENT" && item.routeState === "AVAILABLE");
  const needsClarification = flow?.intent.status === "NEEDS_CLARIFICATION" || flow?.intent.status === "UNKNOWN";
  const startNew = () => { clearCurrentConsultationFlow(); setFlow(undefined); setParams({}, { replace: true }); form.resetFields(); parse.reset(); };
  const editQuestion = () => {
    form.scrollToField("question", { block: "center" });
  };

  return <section className="workbench consultation-flow" id="consultation-flow-workspace">
    <WorkbenchHeader eyebrow="内部调试" title="咨询流程调试" description="验证问题识别、路由建议与工作台交接；日常咨询请使用“知识产权咨询”。" tags={["LOCAL-INTENT-V1", "会话内进度"]} />
    <div className="flow-layout">
      <aside className="flow-intake">
        <div className="panel-kicker"><GitBranch size={15} aria-hidden="true" /><span>流程起点</span></div>
        <Typography.Title level={4}>描述本次咨询</Typography.Title>
        <Form form={form} layout="vertical" initialValues={{ question: intakeExamples[0].question, context: "", purpose: "TECHNICAL_REFERENCE" }} onFinish={(value) => parse.mutate(value)}>
          <Form.Item name="question" label="咨询问题" rules={[{ required: true, message: "请填写需要咨询的问题" }]}><Input.TextArea rows={6} /></Form.Item>
          <div className="flow-examples" aria-label="可直接使用的问题示例">
            <Typography.Text type="secondary">可直接使用</Typography.Text>
            <Space wrap>{intakeExamples.map((example) => <Button key={example.label} size="small" onClick={() => form.setFieldValue("question", example.question)}>{example.label}</Button>)}</Space>
          </div>
          <Form.Item name="context" label="已有上下文" extra="每行作为一轮较早输入；不需要时可留空。"><Input.TextArea rows={3} /></Form.Item>
          <Form.Item name="purpose" label="咨询目的"><Input value="TECHNICAL_REFERENCE" disabled /></Form.Item>
          <RequestError error={parse.error} />
          <Button block type="primary" htmlType="submit" loading={parse.isPending} disabled={parse.isPending}>识别意图并建立流程</Button>
        </Form>
        {flow && <Button block className="flow-reset" icon={<RotateCcw size={15} aria-hidden="true" />} onClick={startNew}>开始新的咨询</Button>}
      </aside>

      <main className="flow-main" aria-busy={parse.isPending}>
        <Steps responsive items={[
          { title: "识别问题", status: flow ? "finish" : parse.isPending ? "process" : "wait" },
          { title: "执行专业能力", status: completed ? "finish" : flow ? "process" : "wait" },
          { title: "形成咨询结果", status: isSuccessfulFlowStatus(flow?.results["consultation-answer"]?.status) ? "finish" : completed ? "process" : "wait" }
        ]} />
        {!flow ? <div className="flow-empty"><GitBranch size={28} aria-hidden="true" /><Typography.Title level={4}>等待建立咨询流程</Typography.Title><Typography.Paragraph type="secondary">提交问题后，这里会显示识别结果、可执行工作台和每一步的真实任务状态。</Typography.Paragraph></div> : <>
          <section className="flow-summary">
            <div><span className="result-kicker">当前流程</span><Typography.Title level={3}>{flow.question}</Typography.Title><Typography.Text type="secondary">解析版本 {flow.intent.parserVersion} · 已完成 {completed} 个专业步骤</Typography.Text></div>
            <StatusTag value={flow.intent.status} />
          </section>
          {needsClarification && <Alert type="warning" showIcon title="需要先补充信息" description={flow.intent.clarificationQuestions.join("；") || "当前问题尚未形成可执行路由，请补充明确对象和咨询目的后重新识别。"} />}
          <section className="flow-route-section"><div className="section-title"><div><span className="result-kicker">下一步</span><Typography.Title level={3}>建议执行能力</Typography.Title></div></div>
            <div className="flow-route-grid">{flow.intent.intents.map((intent) => <IntentCard key={`${intent.intentId}-${intent.order}`} intent={intent} flow={flow} onEdit={editQuestion} />)}</div>
          </section>
          <section className="flow-finish"><div><Typography.Title level={4}>咨询答复</Typography.Title><Typography.Paragraph type="secondary">专利类问题可继续进入既有带来源咨询闭环；商标和案例问题目前以各自工作台结果为准，不生成伪统一答复。</Typography.Paragraph></div>
            {canAnswer ? <Button type="primary" href={consultationFlowHref("/consultation", flow.flowId)} icon={<ArrowRight size={15} aria-hidden="true" />}>继续形成咨询答复</Button> : <Button disabled>当前意图无适用统一答复</Button>}
          </section>
        </>}
      </main>
    </div>
  </section>;
}
