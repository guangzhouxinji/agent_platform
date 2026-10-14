import type { Candidate, Content, Draft, Manifest, Receipt, Review } from './enforcementDraftTypes';
import { sameEnforcementManifest } from './enforcementAgentStart';

export type DraftReadTarget = Readonly<{ id: string; revision: number; manifest?: Manifest; receipt?: Receipt }>;
const sha = (value: unknown): value is string => typeof value === 'string' && /^[a-fA-F0-9]{64}$/.test(value);
const revision = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
function matchesManifest(left: Manifest, right: Manifest): boolean {
  try { return sameEnforcementManifest(left, right); } catch { return false; }
}
function reject(detail: string): never { throw new Error('文书读取绑定失配：' + detail + '；未显示结果，未交接人工复核'); }

/** History is a navigation source, not permission or evidence of a successful write. */
export function assertDraftReadHistory(values: Draft[], id: string): void {
  if (!Array.isArray(values) || !values.length) reject('未找到文书版本');
  const seen = new Set<number>();
  for (const value of values) {
    if (!value || value.id !== id || !revision(value.revision) || !sha(value.contentSha256) || seen.has(value.revision)) reject('历史文书标识、版本或 SHA 不一致');
    seen.add(value.revision);
  }
}

export function assertDraftReadBinding(value: Draft, target: DraftReadTarget, history: Draft[]): Content {
  if (target.manifest && (!value?.manifest || !matchesManifest(value.manifest, target.manifest))) {
    throw new Error('Agent 文书结果与当前输入版本不一致，未交接复核');
  }
  if (!value || value.id !== target.id || value.revision !== target.revision || !revision(value.revision) || !sha(value.contentSha256)) reject('草稿标识、版本或 SHA 不一致');
  const historical = history.find(item => item.revision === target.revision);
  if (!historical || historical.id !== target.id || historical.contentSha256 !== value.contentSha256 || !matchesManifest(historical.manifest, value.manifest)) reject('草稿与确切历史版本不一致');
  const receipt = target.receipt;
  if (receipt) {
    if (receipt.draftId !== target.id || receipt.revision !== target.revision || !sha(receipt.contentSha256)) reject('已确认回执的文书标识或版本不一致');
    // REGENERATE receipts describe the independent candidate, not the unchanged base draft.
    if (receipt.operation !== 'REGENERATE' && receipt.contentSha256 !== value.contentSha256) reject('草稿与已确认回执 SHA 不一致');
  }
  let content: Content;
  try { content = JSON.parse(value.contentJson) as Content; } catch { return reject('文书结构版本不可读'); }
  if (!content || !content.profile?.title || !Array.isArray(content.sections) || content.sections.some(section => !section || !Array.isArray(section.fields) || section.fields.some(field => !field?.definition))) reject('文书结构版本不可读');
  if (!Array.isArray(value.missingItems)) reject('文书诊断结构不可读');
  return content;
}

export function assertDraftReadRelated(value: Draft, candidates: Candidate[], reviews: Review[], receipt?: Receipt): void {
  if (!Array.isArray(candidates) || !Array.isArray(reviews)) reject('候选或复核结构不可读');
  const seen = new Set<string>();
  for (const candidate of candidates) {
    if (!candidate || !candidate.id || seen.has(candidate.id) || candidate.baseRevision !== value.revision || candidate.baseContentSha256 !== value.contentSha256 || !sha(candidate.contentSha256) || !Array.isArray(candidate.differences)) reject('候选未绑定已读草稿版本及 SHA');
    // The native API carries draftId; the existing frontend projection omits it.
    if ('draftId' in candidate && candidate.draftId !== value.id) reject('候选属于另一文书');
    seen.add(candidate.id);
  }
  for (const review of reviews) {
    if (!review || review.revision !== value.revision || review.contentSha256 !== value.contentSha256) reject('复核未绑定已读草稿版本及 SHA');
    if ('draftId' in review && review.draftId !== value.id) reject('复核属于另一文书');
  }
  if (receipt?.operation === 'REGENERATE') {
    const candidate = candidates.find(item => item.id === receipt.candidateId);
    if (!candidate || candidate.contentSha256 !== receipt.contentSha256) reject('候选与已确认回执标识或 SHA 不一致');
  }
}

export type DraftReadRequest = Readonly<{ id: string; revision?: number; manifest?: Manifest; receipt?: Receipt }>;
/** Re-query a confirmed command's exact revision, but never bind its receipt to another historical version. */
export function draftReadRequest(id: string, revision: number | undefined, manifest: Manifest | undefined, priorReceipt: Receipt | null, confirmedReceipt?: Receipt): DraftReadRequest {
  const receipt = confirmedReceipt ?? (priorReceipt?.draftId === id && (revision === undefined || revision === priorReceipt.revision) ? priorReceipt : undefined);
  return { id, revision, manifest, receipt };
}
export type BoundDraftRead = { history: Draft[]; current: Draft; candidates: Candidate[]; reviews: Review[] };
/** Fetch and validate one atomic result; no React state or write/recovery ownership. */
export async function loadBoundDraftRead(
  request: (path: string) => Promise<unknown>, target: DraftReadRequest, active: () => boolean,
): Promise<BoundDraftRead | null> {
  const prefix = '/document-drafts/' + encodeURIComponent(target.id);
  const history = await request(prefix + '/history') as Draft[];
  if (!active()) return null;
  assertDraftReadHistory(history, target.id);
  const revision = target.revision ?? target.receipt?.revision ?? Math.max(...history.map(value => value.revision));
  const current = await request(prefix + '?revision=' + revision) as Draft;
  if (!active()) return null;
  assertDraftReadBinding(current, { ...target, revision }, history);
  // Hash the received UTF-8 text, not a parsed/re-serialized projection.
  let digest: ArrayBuffer;
  try {
    digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(current.contentJson));
  } catch {
    if (!active()) return null;
    return reject('正文摘要核验不可用');
  }
  if (!active()) return null;
  const actualSha = Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, '0')).join('');
  if (actualSha !== current.contentSha256.toLowerCase()) reject('正文原文与声明 SHA 不一致');
  const candidates = await request(prefix + '/candidates?revision=' + revision) as Candidate[];
  if (!active()) return null;
  const reviews = await request(prefix + '/reviews?revision=' + revision) as Review[];
  if (!active()) return null;
  assertDraftReadRelated(current, candidates, reviews, target.receipt);
  return { history: [...history].sort((a, b) => a.revision - b.revision), current, candidates, reviews };
}
