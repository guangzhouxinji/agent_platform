import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Alert, Button, Descriptions, Form, Input, Modal, Select, Space, Table, Tag, Typography, Upload, message } from "antd";
import { Download, Eye, FilePlus2, FileStack, Pencil, RefreshCw, ShieldCheck } from "lucide-react";
import { apiRequest } from "../../shared/api/client";
import { ACTOR, ENFORCEMENT_REVIEWER, ORGANIZATION, RequestError, WorkbenchHeader } from "../../shared/ui/Workbench";

type Envelope<T> = { requestId: string; resultType: string; data: T; limitations: string[] };
type EvidenceSummary = { evidenceId: string; name: string; type: string; status: string; relatedLeadId: string; sourceChannel: string; recordVersion: number; latestContentVersion: number; updatedAt: string };
type EvidencePage = { items: EvidenceSummary[]; total: number; page: number; pageSize: number };
type ContentVersion = { version: number; originalVersion?: number; derivedKind: string; controlledResourceRef: string; fileName?: string; mediaType?: string; size?: number; digestAlgorithm?: string; digest?: string; previewable: boolean; createdBy: string; createdAt: string };
type EvidenceDetail = EvidenceSummary & { organizationRef: string; relatedCaseRef?: string; collection: { collector?: string; collectionTime?: string; missingReason?: string }; physicalDescription?: string; custodyInformation?: string; versions: ContentVersion[]; metadataHistory: Array<{ recordVersion: number; name: string; type: string; reason: string; changedBy: string; changedAt: string }>; statusHistory: Array<{ fromStatus: string; toStatus: string; reason: string; actor: string; occurredAt: string }>; createdAt: string };
type LeadPage = { items: Array<{ leadId: string; sourceRecordId: string }> };
type EvidenceForm = { name: string; type: string; relatedLeadId: string; collector?: string; collectionTime?: string; collectionMissingReason?: string; physicalDescription?: string; custodyInformation?: string; reason?: string };

const headers = (actor = ACTOR) => ({ "X-V0-Actor": actor, "X-V0-Organization": ORGANIZATION });
const statusLabels: Record<string, string> = { PENDING_VERIFICATION: "待核验", VERIFIED: "已核验（未采信）", EXCLUDED: "已排除", ARCHIVED: "已归档" };
const typeLabels: Record<string, string> = { DOCUMENT: "书证", ELECTRONIC_DATA: "电子数据", AUDIO_VISUAL: "视听资料", PHYSICAL_OBJECT: "物证", OTHER: "其他" };
const derivedLabels: Record<string, string> = { ORIGINAL: "原件", OCR_TEXT: "OCR 文本", TRANSCODE: "转码件", PREVIEW: "预览件", OTHER: "其他派生件" };

const toBase64 = (file: File) => new Promise<string>((resolve, reject) => {
  const reader = new FileReader();
  reader.onload = () => resolve(String(reader.result).split(",")[1] ?? "");
  reader.onerror = () => reject(reader.error);
  reader.readAsDataURL(file);
});

