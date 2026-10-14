export class EnforcementAccessDenied extends Error {
  constructor(readonly status=403) { super("当前授权不可用，请重新登录"); }
}

export class EnforcementConflict extends Error {
  readonly status=409;
  constructor() { super("版本或请求冲突，请刷新确切版本"); }
}

export class EnforcementRequestFailure extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

type Slice = "procedure" | "conduct" | "job-pack" | "document-templates" | "legal-references" | "document-rules" | "document-drafts" | "penalty";
const clientId = import.meta.env.VITE_ENFORCEMENT_PRODUCT_CLIENT_ID as string | undefined;
const documentClientId = import.meta.env.VITE_ENFORCEMENT_DOCUMENT_CLIENT_ID as string | undefined;
const legalClientId = import.meta.env.VITE_ENFORCEMENT_LEGAL_CLIENT_ID as string | undefined;

function client(slice: Slice) {
  if (slice === "document-drafts") return import.meta.env.VITE_ENFORCEMENT_DOCUMENT_DRAFT_CLIENT_ID as string | undefined;
  if (slice === "penalty") return import.meta.env.VITE_ENFORCEMENT_PENALTY_CLIENT_ID as string | undefined;
  if (slice === "document-templates") return documentClientId;
  if (slice === "legal-references") return legalClientId;
  if (slice === "document-rules") return import.meta.env.VITE_ENFORCEMENT_DOCUMENT_RULE_CLIENT_ID as string | undefined;
  return clientId;
}

function redirect(slice: Slice) {
  return `${window.location.origin}/app/enforcement/${slice}`;
}

function base64url(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function beginEnforcementLogin(slice: Slice) {
  const selectedClient = client(slice);
  if (!selectedClient || !window.isSecureContext) throw new Error("产品身份入口不可用");
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  const state = base64url(crypto.getRandomValues(new Uint8Array(16)));
  sessionStorage.setItem(`enf-v2-${slice}-pkce`, JSON.stringify({ verifier, state, ...(slice === "job-pack" ? { packageId: new URLSearchParams(window.location.search).get("package") } : {}) }));
  const params = new URLSearchParams({ response_type: "code", client_id: selectedClient,
    redirect_uri: redirect(slice), scope: "openid", state, code_challenge: base64url(digest),
    code_challenge_method: "S256" });
  window.location.assign(`/oauth2/authorize?${params}`);
}

export async function completeEnforcementLogin(slice: Slice): Promise<string | null> {
  const query = new URLSearchParams(window.location.search);
  const code = query.get("code");
  if (!code) return null;
  const saved = sessionStorage.getItem(`enf-v2-${slice}-pkce`);
  sessionStorage.removeItem(`enf-v2-${slice}-pkce`);
  history.replaceState(null, "", redirect(slice));
  const selectedClient = client(slice);
  if (!saved || !selectedClient) throw new Error("登录状态已失效");
  const { verifier, state, packageId } = JSON.parse(saved) as { verifier: string; state: string; packageId?: string };
  if (state !== query.get("state")) throw new Error("登录状态不匹配");
  const response = await fetch("/oauth2/token", { method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code,
      redirect_uri: redirect(slice), client_id: selectedClient, code_verifier: verifier }) });
  if (!response.ok) throw new Error("登录未完成");
  const result = await response.json() as { access_token?: string };
  if (!result.access_token) throw new Error("未取得产品会话");
  if (slice === "job-pack" && typeof packageId === "string")
    history.replaceState(null, "", redirect(slice) + "?package=" + encodeURIComponent(packageId));
  return result.access_token;
}

export async function endEnforcementLogin(token: string) {
  const response = await fetch("/v2/identity/logout", {
    method: "POST", headers: { Authorization: `Bearer ${token}` }
  });
  if (!response.ok && response.status !== 401) throw new Error(`退出失败 (${response.status})`);
}

export type EnforcementIdentity = { issuer: string; subject: string; sessionId: string; grants: Array<{
  organization: string; domain: string; resourceType: string; resourceId: string; action: string; purpose: string;
}> };

export async function readEnforcementIdentity(token: string): Promise<EnforcementIdentity> {
  const response = await fetch("/v2/identity/me", {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" }
  });
  if (response.status === 401 || response.status === 403) throw new EnforcementAccessDenied(response.status);
  if (!response.ok) throw new Error(`身份上下文不可用 (${response.status})`);
  return response.json() as Promise<EnforcementIdentity>;
}

export async function enforcementRequest<T>(token: string, path: string, body?: unknown, key?: string): Promise<T> {
  const response = await fetch(`/v2/enforcement${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json", "Idempotency-Key": key! }) },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  if (response.status === 401 || response.status === 403) throw new EnforcementAccessDenied(response.status);
  if (response.status === 409) throw new EnforcementConflict();
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { message?: string; error?: { message?: string } } | null;
    throw new EnforcementRequestFailure(response.status, problem?.error?.message || problem?.message || `产品请求失败 (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export async function enforcementUpload<T>(token: string, path: string, body: FormData, key = crypto.randomUUID()): Promise<T> {
  const response = await fetch(`/v2/enforcement${path}`, { method: "POST",
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json", "Idempotency-Key": key }, body });
  if (response.status === 401 || response.status === 403) throw new EnforcementAccessDenied(response.status);
  if (response.status === 409) throw new EnforcementConflict();
  if (!response.ok) throw new Error(`原件上传失败 (${response.status})`);
  return response.json() as Promise<T>;
}

export async function enforcementDownload(token: string, path: string): Promise<Blob> {
  const response = await fetch(`/v2/enforcement${path}`, {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/octet-stream" }
  });
  if (response.status === 401 || response.status === 403) throw new EnforcementAccessDenied(response.status);
  if (!response.ok) throw new Error(`文件下载失败 (${response.status})`);
  return response.blob();
}
