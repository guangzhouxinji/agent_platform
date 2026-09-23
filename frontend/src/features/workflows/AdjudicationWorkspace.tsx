import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Button, Form, Input, Select, Space, Steps, Tag, Typography } from "antd";
import { FileCheck2, FilePlus2, Scale } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { EmptyState, JsonDrawer, ORGANIZATION, RequestError, SnapshotSummary, WorkbenchHeader, writeHeaders } from "../../shared/ui/Workbench";
import { ScopeTags, ValueList, VersionButtons, WorkflowSection } from "./WorkflowView";

const USER = "v0-adjudication-user";
const REVIEWER = "v0-adjudication-reviewer";
type Catalog<T> = { limitation: string; items: T[] };
type Envelope = { resultType: string; data: any; limitations: string[] };

export default function AdjudicationWorkspace() {
  const [caseId, setCaseId] = useState("");
  const [materialIds, setMaterialIds] = useState<string[]>([]);
  const [snapshot, setSnapshot] = useState<any>();
  const [reason, setReason] = useState("内部开发流程复核");
  const [replacement, setReplacement] = useState("人工修订：已复核材料来源与候选引用，仍需正式人员确认。");
  const [drawer, setDrawer] = useState<{ title: string; value: unknown }>();
  const cases = useQuery({ queryKey: ["development", "adjudication", "cases"], queryFn: () => apiRequest<Catalog<any>>("/api/v0/development-sources/adjudication/cases") });
  const materials = useQuery({ queryKey: ["development", "adjudication", "materials"], queryFn: () => apiRequest<Catalog<any>>("/api/v0/development-sources/adjudication/materials") });
  const selectedCase = cases.data?.items.find((item) => item.id === caseId);
  const availableMaterials = useMemo(() => materials.data?.items.filter((item) => item.caseRef.id === caseId) ?? [], [materials.data, caseId]);
  useEffect(() => { if (!caseId && cases.data?.items.length) setCaseId(cases.data.items[0].id); }, [caseId, cases.data]);
  useEffect(() => setMaterialIds(availableMaterials.filter((item) => item.data.available).slice(0, 2).map((item) => item.source.id)), [caseId, availableMaterials]);

  const run = useMutation({ mutationFn: ({ path, actor = USER, body }: { path: string; actor?: string; body?: unknown }) => apiRequest<Envelope>(path, { method: "POST", headers: writeHeaders(actor), body: body === undefined ? undefined : JSON.stringify(body) }), onSuccess: (value) => setSnapshot(value.data) });
  const create = useMutation({ mutationFn: () => apiRequest<Envelope>("/api/v0/adjudication/work-items", { method: "POST", headers: { ...writeHeaders(USER), "X-V0-Organization": ORGANIZATION }, body: JSON.stringify({ caseId, caseVersion: selectedCase?.version, materialIds }) }), onSuccess: (value) => setSnapshot(value.data) });
  const workId = snapshot?.work?.workItemId;
  const inspection = snapshot?.inspections?.at(-1);
  const candidates = snapshot?.candidates?.at(-1);
  const draft = snapshot?.drafts?.at(-1);
  const error = cases.error || materials.error || create.error || run.error;
  const busy = create.isPending || run.isPending;
  const step = draft ? 4 : candidates ? 3 : inspection ? 2 : snapshot ? 1 : 0;
  const action = (suffix: string, body?: unknown, actor = USER) => run.mutate({ path: `/api/v0/adjudication/work-items/${workId}${suffix}`, body, actor });

  const openVersion = async ({ resource, id, version }: { resource: string; id: string; version: number }) => {
    try {
      const value = await apiRequest<Envelope>(`/api/v0/adjudication/work-items/${workId}/${resource}/${id}/versions/${version}`, { actor: USER });
      setDrawer({ title: "精确历史版本", value: value.data });
    } catch (cause) { setDrawer({ title: "读取失败", value: cause instanceof Error ? cause.message : cause }); }
  };
  const versions = [
    ...(snapshot?.inspections ?? []).map((item: any) => ({ key: `i-${item.inspectionId}-${item.version}`, label: `材料检查 v${item.version}`, resource: "material-inspections", id: item.inspectionId, version: item.version })),
    ...(snapshot?.candidates ?? []).map((item: any) => ({ key: `c-${item.candidateSetId}-${item.version}`, label: `法规候选 v${item.version}`, resource: "legal-candidate-sets", id: item.candidateSetId, version: item.version })),
    ...(snapshot?.drafts ?? []).map((item: any) => ({ key: `d-${item.draftId}-${item.version}`, label: `裁决草稿 v${item.version}`, resource: "drafts", id: item.draftId, version: item.version }))
  ];

  return <section className="workbench">
    <WorkbenchHeader eyebrow="业务验证" title="裁决辅助工作台" description="从开发案件材料进入检查、法规候选、引用、草稿和人工测试复核。" tags={["SYNTHETIC_V0", "NOT_OFFICIAL_DECISION"]} />
    <div className="workbench-grid"><aside className="control-panel">
      <Steps orientation="vertical" size="small" current={step} items={[{ title: "选择案件" }, { title: "创建工作项" }, { title: "检查材料" }, { title: "选择引用" }, { title: "草稿与复核" }]} />
      <Form layout="vertical" className="workflow-actions" onFinish={() => create.mutate()}>
        <Form.Item label="开发案件"><Select loading={cases.isLoading} value={caseId || undefined} onChange={setCaseId} options={cases.data?.items.map((item) => ({ value: item.id, label: `${item.id} · ${item.version}` }))} /></Form.Item>
        <Form.Item label="案件材料"><Select mode="multiple" value={materialIds} onChange={setMaterialIds} options={availableMaterials.map((item) => ({ value: item.source.id, label: `${item.source.id} · ${item.data.available ? item.data.conflicts.length ? "存在冲突" : item.data.missingItems.length ? "存在缺项" : "材料可用" : "来源不可用"}` }))} /></Form.Item>
        {!snapshot && <Button block type="primary" htmlType="submit" loading={busy} disabled={!selectedCase || !materialIds.length} icon={<Scale size={16} />}>创建裁决工作项</Button>}
        {snapshot && <div className="workflow-actions"><Button block type="primary" loading={busy} onClick={() => action("/material-inspections")}>检查案件材料</Button><Button block disabled={!inspection} loading={busy} onClick={() => action("/legal-candidate-sets", { inspectionVersion: inspection.version })}>生成法规候选</Button><Button block disabled={!candidates?.entries?.length} loading={busy} onClick={() => action("/citations/actions", { expectedVersion: snapshot.citations.length, code: candidates.entries[0].code, action: "SELECT", reason }, REVIEWER)}>选择首个法规候选</Button><Button block disabled={!inspection} loading={busy} icon={<FilePlus2 size={16} />} onClick={() => action("/drafts", { inspectionVersion: inspection.version })}>生成裁决草稿</Button></div>}
        {draft && <><Form.Item label="人工修订内容"><Input.TextArea value={replacement} onChange={(event) => setReplacement(event.target.value)} rows={4} /></Form.Item><Form.Item label="复核说明"><Input.TextArea value={reason} onChange={(event) => setReason(event.target.value)} rows={3} /></Form.Item>
          <Button block loading={busy} onClick={() => action("/drafts/actions", { expectedVersion: draft.version, replacementText: replacement, reason }, REVIEWER)}>保存草稿修订版</Button><Space orientation="vertical" size={8} style={{ width: "100%" }}><Button block type="primary" loading={busy} icon={<FileCheck2 size={16} />} onClick={() => action("/drafts/test-reviews", { expectedDraftVersion: draft.version, action: "CONFIRM", reason }, REVIEWER)}>确认测试复核</Button><Button block loading={busy} onClick={() => action("/drafts/test-reviews", { expectedDraftVersion: draft.version, action: "RETURN", reason }, REVIEWER)}>退回草稿</Button></Space></>}
      </Form>
    </aside><main className="result-panel"><RequestError error={error} retry={() => { cases.refetch(); materials.refetch(); }} />
      {!snapshot ? <EmptyState text="选择开发案件和材料后创建工作项，这里将显示检查、候选、草稿与版本记录。" /> : <>
        <div className="section-title"><div><Typography.Title level={3}>当前裁决工作项</Typography.Title><Typography.Text type="secondary">{workId}</Typography.Text></div><Space wrap><ScopeTags /></Space></div>
        <SnapshotSummary items={[["组织", snapshot.work.organizationRef], ["案件", snapshot.work.caseRef.id], ["案件版本", snapshot.work.caseRef.version], ["材料数", snapshot.work.materialRefs.length], ["草稿版本", draft?.version], ["测试复核", snapshot.reviews.length]]} />
        <WorkflowSection title="材料检查" meta={inspection && <Tag>版本 {inspection.version}</Tag>}><div className="workflow-columns"><div><strong>当事人陈述</strong><ValueList values={inspection?.partyStatements} /></div><div><strong>待核实事实</strong><ValueList values={inspection?.unresolvedFacts} /></div><div><strong>缺失与冲突</strong><ValueList values={[...(inspection?.missingItems ?? []), ...(inspection?.conflicts ?? [])]} /></div></div></WorkflowSection>
        <WorkflowSection title="法规候选与人工引用" meta={candidates && <Tag>候选集 v{candidates.version}</Tag>}><div className="workflow-columns"><div><strong>候选法规</strong><ValueList values={candidates?.entries} /></div><div><strong>限制</strong><ValueList values={candidates?.limitations} /></div><div><strong>人工引用</strong><ValueList values={snapshot.citations} /></div></div></WorkflowSection>
        <WorkflowSection title="裁决草稿" meta={<Tag color="gold">非正式草稿</Tag>}><div className="draft-sections">{draft?.sections?.map((section: any) => <article key={section.anchor}><strong>{section.anchor}</strong><Typography.Paragraph>{section.content}</Typography.Paragraph></article>) ?? <Typography.Text type="secondary">尚未生成草稿</Typography.Text>}</div><div className="workflow-columns"><div><strong>草稿缺失项</strong><ValueList values={draft?.missingItems} /></div><div><strong>草稿冲突项</strong><ValueList values={draft?.conflicts} /></div><div><strong>测试复核记录</strong><ValueList values={snapshot.reviews} /></div></div></WorkflowSection>
        <WorkflowSection title="版本历史"><VersionButtons items={versions} onOpen={openVersion} /></WorkflowSection>
      </>}
    </main></div><JsonDrawer title={drawer?.title ?? "详情"} value={drawer?.value} onClose={() => setDrawer(undefined)} />
  </section>;
}
