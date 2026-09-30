import type { Dispatch, SetStateAction } from "react";
import { Button, Form, Input, Select, Typography, Upload } from "antd";
import type { UploadFile } from "antd";
import { FileUp, RefreshCw } from "lucide-react";

export type Clue = { id: string; observationId: string; ruleId: string; state: string; version: number };
export type Revision = { clueId: string; version: number; actor: string; correctedText: string; reason: string; materialGap: string };
export type Material = { id: string; clueId: string; sha256: string; state: string; collector: string };
export type MaterialAction = { revision: number; action: string; actor: string; sourceRef?: string;
  reason: string; fileSha256: string; occurredAt: string };
type Receipt = { requestId: string; operation: string; resultId: string; resultVersion: number };
type Context = {
  token: string | null; busy: boolean;
  onUnauthorized: () => void;
  request: <T>(path: string, body?: unknown) => Promise<T>;
  run: (action: () => Promise<void>) => Promise<void>;
  report: (receipt: Receipt) => void;
  clue: Clue | null; setClue: Dispatch<SetStateAction<Clue | null>>;
  revisions: Revision[]; setRevisions: Dispatch<SetStateAction<Revision[]>>;
  material: Material | null; setMaterial: Dispatch<SetStateAction<Material | null>>;
  materialActions: MaterialAction[]; setMaterialActions: Dispatch<SetStateAction<MaterialAction[]>>;
  file: UploadFile[]; setFile: Dispatch<SetStateAction<UploadFile[]>>;
};

async function fileBase64(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("文件读取失败"));
    reader.onload = () => resolve(String(reader.result).split(",")[1]);
    reader.readAsDataURL(file);
  });
}

