export type EvidenceRef = { id: string; version: string; sha256: string };
export type EvidenceRule = { id: string; revision: number; organization: string; caseType: string;
  basisRef: string; state: string; matters: Array<{ id: string; required: boolean; expectedMaterial: string }> };
export type EvidencePublication = { basisVersion: string; scope: string; reviewRef: string;
  reviewDigest: string; reviewedBy: string };
export type TrialResult = { matters: Array<{ matterId: string; coverage: string;
  candidates: Array<{ materialId: string; locator: string; value: string; sha256: string }> }>;
  unrelatedMaterials: string[]; unprocessedMaterials: string[] };
export type EvidenceRevision = { id: string; version: number; caseRef: string; caseVersion: string;
  ruleId: string; ruleVersion: number; state: string; evidenceSet: EvidenceRef[];
  matters: Array<{ id: string; coverage: string }>;
  links: Array<{ matterId: string; evidenceId: string; evidenceVersion: number; locator: string;
    value: string; relation: string; originKind: string; reason: string }>;
  times: Array<{ evidenceId: string; eventTime?: string; collectionTime?: string; uploadTime?: string;
    eventUnknown?: string; collectionUnknown?: string; uploadUnknown?: string }>;
  unrelated: string[]; unprocessed: string[];
  exclusions: Array<{ evidenceId: string; reason: string; actor: string }>;
  reviewedBy?: string; reviewReason?: string };
export type EvidenceReceipt = { requestId: string; id?: string; resultId?: string; revision: number };
export type EvidenceLink = EvidenceRevision["links"][number];
export type EvidenceLinkPage = { items: EvidenceLink[]; total: number; nextCursor: string | null };
export type EvidenceOriginalPreview = { evidenceId: string; version: string; sha256: string;
  locator: string; text: string; locatedValue: string };

const redirect = `${window.location.origin}/app/enforcement/evidence`;
const clientId = import.meta.env.VITE_ENFORCEMENT_PRODUCT_CLIENT_ID as string | undefined;

function base64url(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function beginEvidenceLogin() {
  if (!clientId || !window.isSecureContext) throw new Error("产品身份入口不可用");
  const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  const state = base64url(crypto.getRandomValues(new Uint8Array(16)));
  sessionStorage.setItem("enf-v2-evidence-pkce", JSON.stringify({ verifier, state }));
  const params = new URLSearchParams({ response_type: "code", client_id: clientId,
    redirect_uri: redirect, scope: "openid", state, code_challenge: base64url(digest),
    code_challenge_method: "S256" });
  window.location.assign(`/oauth2/authorize?${params}`);
}

export async function completeEvidenceLogin(): Promise<string | null> {
  const query = new URLSearchParams(window.location.search);
  const code = query.get("code");
  if (!code) return null;
  const saved = sessionStorage.getItem("enf-v2-evidence-pkce");
  sessionStorage.removeItem("enf-v2-evidence-pkce");
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

export function parseEvidenceRefs(value: string): EvidenceRef[] {
  const refs = value.split("\n").map(line => line.trim()).filter(Boolean).map(line => {
    const [id, version, sha256, extra] = line.split("|").map(part => part.trim());
    if (!id || !/^[1-9][0-9]*$/.test(version) || !/^[a-f0-9]{64}$/.test(sha256) || extra)
      throw new Error("材料须逐行填写 ID|版本|64位SHA-256");
    return { id, version, sha256 };
  });
  if (!refs.length || new Set(refs.map(ref => ref.id)).size !== refs.length)
    throw new Error("材料集合不能为空或重复");
  return refs;
}

export async function evidenceRequest<T>(token: string, path: string, key?: string, body?: unknown): Promise<T> {
  const response = await fetch(`/v2/enforcement${path}`, { method: body === undefined ? "GET" : "POST",
    headers: { Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { "Content-Type": "application/json", "Idempotency-Key": key! }) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) throw new EvidenceAccessDenied();
    throw new Error(response.status === 409 ? "版本或请求冲突，请刷新确切版本" : "服务暂不可用，请重试");
  }
  return response.json() as Promise<T>;
}

export class EvidenceAccessDenied extends Error {
  constructor() { super("当前授权不可用，请重新登录"); }
}
