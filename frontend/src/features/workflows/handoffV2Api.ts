export type HandoffPackage = {
  id: string; version: number; workId: string; organization: string;
  caseRef: string; caseVersion: string; evidenceOrganizationId: string;
  evidenceOrganizationVersion: number; evidenceSetDigest: string;
  gaps: string[]; unprocessed: string[]; purposeRef: string; contentDigest: string; creator: string;
};
export type HandoffRequest = {
  id: string; packageId: string; packageVersion: number; version: number;
  state: "PENDING" | "ACCEPTED" | "RETURNED" | "WITHDRAWN";
  purposeRef: string; claimant: string | null;
};
export type HandoffResult = {
  requestId: string; version: number; packageId: string; packageVersion: number;
  actor: string; opinion: string; reason: string;
};
export type HandoffReceipt = { requestId: string; objectId: string; version: number; operation: string };

const redirect = `${window.location.origin}/app/enforcement/handoff`;
const clientId = import.meta.env.VITE_ENFORCEMENT_PRODUCT_CLIENT_ID as string | undefined;

function base64url(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function beginHandoffLogin() {
  if (!clientId || !window.isSecureContext) throw new Error("产品身份入口不可用");
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  const state = base64url(crypto.getRandomValues(new Uint8Array(16)));
  sessionStorage.setItem("enf-v2-handoff-pkce", JSON.stringify({ verifier, state }));
  const params = new URLSearchParams({ response_type: "code", client_id: clientId,
    redirect_uri: redirect, scope: "openid", state, code_challenge: base64url(digest),
    code_challenge_method: "S256" });
  window.location.assign(`/oauth2/authorize?${params}`);
}

export async function completeHandoffLogin(): Promise<string | null> {
  const query = new URLSearchParams(window.location.search);
  const code = query.get("code");
  if (!code) return null;
  const saved = sessionStorage.getItem("enf-v2-handoff-pkce");
  sessionStorage.removeItem("enf-v2-handoff-pkce");
  history.replaceState(null, "", redirect);
  if (!saved || !clientId) throw new Error("登录状态已失效");
  const { verifier, state } = JSON.parse(saved) as { verifier: string; state: string };
  if (state !== query.get("state")) throw new Error("登录状态不匹配");
  const response = await fetch("/oauth2/token", { method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: redirect,
      client_id: clientId, code_verifier: verifier }) });
  if (!response.ok) throw new Error("登录未完成");
  const result = await response.json() as { access_token?: string };
  if (!result.access_token) throw new Error("未取得产品会话");
  return result.access_token;
}

export class HandoffAccessDenied extends Error {
  constructor() { super("当前授权不可用，请重新登录"); }
}
export class HandoffConflict extends Error {
  constructor() { super("版本或请求冲突，请刷新确切版本"); }
}

export async function handoffRequest<T>(token: string, path: string, body?: unknown, key?: string): Promise<T> {
  const response = await fetch(`/v2/enforcement${path}`, { method: body === undefined ? "GET" : "POST",
    headers: { Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { "Content-Type": "application/json", "Idempotency-Key": key! }) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401 || response.status === 403) throw new HandoffAccessDenied();
  if (response.status === 409) throw new HandoffConflict();
  if (!response.ok) throw new Error("服务暂不可用，请重试");
  return response.json() as Promise<T>;
}
