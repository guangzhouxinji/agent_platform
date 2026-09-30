import { useEffect, useRef, useState } from "react";
import { Alert, Button, Descriptions, Empty, Form, Input, InputNumber, List, Space, Tag, Typography } from "antd";
import { LogIn, LogOut, RefreshCw } from "lucide-react";
import { beginHandoffLogin, completeHandoffLogin, handoffRequest, HandoffAccessDenied,
  HandoffConflict, type HandoffPackage, type HandoffReceipt, type HandoffRequest,
  type HandoffResult } from "./handoffV2Api";
import "./EnforcementHandoffV2Page.css";

export default function EnforcementHandoffV2Page() {
  const [token, setToken] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [pack, setPack] = useState<HandoffPackage | null>(null);
  const [tasks, setTasks] = useState<HandoffRequest[]>([]);
  const [selected, setSelected] = useState<HandoffRequest | null>(null);
  const [result, setResult] = useState<HandoffResult | null>(null);
  const [receipt, setReceipt] = useState<HandoffReceipt | null>(null);
  const [lookupId, setLookupId] = useState("");
  const [lookupVersion, setLookupVersion] = useState<number | null>(null);
  const pending = useRef<{ fingerprint: string; key: string } | null>(null);

  useEffect(() => {
    void completeHandoffLogin().then(value => { if (value) setToken(value); })
      .catch(failure => setError(failure instanceof Error ? failure.message : "登录失败"));
  }, []);

  useEffect(() => {
    if (!token) return;
    void handoffRequest<HandoffRequest[]>(token, "/human-tasks")
      .then(setTasks).catch(failure => {
        if (failure instanceof HandoffAccessDenied) clear();
        setError(failure instanceof Error ? failure.message : "待办读取失败");
      });
  }, [token]);

  function clear() {
    setToken(null); setPack(null); setTasks([]); setSelected(null); setResult(null); setReceipt(null);
    pending.current = null;
  }

  async function request<T>(path: string, body?: unknown): Promise<T> {
    if (!token) throw new Error("请先登录");
    const fingerprint = `${path}\u0000${JSON.stringify(body)}`;
    if (body !== undefined && pending.current?.fingerprint !== fingerprint)
      pending.current = { fingerprint, key: crypto.randomUUID() };
    setBusy(true); setError("");
    try {
      const value = await handoffRequest<T>(token, path, body, pending.current?.key);
      if (body !== undefined) pending.current = null;
      return value;
    } catch (failure) {
      if (failure instanceof HandoffAccessDenied) clear();
      if (failure instanceof HandoffConflict) { setSelected(null); setResult(null); }
      throw failure;
    } finally { setBusy(false); }
  }

  async function run(action: () => Promise<void>) {
    try { await action(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "请求失败"); }
  }

  async function refresh() { setTasks(await request<HandoffRequest[]>("/human-tasks")); }
  async function openTask(id: string) {
    const value = await request<HandoffRequest>(`/human-requests/${encodeURIComponent(id)}`);
    setSelected(value);
    setResult(value.state === "RETURNED"
      ? await request<HandoffResult>(`/human-requests/${encodeURIComponent(id)}/result`) : null);
  }
  async function command(path: string, body: unknown, id?: string) {
    setReceipt(await request<HandoffReceipt>(path, body));
    if (id) await openTask(id);
    await refresh();
  }

  return <main className="enf5-workspace">
    <header className="enf5-header">
      <div><Typography.Title level={2}>辅助包与人工回流</Typography.Title><span>执法 V2</span></div>
      {token ? <Button icon={<LogOut size={16} />} onClick={clear}>退出</Button>
        : <Button type="primary" icon={<LogIn size={16} />} onClick={() => void run(beginHandoffLogin)}>登录</Button>}
    </header>
    {error && <Alert type="error" showIcon role="alert" title={error} closable onClose={() => setError("")} />}
    {!token ? <div className="enf5-empty">当前没有产品会话</div> : <div className="enf5-grid">
      <section>
        <Typography.Title level={4}>辅助包</Typography.Title>
        <Form layout="vertical" onFinish={values => void run(async () => {
          const id = String(values.id);
          const created = await request<HandoffReceipt>("/work-packages", {
            id, evidenceOrganizationId: values.evidenceOrganizationId,
            evidenceOrganizationVersion: values.evidenceOrganizationVersion, purposeRef: values.purposeRef });
          setReceipt(created);
          setPack(await request<HandoffPackage>(`/work-packages/${encodeURIComponent(id)}/versions/${created.version}`));
        })}>
          <Form.Item label="辅助包 ID" name="id" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="证据整理 ID" name="evidenceOrganizationId" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label="确切版本" name="evidenceOrganizationVersion" rules={[{ required: true }]}>
            <InputNumber min={1} precision={0} style={{ width: "100%" }} /></Form.Item>
          <Form.Item label="用途" name="purposeRef" rules={[{ required: true }]}><Input /></Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>创建包</Button>
        </Form>
        <Space.Compact className="enf5-lookup"><Input value={lookupId} onChange={event => setLookupId(event.target.value)} placeholder="包 ID" />
          <InputNumber value={lookupVersion} onChange={setLookupVersion} min={1} precision={0} placeholder="版本" />
          <Button onClick={() => void run(async () => {
            if (!lookupId || !lookupVersion) throw new Error("请输入包 ID 和确切版本");
            setPack(await request<HandoffPackage>(`/work-packages/${encodeURIComponent(lookupId)}/versions/${lookupVersion}`));
          })}>读取</Button></Space.Compact>
        {pack && <div className="enf5-detail"><Descriptions size="small" column={1} bordered items={[
          { key: "version", label: "包 / 工作", children: `${pack.id} v${pack.version} / ${pack.workId}` },
          { key: "case", label: "案件引用", children: `${pack.caseRef} v${pack.caseVersion}` },
          { key: "evidence", label: "整理输入", children: `${pack.evidenceOrganizationId} v${pack.evidenceOrganizationVersion}` },
          { key: "digest", label: "材料集合摘要", children: pack.evidenceSetDigest },
          { key: "gaps", label: "缺口", children: pack.gaps.length ? pack.gaps.join("、") : "无" },
          { key: "unprocessed", label: "未处理", children: pack.unprocessed.length ? pack.unprocessed.join("、") : "无" }
        ]} />
          <Form layout="inline" className="enf5-command" onFinish={values => void run(() => command(
            `/work-packages/${encodeURIComponent(pack.id)}/versions/${pack.version}/human-requests`,
            { id: values.requestId, packageId: pack.id, packageVersion: pack.version,
              purposeRef: pack.purposeRef }))}>
            <Form.Item name="requestId" rules={[{ required: true }]}><Input placeholder="请求 ID" /></Form.Item>
            <Button htmlType="submit" loading={busy}>请求人工处理</Button>
          </Form>
        </div>}
      </section>
      <section>
        <div className="enf5-section-heading"><Typography.Title level={4}>人工待办</Typography.Title>
          <Button icon={<RefreshCw size={16} />} title="刷新待办" onClick={() => void run(refresh)} loading={busy} /></div>
        <List dataSource={tasks} locale={{ emptyText: <Empty description="暂无可领取请求" /> }}
          renderItem={item => <List.Item onClick={() => void run(() => openTask(item.id))}>
            <Button type="link">{item.id}</Button><Tag>{item.state}</Tag><span>{item.packageId} v{item.packageVersion}</span>
          </List.Item>} />
        {selected && <div className="enf5-detail"><Descriptions size="small" column={1} bordered items={[
          { key: "request", label: "请求", children: `${selected.id} v${selected.version}` },
          { key: "package", label: "包版本", children: `${selected.packageId} v${selected.packageVersion}` },
          { key: "state", label: "状态", children: selected.state },
          { key: "claimant", label: "领取者", children: selected.claimant || "未领取" }
        ]} />
          {selected.state === "PENDING" && <Button type="primary" loading={busy} onClick={() => void run(() =>
            command(`/human-requests/${encodeURIComponent(selected.id)}/claims`,
              { expectedVersion: selected.version }, selected.id))}>领取</Button>}
          {selected.state === "ACCEPTED" && <>
            <Form layout="vertical" className="enf5-command" onFinish={values => void run(() =>
              command(`/human-requests/${encodeURIComponent(selected.id)}/results`,
                { expectedVersion: selected.version, opinion: values.opinion, reason: values.reason }, selected.id))}>
              <Form.Item label="意见" name="opinion" rules={[{ required: true }]}><Input.TextArea rows={3} /></Form.Item>
              <Form.Item label="理由" name="reason" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item>
              <Button type="primary" htmlType="submit" loading={busy}>提交意见</Button>
            </Form>
            <Form layout="inline" className="enf5-command" onFinish={values => void run(() =>
              command(`/human-requests/${encodeURIComponent(selected.id)}/returns`,
                { expectedVersion: selected.version, reason: values.reason }, selected.id))}>
              <Form.Item name="reason" rules={[{ required: true }]}><Input placeholder="退回原因" /></Form.Item>
              <Button htmlType="submit" loading={busy}>退回待办</Button>
            </Form>
          </>}
          {(selected.state === "PENDING" || selected.state === "ACCEPTED") &&
            <Form layout="inline" className="enf5-command" onFinish={values => void run(() =>
              command(`/human-requests/${encodeURIComponent(selected.id)}/withdrawals`,
                { expectedVersion: selected.version, reason: values.reason }, selected.id))}>
              <Form.Item name="reason" rules={[{ required: true }]}><Input placeholder="撤回原因" /></Form.Item>
              <Button danger htmlType="submit" loading={busy}>撤回请求</Button>
            </Form>}
          {result && <Descriptions size="small" column={1} bordered items={[
            { key: "opinion", label: "具名意见", children: result.opinion },
            { key: "reason", label: "理由", children: result.reason },
            { key: "ref", label: "确切包版", children: `${result.packageId} v${result.packageVersion}` }
          ]} />}
        </div>}
      </section>
    </div>}
    {receipt && token && <Alert type="success" showIcon role="status"
      title={`${receipt.operation} · ${receipt.objectId} v${receipt.version}`} />}
  </main>;
}
