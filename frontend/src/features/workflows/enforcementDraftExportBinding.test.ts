import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { webcrypto } from 'node:crypto';
import { Blob } from 'node:buffer';
import { assertExportArtifact, assertExportWriteReceipt, exportReadTarget, loadBoundDraftExport } from './enforcementDraftExportBinding';
import type { Draft, Manifest } from './enforcementDraftTypes';
const sha = 'a'.repeat(64), other = 'b'.repeat(64);
const ref = (id: string) => ({ id, revision: 1, sha256: sha });
const manifest: Manifest = { organization: 'org', type: 'FACT_RECORD', caseInput: ref('case'), template: ref('template'), rule: ref('rule'), evidence: [ref('evidence')], legalReferences: [ref('legal')], conduct: ref('conduct'), penalty: ref('penalty') };
const draft: Draft = { id: 'draft', revision: 2, contentSha256: sha, manifest, contentJson: '{}', inputManifestHash: sha, reviewState: 'APPROVED', missingItems: [], author: 'actor', createdAt: '2026-10-07' };
const receipt = { operation: 'EXPORT', draftId: 'draft', revision: 2, contentSha256: sha, exportId: 'export/one', requestId: 'key' };
const request = { path: '/document-drafts/draft/exports', key: 'key', bodyJson: JSON.stringify({ revision: 2, contentSha: sha }) };
const target = exportReadTarget(receipt, draft);
const bytes = new TextEncoder().encode('isolated contract-test artifact, not business evidence');
let artifactSha: string;
const metadata = () => ({ id: receipt.exportId, draftId: 'draft', revision: 2, contentSha256: sha, manifest, requestId: 'key', artifactSha256: artifactSha, rendererVersion: 'renderer', actor: 'actor', createdAt: '2026-10-07' });
const blob = () => new Blob([bytes]) as unknown as globalThis.Blob;
beforeEach(async () => {
  vi.stubGlobal('crypto', webcrypto);
  artifactSha = Buffer.from(await webcrypto.subtle.digest('SHA-256', bytes)).toString('hex');
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });
describe('enforcement export exact receipt/draft/artifact binding', () => {
  it('accepts native EXPORT receipt and snapshots the exact original manifest', () => {
    expect(() => assertExportWriteReceipt(request, receipt)).not.toThrow();
    const original = structuredClone(draft), bound = exportReadTarget(receipt, original);
    original.manifest.caseInput.id = 'changed';
    expect(bound.draft.manifest.caseInput.id).toBe('case');
    expect(() => assertExportArtifact(metadata(), bound)).not.toThrow();
  });
  it.each([{ operation: 'REVIEW' }, { draftId: 'other' }, { revision: 3 }, { revision: 2.5 }, { contentSha256: other }, { requestId: 'other' }, { exportId: '' }, { exportId: 42 }, { exportId: ' x ' }])('rejects invalid export receipt before command confirmation %j', patch => {
    expect(() => assertExportWriteReceipt(request, { ...receipt, ...patch })).toThrow(/回执/);
  });
  it.each([{ operation: 'REVIEW' }, { draftId: 'other' }, { revision: 3 }, { contentSha256: other }, { requestId: '' }, { exportId: '' }])('rejects receipt not bound to original draft %j', patch => {
    expect(() => exportReadTarget({ ...receipt, ...patch }, draft)).toThrow();
  });
  it.each([{ id: 'other' }, { draftId: 'other' }, { revision: 3 }, { contentSha256: other }, { requestId: 'other' }, { artifactSha256: '' }, { artifactSha256: 'not-sha' }, { manifest: null }])('rejects wrong artifact %j before file fetch', async patch => {
    const download = vi.fn();
    await expect(loadBoundDraftExport(target, async () => ({ ...metadata(), ...patch }), download, () => true)).rejects.toThrow(/绑定失配/);
    expect(download).not.toHaveBeenCalled();
  });
  it.each(['caseInput', 'template', 'rule', 'conduct', 'penalty'] as const)('binds manifest reference %s', part => {
    expect(() => assertExportArtifact({ ...metadata(), manifest: { ...manifest, [part]: { ...ref('other'), sha256: other } } }, target)).toThrow(/来源清单/);
  });
  it.each([{ organization: 'other' }, { type: 'PENALTY_INPUT_REVIEW' }, { evidence: [] }, { legalReferences: [] }, { evidence: [ref('other')] }, { legalReferences: [ref('other')] }, { caseInput: { ...ref('case'), revision: 3 } }, { rule: { ...ref('rule'), sha256: other } }])('binds all manifest fields %j', patch => {
    expect(() => assertExportArtifact({ ...metadata(), manifest: { ...manifest, ...patch } }, target)).toThrow();
  });
  it('reads original encoded exportId, computes actual SHA, and returns the original Blob', async () => {
    const original = blob(), read = vi.fn().mockResolvedValue(metadata()), download = vi.fn().mockResolvedValue(original);
    expect(await loadBoundDraftExport(target, read, download, () => true)).toBe(original);
    expect(read).toHaveBeenCalledExactlyOnceWith('/document-drafts/exports/export%2Fone');
    expect(download).toHaveBeenCalledExactlyOnceWith('/document-drafts/exports/export%2Fone/file');
  });
  it('rejects file SHA mismatch', async () => {
    await expect(loadBoundDraftExport(target, async () => ({ ...metadata(), artifactSha256: other }), async () => blob(), () => true)).rejects.toThrow(/原件 SHA/);
  });
  it.each(['metadata', 'file', 'bytes', 'digest'] as const)('drops stale context after %s', async stage => {
    let active = true;
    const original = blob();
    const read = vi.fn(async () => { if (stage === 'metadata') active = false; return metadata(); });
    const download = vi.fn(async () => { if (stage === 'file') active = false; return original; });
    const arrayBuffer = original.arrayBuffer.bind(original);
    vi.spyOn(original, 'arrayBuffer').mockImplementation(async () => { const value = await arrayBuffer(); if (stage === 'bytes') active = false; return value; });
    if (stage === 'digest') {
      const digest = webcrypto.subtle.digest.bind(webcrypto.subtle);
      vi.spyOn(webcrypto.subtle, 'digest').mockImplementation(async (...args) => { const value = await digest(...args); active = false; return value; });
    }
    expect(await loadBoundDraftExport(target, read, download, () => active)).toBeNull();
    if (stage === 'metadata') expect(download).not.toHaveBeenCalled();
  });
  it('does no reads for an already invalid context', async () => {
    const read = vi.fn(), download = vi.fn();
    expect(await loadBoundDraftExport(target, read, download, () => false)).toBeNull();
    expect(read).not.toHaveBeenCalled(); expect(download).not.toHaveBeenCalled();
  });
});