export function flowTabs({ token, busy, onUnauthorized, request, run, report, clue, setClue, revisions,
  setRevisions, material, setMaterial, materialActions, setMaterialActions, file, setFile }: Context) {
  async function refreshMaterial(id: string) {
    setMaterial(await request<Material>(`/materials/${encodeURIComponent(id)}`));
    setMaterialActions(await request<MaterialAction[]>(`/materials/${encodeURIComponent(id)}/actions`));
  }
  return [
    { key: "clue", label: "线索与修订", children: <div className="enf3-columns">
      <section><Typography.Title level={4}>规则发现</Typography.Title>
        <Form layout="vertical" onFinish={(values) => void run(async () => {
          const receipt = await request<Receipt>("/discoveries", values); report(receipt);
          setClue(await request<Clue>(`/clues/${encodeURIComponent(receipt.resultId)}`));
        })}>
          <Form.Item name="observationId" label="观察 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="ruleId" label="已发布规则 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>生成待核线索</Button>
        </Form>
        <Form layout="inline" onFinish={(values) => void run(async () => {
          setClue(await request<Clue>(`/clues/${encodeURIComponent(values.id)}`));
          setRevisions(await request<Revision[]>(`/clues/${encodeURIComponent(values.id)}/revisions`));
        })}>
          <Form.Item name="id" label="线索 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Button htmlType="submit" icon={<RefreshCw size={16} />} aria-label="读取线索" />
        </Form>
      </section>
      <section><Typography.Title level={4}>人工修订</Typography.Title>
        {clue && <><div className="enf3-detail"><strong>{clue.id}</strong><span>{clue.state} · v{clue.version}</span></div>
          <Form layout="vertical" onFinish={(values) => void run(async () => {
            report(await request<Receipt>(`/clues/${encodeURIComponent(clue.id)}/revisions`,
              { ...values, expectedVersion: clue.version }));
            setClue(await request<Clue>(`/clues/${encodeURIComponent(clue.id)}`));
            setRevisions(await request<Revision[]>(`/clues/${encodeURIComponent(clue.id)}/revisions`));
          })}>
            <Form.Item name="correctedText" label="修订内容" rules={[{ required: true }]}><Input.TextArea rows={5} /></Form.Item>
            <Form.Item name="reason" label="修订理由" rules={[{ required: true }]}><Input /></Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>追加修订</Button>
          </Form>
          {revisions.map(item => <div className="enf3-revision" key={item.version}>
            <strong>v{item.version} · {item.actor}</strong><p>{item.correctedText}</p><small>{item.reason} · {item.materialGap}</small>
          </div>)}</>}
      </section>
    </div> },
    { key: "material", label: "材料", children: <div className="enf3-columns">
      <section><Typography.Title level={4}>登记原件</Typography.Title>
        <Form layout="vertical" onFinish={(values) => void run(async () => {
          const selected = file[0]?.originFileObj;
          if (!selected) throw new Error("请选择原件");
          const receipt = await request<Receipt>("/materials", { ...values,
            expiresAt: new Date(values.expiresAt).toISOString(), bytes: await fileBase64(selected) });
          report(receipt); await refreshMaterial(receipt.resultId);
        })}>
          <Form.Item name="clueId" label="线索 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="collector" label="申报收集人" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="collectedAt" label="收集时间" rules={[{ required: true }]}><Input type="datetime-local" /></Form.Item>
          <Form.Item name="rightsRef" label="文件用途依据" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="expiresAt" label="文件有效至" rules={[{ required: true }]}><Input type="datetime-local" /></Form.Item>
          <Form.Item name="uses" label="文件允许用途" rules={[{ required: true }]}>
            <Select mode="multiple" options={["SAVE", "PREVIEW", "DOWNLOAD"].map(value => ({ value, label: value }))} />
          </Form.Item>
          <Upload fileList={file} beforeUpload={() => false} onChange={({ fileList }) => setFile(fileList.slice(-1))}
            maxCount={1}><Button icon={<FileUp size={16} />}>选择原件</Button></Upload>
          <div className="enf3-form-action"><Button type="primary" htmlType="submit" loading={busy}>登记材料</Button></div>
        </Form>
      </section>
      <section><Typography.Title level={4}>材料记录</Typography.Title>
        <Form layout="inline" onFinish={(values) => void run(async () => refreshMaterial(values.id))}>
          <Form.Item name="id" label="材料 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Button htmlType="submit" icon={<RefreshCw size={16} />} aria-label="读取材料" />
        </Form>
        {material && <div className="enf3-detail"><strong>{material.id}</strong>
          <span>{material.state} · {material.collector}</span><code>{material.sha256}</code>
          <Button onClick={() => void run(async () => {
            if (!token) return;
            const response = await fetch(`/v2/enforcement/materials/${encodeURIComponent(material.id)}/preview`,
              { headers: { Authorization: `Bearer ${token}` } });
            if (response.status === 401 || response.status === 403) {
              onUnauthorized();
              throw new Error("当前授权不可用，请重新登录");
            }
            if (!response.ok) throw new Error("当前材料不可预览");
            const url = URL.createObjectURL(await response.blob());
            window.open(url, "_blank", "noopener,noreferrer");
            window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
          })}>预览原件</Button>
        </div>}
        {material?.state === "PENDING_VERIFICATION" && materialActions.length === 1 &&
          <Form layout="vertical" onFinish={(values) => void run(async () => {
            report(await request<Receipt>(`/materials/${encodeURIComponent(material.id)}/collection`,
              { ...values, expectedVersion: materialActions.at(-1)?.revision }));
            await refreshMaterial(material.id);
          })}>
            <Form.Item name="sourceRef" label="原件来源定位" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="reason" label="收集方式" rules={[{ required: true }]}><Input /></Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>确认收集</Button>
          </Form>}
        {material?.state === "PENDING_VERIFICATION" && materialActions.some(a => a.action === "COLLECT_ATTEST") &&
          <Form layout="vertical" onFinish={(values) => void run(async () => {
            report(await request<Receipt>(`/materials/${encodeURIComponent(material.id)}/verification`,
              { ...values, expectedVersion: materialActions.at(-1)?.revision }));
            await refreshMaterial(material.id);
          })}>
            <Form.Item name="reason" label="核验记录" rules={[{ required: true }]}><Input /></Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>核验原件</Button>
          </Form>}
        {materialActions.map(action => <div className="enf3-detail" key={action.revision}>
          <strong>v{action.revision} · {action.action}</strong>
          <span>{action.actor} · {action.occurredAt}</span><span>{action.sourceRef} · {action.reason}</span>
          <code>{action.fileSha256}</code>
        </div>)}
      </section>
    </div> }
  ];
}
