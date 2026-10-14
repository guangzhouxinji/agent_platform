import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, Select, Table, Tag, Typography, Upload } from "antd";
import type { UploadFile } from "antd";
import { Download, FilePlus2, LogIn, LogOut, Plus, RefreshCw } from "lucide-react";
import { beginEnforcementLogin, completeEnforcementLogin, enforcementDownload, enforcementRequest, enforcementUpload, readEnforcementIdentity, endEnforcementLogin, EnforcementAccessDenied } from "./enforcementNextV2Api";
import "./EnforcementNextV2Page.css";

type PageOperation = { epoch: number; operation: number; token: string | null };
const staleOperation = Symbol("discarded page operation");

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

  const [uploadForm] = Form.useForm();
  const [previewForm] = Form.useForm();
  const [transitionForm] = Form.useForm();
  const lifecycle = useRef({ mounted: false, epoch: 0, operation: 0, token: null as string | null, working: false, loggingOut: false });
  const [loggingOut, setLoggingOut] = useState(false);

  function isCurrent(context: PageOperation) {
    const live = lifecycle.current;
    return live.mounted && live.epoch === context.epoch && live.operation === context.operation && live.token === context.token;
  }
  function assertCurrent(context: PageOperation) { if (!isCurrent(context)) throw staleOperation; }
  // Invalidate synchronously; pending transports and form validation cannot revive this page.
  function clearSession() {
    const live = lifecycle.current;
    live.epoch++; live.operation++; live.token = null; live.working = false;
    setToken(null); setOrganization(""); setBusy(false);
    setTemplates([]); setCurrent(null); setPreview(null); setFileList([]);
    uploadForm.resetFields(); previewForm.resetFields(); transitionForm.resetFields(); 
  }
  async function guarded<T>(context: PageOperation, action: () => Promise<T>): Promise<T> {
    assertCurrent(context);
    try { const value = await action(); assertCurrent(context); return value; }
    catch (failure) { assertCurrent(context); throw failure; }
  }
  function request<T>(context: PageOperation, sessionToken: string, path: string, body?: unknown, key?: string) {
    assertCurrent(context);
    if (sessionToken !== context.token) throw staleOperation;
    return guarded(context, () => enforcementRequest<T>(sessionToken, path, body, key));
  }
  function upload<T>(context: PageOperation, sessionToken: string, path: string, body: FormData) {
    assertCurrent(context);
    if (sessionToken !== context.token) throw staleOperation;
    return guarded(context, () => enforcementUpload<T>(sessionToken, path, body));
  }
  function downloadFile(context: PageOperation, sessionToken: string, path: string) {
    assertCurrent(context);
    if (sessionToken !== context.token) throw staleOperation;
    return guarded(context, () => enforcementDownload(sessionToken, path));
  }
  useEffect(() => {
    const live = lifecycle.current;
    live.mounted = true; live.epoch++; live.operation++;
    const context: PageOperation = { epoch: live.epoch, operation: live.operation, token: null };
    void (async () => {
      try {
        const value = await guarded(context, () => completeEnforcementLogin("document-templates"));
        assertCurrent(context);
        if (!value) return;
        live.token = value; context.token = value; setToken(value);
        const identity = await guarded(context, () => readEnforcementIdentity(value));
        assertCurrent(context);
        const organization = identity.grants.find(grant => grant.resourceType === "DOCUMENT_TEMPLATE")?.organization;
        if (!organization) throw new Error("身份上下文不可用");
        setOrganization(organization);
      } catch (failure) {
        if (!isCurrent(context)) return;
        clearSession();
        setError(failure instanceof Error ? failure.message : "登录失败");
      }
    })();
    return () => { live.mounted = false; live.epoch++; live.operation++; live.token = null; live.working = false; };
  }, []);

  // Include operation identity: an old catch/finally cannot clear a later login error/busy.
  async function run(action: (context: PageOperation) => Promise<void>) {
    const live = lifecycle.current;
    if (!live.mounted || live.working || live.loggingOut || live.token !== token || (live.token && !organization)) return;
    live.working = true; live.operation++;
    const context = { epoch: live.epoch, operation: live.operation, token: live.token };
    setBusy(true); setError("");
    try { await action(context); assertCurrent(context); }
    catch (failure) {
      if (!isCurrent(context)) return;
      if (failure instanceof EnforcementAccessDenied) clearSession();
      setError(failure instanceof Error ? failure.message : "请求失败");
    } finally {
      if (isCurrent(context)) { live.working = false; setBusy(false); }
    }
  }
  async function logout() {
    const live = lifecycle.current;
    if (!live.mounted || live.loggingOut || !live.token) return;
    const capturedToken = live.token;
    clearSession(); setError(""); live.loggingOut = true; setLoggingOut(true); setBusy(true);
    const context = { epoch: live.epoch, operation: live.operation, token: null };
    try { await endEnforcementLogin(capturedToken); assertCurrent(context); }
    catch (failure) { if (isCurrent(context)) setError(failure instanceof Error ? failure.message : "退出失败"); }
    finally { if (isCurrent(context)) { live.loggingOut = false; setLoggingOut(false); setBusy(false); } }
  }
  async function refresh(context: PageOperation) {
    assertCurrent(context); if (!token || !organization) return;
    const value = await request<Summary[]>(context, token, `/document-templates?organization=${encodeURIComponent(organization)}`);
    assertCurrent(context); setTemplates(value);
  }
  async function read(context: PageOperation, id: string, revision?: number) {
    assertCurrent(context); if (!token) return;
    transitionForm.resetFields(); previewForm.resetFields(); setPreview(null);
    const value = await request<Revision>(context, token, `/document-templates/${encodeURIComponent(id)}${revision ? `/revisions/${revision}` : ""}`);
    assertCurrent(context); setCurrent(value); setPreview(null);
  }

  const renderedEpoch = lifecycle.current.epoch;
  return <main className="enf-next" aria-busy={busy || loggingOut}><header><div><Typography.Title level={2}>执法文书模板</Typography.Title><span>上传、预览与版本管理；不产生正式文号、签章或签发状态</span></div>
    {token ? <Button icon={<LogOut size={16} aria-hidden="true" />} disabled={loggingOut} onClick={() => void logout()}>退出</Button>
      : <Button disabled={busy} type="primary" icon={<LogIn size={16} aria-hidden="true" />} onClick={() => void run(() => beginEnforcementLogin("document-templates"))}>登录</Button>}</header>
    {error && <Alert type="error" showIcon role="alert" title={error} />}
    {!(token && organization) ? <div className="enf-next-locked">登录后维护模板及不可变版本</div> : <div className="enf-next-grid"><section>
      <Typography.Title level={4}>上传模板草稿</Typography.Title>
      <Form form={uploadForm} disabled={busy} layout="vertical" initialValues={{ fields: [{ required: true, repeat: false }], fontNames: "SimSun" }}
        onFinish={values => void run(async context => {
          const file = fileList[0]?.originFileObj; if (!file) throw new Error("请选择 DOCX 原件");
          const metadata = { templateId: values.templateId, name: values.name, stage: values.stage,
            scope: { organization, region: values.region, procedure: values.procedure }, approvalRef: values.approvalRef || null,
            fields: values.fields, fontNames: String(values.fontNames).split(/[,，\n]/).map(value => value.trim()).filter(Boolean) };
          const body = new FormData(); body.append("metadata", JSON.stringify(metadata)); body.append("file", file);
          const result = await upload<Receipt>(context, token, "/document-templates", body); assertCurrent(context); setFileList([]); await read(context, result.templateId, result.revision); assertCurrent(context); await refresh(context); assertCurrent(context);
        })}>
        <Form.Item name="templateId" label="模板 ID" rules={[{ required: true }]}><Input /></Form.Item>
        <div className="enf-next-row"><Form.Item name="name" label="模板名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="stage" label="业务环节" rules={[{ required: true }]}><Input /></Form.Item></div>
        <div className="enf-next-row"><Form.Item name="region" label="地区" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name="procedure" label="程序" rules={[{ required: true }]}><Input /></Form.Item></div>
        <Form.Item label="当前组织"><Input value={organization} disabled /></Form.Item><Form.Item name="approvalRef" label="批准依据 Ref"><Input /></Form.Item>
        <Form.List name="fields">{(fields, { add, remove }) => <>{fields.map(field => <div className="enf-next-form-list" key={field.key}>
          <div className="enf-next-row"><Form.Item name={[field.name, "id"]} label="占位符 ID" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name={[field.name, "type"]} label="字段类型" rules={[{ required: true }]}><Input /></Form.Item></div>
          <div className="enf-next-row"><Form.Item name={[field.name, "required"]} label="必填"><Select options={[{ value: true, label: "是" }, { value: false, label: "否" }]} /></Form.Item><Form.Item name={[field.name, "repeat"]} label="重复区块"><Select options={[{ value: true, label: "是" }, { value: false, label: "否" }]} /></Form.Item></div>
          <Button disabled={busy || (fields.length === 1)} onClick={() => remove(field.name)}>移除字段</Button></div>)}<Button disabled={busy} icon={<Plus size={16} aria-hidden="true" />} onClick={() => add({ required: false, repeat: false })}>添加字段</Button></>}</Form.List>
        <Form.Item name="fontNames" label="字体（逗号或换行分隔）"><Input /></Form.Item>
        <Form.Item label="DOCX 原件" required><Upload disabled={busy} accept=".docx" maxCount={1} fileList={fileList} beforeUpload={() => false} onChange={event => { const live = lifecycle.current; if (live.mounted && live.epoch === renderedEpoch && live.token === token && !live.working && !live.loggingOut) setFileList(event.fileList); }}><Button disabled={busy} icon={<FilePlus2 size={16} aria-hidden="true" />}>选择文件</Button></Upload></Form.Item>
        <Button disabled={busy} type="primary" htmlType="submit" loading={busy}>保存模板草稿</Button>
      </Form></section><section><div className="enf-next-heading"><Typography.Title level={4}>模板版本</Typography.Title><Button disabled={busy} icon={<RefreshCw size={16} aria-hidden="true" />} loading={busy} onClick={() => void run(refresh)}>刷新</Button></div>
        <Table rowKey="templateId" size="small" pagination={false} dataSource={templates} locale={{ emptyText: "刷新后查看当前组织模板" }} onRow={row => ({ onClick: () => void run(context => read(context, row.templateId, row.currentRevision)) })} columns={[
          { title: "模板", dataIndex: "name" }, { title: "版本", dataIndex: "currentRevision" }, { title: "状态", dataIndex: "state", render: value => <Tag>{String(value)}</Tag> }
        ]} />
        {current ? <div className="enf-next-detail"><Descriptions bordered size="small" column={1} items={[
          { key: "ref", label: "模板版本", children: `${current.templateId} v${current.revision}` }, { key: "state", label: "状态", children: current.state },
          { key: "scope", label: "范围", children: `${current.scope.region} · ${current.scope.procedure} · ${current.stage}` }, { key: "sha", label: "原件 SHA", children: current.fileSha256 },
          { key: "evidence", label: "已记录转换证据引用", children: current.transitionEvidenceRef || "无" },
          { key: "actor", label: "创建", children: `${current.createdBy} · ${current.createdAt}` }, { key: "fields", label: "字段", children: current.fields.map(field => field.id).join("；") || "无" }
        ]} /><div className="enf-next-actions"><Button disabled={busy} icon={<Download size={16} aria-hidden="true" />} onClick={() => void run(async context => { const blob = await downloadFile(context, token, `/document-templates/${current.templateId}/revisions/${current.revision}/file`); assertCurrent(context); download(blob, current.fileName); })}>下载原版</Button>
          {current.state === "DRAFT" && <Form form={transitionForm} disabled={busy} layout="vertical" onFinish={values => void run(async context => {
            const evidenceRef = String(values.evidenceRef ?? "").trim();
            if (!evidenceRef) throw new Error("请填写本次发布证据引用");
            await request(context, token, `/document-templates/${encodeURIComponent(current.templateId)}/revisions/${current.revision}/publish`, { expectedRevision: current.revision, evidenceRef }, crypto.randomUUID());
            assertCurrent(context); await read(context, current.templateId, current.revision); assertCurrent(context); await refresh(context); assertCurrent(context);
          })}>
            <Form.Item name="evidenceRef" label="发布证据引用" rules={[{ required: true, whitespace: true, message: "请填写本次发布证据引用" }]}><Input /></Form.Item>
            <Button disabled={busy} loading={busy} type="primary" htmlType="submit">发布</Button>
          </Form>}
          {current.state === "PUBLISHED" && <Form form={transitionForm} disabled={busy} layout="vertical" onFinish={values => void run(async context => {
            const evidenceRef = String(values.evidenceRef ?? "").trim();
            if (!evidenceRef) throw new Error("请填写本次停用证据引用");
            await request(context, token, `/document-templates/${encodeURIComponent(current.templateId)}/revisions/${current.revision}/disable`, { expectedRevision: current.revision, evidenceRef }, crypto.randomUUID());
            assertCurrent(context); await read(context, current.templateId, current.revision); assertCurrent(context); await refresh(context); assertCurrent(context);
          })}>
            <Form.Item name="evidenceRef" label="停用证据引用" rules={[{ required: true, whitespace: true, message: "请填写本次停用证据引用" }]}><Input /></Form.Item>
            <Button disabled={busy} loading={busy} danger htmlType="submit">停用</Button>
          </Form>}
          </div>
          <Form form={previewForm} disabled={busy} layout="vertical" onFinish={values => void run(async context => { const value = await request<Preview>(context, token, `/document-templates/${current.templateId}/revisions/${current.revision}/previews`, { expectedRevision: current.revision, values: JSON.parse(values.values) }, crypto.randomUUID()); assertCurrent(context); setPreview(value); })}><Form.Item name="values" label="样例字段 JSON" rules={[{ required: true }]}><Input.TextArea rows={4} /></Form.Item><Button disabled={busy} htmlType="submit">生成样例预览</Button></Form>
          {preview && <Alert type={preview.diagnostics.length ? "warning" : "success"} showIcon title={`预览 ${preview.previewId} · ${preview.rendererVersion}`} description={preview.diagnostics.join("；") || preview.artifactSha256} action={<Button disabled={busy} onClick={() => void run(async context => { const blob = await downloadFile(context, token, `/document-templates/${current.templateId}/previews/${preview.previewId}/file`); assertCurrent(context); download(blob, `preview-${preview.previewId}.docx`); })}>下载</Button>} />}
        </div> : <Empty description="选择模板查看确切版本" />}
      </section></div>}
  </main>;
}
