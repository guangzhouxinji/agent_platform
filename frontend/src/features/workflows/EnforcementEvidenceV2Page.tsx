import { useEffect, useRef, useState } from "react";
import { Alert, Button, Space, Tabs, Typography } from "antd";
import { LogIn, LogOut } from "lucide-react";
import EvidenceV2RulePanel from "./EvidenceV2RulePanel";
import EvidenceV2OrganizationPanel from "./EvidenceV2OrganizationPanel";
import { beginEvidenceLogin, completeEvidenceLogin, evidenceRequest, EvidenceAccessDenied } from "./evidenceV2Api";
import "./EnforcementEvidenceV2Page.css";

export default function EnforcementEvidenceV2Page() {
  const [token, setToken] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [generation, setGeneration] = useState(0);
  const pending = useRef<{ fingerprint: string; key: string } | null>(null);

  useEffect(() => {
    void completeEvidenceLogin().then(value => { if (value) setToken(value); })
      .catch(failure => setError(failure instanceof Error ? failure.message : "登录失败"));
  }, []);

  function clear() {
    setToken(null);
    setGeneration(value => value + 1);
    pending.current = null;
  }

  async function request<T>(path: string, body?: unknown): Promise<T> {
    if (!token) throw new Error("请先登录");
    setBusy(true);
    setError("");
    const fingerprint = `${path}\u0000${JSON.stringify(body)}`;
    if (body !== undefined && pending.current?.fingerprint !== fingerprint)
      pending.current = { fingerprint, key: crypto.randomUUID() };
    try {
      const result = await evidenceRequest<T>(token, path, pending.current?.key, body);
      if (body !== undefined) pending.current = null;
      return result;
    } catch (failure) {
      if (failure instanceof EvidenceAccessDenied) clear();
      throw failure;
    } finally { setBusy(false); }
  }

  async function run(action: () => Promise<void>) {
    try { await action(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "请求失败"); }
  }

  return <main className="enf4-workspace">
    <header className="enf4-header">
      <div><Typography.Title level={2}>证据整理</Typography.Title><span>执法 V2</span></div>
      <Space>{token ? <Button icon={<LogOut size={16} aria-hidden="true" />} onClick={clear}>退出</Button>
        : <Button type="primary" icon={<LogIn size={16} aria-hidden="true" />}
            onClick={() => void run(beginEvidenceLogin)}>登录</Button>}</Space>
    </header>
    {error && <Alert className="enf4-error" type="error" showIcon role="alert" title={error}
      closable onClose={() => setError("")} />}
    {token ? <Tabs key={generation} className="enf4-tabs" items={[
      { key: "rules", label: "待证规则", children: <EvidenceV2RulePanel request={request} run={run} busy={busy} /> },
      { key: "organizations", label: "证据集合与关系", children: <EvidenceV2OrganizationPanel
        request={request} run={run} busy={busy} /> }
    ]} /> : <div className="enf4-locked">当前没有产品会话</div>}
  </main>;
}
