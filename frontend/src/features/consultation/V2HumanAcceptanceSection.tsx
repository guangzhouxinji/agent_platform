import { useEffect, useState } from "react";
import { Alert, Button, Empty, Tag } from "antd";
import { Check, CornerDownLeft, Eye, RefreshCw } from "lucide-react";
import { newKey, taskPath, v2Request, V2DevError,
  type AcceptedHumanRequest, type PendingHumanRequest, type OwnedHumanRequest,
  type HumanOpinion, type HumanReturn, type HumanContext } from "./v2DevApi";

type Props = { token: string; onUnauthorized: () => void };
const queuePath = "/v2/consultation/human-requests/pending";
type Attempt = { item: PendingHumanRequest; key: string };
type OpinionAttempt = { item: OwnedHumanRequest; key: string };

export default function V2HumanAcceptanceSection({ token, onUnauthorized }: Props) {
  const [items, setItems] = useState<PendingHumanRequest[]>();
  const [mine, setMine] = useState<OwnedHumanRequest[]>();
  const [accepted, setAccepted] = useState<AcceptedHumanRequest>();
  const [opinion, setOpinion] = useState<HumanOpinion>();
  const [returned, setReturned] = useState<HumanReturn>();
  const [context, setContext] = useState<HumanContext>();
  const [contextError, setContextError] = useState("");
  const [attempt, setAttempt] = useState<Attempt>();
  const [opinionAttempt, setOpinionAttempt] = useState<OpinionAttempt>();
  const [returnAttempt, setReturnAttempt] = useState<OpinionAttempt>();
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [mineError, setMineError] = useState("");
  const [returnError, setReturnError] = useState("");

  useEffect(() => { void refresh(); }, []);

  async function refresh() {
    setBusy("refresh"); setError(""); setMineError(""); setContext(undefined); setContextError("");
    try { setItems(await v2Request<PendingHumanRequest[]>(token, queuePath)); }
    catch (failure) {
      setItems(undefined);
      if (failure instanceof V2DevError && failure.status === 401) { onUnauthorized(); return; }
      setError("待领队列未确认，请检查当前身份和来源后重试。");
    }
    try { setMine(await v2Request<OwnedHumanRequest[]>(token, "/v2/consultation/human-requests/mine")); }
    catch (failure) {
      setMine(undefined);
      if (failure instanceof V2DevError && failure.status === 401) { onUnauthorized(); return; }
      setMineError("已领请求未确认，请检查当前身份和来源后重试。");
    } finally { setBusy(""); }
  }

  async function accept(next: Attempt) {
    setBusy(next.item.requestId); setError(""); setAttempt(next);
    try {
      const item = next.item;
      const result = await v2Request<AcceptedHumanRequest>(token,
        taskPath(item.taskId) + `/human-requests/${item.requestSequence}/accept`,
        { expectedTaskRevision: item.inputRevision, expectedRequestSequence: item.requestSequence,
          requestId: item.requestId }, next.key);
      setAccepted(result); setAttempt(undefined);
      await refresh();
      if (error) setError("领取已提交，待领队列未确认。请刷新。");
    } catch (failure) {
      if (failure instanceof V2DevError && failure.status === 401) { onUnauthorized(); return; }
      setError(failure instanceof V2DevError && failure.status === 409
        ? "请求包已变化或已由他人领取，请刷新队列。" : "领取结果未确认，可用原请求键重试。");
    } finally { setBusy(""); }
  }

  async function submit(next: OpinionAttempt) {
    setBusy(next.item.requestId); setMineError(""); setContext(undefined); setOpinionAttempt(next);
    try {
      const item = next.item;
      const result = await v2Request<HumanOpinion>(token,
        taskPath(item.taskId) + `/human-requests/${item.requestSequence}/opinion`,
        { expectedTaskRevision: item.inputRevision, expectedRequestSequence: item.requestSequence,
          requestId: item.requestId, opinionInputId: "OPINION_GAP" }, next.key);
      setOpinion(result); setOpinionAttempt(undefined);
      try { setMine(await v2Request<OwnedHumanRequest[]>(token, "/v2/consultation/human-requests/mine")); }
      catch (failure) {
        setMine(undefined);
        if (failure instanceof V2DevError && failure.status === 401) { onUnauthorized(); return; }
        setMineError("意见已提交，已领列表未确认。请刷新。");
      }
    } catch (failure) {
      if (failure instanceof V2DevError && failure.status === 401) { onUnauthorized(); return; }
      setMineError(failure instanceof V2DevError && failure.status === 409
        ? "请求包或输入已变化，请刷新后核对。" : "意见结果未确认，可用原请求键重试。");
    } finally { setBusy(""); }
  }

  async function returnRequest(next: OpinionAttempt) {
    setBusy(next.item.requestId); setReturnError(""); setContext(undefined); setReturnAttempt(next);
    try {
      const item = next.item;
      const result = await v2Request<HumanReturn>(token,
        taskPath(item.taskId) + `/human-requests/${item.requestSequence}/return`,
        { expectedTaskRevision: item.inputRevision, expectedRequestSequence: item.requestSequence,
          requestId: item.requestId }, next.key);
      setReturned(result); setReturnAttempt(undefined);
      try { setMine(await v2Request<OwnedHumanRequest[]>(token, "/v2/consultation/human-requests/mine")); }
      catch (failure) {
        setMine(undefined);
        if (failure instanceof V2DevError && failure.status === 401) { onUnauthorized(); return; }
        setReturnError("退回已提交，已领列表未确认。请刷新。");
      }
    } catch (failure) {
      if (failure instanceof V2DevError && failure.status === 401) { onUnauthorized(); return; }
      setReturnError(failure instanceof V2DevError && failure.status === 409
        ? "请求包或输入已变化，请刷新后核对。" : "退回结果未确认，可用原请求键重试。");
    } finally { setBusy(""); }
  }

  async function readContext(item: OwnedHumanRequest) {
    setBusy(item.requestId); setContext(undefined); setContextError("");
    try {
      const result = await v2Request<HumanContext>(token,
        taskPath(item.taskId) + `/human-requests/${item.requestSequence}/context`);
      if (result.requestId !== item.requestId || result.inputRevision !== item.inputRevision) {
        setContextError("请求包已变化，请刷新后重新核对。"); return;
      }
      setContext(result);
    } catch (failure) {
      if (failure instanceof V2DevError && failure.status === 401) { onUnauthorized(); return; }
      setContextError("上下文未获准读取，请检查当前包、身份和来源后重试。");
    } finally { setBusy(""); }
  }

  return <section className="v2-dev-section v2-dev-inbox" aria-label="待领请求">
    <div className="v2-dev-section-head"><h2>待领请求</h2>
      <Button icon={<RefreshCw size={16} aria-hidden="true" />} onClick={refresh}
        disabled={!!busy} loading={busy === "refresh"}>刷新</Button></div>
    {accepted && <Alert type="success" showIcon title="本次请求已领取"
      description={`${accepted.acceptedBy} · ${new Date(accepted.acceptedAt).toLocaleString("zh-CN")}`} />}
    {error && <Alert className="v2-dev-error" type="warning" showIcon title={error}
      action={attempt && !error.includes("已变化") ? <Button size="small" disabled={!!busy}
        onClick={() => accept(attempt)}>重试领取</Button> : undefined} />}
    {items?.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有待领请求" />}
    {items?.map((item) => <div className="v2-dev-inbox-row" key={item.requestId}>
      <div><Tag color="processing">请求 {item.requestSequence}</Tag>
        <code>{item.taskId}</code><p>输入版本 {item.inputRevision} ·
          {new Date(item.createdAt).toLocaleString("zh-CN")}</p></div>
      <Button icon={<Check size={16} aria-hidden="true" />} disabled={!!busy}
        loading={busy === item.requestId} onClick={() => accept({ item, key: newKey() })}>领取</Button>
    </div>)}
    <div className="v2-dev-section-head v2-dev-inbox-subhead"><h2>我已领取</h2></div>
    {opinion && <Alert type="success" showIcon title="本次内部意见已提交"
      description={`${opinion.submittedBy} · ${new Date(opinion.submittedAt).toLocaleString("zh-CN")}`} />}
    {returned && <Alert type="success" showIcon title="本次请求已退回"
      description={`${returned.returnedBy} · ${new Date(returned.returnedAt).toLocaleString("zh-CN")}`} />}
    {mineError && <Alert className="v2-dev-error" type="warning" showIcon title={mineError}
      action={opinionAttempt && !mineError.includes("已变化") ? <Button size="small" disabled={!!busy}
        onClick={() => submit(opinionAttempt)}>重试意见</Button> : undefined} />}
    {returnError && <Alert className="v2-dev-error" type="warning" showIcon title={returnError}
      action={returnAttempt && !returnError.includes("已变化") ? <Button size="small" disabled={!!busy}
        onClick={() => returnRequest(returnAttempt)}>重试退回</Button> : undefined} />}
    {contextError && <Alert className="v2-dev-error" type="warning" showIcon title={contextError} />}
    {mine?.length === 0 && <p className="v2-dev-muted">当前没有待处理的已领请求</p>}
    {mine?.map((item) => <div className="v2-dev-inbox-row" key={item.requestId}>
      <div><Tag color="processing">已领请求 {item.requestSequence}</Tag><code>{item.taskId}</code>
        <p>输入版本 {item.inputRevision} · {new Date(item.acceptedAt).toLocaleString("zh-CN")}</p></div>
      <div className="v2-dev-inbox-actions">
        <Button icon={<Eye size={16} aria-hidden="true" />} disabled={!!busy}
          loading={busy === item.requestId} onClick={() => readContext(item)}>查看上下文</Button>
        <Button icon={<CornerDownLeft size={16} aria-hidden="true" />} disabled={!!busy}
          loading={busy === item.requestId} onClick={() => returnRequest({ item, key: newKey() })}>退回</Button>
        <Button icon={<Check size={16} aria-hidden="true" />} disabled={!!busy}
          loading={busy === item.requestId} onClick={() => submit({ item, key: newKey() })}>提交内部意见</Button>
      </div>
    </div>)}
    {context && <div className="v2-dev-human-context" aria-label="已领包上下文">
      <h3>请求 {context.requestSequence} · 输入版本 {context.inputRevision}</h3>
      {context.messages.map((message) => <div key={message.revision}>
        <strong>{message.kind === "GOAL" ? "目标" : "补充"} · {message.revision}</strong>
        <p>{message.text}</p>
      </div>)}
      <div><strong>请求内容</strong><p>{context.requestText}</p></div>
    </div>}
  </section>;
}
