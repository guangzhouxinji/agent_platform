import { useRef, useState } from "react";
import { Alert, Button, Empty, Input, Popconfirm, Space, Tag, Typography } from "antd";
import { Ban, Check, CircleHelp, RefreshCw, Search, ShieldAlert } from "lucide-react";
import { useSearchParams } from "react-router-dom";
import { FEATURE_MANIFEST, GOAL, SUPPLEMENT, newKey, taskPath, v2Request,
  type Confirmation, type HumanOpinion, type HumanReturn, type HumanRequestCurrent, type Preview, type ResultSet,
  type PurposeSelection, type Task, type WorkState } from "./v2DevApi";
import V2HumanRequestSection from "./V2HumanRequestSection";
import V2HumanAcceptanceSection from "./V2HumanAcceptanceSection";
import { correctionPayload } from "./V2CorrectionSection";
import V2TaskInputSection from "./V2TaskInputSection";
import V2VerificationSection from "./V2VerificationSection";
import V2PurposeSection from "./V2PurposeSection";
import { assertSameTask } from "./v2Verification";
import errorText from "./v2DevErrorText";
import { loadHumanOpinion } from "./v2HumanOpinionHistory";
import { loadHumanReturn } from "./v2HumanReturnHistory";
import { exactRead } from "./v2ExactRead";
import "../../styles/v2-dev-consultation.css";
type Snapshot = { task: Task; work: WorkState; result?: ResultSet; focused?: ResultSet; confirmation?: Confirmation;
  purpose?: PurposeSelection; purposeError?: string;
  human?: HumanRequestCurrent; humanError?: string;
  opinion?: HumanOpinion; opinionError?: string; opinionMissing?: boolean;
  returned?: HumanReturn; returnError?: string; returnMissing?: boolean; resultError?: string;
  focusError?: string; featureError?: string };
