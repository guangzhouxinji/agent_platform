export const GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
export const SUPPLEMENT = "补充：该方案使用温度传感器、自动控制器和阀门。";
export const CORRECTED_SUPPLEMENT = "补充：该方案使用温度传感器、自动控制器和电磁阀。";
export const CORRECTION_MESSAGE = "纠正：补充里的阀门应为电磁阀，其他不变。";
export const FEATURE_MANIFEST = "DEV-CON-COMPARE-INPUT-01";

export type Task = { taskId: string; state: "ACTIVE" | "CANCELLED"; revision: number; expiresAt: string;
  messages: { revision: number; body: string }[];
  corrections?: { oldRevision: number; newRevision: number; utterance: string; createdAt: string }[] };
export type WorkState = { taskId: string; taskRevision: number; taskState: string;
  resultSequence: number; focusSequence: number; featureRevision: number;
  resultSet: null | { setId: string; inputRevision: number; currentInput: boolean };
  focus: null | { setId: string; ordinal: number; inputRevision: number; currentInput: boolean };
  feature: null | { inputRevision: number; manifestId: string; currentInput: boolean } };
export type ResultSet = { setId: string; sequence: number; inputRevision: number; method: string;
  actualScope: number; members: { ordinal: number; recordId: string; version: string;
    locator: string; available: boolean; claimText: string | null }[] };
export type Confirmation = { featureRevision: number; inputRevision: number; actorRef: string;
  historicalInput: boolean; candidates: { id: string; text: string; byteStart: number; byteEnd: number }[] };
export type Preview = { targetText: string; claimText: string; recordId: string;
  sourceVersion: string; locator: string; analysisPurpose: string; technicalRelation: string;
  missing: string[] };
export type PurposeSelection = { taskId: string; taskRevision: number; purposeSequence: number;
  analysisPurpose: "NOT_SELECTED" | "TECHNICAL_SIMILARITY"; featureRevision: number;
  focusSequence: number; setId: string | null; ordinal: number; actor: string | null;
  receiptId: string | null; currentInput: boolean };
export type HumanRequest = { taskId: string; requestId: string; requestSequence: number;
  inputRevision: number; requesterRef: string; requestInputId: "REQUEST_GAP"; requestText: string;
  createdAt: string; currentInput: boolean; taskState: string;
  acceptanceState: "NOT_ACCEPTED" | "ACCEPTED"; acceptedBy: string | null; acceptedAt: string | null };
export type HumanRequestCurrent = { requestSequence: number; request: HumanRequest | null };
export type PendingHumanRequest = { taskId: string; requestId: string; requestSequence: number;
  inputRevision: number; createdAt: string };
export type AcceptedHumanRequest = { taskId: string; requestId: string; requestSequence: number;
  inputRevision: number; acceptedBy: string; acceptedAt: string };
export type OwnedHumanRequest = { taskId: string; requestId: string; requestSequence: number;
  inputRevision: number; acceptedAt: string };
export type HumanOpinion = { taskId: string; requestId: string; requestSequence: number;
  inputRevision: number; opinionId: string; opinionInputId: "OPINION_GAP"; opinionText: string;
  submittedBy: string; submittedAt: string; currentInput: boolean };
export type HumanReturn = { taskId: string; requestId: string; requestSequence: number;
  inputRevision: number; returnId: string; returnedBy: string; returnedAt: string;
  currentInput: boolean };
export type HumanContext = { taskId: string; requestId: string; requestSequence: number;
  inputRevision: number; messages: { revision: number; kind: "GOAL" | "SUPPLEMENT" | "CORRECTION"; text: string }[];
  requestText: string };

export class V2DevError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

export async function v2Request<T>(token: string, path: string, body?: object, key?: string): Promise<T> {
  const headers: Record<string, string> = { Authorization: token, Accept: "application/json" };
  if (body) headers["Content-Type"] = "application/json";
  if (key) headers["Idempotency-Key"] = key;
  const response = await fetch(path, { method: body ? "POST" : "GET", headers,
    body: body ? JSON.stringify(body) : undefined, cache: "no-store" });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new V2DevError(response.status, data?.code ?? "REQUEST_FAILED");
  return data as T;
}

export const taskPath = (id: string) => `/v2/consultation/tasks/${encodeURIComponent(id)}`;
export const newKey = () => crypto.randomUUID();
