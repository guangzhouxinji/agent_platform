import { useState } from "react";
import { Alert, Button, Descriptions, Form, Input, Table, Tag, Typography } from "antd";
import { Play, Plus, RefreshCw, ShieldCheck, ShieldOff } from "lucide-react";
import { parseEvidenceRefs, type EvidencePublication, type EvidenceReceipt, type EvidenceRule,
  type TrialResult } from "./evidenceV2Api";

type Request = <T>(path: string, body?: unknown) => Promise<T>;
type Props = { request: Request; run: (action: () => Promise<void>) => Promise<void>; busy: boolean };

function parseMatters(value: string) {
  const matters = value.split("\n").map(line => line.trim()).filter(Boolean).map(line => {
    const [id, required, expectedMaterial, extra] = line.split("|").map(part => part.trim());
    if (!id || !["true", "false"].includes(required) || !expectedMaterial || extra)
      throw new Error("事项须逐行填写 ID|true或false|材料映射");
    return { id, required: required === "true", expectedMaterial };
  });
  if (!matters.length || new Set(matters.map(matter => matter.id)).size !== matters.length)
    throw new Error("事项不能为空或重复");
  return matters;
}

function parseIds(value: string) { return value.split(",").map(item => item.trim()).filter(Boolean); }

export default function EvidenceV2RulePanel({ request, run, busy }: Props) {
  const [rule, setRule] = useState<EvidenceRule | null>(null);
  const [publication, setPublication] = useState<EvidencePublication | null>(null);
  const [trial, setTrial] = useState<TrialResult | null>(null);
  const [notice, setNotice] = useState("");
  const [lookup] = Form.useForm<{ id: string; revision: number }>();
  const [trialForm] = Form.useForm();
  const rulePath = (id: string, revision: number) =>
    `/evidence-rules/${encodeURIComponent(id)}/versions/${revision}`;

  async function load(id: string, revision: number) {
    const current = await request<EvidenceRule>(rulePath(id, revision));
    const review = current.state === "DRAFT" ? null
      : await request<EvidencePublication>(`${rulePath(id, revision)}/publication`);
    setRule(current); setTrial(null);
    setPublication(review);
    lookup.setFieldsValue({ id, revision });
  }

  return <div className="enf4-grid">
    <section>
      <Typography.Title level={4}>规则版本</Typography.Title>
      <Form form={lookup} layout="inline" className="enf4-lookup" onFinish={values => void run(async () => {
        await load(values.id, Number(values.revision));
      })}>
        <Form.Item name="id" label="规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="revision" label="版本" rules={[{ required: true }]}><Input type="number" min={1} /></Form.Item>
        <Button htmlType="submit" loading={busy} icon={<RefreshCw size={16} aria-hidden="true" />}>读取</Button>
      </Form>
      {rule && <div className="enf4-detail">
        <Descriptions size="small" column={1} items={[
          { key: "id", label: "规则", children: `${rule.id} · v${rule.revision}` },
          { key: "scope", label: "范围", children: `${rule.organization} / ${rule.caseType}` },
          { key: "basis", label: "依据引用", children: rule.basisRef },
          { key: "state", label: "状态", children: <Tag>{rule.state}</Tag> }
        ]} />
        <Table size="small" rowKey="id" pagination={false} dataSource={rule.matters}
          columns={[{ title: "事项", dataIndex: "id" }, { title: "必要", dataIndex: "required",
            render: value => value ? "是" : "否" }, { title: "材料映射", dataIndex: "expectedMaterial" }]} />
        {publication && <Descriptions size="small" column={1} items={[
          { key: "basisVersion", label: "依据版本", children: publication.basisVersion },
          { key: "scope", label: "适用范围", children: publication.scope },
          { key: "reviewRef", label: "审核引用", children: publication.reviewRef },
          { key: "reviewDigest", label: "审核摘要", children: <code className="enf4-digest">{publication.reviewDigest}</code> },
          { key: "reviewedBy", label: "审核者", children: publication.reviewedBy }
        ]} />}
        {rule.state === "ACTIVE" && <Button danger disabled={busy} icon={<ShieldOff size={16} aria-hidden="true" />}
          onClick={() => void run(async () => {
            await request<EvidenceReceipt>(`${rulePath(rule.id, rule.revision)}/disable`, {});
            await load(rule.id, rule.revision);
          })}>停用版本</Button>}
      </div>}
      {notice && <Alert type="success" showIcon title={notice} className="enf4-notice" />}
      <Typography.Title level={4}>规则草稿</Typography.Title>
      <Form layout="vertical" onFinish={values => void run(async () => {
        const receipt = await request<EvidenceReceipt>("/evidence-rules", {
          id: values.id, organization: values.organization, caseType: values.caseType,
          basisRef: values.basisRef, matters: parseMatters(values.matters)
        });
        setNotice(`草稿 ${receipt.resultId} · v${receipt.revision}`);
        await load(values.id, receipt.revision);
      })}>
        <div className="enf4-pair"><Form.Item name="id" label="规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="organization" label="组织" rules={[{ required: true }]}><Input /></Form.Item></div>
        <div className="enf4-pair"><Form.Item name="caseType" label="案件类型" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="basisRef" label="依据引用" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Form.Item name="matters" label="事项、必要性与材料映射" rules={[{ required: true }]}>
          <Input.TextArea rows={3} /></Form.Item>
        <Button type="primary" htmlType="submit" loading={busy} icon={<Plus size={16} aria-hidden="true" />}>保存草稿</Button>
      </Form>
    </section>
    <section>
      <Typography.Title level={4}>只读试跑</Typography.Title>
      <Form form={trialForm} layout="vertical" onFinish={values => void run(async () => {
        if (!rule) throw new Error("请先读取规则版本");
        const result = await request<TrialResult>(`${rulePath(rule.id, rule.revision)}/trials`, {
          caseRef: values.caseRef, caseVersion: values.caseVersion,
          materials: parseEvidenceRefs(values.materials)
        });
        setTrial(result);
      })}>
        <div className="enf4-pair"><Form.Item name="caseRef" label="案件 Ref" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="caseVersion" label="快照版本" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Form.Item name="materials" label="锁定材料 ID|版本|SHA-256" rules={[{ required: true }]}>
          <Input.TextArea rows={4} /></Form.Item>
        <Button htmlType="submit" disabled={!rule} loading={busy} icon={<Play size={16} aria-hidden="true" />}>试跑</Button>
      </Form>
      {trial && <div className="enf4-detail"><Table size="small" rowKey="matterId" pagination={false}
        dataSource={trial.matters} columns={[{ title: "事项", dataIndex: "matterId" },
          { title: "覆盖", dataIndex: "coverage", render: value => <Tag>{value}</Tag> },
          { title: "原文定位", render: (_, row) => row.candidates.map(candidate =>
            <div key={`${candidate.materialId}-${candidate.locator}`}>{candidate.materialId} · {candidate.locator} · {candidate.value}</div>) }]} />
        {trial.unrelatedMaterials.length > 0 && <p>无关材料：{trial.unrelatedMaterials.join("、")}</p>}</div>}
      <Typography.Title level={4}>发布审核</Typography.Title>
      <Form layout="vertical" onFinish={values => void run(async () => {
        if (!rule) throw new Error("请先读取规则版本");
        const receipt = await request<EvidenceReceipt>(`/evidence-rules/${encodeURIComponent(rule.id)}/publications`, {
          basisVersion: values.basisVersion, scope: values.scope, reviewRef: values.reviewRef,
          positive: [{ id: "positive", observedMatters: parseIds(values.positive), expectedMissing: [] }],
          negative: [{ id: "negative", observedMatters: parseIds(values.negativeCovered),
            expectedMissing: parseIds(values.negativeMissing) }]
        });
        setNotice(`已发布 v${receipt.revision}`);
        await load(rule.id, receipt.revision);
      })}>
        <div className="enf4-pair"><Form.Item name="basisVersion" label="依据版本" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="scope" label="适用范围" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Form.Item name="reviewRef" label="审核引用" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="positive" label="正例覆盖事项（逗号分隔）" rules={[{ required: true }]}><Input /></Form.Item>
        <div className="enf4-pair"><Form.Item name="negativeCovered" label="反例覆盖事项"><Input /></Form.Item>
          <Form.Item name="negativeMissing" label="反例缺失事项" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Button htmlType="submit" disabled={!rule || rule.state !== "DRAFT"} loading={busy}
          icon={<ShieldCheck size={16} aria-hidden="true" />}>发布版本</Button>
      </Form>
    </section>
  </div>;
}
