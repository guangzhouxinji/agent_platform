import type { Selection } from './types';

export type SelectionWrite = {
  taskId: string; resultSetId: string; key: string;
  body: { expectedRevision: number; resultSha: string; memberIds: string[] };
};
const text = (v: unknown): v is string => typeof v === 'string' && v.trim().length > 0;
const sha = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);

// Current permission/source/retention checks belong to the existing server, not this projection.
export function assertSelection(value: Selection, taskId: string, resultSetId: string, selectionId?: string, write?: SelectionWrite): Selection {
  if (!value || value.taskId !== taskId || value.resultSetId !== resultSetId ||
      !text(value.selectionId) || (selectionId !== undefined && value.selectionId !== selectionId) ||
      !sha(value.resultSha) || !Number.isSafeInteger(value.revision) || value.revision < 1 ||
      !Number.isSafeInteger(value.taskVersion) || value.taskVersion < 1 ||
      !Number.isSafeInteger(value.resultSetVersion) || value.resultSetVersion < 1 ||
      !Number.isFinite(Date.parse(value.createdAt)) || !Number.isFinite(Date.parse(value.expiresAt)) ||
      !Array.isArray(value.members) || value.members.length === 0 ||
      new Set(value.members.map(m => m.memberId)).size !== value.members.length ||
      !value.members.every(m => text(m.memberId) && text(m.sourceId) && text(m.documentId) &&
        text(m.documentVersion) && text(m.sourceVersion) && sha(m.rawSha) && text(m.locatorRef) &&
        Number.isSafeInteger(m.ordinal) && m.ordinal >= 0)) {
    throw new Error('文献选择响应与请求或文献版本不一致，未展示该回执。');
  }
  if (write && (value.resultSha !== write.body.resultSha || value.revision !== write.body.expectedRevision + 1 ||
      JSON.stringify(value.members.map(m => m.memberId).sort()) !== JSON.stringify([...write.body.memberIds].sort()))) {
    throw new Error('保存回执与实际提交的结果摘要、选择版本或成员不一致，请对账原请求。');
  }
  return value;
}
