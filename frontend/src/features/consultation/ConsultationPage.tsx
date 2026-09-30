import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Alert, Button, Collapse, Descriptions, Drawer, Form, Input, Popover, Space, Spin, Tooltip, Typography } from "antd";
import { ChevronDown, ExternalLink, FileSearch, MessageCirclePlus, MessageSquareText, RotateCcw, ShieldCheck } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { apiRequest } from "../../shared/api/client";
import { RequestError, StatusTag, requesterHeaders } from "../../shared/ui/Workbench";
import { FlowHandoff } from "./FlowHandoff";
import { loadConsultationFlow } from "./consultationFlowState";
import DailyConsultationChat from "./DailyConsultationChat";

type Candidate = { sourceId: string; version: string; location: string; matchBasis: string };
type SourceLink = { sourceLinkId: string; sourceId: string; version: string; location: string };
type Consultation = { consultationId: string; answerId: string; answerVersion: number; outcomeType: string; issues: string[]; candidates: Candidate[]; sourceLinks: SourceLink[]; content: string; limitation: string };
type SourceDetail = { sourceLinkId: string; sourceId: string; version: string; location: string };
type Guidance = { guideId: string; title: string; question: string; scenarioId: string; relatedQuestionIds: string[]; status: string; version: string };
type GuidanceResult = { version: string; status: string; guides: Guidance[]; limitation: string };
type ConversationTurn = { id: string; question: string; state: "pending" | "succeeded" | "failed"; answer?: Consultation; error?: unknown };

function supplementSuggestions(question: string, outcome?: Consultation) {
  const normalized = question.replace(/\s/g, "");
  if (!normalized || (outcome && outcome.outcomeType !== "NEEDS_CLARIFICATION" && normalized.length >= 16 && !outcome.issues.length)) return [];
  const suggestions = [];
  if (normalized.length < 16 || outcome?.issues.some((issue) => /技术|对象|方案/.test(issue))) suggestions.push("补充技术对象或方案名称");
  if (!/[0-9]|材料|工艺|结构|参数|步骤|权利要求/.test(normalized) || outcome?.issues.some((issue) => /条件|参数|事实/.test(issue))) suggestions.push("补充关键条件或参数");
  if (!/核对|检索|比较|风险|分析|判断/.test(normalized) || outcome?.issues.some((issue) => /目的|方向/.test(issue))) suggestions.push("说明希望核对的内容");
  return suggestions.slice(0, 3);
}

function ConsultationEmpty({ title = "等待本次咨询", description = "提交问题后，将在这里查看回答状态、依据候选与可核对的来源定位。" }: { title?: string; description?: string }) {
  return <div className="consultation-empty-state">
    <span className="empty-state-icon"><FileSearch size={24} /></span>
    <Typography.Title level={4}>{title}</Typography.Title>
    <Typography.Paragraph type="secondary">{description}</Typography.Paragraph>
    <div className="empty-state-steps" aria-label="咨询处理阶段"><span>问题</span><i aria-hidden="true" /><span>候选</span><i aria-hidden="true" /><span>回答</span></div>
  </div>;
}

function ProcessingState({ label, detail }: { label: string; detail: string }) {
  return <div className="consultation-processing" role="status" aria-live="polite">
    <Spin size="small" /><div><Typography.Text strong>{label}</Typography.Text><Typography.Paragraph type="secondary">{detail}</Typography.Paragraph></div>
  </div>;
}

