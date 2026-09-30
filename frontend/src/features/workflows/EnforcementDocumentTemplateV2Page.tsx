import { useEffect, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, Select, Table, Tag, Typography, Upload } from "antd";
import type { UploadFile } from "antd";
import { Download, FilePlus2, LogIn, LogOut, Plus, RefreshCw } from "lucide-react";
import { beginEnforcementLogin, completeEnforcementLogin, enforcementDownload, enforcementRequest, enforcementUpload, readEnforcementIdentity } from "./enforcementNextV2Api";
import "./EnforcementNextV2Page.css";

type Field = { id: string; type: string; required: boolean; repeat: boolean; format?: string };
type Summary = { templateId: string; currentRevision: number; state: string; name: string; organization: string; fileSha256: string };
type Revision = Summary & { revision: number; stage: string; scope: { organization: string; region: string; procedure: string }; approvalRef?: string; fields: Field[]; fontNames: string[]; fileName: string; createdBy: string; createdAt: string; transitionEvidenceRef?: string };
type Receipt = { templateId: string; revision: number; state: string; fileSha256: string };
type Preview = { previewId: string; templateId: string; revision: number; artifactSha256: string; rendererVersion: string; diagnostics: string[] };

function download(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob); const anchor = document.createElement("a");
  anchor.href = url; anchor.download = name; anchor.click(); URL.revokeObjectURL(url);
}

