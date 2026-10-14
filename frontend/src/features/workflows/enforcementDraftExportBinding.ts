import { sameEnforcementManifest } from './enforcementAgentStart';
import type { Draft, Manifest, Receipt } from './enforcementDraftTypes';
import type { DraftWrite } from './enforcementDraftRecovery';

export type ExportReceipt = Receipt & { exportId: string };
/** Existing ExportArtifact contract; renderer/actor are evidence, not client authority. */
export type ExportArtifact = { id: string; draftId: string; revision: number; contentSha256: string;
  manifest: Manifest; requestId: string; artifactSha256: string; rendererVersion: string; actor: string; createdAt: string };
export type ExportReadTarget = Readonly<{ receipt: ExportReceipt; draft: Pick<Draft, 'id' | 'revision' | 'contentSha256' | 'manifest'> }>;
const sha = (value: unknown): value is string => typeof value === 'string' && /^[a-fA-F0-9]{64}$/.test(value);
const identifier = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value === value.trim() && !/[\u0000-\u001f\u007f]/.test(value);
function reject(detail: string): never { throw new Error('DOCX 导出绑定失配：' + detail + '；未交付文件'); }

/** Validate before clearing the existing uncertain-command fence. */
export function assertExportWriteReceipt(request: DraftWrite, value: unknown): asserts value is ExportReceipt {
  const result = value as Partial<ExportReceipt> | null;
  const body = JSON.parse(request.bodyJson) as { revision: number; contentSha: string };
  if (!result || result.operation !== 'EXPORT' || result.requestId !== request.key ||
    result.draftId !== decodeURIComponent(request.path.split('/')[2]) || !identifier(result.exportId) ||
    !Number.isSafeInteger(result.revision) || (result.revision ?? 0) <= 0 || result.revision !== body.revision ||
    !sha(result.contentSha256) || result.contentSha256 !== body.contentSha) reject('回执与原导出请求不一致，请对账原请求');
}

export function exportReadTarget(receipt: ExportReceipt, draft: Draft): ExportReadTarget {
  if (!receipt || receipt.operation !== 'EXPORT' || !identifier(receipt.exportId) || !identifier(receipt.requestId) ||
    receipt.draftId !== draft.id || receipt.revision !== draft.revision || !Number.isSafeInteger(receipt.revision) || receipt.revision <= 0 ||
    !sha(receipt.contentSha256) || receipt.contentSha256 !== draft.contentSha256) reject('回执与原稿件不一致');
  return structuredClone({ receipt, draft: { id: draft.id, revision: draft.revision, contentSha256: draft.contentSha256, manifest: draft.manifest } });
}

export function assertExportArtifact(value: unknown, target: ExportReadTarget): asserts value is ExportArtifact {
  const artifact = value as Partial<ExportArtifact> | null;
  const { receipt, draft } = target;
  if (!artifact || artifact.id !== receipt.exportId || artifact.draftId !== receipt.draftId || artifact.draftId !== draft.id ||
    artifact.revision !== receipt.revision || artifact.revision !== draft.revision ||
    artifact.contentSha256 !== receipt.contentSha256 || artifact.contentSha256 !== draft.contentSha256 ||
    artifact.requestId !== receipt.requestId || !sha(artifact.artifactSha256)) reject('元数据与确切 EXPORT 回执或原稿件不一致');
  let matches = false;
  try { matches = !!artifact.manifest && sameEnforcementManifest(artifact.manifest, draft.manifest); } catch { /* malformed manifest is a mismatch */ }
  if (!matches) reject('元数据来源清单与原稿件不一致');
}

/** Reads only the confirmed exportId; no command/re-POST dependency exists here. */
export async function loadBoundDraftExport(target: ExportReadTarget, read: (path: string) => Promise<unknown>,
  download: (path: string) => Promise<Blob>, active: () => boolean): Promise<Blob | null> {
  if (!active()) return null;
  const path = '/document-drafts/exports/' + encodeURIComponent(target.receipt.exportId);
  const metadata = await read(path);
  if (!active()) return null;
  assertExportArtifact(metadata, target);
  const blob = await download(path + '/file');
  if (!active()) return null;
  const bytes = await blob.arrayBuffer();
  if (!active()) return null;
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  if (!active()) return null;
  const actual = Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, '0')).join('');
  if (actual !== metadata.artifactSha256) throw new Error('DOCX 下载原件 SHA 不一致，未交付文件');
  return blob;
}

export function deliverDraftExport(blob: Blob, exportId: string): void {
  const url = URL.createObjectURL(blob);
  try { const link = document.createElement('a'); link.href = url; link.download = 'document-' + exportId + '.docx'; link.click(); }
  finally { setTimeout(() => URL.revokeObjectURL(url), 1000); }
}