type Command = () => Promise<void>;
const hasBearerToken = (value: string) => /^Bearer\s+\S+$/.test(value);
export default function V2DevConsultationPage() {
  const [params, setParams] = useSearchParams(); const taskId = params.get("task") ?? "";
  const [draftId, setDraftId] = useState(taskId);
  const [credential, setCredential] = useState("");
  const [token, setToken] = useState("");
  const [showInbox, setShowInbox] = useState(false);
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const [preview, setPreview] = useState<Preview>();
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [retry, setRetry] = useState<Command>();
  const requestId = useRef(0);
  const disconnect = () => {
    requestId.current++; setToken(""); setCredential(""); setSnapshot(undefined); setPreview(undefined); setShowInbox(false);
  };
  async function load(id: string, auth: string) {
    const current = ++requestId.current;
    setSnapshot(undefined); setPreview(undefined); const base = taskPath(id);
    const task = await v2Request<Task>(auth, base);
    const work = await v2Request<WorkState>(auth, base + "/current-work-state");
    assertSameTask(task, work);
    const exact = <T,>(path: string) => exactRead<T>(auth, path);
    const [result, focused, feature, human, purpose] = await Promise.all([
      work.resultSet ? exact<ResultSet>(base + "/text-searches/" + work.resultSet.setId) : null,
      work.focus && work.focus.setId !== work.resultSet?.setId
        ? exact<ResultSet>(base + "/text-searches/" + work.focus.setId) : null,
      work.feature ? exact<Confirmation>(base + "/feature-confirmations/" + work.featureRevision) : null,
      exact<HumanRequestCurrent>(base + "/human-requests/current"),
      exact<PurposeSelection>(base + "/analysis-purposes/current")
    ]);
    const [opinionHistory, returnHistory] = await Promise.all([loadHumanOpinion(auth, id, human.value), loadHumanReturn(auth, id, human.value)]);
    if (current === requestId.current) setSnapshot({ task, work, result: result?.value,
      focused: focused?.value, confirmation: feature?.value, purpose: purpose.value,
      purposeError: purpose.error, human: human.value, humanError: human.error,
      ...opinionHistory, ...returnHistory, resultError: result?.error, focusError: focused?.error, featureError: feature?.error });
  }
  async function perform(label: string, operation: Command, repeatable = true) {
    if (busy) return;
    setBusy(label); setError(""); setRetry(undefined);
    try { await operation(); }
    catch (failure) { setError(errorText(failure)); if (repeatable) setRetry(() => operation); }
    finally { setBusy(""); }
  }
  const connect = () => {
    const auth = credential.trim();
    if (!hasBearerToken(auth) || !draftId.trim()) {
      setError("请输入开发身份凭据和任务编号。新任务请使用下方创建入口。"); return;
    }
    setToken(auth); setShowInbox(false); perform("restore", () => load(draftId.trim(), auth));
  };
  const create = () => {
    const auth = credential.trim() || token;
    if (!hasBearerToken(auth)) { setError("请输入开发身份凭据。"); return; }
    const key = newKey();
    setToken(auth); setShowInbox(false); perform("create", async () => {
      const created = await v2Request<{ taskId: string }>(auth, "/v2/consultation/tasks", { goal: GOAL }, key);
      setParams({ task: created.taskId }); setDraftId(created.taskId); await load(created.taskId, auth);
    });
  };
  const command = (label: string, path: string, body: object) => {
    const key = newKey();
    perform(label, async () => { await v2Request(token, path, body, key); await load(taskId, token); });
  };
  const refresh = () => perform("refresh", () => load(taskId, token), false);
  const work = snapshot?.work; const active = snapshot?.task.state === "ACTIVE";
  const hasSupplement = snapshot?.task.messages.some((message) => message.body === SUPPLEMENT);
  const correction = snapshot?.task.corrections?.[0];
  const focusMember = work?.focus && (work.focus.setId === snapshot?.result?.setId
    ? snapshot.result : snapshot?.focused)?.members.find((item) => item.ordinal === work.focus?.ordinal);
  const previewReady = active && work?.focus?.currentInput && work.feature?.currentInput
    && work.feature.manifestId === FEATURE_MANIFEST && Boolean(focusMember?.available);
  return <main className="v2-dev-workbench">
    <header className="v2-dev-header"><div><span className="v2-dev-eyebrow">开发任务 · SYNTHETIC</span>
      <Typography.Title level={2}>专利技术参考</Typography.Title></div>
      <Tag color="processing">V2 开发验证</Tag></header>
    <section className="v2-dev-connection" aria-label="任务连接">
      <div className="v2-dev-connection-grid">
        <label>开发身份凭据<Input.Password value={credential} onChange={(event) => {
          if (token) { requestId.current++; setToken(""); setShowInbox(false);
            setSnapshot(undefined); setPreview(undefined); }
          setCredential(event.target.value);
        }}
          placeholder="Bearer xj_human-a.…" autoComplete="off" aria-label="开发身份凭据" /></label>
        <label>任务编号<Input value={draftId} onChange={(event) => setDraftId(event.target.value)} placeholder="任务 UUID" aria-label="任务编号" /></label>
        <Space wrap><Button onClick={connect} disabled={!!busy || !draftId.trim()} loading={busy === "restore"}>恢复任务</Button>
          <Button type="primary" onClick={create} disabled={!!busy} loading={busy === "create"}>创建合成任务</Button>
          <Button onClick={() => {
            const auth = credential.trim();
            if (!hasBearerToken(auth)) { setError("请输入开发身份凭据。"); return; }
            requestId.current++; setSnapshot(undefined); setPreview(undefined);
            setShowInbox(false); setToken(auth); setError(""); setShowInbox(true);
          }} disabled={!!busy}>查看待领请求</Button></Space>
      </div>
      {taskId && token && <div className="v2-dev-session"><code>{taskId}</code>
        <Button type="text" icon={<RefreshCw size={16} aria-hidden="true" />} onClick={refresh}
          disabled={!!busy} loading={busy === "refresh"}>刷新</Button>
        <Button type="text" icon={<Ban size={16} aria-hidden="true" />} onClick={disconnect}>断开</Button></div>}
      {error && <Alert className="v2-dev-error" type="error" showIcon title={error} action={retry
        ? <Button size="small" onClick={() => perform("retry", retry)}>重试提交</Button> : undefined} />}
      {busy && <Typography.Text role="status" type="secondary">正在{busy === "restore" ? "恢复" : "处理"}任务…</Typography.Text>}
    </section>
    {token && showInbox && <V2HumanAcceptanceSection key={token} token={token} onUnauthorized={disconnect} />}
    {!snapshot && !busy && <Empty className="v2-dev-empty" description="尚未连接任务" />}
    {snapshot && <>
      <div className="v2-dev-status"><Tag color={active ? "success" : "default"}>{active ? "进行中" : "已取消"}</Tag>
        <span>任务版本 {snapshot.task.revision}</span><span>到期 {new Date(snapshot.task.expiresAt).toLocaleString("zh-CN")}</span></div>
      <div className="v2-dev-columns">
        <div className="v2-dev-main">
          <V2TaskInputSection task={snapshot.task} active={active} busy={!!busy}
            onAppend={() => command("append", taskPath(taskId) + "/messages",
              { message: SUPPLEMENT, expectedVersion: snapshot.task.revision })}
            onCorrect={() => command("correct", taskPath(taskId) + "/corrections", correctionPayload)} />
          <section className="v2-dev-section"><div className="v2-dev-section-head"><h2>候选结果</h2><span>结果序号 {work?.resultSequence ?? 0}</span></div>
            {snapshot.resultError && <Alert type="warning" showIcon title={snapshot.resultError} />}
            {snapshot.result && <div className="v2-dev-result-list">{snapshot.result.members.map((item) =>
              <article className="v2-dev-result" key={item.ordinal}>
                <div className="v2-dev-result-head"><strong>{item.ordinal}. {item.recordId}</strong><span>版本 {item.version} · {item.locator}</span></div>
                {item.available ? <p>{item.claimText}</p> : <Alert type="warning" showIcon title="当前来源不可用" />}
                {active && work?.resultSet?.currentInput && item.available && <Button size="small" disabled={!!busy}
                  onClick={() => command("focus", taskPath(taskId) + "/focus",
                    { setId: snapshot.result!.setId, ordinal: item.ordinal,
                      expectedTaskRevision: snapshot.task.revision, expectedFocusSequence: work.focusSequence })}>
                  选为焦点</Button>}
              </article>)}</div>}
            {!snapshot.result && !snapshot.resultError && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无结果集" />}
            {active && !correction && <Button icon={<Search size={16} aria-hidden="true" />} disabled={!!busy} onClick={() =>
              command("search", taskPath(taskId) + "/text-searches", { expectedTaskRevision: snapshot.task.revision,
                expectedResultSequence: work!.resultSequence })}>生成合成候选</Button>}
          </section>
        </div>
        <div className="v2-dev-side">
          <section className="v2-dev-section"><div className="v2-dev-section-head"><h2>当前焦点</h2><span>序号 {work?.focusSequence ?? 0}</span></div>
            {work?.focus ? <><p>集合 {work.focus.setId} · 第 {work.focus.ordinal} 项</p>
              <Tag color={work.focus.currentInput ? "success" : "warning"}>{work.focus.currentInput ? "当前输入" : "历史输入"}</Tag>
              {snapshot.focusError && <Alert type="warning" showIcon title={snapshot.focusError} />}
              {focusMember?.available && <p className="v2-dev-quote">{focusMember.claimText}</p>}
              {!focusMember?.available && !snapshot.focusError && <p className="v2-dev-muted">来源需重新核对</p>}</>
              : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="未选择焦点" />}
          </section>
          <section className="v2-dev-section"><div className="v2-dev-section-head"><h2>特征确认</h2><span>版本 {work?.featureRevision ?? 0}</span></div>
            {snapshot.featureError && <Alert type="warning" showIcon title={snapshot.featureError} />}
            {snapshot.confirmation && <><Tag color={work?.feature?.currentInput ? "success" : "warning"}>
              {work?.feature?.currentInput ? "当前输入" : "历史输入"}</Tag>
              <ol className="v2-dev-features">{snapshot.confirmation.candidates.map((item) =>
                <li key={item.id}><strong>{item.id}</strong> {item.text}</li>)}</ol></>}
            {!snapshot.confirmation && !snapshot.featureError && <p className="v2-dev-muted">尚无具名确认</p>}
            {active && hasSupplement && !correction && <><p className="v2-dev-quote">温度传感器 · 自动控制器 · 阀门</p>
              {work?.feature?.currentInput && work.feature.manifestId === FEATURE_MANIFEST
                ? <Tag color="success">本输入已确认</Tag>
                : <Button icon={<Check size={16} aria-hidden="true" />} disabled={!!busy} onClick={() =>
                command("confirm", taskPath(taskId) + "/feature-confirmations", {
                  expectedTaskRevision: snapshot.task.revision, expectedFeatureRevision: work!.featureRevision,
                  candidateManifestId: FEATURE_MANIFEST, featureIds: ["F1", "F2", "F3"] })}>
                  确认三项原文特征</Button>}</>}
          </section>
          <section className="v2-dev-section"><div className="v2-dev-section-head"><h2>共同输入</h2>
            <CircleHelp size={17} aria-hidden="true" /></div>
            <Button disabled={!previewReady || !!busy} onClick={() => perform("preview", async () => {
              setPreview(undefined);
              setPreview(await v2Request<Preview>(token, taskPath(taskId) + "/feature-confirmations/"
                + work!.featureRevision + "/focus/" + work!.focusSequence + "/input-preview"));
            }, false)}>核对目标与权项</Button>
            {preview && <div className="v2-dev-preview"><p>{preview.targetText}</p>
              <p><strong>{preview.recordId} · {preview.sourceVersion} · {preview.locator}</strong></p><blockquote>{preview.claimText}</blockquote>
              <Alert type="info" showIcon title="尚未进行专业对应" description={preview.missing.join(" · ")} /></div>}
            {!previewReady && <p className="v2-dev-muted">需要当前输入的确切焦点、新包确认及可读权项。</p>}
          </section>
          <V2PurposeSection value={snapshot.purpose} error={snapshot.purposeError}
            ready={!!previewReady} active={active} busy={!!busy} onSelect={() => {
              if (!work?.focus || !focusMember) return;
              command("purpose", taskPath(taskId) + "/analysis-purposes", {
                expectedTaskRevision: snapshot.task.revision, featureRevision: work.featureRevision,
                focusSequence: work.focusSequence, setId: work.focus.setId, ordinal: work.focus.ordinal, analysisPurpose: "TECHNICAL_SIMILARITY"
              });
            }} />
          <V2HumanRequestSection current={snapshot.human} error={snapshot.humanError}
            opinion={snapshot.opinion} opinionError={snapshot.opinionError} opinionMissing={snapshot.opinionMissing}
            returned={snapshot.returned} returnError={snapshot.returnError} returnMissing={snapshot.returnMissing} active={active}
            busy={busy === "human-request"} disabled={!!busy} onRequest={(sequence) => command("human-request", taskPath(taskId) + "/human-requests",
              { expectedTaskRevision: snapshot.task.revision, expectedRequestSequence: sequence,
                requestInputId: "REQUEST_GAP" })} />
          {active && <Popconfirm title="取消当前任务？" description="取消后只能读取历史，不能继续提交。"
            okText="确认取消" cancelText="返回" onConfirm={() => command("cancel", taskPath(taskId) + "/cancel",
              { expectedVersion: snapshot.task.revision })}>
            <Button danger icon={<ShieldAlert size={16} aria-hidden="true" />} disabled={!!busy}>取消任务</Button>
          </Popconfirm>}
        </div>
      </div>
      <V2VerificationSection snapshot={snapshot} />
    </>}
  </main>;
}