export default function EnforcementDocumentTemplateV2Page() {
  const [token, setToken] = useState<string | null>(null);
  const [organization, setOrganization] = useState("");
  const [templates, setTemplates] = useState<Summary[]>([]);
  const [current, setCurrent] = useState<Revision | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [busy, setBusy] = useState(false); const [error, setError] = useState("");

  useEffect(() => { void completeEnforcementLogin("document-templates").then(async value => {
    setToken(value); if (!value) return; const identity = await readEnforcementIdentity(value);
    setOrganization(identity.grants.find(grant => grant.resourceType === "DOCUMENT_TEMPLATE")?.organization ?? identity.grants[0]?.organization ?? "");
  }).catch(failure => setError(failure instanceof Error ? failure.message : "登录失败")); }, []);

  async function run(action: () => Promise<void>) { setBusy(true); setError(""); try { await action(); } catch (failure) { setError(failure instanceof Error ? failure.message : "请求失败"); } finally { setBusy(false); } }
  async function refresh() { if (!token || !organization) return; setTemplates(await enforcementRequest<Summary[]>(token, `/document-templates?organization=${encodeURIComponent(organization)}`)); }
  async function read(id: string, revision?: number) { if (!token) return; setCurrent(await enforcementRequest<Revision>(token, `/document-templates/${encodeURIComponent(id)}${revision ? `/revisions/${revision}` : ""}`)); setPreview(null); }

  return <main className="enf-next"><header><div><Typography.Title level={2}>执法文书模板</Typography.Title><span>上传、预览与版本管理；不产生正式文号、签章或签发状态</span></div>
    {token ? <Button icon={<LogOut size={16} aria-hidden="true" />} onClick={() => { setToken(null); setTemplates([]); setCurrent(null); }}>退出</Button>
      : <Button type="primary" icon={<LogIn size={16} aria-hidden="true" />} onClick={() => void run(() => beginEnforcementLogin("document-templates"))}>登录</Button>}</header>
    {error && <Alert type="error" showIcon role="alert" title={error} />}
    {!token ? <div className="enf-next-locked">登录后维护模板及不可变版本</div> : <div className="enf-next-grid"><section>
      <Typography.Title level={4}>上传模板草稿</Typography.Title>
      <Form layout="vertical" initialValues={{ fields: [{ required: true, repeat: false }], fontNames: "SimSun" }}
        onFinish={values => void run(async () => {
          const file = fileList[0]?.originFileObj; if (!file) throw new Error("请选择 DOCX 原件");
          const metadata = { templateId: values.templateId, name: values.name, stage: values.stage,
            scope: { organization, region: values.region, procedure: values.procedure }, approvalRef: values.approvalRef || null,
            fields: values.fields, fontNames: String(values.fontNames).split(/[,，\n]/).map(value => value.trim()).filter(Boolean) };
          const body = new FormData(); body.append("metadata", JSON.stringify(metadata)); body.append("file", file);
          const result = await enforcementUpload<Receipt>(token, "/document-templates", body); setFileList([]); await read(result.templateId, result.revision); await refresh();
        })}>
        <Form.Item name="templateId" label="模板 ID" rules={[{ required: true }]}><Input /></Form.Item>
        <div className="enf-next-row"><Form.Item name="name" label="模板名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="stage" label="业务环节" rules={[{ required: true }]}><Input /></Form.Item></div>
        <div className="enf-next-row"><Form.Item name="region" label="地区" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="procedure" label="程序" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Form.Item label="当前组织"><Input value={organization} disabled /></Form.Item><Form.Item name="approvalRef" label="批准依据 Ref（项目验证可留空）"><Input /></Form.Item>
        <Form.List name="fields">{(fields, { add, remove }) => <>{fields.map(field => <div className="enf-next-form-list" key={field.key}>
          <div className="enf-next-row"><Form.Item name={[field.name, "id"]} label="占位符 ID" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name={[field.name, "type"]} label="字段类型" rules={[{ required: true }]}><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={[field.name, "required"]} label="必填"><Select options={[{ value: true, label: "是" }, { value: false, label: "否" }]} /></Form.Item><Form.Item name={[field.name, "repeat"]} label="重复区块"><Select options={[{ value: true, label: "是" }, { value: false, label: "否" }]} /></Form.Item></div>
          <Button disabled={fields.length === 1} onClick={() => remove(field.name)}>移除字段</Button></div>)}<Button icon={<Plus size={16} aria-hidden="true" />} onClick={() => add({ required: false, repeat: false })}>添加字段</Button></>}</Form.List>
        <Form.Item name="fontNames" label="字体（逗号或换行分隔）"><Input /></Form.Item>
        <Form.Item label="DOCX 原件" required><Upload accept=".docx" maxCount={1} fileList={fileList} beforeUpload={() => false} onChange={event => setFileList(event.fileList)}><Button icon={<FilePlus2 size={16} aria-hidden="true" />}>选择文件</Button></Upload></Form.Item>
        <Button type="primary" htmlType="submit" loading={busy}>保存模板草稿</Button>
      </Form></section><section><div className="enf-next-heading"><Typography.Title level={4}>模板版本</Typography.Title><Button icon={<RefreshCw size={16} aria-hidden="true" />} loading={busy} disabled={!organization} onClick={() => void run(refresh)}>刷新</Button></div>
        <Table rowKey="templateId" size="small" pagination={false} dataSource={templates} locale={{ emptyText: "刷新后查看当前组织模板" }} onRow={row => ({ onClick: () => void run(() => read(row.templateId, row.currentRevision)) })} columns={[
          { title: "模板", dataIndex: "name" }, { title: "版本", dataIndex: "currentRevision" }, { title: "状态", dataIndex: "state", render: value => <Tag>{String(value)}</Tag> }
        ]} />
        {current ? <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[
          { key: "ref", label: "模板版本", children: `${current.templateId} v${current.revision}` }, { key: "state", label: "状态", children: current.state },
          { key: "scope", label: "范围", children: `${current.scope.region} · ${current.scope.procedure} · ${current.stage}` }, { key: "sha", label: "原件 SHA", children: current.fileSha256 },
          { key: "actor", label: "创建", children: `${current.createdBy} · ${current.createdAt}` }, { key: "fields", label: "字段", children: current.fields.map(field => field.id).join("；") || "无" }
        ]} /><div className="enf-next-actions"><Button icon={<Download size={16} aria-hidden="true" />} onClick={() => void run(async () => download(await enforcementDownload(token, `/document-templates/${current.templateId}/revisions/${current.revision}/file`), current.fileName))}>下载原版</Button>
          {current.state === "DRAFT" && <Button type="primary" onClick={() => void run(async () => { await enforcementRequest(token, `/document-templates/${current.templateId}/revisions/${current.revision}/publish`, { expectedRevision: current.revision, evidenceRef: "PROJECT-FUNCTIONAL-VALIDATION" }, crypto.randomUUID()); await read(current.templateId, current.revision); await refresh(); })}>发布</Button>}
          {current.state === "PUBLISHED" && <Button danger onClick={() => void run(async () => { await enforcementRequest(token, `/document-templates/${current.templateId}/revisions/${current.revision}/disable`, { expectedRevision: current.revision, evidenceRef: "PROJECT-FUNCTIONAL-VALIDATION" }, crypto.randomUUID()); await read(current.templateId, current.revision); await refresh(); })}>停用</Button>}</div>
          <Form layout="vertical" onFinish={values => void run(async () => setPreview(await enforcementRequest<Preview>(token, `/document-templates/${current.templateId}/revisions/${current.revision}/previews`, { expectedRevision: current.revision, values: JSON.parse(values.values) }, crypto.randomUUID())))}><Form.Item name="values" label="样例字段 JSON" rules={[{ required: true }]}><Input.TextArea rows={4} /></Form.Item><Button htmlType="submit">生成样例预览</Button></Form>
          {preview && <Alert type={preview.diagnostics.length ? "warning" : "success"} showIcon title={`预览 ${preview.previewId} · ${preview.rendererVersion}`} description={preview.diagnostics.join("；") || preview.artifactSha256} action={<Button onClick={() => void run(async () => download(await enforcementDownload(token, `/document-templates/${current.templateId}/previews/${preview.previewId}/file`), `preview-${preview.previewId}.docx`))}>下载</Button>} />}
        </div> : <Empty description="选择模板查看确切版本" />}
      </section></div>}
  </main>;
}
