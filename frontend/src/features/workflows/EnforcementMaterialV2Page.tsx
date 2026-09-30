import { useEffect, useRef, useState } from "react";
import { Alert, Button, Form, Input, Select, Space, Tabs, Typography } from "antd";
import { LogIn, LogOut, Plus, RefreshCw, ShieldCheck, Trash2 } from "lucide-react";
import type { UploadFile } from "antd";
import { flowTabs, type Clue, type Revision, type Material, type MaterialAction } from "./EnforcementMaterialFlowTabs";
import EnforcementRuleTab, { type Rule } from "./EnforcementRuleTab";
import EnforcementMaterialSourceTab from "./EnforcementMaterialSourceTab";
import "./EnforcementMaterialV2Page.css";

type Receipt = { requestId: string; operation: string; resultId: string; resultVersion: number };
type Observation = { id: string; text: string; materialMention?: string; sourceVersion: string };
type ActorMention = { ordinal: number; role: string; literal: string; locator: string };

const clientId = import.meta.env.VITE_ENFORCEMENT_PRODUCT_CLIENT_ID as string | undefined;
const redirect = `${window.location.origin}/app/enforcement/material`;
const tokenEndpoint = "/oauth2/token";
const authorizeEndpoint = "/oauth2/authorize";

function base64url(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function beginLogin() {
  if (!clientId || !window.isSecureContext) throw new Error("产品身份入口不可用");
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  const state = base64url(crypto.getRandomValues(new Uint8Array(16)));
  sessionStorage.setItem("enf-v2-pkce", JSON.stringify({ verifier, state }));
  const params = new URLSearchParams({ response_type: "code", client_id: clientId,
    redirect_uri: redirect, scope: "openid", state, code_challenge: base64url(digest),
    code_challenge_method: "S256" });
  window.location.assign(`${authorizeEndpoint}?${params}`);
}

async function completeLogin(): Promise<string | null> {
  const query = new URLSearchParams(window.location.search);
  const code = query.get("code");
  if (!code) return null;
  const saved = sessionStorage.getItem("enf-v2-pkce");
  sessionStorage.removeItem("enf-v2-pkce");
  history.replaceState(null, "", redirect);
  if (!saved || !clientId) throw new Error("登录状态已失效");
  const { verifier, state } = JSON.parse(saved) as { verifier: string; state: string };
  if (state !== query.get("state")) throw new Error("登录状态不匹配");
  const response = await fetch(tokenEndpoint, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: redirect,
      client_id: clientId, code_verifier: verifier }) });
  if (!response.ok) throw new Error("登录未完成");
  const result = await response.json() as { access_token?: string };
  if (!result.access_token) throw new Error("未取得产品会话");
  return result.access_token;
}

