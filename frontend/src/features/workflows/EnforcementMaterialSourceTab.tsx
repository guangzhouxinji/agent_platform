import { useState } from "react";
import { Button, Form, Input, Select, Switch, Typography } from "antd";
import { RefreshCw } from "lucide-react";

type Receipt = { requestId: string; operation: string; resultId: string; resultVersion: number };
type SyncResult = { sourceId: string; startCursor: string; cursor: string; processed: number;
  attemptId: string; state: string; failureCode?: string };
type SourceConfiguration = { id: string; configVersion: number; credentialRef: string; active: boolean };
type SyncRecord = { ordinal: number; recordId?: string; sourceVersion?: string;
  beforeCursor: string; nextCursor?: string; state: string; failureCode?: string };

type Props = { busy: boolean; request: <T>(path: string, body?: unknown) => Promise<T>;
  run: (action: () => Promise<void>) => Promise<void>; report: (receipt: Receipt) => void };

export default function EnforcementMaterialSourceTab({ busy, request, run, report }: Props) {
  const [sourceConfig, setSourceConfig] = useState<SourceConfiguration | null>(null);
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null);
  const [syncRecords, setSyncRecords] = useState<SyncRecord[]>([]);
  return <>
    <Typography.Title level={4}>来源</Typography.Title>
    <Form layout="vertical" onFinish={(values) => void run(async () => {
      const receipt = await request<Receipt>("/sources", { ...values, epoch: Number(values.epoch),
        expiresAt: new Date(values.expiresAt).toISOString() }); report(receipt);
      setSourceConfig(await request<SourceConfiguration>(`/sources/${encodeURIComponent(receipt.resultId)}`));
    })}>
      <Form.Item name="id" label="来源 ID" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item name="organization" label="组织" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item name="rightsRef" label="用途依据" rules={[{ required: true }]}><Input /></Form.Item>
      <Form.Item name="credentialRef" label="连接凭据 Ref" rules={[{ required: true },
        { pattern: /^[A-Za-z0-9._:-]{1,120}$/, message: "请输入已配置的凭据 Ref" }]}><Input /></Form.Item>
      <div className="enf3-pair"><Form.Item name="epoch" label="来源水位" rules={[{ required: true }]}><Input type="number" min={1} /></Form.Item>
        <Form.Item name="expiresAt" label="有效至" rules={[{ required: true }]}><Input type="datetime-local" /></Form.Item></div>
      <Form.Item name="uses" label="允许用途" rules={[{ required: true }]}>
        <Select mode="multiple" options={["QUERY", "SAVE", "DISPLAY", "INDEX"].map(value => ({ value, label: value }))} />
      </Form.Item>
      <Button type="primary" htmlType="submit" loading={busy}>登记来源</Button>
    </Form>
    <Form layout="inline" onFinish={(values) => void run(async () => {
      setSourceConfig(await request<SourceConfiguration>(`/sources/${encodeURIComponent(values.sourceId)}`));
    })}>
      <Form.Item name="sourceId" label="配置来源 ID" rules={[{ required: true }]}><Input /></Form.Item>
      <Button htmlType="submit" loading={busy}>读取配置</Button>
    </Form>
    {sourceConfig && <>
      <div className="enf3-detail"><strong>{sourceConfig.id}</strong>
        <span>配置 v{sourceConfig.configVersion} · {sourceConfig.active ? "启用" : "停用"}</span></div>
      <Form key={`${sourceConfig.id}-${sourceConfig.configVersion}`} layout="inline"
        initialValues={{ credentialRef: sourceConfig.credentialRef, enabled: sourceConfig.active }}
        onFinish={(values) => void run(async () => {
          report(await request<Receipt>(`/sources/${encodeURIComponent(sourceConfig.id)}/configuration`,
            { expectedVersion: sourceConfig.configVersion, credentialRef: values.credentialRef,
              enabled: values.enabled }));
          setSourceConfig(await request<SourceConfiguration>(`/sources/${encodeURIComponent(sourceConfig.id)}`));
        })}>
        <Form.Item name="credentialRef" label="连接凭据 Ref" rules={[{ required: true },
          { pattern: /^[A-Za-z0-9._:-]{1,120}$/, message: "请输入已配置的凭据 Ref" }]}><Input /></Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
        <Button htmlType="submit" loading={busy}>更新配置</Button>
      </Form>
    </>}
    <Form layout="inline" onFinish={(values) => void run(async () => {
      const result = await request<SyncResult>(`/sources/${encodeURIComponent(values.sourceId)}/syncs`, {});
      setSyncResult(result);
      setSyncRecords(await request<SyncRecord[]>(`/sources/${encodeURIComponent(values.sourceId)}/sync-attempts/${encodeURIComponent(result.attemptId)}/records`));
    })}>
      <Form.Item name="sourceId" label="同步来源 ID" rules={[{ required: true }]}><Input /></Form.Item>
      <Button htmlType="submit" loading={busy} icon={<RefreshCw size={16} />}>同步一页</Button>
    </Form>
    {syncResult && <div className="enf3-detail" role="status">
      <strong>{syncResult.state}</strong><span>{syncResult.sourceId} · {syncResult.startCursor} → {syncResult.cursor}</span>
      <span>{syncResult.processed} 条{syncResult.failureCode ? ` · ${syncResult.failureCode}` : ""}</span>
    </div>}
    {syncRecords.map(record => <div className="enf3-detail" key={record.ordinal}>
      <strong>{record.recordId || `#${record.ordinal}`} · {record.sourceVersion || "未提供版本"}</strong>
      <span>{record.beforeCursor} → {record.nextCursor || "未推进"}</span>
      <span>{record.state}{record.failureCode ? ` · ${record.failureCode}` : ""}</span>
    </div>)}
  </>;
}
