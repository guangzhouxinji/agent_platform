import { useEffect, useState } from "react";
import { Alert, Button, Drawer, Empty, Input, Space, Tag, Typography } from "antd";
import { ArrowRight, FileSearch, RefreshCw, Search, X } from "lucide-react";
import { ApiError } from "../../shared/api/client";
import { patentFixture, type Candidate, type FixtureView, type Source } from "./patentFixtureApi";
import "../../styles/patent-fixture.css";

const taskKey = "patent-fixture-task-id";
const outcomes: Record<string, string> = { RESULTS: "已返回样例候选", NO_RESULT: "没有样例结果", TECHNICAL_FAILURE: "样例来源故障" };
const status: Record<string, string> = { WAITING_FOR_USER_INPUT: "等待补充", IN_PROGRESS: "进行中", USER_CANCELLED: "已取消", INSUFFICIENT_EVIDENCE: "证据不足" };

export default function PatentFixturePage() {
  const [view, setView] = useState<FixtureView>();
  const [description, setDescription] = useState("");
  const [scope, setScope] = useState("样例技术资料");
  const [features, setFeatures] = useState("");
  const [query, setQuery] = useState("");
  const [newScope, setNewScope] = useState("");
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [source, setSource] = useState<Source>();
  const [sourceError, setSourceError] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const id = sessionStorage.getItem(taskKey);
    if (id) patentFixture.read(id).then(setView).catch(() => sessionStorage.removeItem(taskKey));
  }, []);

  async function execute(action: () => Promise<FixtureView>) {
    setBusy(true); setError("");
    try {
      const result = await action();
      setView(result); sessionStorage.setItem(taskKey, result.taskId);
      setSource(undefined); setSourceError("");
    } catch (reason) {
      const failure = reason as ApiError;
      setError(failure.code === "VERSION_CONFLICT" ? "任务已发生变化，请刷新任务后再操作。"
        : failure.message?.includes("duplicate retrieval") ? "同一检索词不能重复执行，请调整检索词后重试。"
        : failure.message || "操作失败，请重试。");
    } finally { setBusy(false); }
  }

  async function openSource(candidate: Candidate) {
    if (!view) return;
    setSource(undefined); setSourceError("");
    try { setSource(await patentFixture.source(view, candidate)); }
    catch (reason) { setSourceError((reason as Error).message || "来源不可用，请刷新任务。"); }
  }

  function reset() {
    sessionStorage.removeItem(taskKey); setView(undefined); setSource(undefined); setError("");
  }

  const current = view?.candidates.filter((item) => item.available) ?? [];
  const latest = current[0]?.resultVersion;
  const isCancelled = view?.taskStatus === "USER_CANCELLED";

  return <main className="patent-fixture">
    <header className="patent-fixture-heading">
      <div><span className="workspace-eyebrow">咨询 / 开发样例</span><h2>专利技术资料核对</h2></div>
      {view && <Button onClick={reset} icon={<X size={16} />} disabled={busy}>新建样例任务</Button>}
    </header>
    <Alert type="warning" showIcon message="仅供开发期操作回放：全部文献和片段均为虚构资料，不能用于专业判断或政府验收。任务只保存在当前服务进程。" />
    {error && <Alert className="patent-fixture-error" type="error" showIcon role="alert" message={error}
      action={view && <Button size="small" icon={<RefreshCw size={14} />} onClick={() => execute(() => patentFixture.read(view.taskId))}>刷新任务</Button>} />}

    {!view ? <section className="patent-fixture-section patent-fixture-start" aria-labelledby="fixture-start">
      <h3 id="fixture-start">技术方案</h3>
      <label htmlFor="fixture-description">方案描述</label>
      <Input.TextArea id="fixture-description" value={description} onChange={(event) => setDescription(event.target.value)} rows={3} placeholder="例如：温度控制装置记录读数并驱动开关" />
      <label htmlFor="fixture-scope">检索范围</label>
      <Input id="fixture-scope" value={scope} onChange={(event) => setScope(event.target.value)} />
      <label htmlFor="fixture-features">关键技术特征（每行一项，可稍后补充）</label>
      <Input.TextArea id="fixture-features" value={features} onChange={(event) => setFeatures(event.target.value)} rows={3} placeholder="测温单元&#10;控制单元" />
      <Button type="primary" loading={busy} disabled={!description.trim() || !scope.trim()} icon={<ArrowRight size={16} />}
        onClick={() => execute(() => patentFixture.start(description, scope, features.split("\n").map((item) => item.trim()).filter(Boolean)))}>创建任务</Button>
    </section> : <>
      <div className="patent-fixture-meta"><strong>{view.description}</strong><Tag>{status[view.taskStatus] ?? view.taskStatus}</Tag><span>任务版本 {view.taskVersion}</span><span>范围：{view.scope}</span></div>
      <div className="patent-fixture-grid">
        <section className="patent-fixture-section" aria-labelledby="fixture-search"><h3 id="fixture-search">1. 检索资料</h3>
          <p>关键特征</p>
          {view.features.length ? <ul className="patent-fixture-features">{view.features.map((feature) => <li key={feature.id}>
            <span>{feature.text} <small>v{feature.version}</small></span>
            <div><Input aria-label={`修订${feature.text}`} value={edits[feature.id] ?? ""} onChange={(event) => setEdits({ ...edits, [feature.id]: event.target.value })} placeholder="修订特征" />
              <Button disabled={busy || isCancelled || !edits[feature.id]?.trim()} onClick={() => { setQuery(""); execute(() => patentFixture.act(view, `corrections/features/${feature.id}`, { replacement: edits[feature.id] })); }}>修订</Button></div>
          </li>)}</ul> : <><Alert type="info" message="缺少关键特征，补充后才能检索。" />
            <Input.TextArea aria-label="补充关键特征" value={features} onChange={(event) => setFeatures(event.target.value)} placeholder="每行一项" rows={3} />
            <Button loading={busy} disabled={!features.trim()} onClick={() => execute(() => patentFixture.act(view, "features", { features: features.split("\n").map((item) => item.trim()).filter(Boolean) }))}>补充特征</Button></>}
          <div className="patent-fixture-action"><Input aria-label="样例检索词" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={view.candidates.some((item) => !item.available) ? "调整检索词后重新检索" : "输入检索词"} onPressEnter={() => { if (query.trim()) execute(() => patentFixture.act(view, "searches", { query })); }} />
            <Button type="primary" icon={<Search size={16} />} loading={busy} disabled={!view.features.length || !query.trim() || isCancelled} onClick={() => execute(() => patentFixture.act(view, "searches", { query }))}>检索</Button></div>
          {view.retrievalOutcomes.length > 0 && <p className="patent-fixture-note">最近检索：{outcomes[view.retrievalOutcomes.at(-1)!] ?? view.retrievalOutcomes.at(-1)}</p>}
          <div className="patent-fixture-action"><Input aria-label="变更检索范围" value={newScope} onChange={(event) => setNewScope(event.target.value)} placeholder="变更范围后，原结果会失效" />
            <Button disabled={busy || isCancelled || !newScope.trim() || newScope.trim() === view.scope} onClick={() => { setQuery(""); execute(() => patentFixture.act(view, "corrections/scope", { replacement: newScope })); }}>确认变更</Button></div>
        </section>
        <section className="patent-fixture-section" aria-labelledby="fixture-candidates"><h3 id="fixture-candidates">2. 阅读候选与来源</h3>
          {current.length ? <div className="patent-fixture-candidates">{current.map((item, index) => <article key={`${item.resultVersion}-${item.candidateId}`}>
            <div><strong>{item.title}</strong><Tag>结果集 v{item.resultVersion}</Tag>{view.selectedCandidateReference === `${item.resultVersion}:${item.candidateId}` && <Tag color="green">已选择</Tag>}</div>
            <p>{item.sourceId} · 文献版本 {item.documentVersion} · {item.locator}</p>
            <Space wrap><Button icon={<FileSearch size={16} />} onClick={() => openSource(item)}>核对样例出处</Button>
              {item.resultVersion === latest && <Button disabled={busy || isCancelled} onClick={() => execute(() => patentFixture.act(view, "selections", { resultVersion: item.resultVersion, ordinal: index + 1 }))}>选择此版本</Button>}</Space>
          </article>)}</div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={view.candidates.length ? "原候选已失效，请重新检索。" : "暂无候选，先检索。"} />}
          {view.candidates.some((item) => !item.available) && <p className="patent-fixture-note">旧结果已失效，保留历史但不能打开来源或用于新对照。</p>}
        </section>
      </div>
      <section className="patent-fixture-section" aria-labelledby="fixture-compare"><div className="patent-fixture-section-heading"><h3 id="fixture-compare">3. 逐项技术对照</h3>
        <Button type="primary" disabled={busy || isCancelled || !view.selectedCandidateReference} onClick={() => execute(() => patentFixture.act(view, "comparisons"))}>对选中文献生成待核对表</Button></div>
        {view.comparisons.length ? <div className="patent-fixture-table-wrap"><table><thead><tr><th>技术特征</th><th>关系</th><th>来源定位 / 版本</th><th>当前状态</th></tr></thead><tbody>
          {view.comparisons.map((item) => <tr key={item.featureKey}><td>{item.feature}</td><td>未知，待专业核对</td><td>{item.locator ?? "无"} / {item.documentVersion ?? "无"}</td><td>{item.current ? "当前" : "已失效"}</td></tr>)}
        </tbody></table></div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="先选择文献具体版本，再生成逐项待核对表。" />}
      </section>
      <section className="patent-fixture-footer"><Space wrap>
        <Button disabled={busy || isCancelled} onClick={() => execute(() => patentFixture.act(view, "human-handoffs"))}>准备人工交接</Button>
        <Button disabled={busy || isCancelled || current.length > 0} onClick={() => execute(() => patentFixture.act(view, "end-without-evidence"))}>以无证据结束</Button>
        <Button danger disabled={busy || isCancelled} onClick={() => execute(() => patentFixture.act(view, "cancel"))}>取消任务</Button>
      </Space><span>人工交接：{view.humanAvailability === "UNAVAILABLE" ? "未接通，不能宣称已受理" : "尚未请求"}</span></section>
    </>}
    <Drawer title="虚构文献来源" open={Boolean(source || sourceError)} onClose={() => { setSource(undefined); setSourceError(""); }}>
      {sourceError && <Alert type="error" message={sourceError} />}
      {source && <div className="patent-fixture-source"><Typography.Title level={4}>{source.title}</Typography.Title><p>{source.sourceId} · {source.documentVersion}</p><p>定位：{source.locator}</p><blockquote>{source.excerpt}</blockquote><Alert type="warning" message={source.limitation} /></div>}
    </Drawer>
  </main>;
}