export default function EnforcementMaterialV2Page() {
  const [token, setToken] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [rule, setRule] = useState<Rule | null>(null);
  const [observation, setObservation] = useState<Observation | null>(null);
  const [actors, setActors] = useState<ActorMention[]>([]);
  const [clue, setClue] = useState<Clue | null>(null);
  const [revisions, setRevisions] = useState<Revision[]>([]);
  const [material, setMaterial] = useState<Material | null>(null);
  const [materialActions, setMaterialActions] = useState<MaterialAction[]>([]);
  const [file, setFile] = useState<UploadFile[]>([]);
  const pending = useRef<{ fingerprint: string; key: string } | null>(null);
  function clearRestrictedContent() {
    setToken(null); setRule(null); setClue(null); setObservation(null); setActors([]);
    setMaterial(null); setMaterialActions([]); setRevisions([]); setFile([]); setNotice("");
    pending.current = null;
  }

  useEffect(() => {
    void completeLogin().then(value => { if (value) setToken(value); }).catch(failure => setError(String(failure.message)));
  }, []);

  async function request<T>(path: string, body?: unknown): Promise<T> {
    if (!token) throw new Error("请先登录");
    setBusy(true); setError("");
    const fingerprint = `${path}\u0000${JSON.stringify(body)}`;
    if (body && pending.current?.fingerprint !== fingerprint)
      pending.current = { fingerprint, key: crypto.randomUUID() };
    try {
      const response = await fetch(`/v2/enforcement${path}`, {
        method: body ? "POST" : "GET",
        headers: { Authorization: `Bearer ${token}`,
          ...(body ? { "Content-Type": "application/json", "Idempotency-Key": pending.current!.key } : {}) },
        body: body ? JSON.stringify(body) : undefined
      });
      if (!response.ok) {
        if (response.status === 401 || response.status === 403) {
          clearRestrictedContent();
          throw new Error("当前授权不可用，请重新登录");
        }
        throw new Error(response.status === 409 ? "版本或请求冲突，请核对后重试" : "服务暂不可用，请重试");
      }
      const result = await response.json() as T;
      if (body) pending.current = null;
      return result;
    } finally { setBusy(false); }
  }

  async function run(action: () => Promise<void>) {
    try { await action(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "请求失败"); }
  }

  function report(receipt: Receipt) { setNotice(`${receipt.operation} · ${receipt.resultId}`); }

  const tabs = [
    { key: "source", label: "来源与观察", children: <div className="enf3-columns">
      <section><EnforcementMaterialSourceTab key={token || "signed-out"} busy={busy}
        request={request} run={run} report={report} />
      </section>
      <section><Typography.Title level={4}>观察记录</Typography.Title>
        <Form layout="vertical" onFinish={(values) => void run(async () => {
          const receipt = await request<Receipt>("/observations", values); report(receipt);
          setObservation(await request<Observation>(`/observations/${encodeURIComponent(receipt.resultId)}`));
          setActors(await request<ActorMention[]>(`/observations/${encodeURIComponent(receipt.resultId)}/actors`));
        })}>
          <div className="enf3-pair"><Form.Item name="sourceId" label="来源 ID" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="recordId" label="记录 ID" rules={[{ required: true }]}><Input /></Form.Item></div>
          <Form.Item name="sourceVersion" label="来源版本" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="text" label="原文" rules={[{ required: true }]}><Input.TextArea rows={5} /></Form.Item>
          <Form.Item name="materialMention" label="材料提及"><Input /></Form.Item>
          <Form.List name="actorMentions">{(fields, { add, remove }) => <>
            {fields.map(field => <div className="enf3-pair" key={field.key}>
              <Form.Item name={[field.name, "role"]} label="主体角色" rules={[{ required: true }]}>
                <Select options={["OPERATOR_CANDIDATE", "SUPPLIER_CANDIDATE", "OTHER_CANDIDATE"]
                  .map(value => ({ value, label: value }))} />
              </Form.Item>
              <Form.Item name={[field.name, "literal"]} label="原文字面" rules={[{ required: true }]}><Input /></Form.Item>
              <Button icon={<Trash2 size={16} />} aria-label="移除主体" onClick={() => remove(field.name)} />
            </div>)}
            <Button icon={<Plus size={16} />} onClick={() => add()}>主体候选</Button>
          </>}</Form.List>
          <Button type="primary" htmlType="submit" loading={busy}>保存观察</Button>
        </Form>
        <Form layout="inline" onFinish={(values) => void run(async () => {
          setObservation(await request<Observation>(`/observations/${encodeURIComponent(values.id)}`));
          setActors(await request<ActorMention[]>(`/observations/${encodeURIComponent(values.id)}/actors`));
        })}>
          <Form.Item name="id" label="观察 ID" rules={[{ required: true }]}><Input /></Form.Item>
          <Button htmlType="submit" icon={<RefreshCw size={16} />} aria-label="读取观察" />
        </Form>
        {observation && <pre className="enf3-source">{observation.text}</pre>}
        {actors.map(actor => <div className="enf3-detail" key={actor.ordinal}>
          <strong>{actor.literal}</strong><span>{actor.role} · {actor.locator}</span>
        </div>)}
      </section>
    </div> },
    { key: "rules", label: "发现规则", children: <EnforcementRuleTab
      rule={rule} setRule={setRule} busy={busy} request={request} run={run} report={report} /> },
    ...flowTabs({ token, busy, onUnauthorized: clearRestrictedContent, request, run, report, clue, setClue, revisions, setRevisions,
      material, setMaterial, materialActions, setMaterialActions, file, setFile })
  ];

  return <main className="enf3-workspace">
    <header className="enf3-header"><div><Typography.Title level={2}>线索与材料</Typography.Title>
      <span>执法 · V2</span></div><Space>
      {token ? <Button icon={<LogOut size={16} />} onClick={() => void run(async () => {
        await fetch("/v2/identity/logout", { method: "POST", headers: { Authorization: `Bearer ${token}` } });
        clearRestrictedContent();
      })}>退出</Button> : <Button type="primary" icon={<LogIn size={16} />} onClick={() => void run(beginLogin)}>登录</Button>}
    </Space></header>
    {error && <Alert className="enf3-alert" type="error" showIcon message={error} role="alert" />}
    {notice && <Alert className="enf3-alert" type="success" showIcon message={notice} closable onClose={() => setNotice("")} />}
    {token ? <Tabs items={tabs} className="enf3-tabs" /> : <div className="enf3-locked"><ShieldCheck size={28} aria-hidden="true" />
      <Typography.Text>请使用产品身份登录。</Typography.Text></div>}
  </main>;
}
