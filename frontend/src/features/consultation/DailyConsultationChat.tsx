import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Alert, Button, Collapse, Descriptions, Drawer, Form, Input, Popover, Space, Typography } from "antd";
import { ChevronDown, ExternalLink, FileSearch, MessageSquareText, RotateCcw } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { RequestError, StatusTag, requesterHeaders } from "../../shared/ui/Workbench";
import "../../styles/daily-consultation.css";

type Candidate = { sourceId: string; version: string; title: string; identifier: string; matchBasis: string; location: string };
type Source = { sourceId: string; version: string; title: string; location: string; sourceHref: string };
type RouteResult = { intentId: string; label: string; category: string; status: string; taskId: string; summary: string; actualScope: string; limitation: string; nextAction: string; nextActionHref: string; candidates: Candidate[]; sources: Source[] };
type DailyAnswer = { consultationId: string; status: string; parserVersion: string; intentResolutionSource?: string; primaryIntentId?: string; clarificationQuestions: string[]; results: RouteResult[]; content: string; limitation: string };
type SourceDetail = { sourceLinkId?: string; sourceId: string; version: string; location: string };
type Guidance = { guideId: string; title: string; question: string };
type GuidanceResult = { guides: Guidance[] };
type Turn = { id: string; question: string; state: "pending" | "succeeded" | "failed"; answer?: DailyAnswer; error?: unknown };

function EmptyConversation() {
  return <div className="consultation-empty-state"><span className="empty-state-icon"><FileSearch size={24} aria-hidden="true" /></span>
    <Typography.Title level={4}>从一条问题开始</Typography.Title>
    <Typography.Paragraph type="secondary">可以直接咨询专利、商标或案例，系统会自动识别并调用已有能力。</Typography.Paragraph>
    <div className="empty-state-steps" aria-label="咨询处理阶段"><span>识别</span><i aria-hidden="true" /><span>检索</span><i aria-hidden="true" /><span>答复</span></div>
  </div>;
}

function RouteAnswer({ result, openSource }: { result: RouteResult; openSource: (source: Source) => void }) {
  return <section className="daily-route-result" aria-label={`${result.label}结果`}>
    <div className="daily-route-heading"><div><Typography.Text strong>{result.label}</Typography.Text>{result.taskId && <Typography.Text type="secondary">任务 {result.taskId}</Typography.Text>}</div><StatusTag value={result.status} /></div>
    <Typography.Paragraph>{result.summary}</Typography.Paragraph>
    {result.actualScope && <Typography.Paragraph className="daily-scope" type="secondary">查询范围：{result.actualScope}</Typography.Paragraph>}
    {result.limitation && <Alert type="info" showIcon title="结果边界" description={result.limitation} />}
    {!!result.candidates.length && <Collapse className="reply-evidence" ghost items={[{ key: "candidates", label: `查看 ${result.candidates.length} 条候选`, children: <div className="candidate-grid">{result.candidates.map((item) => <article className="candidate-card" key={`${item.sourceId}-${item.version}`}><Typography.Title level={4}>{item.title || item.sourceId}</Typography.Title><Typography.Text type="secondary">{item.identifier || item.sourceId} · 版本 {item.version}</Typography.Text><Typography.Paragraph>{item.matchBasis}</Typography.Paragraph>{item.location && <small>{item.location}</small>}</article>)}</div> }]} />}
    {!!result.sources.length && <Collapse className="reply-evidence reply-sources" ghost items={[{ key: "sources", label: `查看 ${result.sources.length} 个来源`, children: <div className="source-actions">{result.sources.map((item) => item.sourceHref.startsWith("/api/") ? <button type="button" className="source-open-button" key={`${item.sourceId}-${item.version}`} onClick={() => openSource(item)}><ExternalLink size={14} aria-hidden="true" /><span><strong>{item.title || item.sourceId}</strong><small>{item.sourceId} · {item.version} · {item.location}</small></span></button> : <a className="source-open-button" key={`${item.sourceId}-${item.version}`} href={item.sourceHref}><ExternalLink size={14} aria-hidden="true" /><span><strong>{item.title || item.sourceId}</strong><small>{item.sourceId} · {item.version} · {item.location}</small></span></a>)}</div> }]} />}
    {result.nextAction && <div className="daily-next-action"><Typography.Text type="secondary">下一步：{result.nextAction}</Typography.Text>{result.nextActionHref && <Button href={result.nextActionHref}>继续处理</Button>}</div>}
  </section>;
}

