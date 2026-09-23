import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Button, Checkbox, Form, Input, Select, Space, Steps, Tag, Typography } from "antd";
import { FileCheck2, Play, RefreshCw, ShieldCheck } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { EmptyState, JsonDrawer, ORGANIZATION, RequestError, SnapshotSummary, WorkbenchHeader, writeHeaders } from "../../shared/ui/Workbench";
import { ScopeTags, ValueList, VersionButtons, WorkflowSection } from "./WorkflowView";

const USER = "v0-enforcement-user";
type Catalog<T> = { limitation: string; items: T[] };
type Envelope = { resultType: string; data: any; limitations: string[] };

export default function EnforcementWorkspace() {
  const [clueId, setClueId] = useState("");
  const [evidenceIds, setEvidenceIds] = useState<string[]>([]);
  const [snapshot, setSnapshot] = useState<any>();
  const [reason, setReason] = useState("内部开发流程复核");
  const [drawer, setDrawer] = useState<{ title: string; value: unknown }>();
  const clues = useQuery({ queryKey: ["development", "enforcement", "clues"], queryFn: () => apiRequest<Catalog<any>>("/api/v0/development-sources/enforcement/clues") });
  const evidence = useQuery({ queryKey: ["development", "enforcement", "evidence"], queryFn: () => apiRequest<Catalog<any>>("/api/v0/development-sources/enforcement/evidence") });
  const selectedClue = clues.data?.items.find((item) => item.source.sourceId === clueId);
  const availableEvidence = useMemo(() => evidence.data?.items.filter((item) => item.relatedClueId === clueId) ?? [], [evidence.data, clueId]);

  useEffect(() => { if (!clueId && clues.data?.items.length) setClueId(clues.data.items[0].source.sourceId); }, [clueId, clues.data]);
  useEffect(() => setEvidenceIds(availableEvidence.filter((item) => item.availability === "AVAILABLE").slice(0, 2).map((item) => item.source.sourceId)), [clueId, availableEvidence]);

  const run = useMutation({ mutationFn: async ({ path, actor = USER, body }: { path: string; actor?: string; body?: unknown }) => apiRequest<Envelope>(path, { method: "POST", headers: writeHeaders(actor), body: body === undefined ? undefined : JSON.stringify(body) }), onSuccess: (value) => setSnapshot(value.data) });
  const createMutation = useMutation({ mutationFn: () => apiRequest<Envelope>("/api/v0/enforcement/work-items", { method: "POST", headers: { ...writeHeaders(USER), "X-V0-Organization": ORGANIZATION }, body: JSON.stringify({ clueId, clueVersion: selectedClue?.source.version }) }), onSuccess: (value) => setSnapshot(value.data) });
  const workId = snapshot?.workItem?.workItemId;
  const latestClue = snapshot?.clueAnalyses?.at(-1);
  const latestRelations = snapshot?.relationSets?.at(-1);
  const latestAnalysis = snapshot?.auxiliaryAnalyses?.at(-1);
  const error = clues.error || evidence.error || createMutation.error || run.error;
  const busy = createMutation.isPending || run.isPending;
  const step = latestAnalysis ? 3 : latestClue ? 2 : snapshot ? 1 : 0;

  const openVersion = async ({ resource, id, version }: { resource: string; id: string; version: number }) => {
    try {
      const value = await apiRequest<Envelope>(`/api/v0/enforcement/work-items/${workId}/${resource}/${id}/versions/${version}`, { actor: USER });
      setDrawer({ title: "精确历史版本", value: value.data });
    } catch (cause) { setDrawer({ title: "读取失败", value: cause instanceof Error ? cause.message : cause }); }
  };
  const versions = [
    ...(snapshot?.clueAnalyses ?? []).map((item: any) => ({ key: `c-${item.analysisId}-${item.version}`, label: `线索分析 v${item.version}`, resource: "clue-analyses", id: item.analysisId, version: item.version })),
    ...(snapshot?.relationSets ?? []).map((item: any) => ({ key: `r-${item.relationSetId}-${item.version}`, label: `证据关系 v${item.version}`, resource: "relation-sets", id: item.relationSetId, version: item.version })),
    ...(snapshot?.auxiliaryAnalyses ?? []).map((item: any) => ({ key: `a-${item.analysisResultId}-${item.version}`, label: `辅助分析 v${item.version}`, resource: "auxiliary-analyses", id: item.analysisResultId, version: item.version }))
  ];

  return <section className="workbench">
    <WorkbenchHeader eyebrow="业务验证" title="执法辅助工作台" description="从开发线索进入证据处理、辅助分析、修订与人工测试复核。" tags={["SYNTHETIC_V0", "NOT_OFFICIAL_DECISION"]} />
    <div className="workbench-grid"><aside className="control-panel">
      <Steps orientation="vertical" size="small" current={step} items={[{ title: "选择线索" }, { title: "创建工作项" }, { title: "处理证据" }, { title: "人工复核" }]} />
      <Form layout="vertical" className="workflow-actions" onFinish={() => createMutation.mutate()}>
        <Form.Item label="开发线索"><Select loading={clues.isLoading} value={clueId || undefined} onChange={setClueId} options={clues.data?.items.map((item) => ({ value: item.source.sourceId, label: `${item.subjects?.[0]?.name ?? "开发线索"} · ${item.source.sourceId} · ${item.source.version}` }))} /></Form.Item>
        {!snapshot && <Button block type="primary" htmlType="submit" loading={busy} disabled={!selectedClue} icon={<ShieldCheck size={16} />}>创建工作项</Button>}
        {snapshot && <><Typography.Text className="workflow-field-label">本次处理证据</Typography.Text><Checkbox.Group value={evidenceIds} onChange={(values) => setEvidenceIds(values as string[])} options={availableEvidence.map((item) => ({ value: item.source.sourceId, label: `${item.issue} · ${item.source.sourceId} · ${item.availability}` }))} />
          <Button block type="primary" loading={busy} disabled={!evidenceIds.length} icon={<Play size={16} />} onClick={() => run.mutate({ path: `/api/v0/enforcement/work-items/${workId}/process`, body: { evidenceIds } })}>处理证据并生成分析</Button></>}
        {latestAnalysis && <><Form.Item label="复核说明"><Input.TextArea value={reason} onChange={(event) => setReason(event.target.value)} rows={3} /></Form.Item>
          <Button block loading={busy} icon={<RefreshCw size={16} />} onClick={() => run.mutate({ path: `/api/v0/enforcement/work-items/${workId}/clue-analyses/${latestClue.analysisId}/actions`, actor: "v0-enforcement-reviewer", body: { action: "REVISE", expectedVersion: latestClue.version, reason } })}>保存线索修订版</Button>
          <Space orientation="vertical" size={8} style={{ width: "100%" }}><Button block type="primary" loading={busy} icon={<FileCheck2 size={16} />} onClick={() => run.mutate({ path: `/api/v0/enforcement/work-items/${workId}/auxiliary-analyses/${latestAnalysis.analysisResultId}/actions`, actor: "v0-enforcement-reviewer", body: { action: "CONFIRM", expectedVersion: latestAnalysis.version, reason } })}>确认辅助分析</Button><Button block loading={busy} onClick={() => run.mutate({ path: `/api/v0/enforcement/work-items/${workId}/auxiliary-analyses/${latestAnalysis.analysisResultId}/actions`, actor: "v0-enforcement-reviewer", body: { action: "RETURN", expectedVersion: latestAnalysis.version, reason } })}>退回修改</Button></Space></>}
      </Form>
    </aside><main className="result-panel"><RequestError error={error} retry={() => { clues.refetch(); evidence.refetch(); }} />
      {!snapshot ? <EmptyState text="选择开发线索并创建工作项后，这里将显示证据、分析与版本记录。" /> : <>
        <div className="section-title"><div><Typography.Title level={3}>当前工作项</Typography.Title><Typography.Text type="secondary">{workId}</Typography.Text></div><Space wrap><ScopeTags /></Space></div>
        <SnapshotSummary items={[["组织", snapshot.workItem.organizationRef], ["线索", snapshot.workItem.clueRef.sourceId], ["线索版本", snapshot.workItem.clueRef.version], ["证据数", snapshot.evidenceReferences.length], ["分析版本", latestAnalysis?.version], ["复核记录", snapshot.reviews.length]]} />
        <WorkflowSection title="线索字段与冲突" meta={latestClue && <Tag>版本 {latestClue.version}</Tag>}><div className="workflow-columns"><div><strong>主体候选</strong><ValueList values={latestClue?.subjectCandidates} /></div><div><strong>事实与时间</strong><ValueList values={[...(latestClue?.suspectedFacts ?? []), ...(latestClue?.timeCandidates ?? [])]} /></div><div><strong>缺失与冲突</strong><ValueList values={[...(latestClue?.missingItems ?? []), ...(latestClue?.conflicts ?? [])]} /></div></div></WorkflowSection>
        <WorkflowSection title="证据关系" meta={latestRelations && <Tag>{latestRelations.completeness}</Tag>}><div className="workflow-columns"><div><strong>证据引用</strong><ValueList values={snapshot.evidenceReferences} /></div><div><strong>候选关系</strong><ValueList values={latestRelations?.relations} /></div><div><strong>时间线与缺口</strong><ValueList values={[...(latestRelations?.timeline ?? []), ...(latestRelations?.gaps ?? [])]} /></div></div></WorkflowSection>
        <WorkflowSection title="辅助分析与测试复核" meta={<Tag color="gold">非正式决定</Tag>}><div className="workflow-columns"><div><strong>{latestAnalysis?.auxiliaryOutcome ?? "待生成"}</strong><ValueList values={latestAnalysis?.items} /></div><div><strong>限制与待复核</strong><ValueList values={[...(latestAnalysis?.gaps ?? []), ...(latestAnalysis?.needsReviewReasons ?? [])]} /></div><div><strong>人工记录</strong><ValueList values={snapshot.reviews} /></div></div></WorkflowSection>
        <WorkflowSection title="版本历史"><VersionButtons items={versions} onOpen={openVersion} /></WorkflowSection>
      </>}
    </main></div><JsonDrawer title={drawer?.title ?? "详情"} value={drawer?.value} onClose={() => setDrawer(undefined)} />
  </section>;
}
