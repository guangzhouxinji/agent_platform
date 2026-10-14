import { EnforcementAccessDenied, EnforcementConflict } from "./enforcementNextV2Api";

type Problem = { code?: string; message?: string; request_id?: string; gaps?: string[] };
export class PenaltyConflict extends EnforcementConflict {
  readonly code?: string; readonly gaps: string[];
  constructor(problem: Problem) {
    super(); this.code = problem.code; this.gaps = problem.gaps ?? [];
    this.message = [problem.message || this.message, this.gaps.join("；"), problem.request_id && "request_id: " + problem.request_id].filter(Boolean).join(" · ");
  }
}

/** The existing V2 transport and identity semantics, with penalty-specific diagnostics; no fallback endpoint. */
export async function penaltyRequest<T>(token: string, path: string, body?: unknown, key?: string): Promise<T> {
  if (!path.startsWith("/penalty/")) throw new Error("处罚产品入口无效");
  const response = await fetch("/v2/enforcement" + path, { method: body === undefined ? "GET" : "POST",
    headers: { Authorization: "Bearer " + token, Accept: "application/json", ...(body === undefined ? {} : { "Content-Type": "application/json", "Idempotency-Key": key ?? crypto.randomUUID() }) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401 || response.status === 403) throw new EnforcementAccessDenied();
  const payload = await response.json().catch(() => null) as T | Problem | null;
  if (response.status === 409) throw new PenaltyConflict((payload ?? {}) as Problem);
  if (!response.ok) { const problem = (payload ?? {}) as Problem; throw new Error([problem.message || "处罚产品请求失败 (" + response.status + ")", problem.request_id && "request_id: " + problem.request_id].filter(Boolean).join(" · ")); }
  return payload as T;
}