export default function DailyConsultationChat({ initialQuestion = "" }: { initialQuestion?: string }) {
  const [form] = Form.useForm();
  const [turns, setTurns] = useState<Turn[]>([]);
  const [guidanceOpen, setGuidanceOpen] = useState(false);
  const [selectedSource, setSelectedSource] = useState<Source>();
  const guidance = useQuery({ queryKey: ["consultation-guides"], queryFn: () => apiRequest<GuidanceResult>("/api/v0/consultation-guides?language=zh-CN&limit=6", { headers: requesterHeaders() }) });
  const sourceLookup = useMutation({ mutationFn: (source: Source) => apiRequest<SourceDetail>(source.sourceHref, { headers: requesterHeaders() }) });
  const submit = useMutation({ mutationFn: ({ question, context }: { question: string; context: Array<{ round: number; content: string }> }) => apiRequest<DailyAnswer>("/api/v0/consultations/daily", { method: "POST", headers: requesterHeaders(true), body: JSON.stringify({ question, context }) }) });
  const send = ({ question }: { question: string }) => {
    const id = crypto.randomUUID();
    const context = turns.map((turn, index) => ({ round: index + 1, content: turn.question }));
    setTurns((current) => [...current, { id, question, state: "pending" }]);
    submit.mutate({ question, context }, {
      onSuccess: (answer) => { setTurns((current) => current.map((turn) => turn.id === id ? { ...turn, state: "succeeded", answer } : turn)); form.resetFields(["question"]); },
      onError: (error) => setTurns((current) => current.map((turn) => turn.id === id ? { ...turn, state: "failed", error } : turn))
    });
  };
  const startNew = () => { submit.reset(); sourceLookup.reset(); setTurns([]); setSelectedSource(undefined); form.resetFields(); };
  const openSource = (source: Source) => { setSelectedSource(source); sourceLookup.reset(); sourceLookup.mutate(source); };
  const guidanceContent = <div className="guidance-popover-content">{guidance.isLoading && <Typography.Text type="secondary">正在读取问题示例...</Typography.Text>}<RequestError error={guidance.error} retry={() => guidance.refetch()} />{guidance.data && <div className="guidance-list">{guidance.data.guides.map((item) => <button key={item.guideId} type="button" className="guidance-item" onClick={() => { form.setFieldValue("question", item.question); setGuidanceOpen(false); }}><span>{item.title}</span><small>{item.question}</small></button>)}</div>}</div>;

  return <><div className="consultation-chat-layout"><main className="consultation-transcript" aria-busy={submit.isPending} aria-label="咨询对话">
    <div className="consultation-session-actions">{!!turns.length && <Button className="consultation-new-session" type="text" icon={<RotateCcw size={15} aria-hidden="true" />} onClick={startNew}>新建咨询</Button>}<Popover overlayClassName="consultation-guidance-popover" placement="bottomRight" trigger="click" open={guidanceOpen} onOpenChange={setGuidanceOpen} title="问题示例" content={guidanceContent}><Button className="consultation-guidance-trigger" type="text" icon={<ChevronDown size={16} aria-hidden="true" />}>问题示例</Button></Popover></div>
    <div className="consultation-messages"><article className="chat-message assistant-message chat-welcome"><Typography.Paragraph>输入专利、商标或案例问题即可开始。</Typography.Paragraph></article>
      {turns.map((turn) => <div className="conversation-turn" key={turn.id}><article className="chat-message user-message" aria-label="你的消息"><Typography.Paragraph>{turn.question}</Typography.Paragraph></article>
        {turn.state === "pending" && <article className="chat-message assistant-message"><Typography.Text role="status">正在识别问题并执行可用能力...</Typography.Text></article>}
        {turn.state === "failed" && <RequestError error={turn.error} />}
        {turn.answer && <article className="chat-message assistant-message reply-message"><div className="reply-status"><StatusTag value={turn.answer.status} /><Typography.Text type="secondary">解析版本 {turn.answer.parserVersion}{turn.answer.intentResolutionSource === "MODEL_CANDIDATE_CONFIRMED" ? " · 模型候选已采用" : " · 固定规则校验/回退"}</Typography.Text></div><Typography.Paragraph className="consultation-answer">{turn.answer.content}</Typography.Paragraph>
          {!!turn.answer.clarificationQuestions.length && <Alert className="reply-issues" type="warning" showIcon title="需要补充" description={turn.answer.clarificationQuestions.join("；")} />}
          <div className="daily-route-list">{turn.answer.results.map((result) => <RouteAnswer key={`${result.intentId}-${result.taskId}`} result={result} openSource={openSource} />)}</div>
          <Typography.Paragraph className="reply-limitation" type="secondary">{turn.answer.limitation}</Typography.Paragraph>
        </article>}
      </div>)}
      {!turns.length && <EmptyConversation />}
    </div>
    <section className="consultation-composer" aria-labelledby="daily-consultation-composer"><Typography.Title id="daily-consultation-composer" level={4} className="sr-only">咨询输入</Typography.Title><Form form={form} layout="vertical" initialValues={{ question: initialQuestion }} onFinish={send}><Form.Item name="question" label={<span className="sr-only">咨询问题</span>} rules={[{ required: true, message: "请填写需要咨询的问题" }]}><Input.TextArea rows={2} placeholder="输入专利、商标或案例问题" aria-label="咨询问题" /></Form.Item><div className="consultation-composer-actions"><Button className="consultation-submit" type="primary" htmlType="submit" icon={<MessageSquareText size={16} aria-hidden="true" />} loading={submit.isPending} disabled={submit.isPending}>发送</Button></div></Form></section>
  </main></div>
  <Drawer className="detail-drawer consultation-source-drawer" title={selectedSource ? `来源 ${selectedSource.sourceId}` : "来源定位"} open={Boolean(selectedSource)} onClose={() => { setSelectedSource(undefined); sourceLookup.reset(); }} size="min(92vw, 720px)" destroyOnHidden><RequestError error={sourceLookup.error} retry={() => selectedSource && sourceLookup.mutate(selectedSource)} />{sourceLookup.isPending && <Typography.Text role="status">正在重新检查当前权限和来源版本...</Typography.Text>}{sourceLookup.data && <Descriptions bordered size="small" column={1} items={[{ key: "source", label: "来源编号", children: sourceLookup.data.sourceId }, { key: "version", label: "来源版本", children: sourceLookup.data.version }, { key: "location", label: "定位", children: sourceLookup.data.location }]} />}</Drawer></>;
}