export default function EnforcementEvidencePage() {
  const [selectedId, setSelectedId] = useState<string>();
  const [dialog, setDialog] = useState<"create" | "edit">();
  const [file, setFile] = useState<File>();
  const [filters, setFilters] = useState<{ leadId?: string; type?: string; status?: string }>({});
  const [form] = Form.useForm<EvidenceForm>();
  const [messageApi, messageContext] = message.useMessage();
  const query = new URLSearchParams({ page: "1", pageSize: "100", ...Object.fromEntries(Object.entries(filters).filter(([, value]) => value)) }).toString();
  const evidence = useQuery({ queryKey: ["enforcement-evidence", filters], queryFn: async () => (await apiRequest<Envelope<EvidencePage>>(`/api/v0/enforcement/evidence?${query}`, { headers: headers() })).data });
  const leads = useQuery({ queryKey: ["evidence-leads"], queryFn: async () => (await apiRequest<Envelope<LeadPage>>("/api/v0/enforcement/leads?page=1&pageSize=100", { headers: headers() })).data });
  const detail = useQuery({ queryKey: ["enforcement-evidence-detail", selectedId], enabled: Boolean(selectedId), queryFn: async () => (await apiRequest<Envelope<EvidenceDetail>>(`/api/v0/enforcement/evidence/${encodeURIComponent(selectedId!)}`, { headers: headers() })).data });
  useEffect(() => { if (!selectedId && evidence.data?.items.length) setSelectedId(evidence.data.items[0].evidenceId); }, [evidence.data, selectedId]);
  const selected = useMemo(() => evidence.data?.items.find(item => item.evidenceId === selectedId), [evidence.data, selectedId]);
  const refresh = () => {
    evidence.refetch();
    if (selectedId) detail.refetch();
  };

  const save = useMutation({ mutationFn: async (values: EvidenceForm) => {
    if (dialog === "edit") return apiRequest<Envelope<EvidenceDetail>>(`/api/v0/enforcement/evidence/${encodeURIComponent(selectedId!)}`, { method: "PUT", headers: headers(), body: JSON.stringify({ ...values, expectedVersion: detail.data!.recordVersion, collectionTime: values.collectionTime || null }) });
    if (!file) throw new Error("请选择证据文件");
    return apiRequest<Envelope<EvidenceDetail>>("/api/v0/enforcement/evidence", { method: "POST", headers: headers(), body: JSON.stringify({ ...values, collectionTime: values.collectionTime || null, fileName: file.name, mediaType: file.type || "application/octet-stream", contentBase64: await toBase64(file) }) });
  }, onSuccess: response => { messageApi.success(dialog === "edit" ? "证据元数据已生成新版本" : "证据材料已登记，等待人工核验"); setSelectedId(response.data.evidenceId); closeDialog(); refresh(); } });
  const changeStatus = useMutation({ mutationFn: ({ status, reason }: { status: string; reason: string }) => apiRequest<Envelope<EvidenceDetail>>(`/api/v0/enforcement/evidence/${encodeURIComponent(selectedId!)}/status`, { method: "POST", headers: headers(ENFORCEMENT_REVIEWER), body: JSON.stringify({ expectedVersion: detail.data!.recordVersion, status, reason }) }), onSuccess: response => { messageApi.success(`状态已更新为${statusLabels[response.data.status]}`); refresh(); } });
  const error = evidence.error || leads.error || detail.error || save.error || changeStatus.error;

  const openCreate = () => { form.resetFields(); setFile(undefined); setDialog("create"); };
  const openEdit = () => { if (!detail.data) return; form.setFieldsValue({ name: detail.data.name, type: detail.data.type, relatedLeadId: detail.data.relatedLeadId, collector: detail.data.collection.collector, collectionTime: detail.data.collection.collectionTime, collectionMissingReason: detail.data.collection.missingReason, physicalDescription: detail.data.physicalDescription, custodyInformation: detail.data.custodyInformation, reason: "核对来源后修订元数据" }); setDialog("edit"); };
  const closeDialog = () => { setDialog(undefined); setFile(undefined); form.resetFields(); };
  const resource = async (version: ContentVersion, action: "preview" | "download") => {
    try {
      const response = await fetch(`/api/v0/enforcement/evidence/${encodeURIComponent(selectedId!)}/versions/${version.version}/${action}`, { headers: headers() });
      if (!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.error?.message ?? "文件读取失败"); }
      const url = URL.createObjectURL(await response.blob());
      if (action === "preview") window.open(url, "_blank", "noopener,noreferrer");
      else { const anchor = document.createElement("a"); anchor.href = url; anchor.download = version.fileName ?? `evidence-${version.version}`; anchor.click(); }
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (cause) { messageApi.error(cause instanceof Error ? cause.message : "文件读取失败"); }
  };

  return <section className="workbench evidence-workspace">
    {messageContext}<WorkbenchHeader eyebrow="执法辅助" title="证据材料" description="本地受控证据登记、版本和人工核验。" tags={["DEVELOPMENT_ONLY", "NOT_EVIDENCE_ACCEPTANCE"]} />
    <div className="evidence-toolbar"><div><Typography.Title level={3}>证据材料</Typography.Title><Typography.Text type="secondary">本地 DM8 受控管理；核验不等于采信、真实或合法</Typography.Text></div><Space wrap><Button icon={<RefreshCw size={16} aria-hidden="true" />} onClick={refresh}>刷新</Button><Button type="primary" icon={<FilePlus2 size={16} aria-hidden="true" />} onClick={openCreate}>登记证据</Button></Space></div>
    <Alert type="warning" showIcon title="开发辅助记录，不形成证据采信或执法结论" /><RequestError error={error} retry={refresh} />
    <div className="evidence-filters"><Select allowClear placeholder="全部线索" value={filters.leadId} onChange={leadId => setFilters(value => ({ ...value, leadId }))} options={leads.data?.items.map(item => ({ value: item.leadId, label: `${item.sourceRecordId} · ${item.leadId}` }))} /><Select allowClear placeholder="全部类型" value={filters.type} onChange={type => setFilters(value => ({ ...value, type }))} options={Object.entries(typeLabels).map(([value, label]) => ({ value, label }))} /><Select allowClear placeholder="全部状态" value={filters.status} onChange={status => setFilters(value => ({ ...value, status }))} options={Object.entries(statusLabels).map(([value, label]) => ({ value, label }))} /></div>
    <div className="evidence-layout"><section aria-label="证据列表"><Table rowKey="evidenceId" size="small" loading={evidence.isLoading} dataSource={evidence.data?.items ?? []} pagination={false} onRow={record => ({ onClick: () => setSelectedId(record.evidenceId) })} rowClassName={record => record.evidenceId === selectedId ? "selected-row" : ""} columns={[{ title: "证据", render: (_, row: EvidenceSummary) => <div className="evidence-name"><strong>{row.name}</strong><span>{row.evidenceId}</span></div> }, { title: "类型", dataIndex: "type", render: value => typeLabels[value] ?? value, responsive: ["md"] }, { title: "状态", dataIndex: "status", render: value => <Tag color={value === "VERIFIED" ? "success" : value === "EXCLUDED" ? "error" : "processing"}>{statusLabels[value] ?? value}</Tag> }]} /></section>
      <aside className="evidence-detail" aria-label="证据详情">{!selected || !detail.data ? <Alert type="info" showIcon title="选择一份证据查看详情" /> : <><div className="evidence-detail-heading"><div><Typography.Title level={4}>{detail.data.name}</Typography.Title><Typography.Text type="secondary">记录版本 {detail.data.recordVersion}</Typography.Text></div><Button icon={<Pencil size={15} aria-hidden="true" />} onClick={openEdit}>修改元数据</Button></div>
        <Descriptions size="small" column={1} items={[{ key: "status", label: "核验状态", children: statusLabels[detail.data.status] }, { key: "lead", label: "关联线索", children: detail.data.relatedLeadId }, { key: "source", label: "接入渠道", children: detail.data.sourceChannel === "AUTHORIZED_UPLOAD" ? "授权人员上传" : "已授权本地引用" }, { key: "collector", label: "收集信息", children: detail.data.collection.collector ? `${detail.data.collection.collector} · ${detail.data.collection.collectionTime}` : `未知：${detail.data.collection.missingReason}` }]} />
        <div className="evidence-actions"><Button icon={<ShieldCheck size={16} aria-hidden="true" />} disabled={detail.data.status !== "PENDING_VERIFICATION"} loading={changeStatus.isPending} onClick={() => changeStatus.mutate({ status: "VERIFIED", reason: "授权测试人员已核对登记信息；不代表证据采信" })}>标记已核验</Button><Button danger disabled={detail.data.status === "ARCHIVED" || detail.data.status === "EXCLUDED"} onClick={() => changeStatus.mutate({ status: "EXCLUDED", reason: "人工排除当前记录，保留历史与引用" })}>标记排除</Button><Button disabled={detail.data.status === "ARCHIVED"} onClick={() => changeStatus.mutate({ status: "ARCHIVED", reason: "归档并保留历史与引用" })}>归档</Button></div>
        <Typography.Title level={5}><FileStack size={16} aria-hidden="true" /> 内容版本</Typography.Title><div className="evidence-version-list">{detail.data.versions.map(version => <article key={version.version}><div><strong>V{version.version} · {derivedLabels[version.derivedKind]}</strong><span>{version.fileName ?? version.controlledResourceRef}</span></div><div className="evidence-digest"><span>{version.digest ? `${version.digestAlgorithm} ${version.digest}` : "受控资源引用，无本地字节"}</span><Space><Button aria-label={`预览版本 ${version.version}`} icon={<Eye size={15} aria-hidden="true" />} disabled={!version.previewable} onClick={() => resource(version, "preview")} /><Button aria-label={`下载版本 ${version.version}`} icon={<Download size={15} aria-hidden="true" />} disabled={!version.fileName} onClick={() => resource(version, "download")} /></Space></div>{!version.previewable && version.fileName && <Typography.Text type="secondary">该格式无法在线预览，可在授权后下载。</Typography.Text>}</article>)}</div>
        <Typography.Title level={5}>状态历史</Typography.Title><div className="evidence-history">{detail.data.statusHistory.length ? detail.data.statusHistory.map((item, index) => <div key={`${item.occurredAt}-${index}`}><Tag>{statusLabels[item.fromStatus]} → {statusLabels[item.toStatus]}</Tag><span>{item.reason} · {item.actor}</span></div>) : <Typography.Text type="secondary">尚无状态变更</Typography.Text>}</div></>}</aside></div>
    <Modal title={dialog === "edit" ? "修改证据元数据" : "登记证据材料"} open={Boolean(dialog)} onCancel={closeDialog} footer={null} forceRender width={720}><Form form={form} layout="vertical" onFinish={values => save.mutate(values)}><div className="evidence-form-grid"><Form.Item name="name" label="证据名称" rules={[{ required: true, message: "请输入证据名称" }]}><Input /></Form.Item><Form.Item name="type" label="证据类型" rules={[{ required: true, message: "请选择证据类型" }]}><Select options={Object.entries(typeLabels).map(([value, label]) => ({ value, label }))} /></Form.Item><Form.Item name="relatedLeadId" label="关联线索" rules={[{ required: true, message: "请选择关联线索" }]}><Select disabled={dialog === "edit"} options={leads.data?.items.map(item => ({ value: item.leadId, label: `${item.sourceRecordId} · ${item.leadId}` }))} /></Form.Item><Form.Item name="collector" label="收集人"><Input /></Form.Item><Form.Item name="collectionTime" label="收集时间（ISO 8601）"><Input placeholder="2026-09-21T08:00:00Z" /></Form.Item><Form.Item name="collectionMissingReason" label="收集信息缺失原因"><Input placeholder="收集人或时间未知时必填" /></Form.Item></div><Form.Item name="physicalDescription" label="物证描述"><Input.TextArea rows={2} /></Form.Item><Form.Item name="custodyInformation" label="保管信息"><Input.TextArea rows={2} /></Form.Item>{dialog === "create" ? <Form.Item label="原始文件" required><Upload beforeUpload={value => { setFile(value); return false; }} maxCount={1} onRemove={() => setFile(undefined)}><Button icon={<FilePlus2 size={15} aria-hidden="true" />}>选择文件</Button></Upload></Form.Item> : <Form.Item name="reason" label="修订原因" rules={[{ required: true, message: "请输入修订原因" }]}><Input /></Form.Item>}<Space><Button type="primary" htmlType="submit" loading={save.isPending}>{dialog === "edit" ? "生成元数据版本" : "登记并计算摘要"}</Button><Button onClick={closeDialog}>取消</Button></Space></Form></Modal>
  </section>;
}
