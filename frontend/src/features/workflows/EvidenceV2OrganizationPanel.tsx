import { useState } from "react";
import { Alert, Button, Descriptions, Drawer, Form, Input, Select, Space, Table, Tag, Typography } from "antd";
import { FileSearch, PenLine, RefreshCw, ShieldCheck, Ban, Eye, ChevronLeft, ChevronRight } from "lucide-react";
import { parseEvidenceRefs, type EvidenceLinkPage, type EvidenceOriginalPreview,
  type EvidenceReceipt, type EvidenceRevision } from "./evidenceV2Api";

type Request = <T>(path: string, body?: unknown) => Promise<T>;
type Props = { request: Request; run: (action: () => Promise<void>) => Promise<void>; busy: boolean };

export default function EvidenceV2OrganizationPanel({ request, run, busy }: Props) {
  const [revision, setRevision] = useState<EvidenceRevision | null>(null);
  const [notice, setNotice] = useState("");
  const [pages, setPages] = useState<Record<string, { result: EvidenceLinkPage;
    cursor: string | null; previous: Array<string | null> }>>({});
  const [preview, setPreview] = useState<EvidenceOriginalPreview | null>(null);
  const [lookup] = Form.useForm<{ id: string; version: number }>();
  const [correction] = Form.useForm();
  const [review] = Form.useForm();
  const [exclusion] = Form.useForm();

  async function load(id: string, version: number) {
    const result = await request<EvidenceRevision>(
      `/evidence-organizations/${encodeURIComponent(id)}/versions/${version}`);
    const entries = await Promise.all(result.matters.map(async matter => [matter.id, {
      result: await request<EvidenceLinkPage>(`/evidence-organizations/${encodeURIComponent(id)}` +
        `/versions/${version}/links?matterId=${encodeURIComponent(matter.id)}&pageSize=5`),
      cursor: null, previous: [] as Array<string | null>
    }] as const));
    setPages(Object.fromEntries(entries));
    setPreview(null);
    setRevision(result);
    lookup.setFieldsValue({ id, version });
  }

  async function page(matterId: string, next: boolean) {
    if (!revision) return;
    const current = pages[matterId];
    const cursor = next ? current.result.nextCursor : current.previous.at(-1);
    if (cursor === undefined || (next && cursor === null)) return;
    const result = await request<EvidenceLinkPage>(
      `/evidence-organizations/${encodeURIComponent(revision.id)}/versions/${revision.version}` +
      `/links?matterId=${encodeURIComponent(matterId)}&pageSize=5` +
      (cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""));
    setPages(currentPages => ({ ...currentPages, [matterId]: {
      result, cursor, previous: next ? [...current.previous, current.cursor] : current.previous.slice(0, -1)
    } }));
  }

  async function showOriginal(evidenceId: string, locator: string) {
    if (!revision) return;
    const result = await request<EvidenceOriginalPreview>(
      `/evidence-organizations/${encodeURIComponent(revision.id)}/versions/${revision.version}` +
      `/materials/${encodeURIComponent(evidenceId)}/preview?locator=${encodeURIComponent(locator)}`);
    setPreview(result);
  }

  async function command(path: string, body: unknown) {
    if (!revision) throw new Error("请先读取整理版本");
    const receipt = await request<EvidenceReceipt>(
      `/evidence-organizations/${encodeURIComponent(revision.id)}${path}`, body);
    setNotice(`${revision.id} · v${receipt.revision}`);
    await load(revision.id, receipt.revision);
  }

  return <div className="enf4-grid">
    <section>
      <Typography.Title level={4}>证据集合</Typography.Title>
      <Form form={lookup} layout="inline" className="enf4-lookup" onFinish={values => void run(async () => {
        await load(values.id, Number(values.version));
      })}>
        <Form.Item name="id" label="整理 ID" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="version" label="版本" rules={[{ required: true }]}><Input type="number" min={1} /></Form.Item>
        <Button htmlType="submit" loading={busy} icon={<RefreshCw size={16} aria-hidden="true" />}>读取</Button>
      </Form>
      {revision && <div className="enf4-detail">
        <Descriptions size="small" column={1} items={[
          { key: "id", label: "整理版本", children: `${revision.id} · v${revision.version}` },
          { key: "case", label: "案件快照", children: `${revision.caseRef} · v${revision.caseVersion}` },
          { key: "rule", label: "规则", children: `${revision.ruleId} · v${revision.ruleVersion}` },
          { key: "state", label: "审核状态", children: <Tag>{revision.state}</Tag> },
          { key: "review", label: "审核者", children: revision.reviewedBy ?? "未审核" }
        ]} />
        <Table size="small" rowKey="id" pagination={false} dataSource={revision.evidenceSet}
          columns={[{ title: "原件 Ref", dataIndex: "id" }, { title: "版本", dataIndex: "version" },
            { title: "SHA-256", dataIndex: "sha256", render: value => <code className="enf4-digest">{value}</code> }]} />
        <Typography.Title level={5}>逐事项关系</Typography.Title>
        {revision.matters.map(matter => <section className="enf4-matter" key={matter.id}>
          <div className="enf4-matter-heading"><strong>{matter.id}</strong><Tag>{matter.coverage}</Tag></div>
          <Table size="small" rowKey={(row, index) => `${row.evidenceId}-${row.locator}-${index}`}
            pagination={false} dataSource={pages[matter.id]?.result.items ?? []}
            columns={[{ title: "材料", dataIndex: "evidenceId" },
              { title: "关系", dataIndex: "relation", render: value => <Tag>{value}</Tag> },
              { title: "位置与原值", render: (_, row) => <span>{row.locator} · {row.value}</span> },
              { title: "来源", dataIndex: "originKind", responsive: ["md"] },
              { title: "原件", render: (_, row) => <Button icon={<Eye size={16} aria-hidden="true" />}
                disabled={busy} onClick={() => void run(() => showOriginal(row.evidenceId, row.locator))}>
                预览</Button> }]} />
          <div className="enf4-pager"><span>{pages[matter.id]?.result.total ?? 0} 条 ·
            第 {(pages[matter.id]?.previous.length ?? 0) + 1} 页</span>
            <Space><Button aria-label={`${matter.id} 上一页`} icon={<ChevronLeft size={16} />}
              disabled={busy || !pages[matter.id]?.previous.length}
              onClick={() => void run(() => page(matter.id, false))} />
              <Button aria-label={`${matter.id} 下一页`} icon={<ChevronRight size={16} />}
                disabled={busy || !pages[matter.id]?.result.nextCursor}
                onClick={() => void run(() => page(matter.id, true))} /></Space></div>
        </section>)}
        <Typography.Title level={5}>时间线</Typography.Title>
        <Table size="small" rowKey="evidenceId" pagination={false} dataSource={revision.times}
          columns={[{ title: "材料", dataIndex: "evidenceId" },
            { title: "发生", render: (_, row) => row.eventTime ?? row.eventUnknown ?? "未知" },
            { title: "收集", render: (_, row) => row.collectionTime ?? row.collectionUnknown ?? "未知" },
            { title: "上传", render: (_, row) => row.uploadTime ?? row.uploadUnknown ?? "未知" }]} />
        {revision.unrelated.length > 0 && <p>无关：{revision.unrelated.join("、")}</p>}
        {revision.unprocessed.length > 0 && <p>未处理：{revision.unprocessed.join("、")}</p>}
        {revision.exclusions.length > 0 && <p>排除记录：{revision.exclusions.map(item =>
          `${item.evidenceId}（${item.reason} · ${item.actor}）`).join("；")}</p>}
      </div>}
      {notice && <Alert type="success" showIcon title={notice} className="enf4-notice" />}
      <Drawer title={preview ? `${preview.evidenceId} · v${preview.version} · ${preview.locator}` : "原件预览"}
        open={!!preview} onClose={() => setPreview(null)} width="min(680px, 100vw)" destroyOnHidden>
        {preview && <><p>SHA-256：<code className="enf4-digest">{preview.sha256}</code></p>
          <pre className="enf4-original">{preview.text.split(/\r?\n/).map((line, index) =>
            <span key={index} className={`enf4-original-line${preview.locator === `line ${index + 1}` ? " enf4-located" : ""}`}>
              {index + 1}. {line}{"\n"}</span>)}</pre></>}
      </Drawer>
      <Typography.Title level={4}>新建整理</Typography.Title>
      <Form layout="vertical" onFinish={values => void run(async () => {
        const receipt = await request<EvidenceReceipt>("/evidence-organizations", {
          id: values.id, ruleId: values.ruleId, ruleRevision: Number(values.ruleRevision),
          evidenceSet: { caseRef: values.caseRef, caseVersion: values.caseVersion,
            materials: parseEvidenceRefs(values.materials) }
        });
        setNotice(`${values.id} · v${receipt.revision}`);
        await load(values.id, receipt.revision);
      })}>
        <div className="enf4-pair"><Form.Item name="id" label="整理 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="ruleId" label="规则 ID" rules={[{ required: true }]}><Input /></Form.Item></div>
        <div className="enf4-pair"><Form.Item name="ruleRevision" label="已发布规则版本" rules={[{ required: true }]}>
          <Input type="number" min={1} /></Form.Item>
          <Form.Item name="caseRef" label="案件 Ref" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Form.Item name="caseVersion" label="案件快照版本" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="materials" label="锁定材料 ID|版本|SHA-256" rules={[{ required: true }]}>
          <Input.TextArea rows={4} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={busy} icon={<FileSearch size={16} aria-hidden="true" />}>整理证据</Button>
      </Form>
    </section>
    <section>
      <Typography.Title level={4}>人工修订</Typography.Title>
      <Form form={correction} layout="vertical" onFinish={values => void run(async () => {
        await command("/revisions", { ...values, expectedVersion: revision?.version });
        correction.resetFields();
      })}>
        <div className="enf4-pair"><Form.Item name="matterId" label="待证事项" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="evidenceId" label="材料 ID" rules={[{ required: true }]}><Input /></Form.Item></div>
        <div className="enf4-pair"><Form.Item name="locator" label="原文定位" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="relation" label="修订关系" rules={[{ required: true }]}>
            <Select options={["SUPPORT", "CORROBORATE", "CONTRADICT", "IRRELEVANT", "UNVERIFIED"]
              .map(value => ({ value, label: value }))} /></Form.Item></div>
        <Form.Item name="reason" label="修订理由" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item>
        <Button htmlType="submit" disabled={!revision} loading={busy} icon={<PenLine size={16} aria-hidden="true" />}>追加修订</Button>
      </Form>
      <Typography.Title level={4}>独立审核</Typography.Title>
      <Form form={review} layout="vertical" onFinish={values => void run(async () => {
        await command("/review", { ...values, expectedVersion: revision?.version });
        review.resetFields();
      })}>
        <Form.Item name="decision" label="审核结果" rules={[{ required: true }]}>
          <Select options={[{ value: "CONFIRMED", label: "确认" }, { value: "RETURNED", label: "退回" }]} /></Form.Item>
        <Form.Item name="reason" label="审核理由" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item>
        <Button htmlType="submit" disabled={!revision || revision.state !== "PENDING_REVIEW"}
          loading={busy} icon={<ShieldCheck size={16} aria-hidden="true" />}>提交审核</Button>
      </Form>
      <Typography.Title level={4}>排除材料</Typography.Title>
      <Form form={exclusion} layout="vertical" onFinish={values => void run(async () => {
        await command("/exclusions", { ...values, expectedVersion: revision?.version });
        exclusion.resetFields();
      })}>
        <Form.Item name="evidenceId" label="材料 ID" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="reason" label="排除理由" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item>
        <Button danger htmlType="submit" disabled={!revision} loading={busy}
          icon={<Ban size={16} aria-hidden="true" />}>追加排除版本</Button>
      </Form>
    </section>
  </div>;
}