function CoreConsultation({ flowId, initialQuestion }: { flowId?: string | null; initialQuestion?: string }) {
  const [form] = Form.useForm();
  const [outcome, setOutcome] = useState<Consultation>();
  const [selectedSource, setSelectedSource] = useState<{ consultationId: string; link: SourceLink }>();
  const [guidanceOpen, setGuidanceOpen] = useState(false);
  const [turns, setTurns] = useState<ConversationTurn[]>([]);
  const [lastAction, setLastAction] = useState<{ kind: "consultation"; body: Record<string, unknown>; turnId: string } | { kind: "supplement"; consultationId: string; content: string; turnId: string }>();
  const submit = useMutation({ mutationFn: (body: any) => apiRequest<Consultation>("/api/v0/consultations", { method: "POST", headers: requesterHeaders(true), body: JSON.stringify(body) }) });
  const supplement = useMutation({ mutationFn: ({ consultationId, content }: { consultationId: string; content: string }) => apiRequest<Consultation>(`/api/v0/consultations/${consultationId}/inputs`, { method: "POST", headers: requesterHeaders(true), body: JSON.stringify({ content }) }) });
  const sourceLookup = useMutation({ mutationFn: ({ consultationId, sourceLinkId }: { consultationId: string; sourceLinkId: string }) => apiRequest<SourceDetail>(`/api/v0/consultations/${consultationId}/source-links/${sourceLinkId}`, { headers: requesterHeaders() }) });
  const guidance = useQuery({ queryKey: ["consultation-guides"], queryFn: () => apiRequest<GuidanceResult>("/api/v0/consultation-guides?language=zh-CN&limit=6", { headers: requesterHeaders() }) });
  const isWorking = submit.isPending || supplement.isPending;
  const applyAnswer = (turnId: string, result: Consultation) => {
    setOutcome(result);
    setTurns((current) => current.map((turn) => turn.id === turnId ? { ...turn, state: "succeeded", answer: result, error: undefined } : turn));
    form.resetFields(["question"]);
  };
  const applyError = (turnId: string, error: unknown) => setTurns((current) => current.map((turn) => turn.id === turnId ? { ...turn, state: "failed", error } : turn));
  const runInitial = (turnId: string, body: Record<string, unknown>) => submit.mutate(body, { onSuccess: (result) => applyAnswer(turnId, result), onError: (error) => applyError(turnId, error) });
  const runSupplement = (turnId: string, consultationId: string, content: string) => supplement.mutate({ consultationId, content }, { onSuccess: (result) => applyAnswer(turnId, result), onError: (error) => applyError(turnId, error) });
  const startConsultation = ({ question }: { question: string }) => {
    const body = { question };
    submit.reset(); supplement.reset();
    const turnId = crypto.randomUUID();
    setLastAction({ kind: "consultation", body, turnId });
    setTurns((current) => [...current, { id: turnId, question, state: "pending" }]);
    runInitial(turnId, body);
  };
  const addSupplement = ({ question }: { question: string }) => {
    if (!outcome) return;
    submit.reset(); supplement.reset();
    const turnId = crypto.randomUUID();
    setLastAction({ kind: "supplement", consultationId: outcome.consultationId, content: question, turnId });
    setTurns((current) => [...current, { id: turnId, question, state: "pending" }]);
    runSupplement(turnId, outcome.consultationId, question);
  };
  const retry = () => {
    if (!lastAction) return;
    setTurns((current) => current.map((turn) => turn.id === lastAction.turnId ? { ...turn, state: "pending", error: undefined } : turn));
    if (lastAction.kind === "supplement") runSupplement(lastAction.turnId, lastAction.consultationId, lastAction.content);
    else runInitial(lastAction.turnId, lastAction.body);
  };
  const startNewConversation = () => {
    submit.reset(); supplement.reset(); sourceLookup.reset();
    setOutcome(undefined); setTurns([]); setLastAction(undefined); setSelectedSource(undefined); setGuidanceOpen(false);
    form.resetFields();
  };
  const openSource = (consultationId: string, link: SourceLink) => {
    setSelectedSource({ consultationId, link });
    sourceLookup.reset();
    sourceLookup.mutate({ consultationId, sourceLinkId: link.sourceLinkId });
  };
  const addSuggestionToDraft = (suggestion: string) => {
    const currentDraft = String(form.getFieldValue("question") ?? "").trim();
    form.setFieldValue("question", currentDraft ? `${currentDraft}；${suggestion}` : suggestion);
  };

  const guidanceContent = <div className="guidance-popover-content">
    {guidance.isLoading && <Typography.Text type="secondary">正在读取问题示例...</Typography.Text>}
    <RequestError error={guidance.error} retry={() => guidance.refetch()} />
    {guidance.data && <div className="guidance-list">{guidance.data.guides.map((item) => <button key={item.guideId} type="button" className="guidance-item" onClick={() => { form.setFieldValue("question", item.question); setGuidanceOpen(false); }} title="填入咨询问题，不会自动发送">
      <span>{item.title}</span><small>{item.question}</small>
    </button>)}</div>}
  </div>;

  return <><FlowHandoff flowId={flowId} step="consultation-answer" status={outcome?.outcomeType} reference={outcome?.answerId} /><div className="consultation-chat-layout">
    <main className="consultation-transcript" aria-busy={isWorking} aria-label="咨询对话">
      <div className="consultation-session-actions">
        {!!turns.length && <Button className="consultation-new-session" type="text" icon={<RotateCcw size={15} aria-hidden="true" />} onClick={startNewConversation}>新建咨询</Button>}
        <Popover overlayClassName="consultation-guidance-popover" placement="bottomRight" trigger="click" open={guidanceOpen} onOpenChange={setGuidanceOpen} title="问题示例" content={guidanceContent}>
          <Button className="consultation-guidance-trigger" type="text" icon={<ChevronDown size={16} aria-hidden="true" />}>问题示例</Button>
        </Popover>
      </div>
      <div className="consultation-messages">
      <article className="chat-message assistant-message chat-welcome"><Typography.Paragraph>输入问题即可开始查询。</Typography.Paragraph></article>
      {turns.map((turn) => <div className="conversation-turn" key={turn.id}>
        <article className="chat-message user-message" aria-label="你的消息"><Typography.Paragraph>{turn.question}</Typography.Paragraph></article>
        {turn.answer && <article data-outcome={turn.answer.outcomeType} className="chat-message assistant-message reply-message">
          <div className="reply-status"><StatusTag value={turn.answer.outcomeType} /><Typography.Text type="secondary">回答版本 {turn.answer.answerVersion}</Typography.Text></div>
          <Typography.Paragraph className="consultation-answer">{turn.answer.content}</Typography.Paragraph>
          {!!turn.answer.issues.length && <Alert className="reply-issues" type="warning" showIcon title="需要核对或补充" description={turn.answer.issues.join("；")} />}
          {turn.answer.limitation && <Typography.Paragraph className="reply-limitation" type="secondary">{turn.answer.limitation}</Typography.Paragraph>}
          {supplementSuggestions(turn.question, turn.answer).length > 0 && <section className="reply-suggestions" aria-label="可选补充建议"><Typography.Text type="secondary">如需更聚焦，可补充：</Typography.Text><Space wrap>{supplementSuggestions(turn.question, turn.answer).map((suggestion) => <span className="suggestion-chip" key={suggestion}><Button type="text" className="suggestion-send" onClick={() => addSupplement({ question: suggestion })}>{suggestion}</Button><Tooltip title="添加到输入框"><Button type="text" className="suggestion-add" aria-label={`添加“${suggestion}”到输入框`} icon={<MessageCirclePlus size={16} aria-hidden="true" />} onClick={() => addSuggestionToDraft(suggestion)} /></Tooltip></span>)}</Space></section>}
          {!!turn.answer.candidates.length && <Collapse className="reply-evidence" ghost items={[{ key: "evidence", label: `查看 ${turn.answer.candidates.length} 条依据候选`, children: <div className="candidate-grid">{turn.answer.candidates.map((item) => <article className="candidate-card candidate" key={`${item.sourceId}-${item.version}`}><div className="candidate-evidence-head"><div><Typography.Title level={4}>专利文献 {item.sourceId}</Typography.Title><Typography.Text type="secondary">版本 {item.version} · {item.location}</Typography.Text></div><ShieldCheck size={18} aria-hidden="true" /></div><Typography.Paragraph>{item.matchBasis}</Typography.Paragraph></article>)}</div> }]} />}
          {!!turn.answer.sourceLinks.length && <Collapse className="reply-evidence reply-sources" ghost items={[{ key: "sources", label: `查看 ${turn.answer.sourceLinks.length} 个来源`, children: <div className="source-actions">{turn.answer.sourceLinks.map((item) => <button className="source-open-button" type="button" key={item.sourceLinkId} onClick={() => openSource(turn.answer!.consultationId, item)}><ExternalLink size={14} aria-hidden="true" /><span><strong>{item.sourceId}</strong><small>版本 {item.version} · {item.location}</small></span></button>)}</div> }]} />}
        </article>}
        {turn.state === "pending" && <article className="chat-message assistant-message"><ProcessingState label="正在查询" detail="正在整理候选信息。" /></article>}
        {turn.state === "failed" && <div className="turn-error" role="status"><RequestError error={turn.error} retry={lastAction?.turnId === turn.id ? retry : undefined} /></div>}
      </div>)}
      {!turns.length && !isWorking && <ConsultationEmpty title="从一条问题开始" description="查询结果会显示在这里。" />}
      </div>
      <section className="consultation-composer" aria-labelledby="consultation-composer-title">
        <Typography.Title id="consultation-composer-title" level={4} className="sr-only">咨询输入</Typography.Title>
        <Form form={form} layout="vertical" validateTrigger={["onBlur", "onSubmit"]} initialValues={{ question: initialQuestion || "" }} onFinish={(values) => outcome ? addSupplement({ question: String(values.question ?? "") }) : startConsultation({ question: String(values.question ?? "") })}>
          <Form.Item name="question" label={<span className="sr-only">咨询问题</span>} rules={[{ required: true, message: "请先填写需要检索或核对的问题" }]}><Input.TextArea rows={2} placeholder="输入问题或补充信息" aria-label="咨询问题" /></Form.Item>
          <div className="consultation-composer-actions"><Button className="consultation-submit" type="primary" htmlType="submit" icon={<MessageSquareText size={16} />} loading={isWorking} disabled={isWorking}>{isWorking ? "正在查询" : "发送"}</Button></div>
        </Form>
      </section>
    </main>
  </div><Drawer className="detail-drawer consultation-source-drawer" title={selectedSource ? `来源 ${selectedSource.link.sourceId}` : "来源定位"} open={Boolean(selectedSource)} onClose={() => { setSelectedSource(undefined); sourceLookup.reset(); }} size="min(92vw, 720px)" destroyOnHidden>
    {sourceLookup.isPending && <ProcessingState label="正在核对来源" detail="正在重新检查当前访问权限与来源版本。" />}
    <RequestError error={sourceLookup.error} retry={() => selectedSource && sourceLookup.mutate({ consultationId: selectedSource.consultationId, sourceLinkId: selectedSource.link.sourceLinkId })} />
    {sourceLookup.data && <div className="source-detail"><Descriptions bordered size="small" column={1} items={[
      { key: "source", label: "来源编号", children: sourceLookup.data.sourceId },
      { key: "version", label: "来源版本", children: sourceLookup.data.version },
      { key: "location", label: "定位", children: sourceLookup.data.location },
      { key: "link", label: "引用编号", children: sourceLookup.data.sourceLinkId }
    ]} /><Alert type="info" showIcon title="已按当前权限重新核对" description="该定位来自本次回答保存的来源版本，仅用于技术参考。" /></div>}
  </Drawer></>;
}

export default function ConsultationPage() {
  const [params] = useSearchParams();
  const flowId = params.get("flowId");
  const flow = loadConsultationFlow(flowId);
  return <section className="workbench" id="consultation-workspace">
    {flowId ? <CoreConsultation flowId={flowId} initialQuestion={flow?.question} /> : <DailyConsultationChat />}
  </section>;
}
