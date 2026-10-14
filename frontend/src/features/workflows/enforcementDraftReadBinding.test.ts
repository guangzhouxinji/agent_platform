import { createHash, webcrypto } from "node:crypto";
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { assertDraftReadBinding, assertDraftReadHistory, assertDraftReadRelated, draftReadRequest, loadBoundDraftRead } from './enforcementDraftReadBinding';
import type { Candidate, Draft, Manifest, Receipt, Review } from './enforcementDraftTypes';
const sha = 'a'.repeat(64), otherSha = 'b'.repeat(64);
const ref = (id: string) => ({ id, revision: 1, sha256: sha });
const manifest: Manifest = { organization: 'org', type: 'FACT_RECORD', caseInput: ref('case'), template: ref('template'), rule: ref('rule'), evidence: [ref('evidence')], legalReferences: [] };
const content = { profile: { title: '案件事实记录草稿', classification: 'NON_OFFICIAL', revision: 1 }, sections: [{ id: 'facts', visibility: 'VISIBLE', fields: [{ definition: { id: 'fact', label: '事实', editableText: true, repeated: false }, value: '材料记载的事实', missing: false, source: { sourceRef: 'evidence', sourceVersion: 1, sourceSha256: sha, locator: '第1页', value: '材料记载的事实' } }] }] };
const contentSha = createHash("sha256").update(JSON.stringify(content), "utf8").digest("hex");
const draft: Draft = { id: 'draft', revision: 2, manifest, contentJson: JSON.stringify(content), contentSha256: contentSha, inputManifestHash: sha, reviewState: 'DRAFT', missingItems: [], author: 'operator', createdAt: '2026-10-07T10:00:00Z' };
const receipt: Receipt = { operation: 'REVISE', draftId: 'draft', revision: 2, contentSha256: contentSha, requestId: 'request' };
const target = { id: 'draft', revision: 2, manifest, receipt };
const candidate: Candidate = { id: 'candidate', baseRevision: 2, baseContentSha256: contentSha, contentSha256: otherSha, differences: [{ field: 'fact', beforeJson: '旧事实', afterJson: '候选事实' }] };
const review: Review = { revision: 2, contentSha256: contentSha, outcome: 'SUBMITTED', actor: 'operator', reason: '复核事实', createdAt: '2026-10-07T10:01:00Z' };
beforeEach(() => vi.stubGlobal('crypto', webcrypto));
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });
describe('enforcement draft exact read binding', () => {
  it('accepts nonempty native content, sources, history, candidate and review', () => {
    assertDraftReadHistory([draft], 'draft');
    expect(assertDraftReadBinding(draft, target, [draft])).toEqual(content);
    expect(() => assertDraftReadRelated(draft, [candidate], [review])).not.toThrow();
  });
  it.each([{ id: 'other' }, { revision: 3 }, { contentSha256: otherSha }, { contentSha256: 'bad' }])('rejects wrong draft binding %j', patch => {
    expect(() => assertDraftReadBinding({ ...draft, ...patch }, target, [draft])).toThrow();
  });
  it.each([{ draftId: 'other' }, { revision: 3 }, { contentSha256: otherSha }])('rejects wrong receipt binding %j', patch => {
    expect(() => assertDraftReadBinding(draft, { ...target, receipt: { ...receipt, ...patch } }, [draft])).toThrow();
  });
  it.each(['caseInput', 'template', 'rule'] as const)('rejects cross-manifest %s', part => {
    expect(() => assertDraftReadBinding({ ...draft, manifest: { ...manifest, [part]: ref('other') } }, target, [draft])).toThrow(/输入版本不一致/);
  });
  it('rejects history SHA or manifest mismatch, absent revision and duplicate versions', () => {
    expect(() => assertDraftReadBinding(draft, target, [{ ...draft, contentSha256: otherSha }])).toThrow();
    expect(() => assertDraftReadBinding(draft, target, [{ ...draft, manifest: { ...manifest, organization: 'other' } }])).toThrow();
    expect(() => assertDraftReadBinding(draft, target, [])).toThrow();
    expect(() => assertDraftReadHistory([draft, draft], 'draft')).toThrow();
    expect(() => assertDraftReadHistory([{ ...draft, id: 'other' }], 'draft')).toThrow();
  });
  it.each([{ baseRevision: 1 }, { baseContentSha256: otherSha }, { contentSha256: 'bad' }, { draftId: 'other' }])('rejects wrong candidate binding %j', patch => {
    expect(() => assertDraftReadRelated(draft, [{ ...candidate, ...patch }], [review])).toThrow(/候选/);
  });
  it.each([{ revision: 1 }, { contentSha256: otherSha }, { draftId: 'other' }])('rejects wrong review binding %j', patch => {
    expect(() => assertDraftReadRelated(draft, [candidate], [{ ...review, ...patch }])).toThrow(/复核/);
  });
  it('binds REGENERATE receipt SHA to candidate rather than unchanged base content', () => {
    const regenerated = { ...receipt, operation: 'REGENERATE', candidateId: candidate.id, contentSha256: otherSha };
    expect(() => assertDraftReadBinding(draft, { ...target, receipt: regenerated }, [draft])).not.toThrow();
    expect(() => assertDraftReadRelated(draft, [candidate], [review], regenerated)).not.toThrow();
    expect(() => assertDraftReadRelated(draft, [], [review], regenerated)).toThrow();
    expect(() => assertDraftReadRelated(draft, [{ ...candidate, contentSha256: contentSha }], [review], regenerated)).toThrow();
  });
  it('rejects unreadable content without inventing replacement text', () => {
    expect(() => assertDraftReadBinding({ ...draft, contentJson: '{' }, target, [draft])).toThrow(/结构/);
    expect(() => assertDraftReadBinding({ ...draft, contentJson: JSON.stringify({ ...content, sections: [{ id: 'bad' }] }) }, target, [draft])).toThrow(/结构/);
  });
});

describe('atomic bound read orchestration', () => {
  it('selects prior confirmed receipt only for the same draft and exact revision', () => {
    expect(draftReadRequest('draft', undefined, manifest, receipt).receipt).toBe(receipt);
    expect(draftReadRequest('draft', 2, manifest, receipt).receipt).toBe(receipt);
    expect(draftReadRequest('draft', 1, manifest, receipt).receipt).toBeUndefined();
    expect(draftReadRequest('other', undefined, manifest, receipt).receipt).toBeUndefined();
  });
  it('returns one fully checked group and uses receipt version instead of latest', async () => {
    const request = vi.fn().mockResolvedValueOnce([draft, { ...draft, revision: 3 }]).mockResolvedValueOnce(draft).mockResolvedValueOnce([candidate]).mockResolvedValueOnce([review]);
    const result = await loadBoundDraftRead(request, draftReadRequest('draft', undefined, manifest, receipt), () => true);
    expect(result).toEqual({ history: [draft, { ...draft, revision: 3 }], current: draft, candidates: [candidate], reviews: [review] });
    expect(request.mock.calls.map(call => call[0])).toEqual(['/document-drafts/draft/history', '/document-drafts/draft?revision=2', '/document-drafts/draft/candidates?revision=2', '/document-drafts/draft/reviews?revision=2']);
  });
  it('does not fetch related data for a mismatched draft', async () => {
    const request = vi.fn().mockResolvedValueOnce([draft]).mockResolvedValueOnce({ ...draft, id: 'other' });
    await expect(loadBoundDraftRead(request, target, () => true)).rejects.toThrow(/绑定失配/);
    expect(request).toHaveBeenCalledTimes(2);
  });
  it('returns no group or further reads when history arrives after context changes', async () => {
    const request = vi.fn().mockResolvedValue([draft]);
    await expect(loadBoundDraftRead(request, target, () => false)).resolves.toBeNull();
    expect(request).toHaveBeenCalledTimes(1);
  });
  it('hashes exact UTF-8 bytes including whitespace, escaped Unicode and non-BMP characters', async () => {
    const raw = ' \n' + JSON.stringify(content, null, 2).replace('案件', '\\u6848\\u4ef6').replace('材料记载的事实', '材料 e\u0301 🧾') + '\n ';
    const exact = { ...draft, contentJson: raw, contentSha256: createHash('sha256').update(raw, 'utf8').digest('hex') };
    const request = vi.fn().mockResolvedValueOnce([exact]).mockResolvedValueOnce(exact).mockResolvedValueOnce([]).mockResolvedValueOnce([]);
    const digest = vi.spyOn(webcrypto.subtle, 'digest');
    await expect(loadBoundDraftRead(request, { id: exact.id, revision: exact.revision }, () => true)).resolves.toMatchObject({ current: exact });
    expect(digest).toHaveBeenCalledTimes(1);
    expect(digest.mock.calls[0][0]).toBe('SHA-256');
    expect(Array.from(digest.mock.calls[0][1] as Uint8Array)).toEqual(Array.from(new TextEncoder().encode(raw)));
    expect(exact.contentSha256).not.toBe(createHash('sha256').update(JSON.stringify(JSON.parse(raw)), 'utf8').digest('hex'));
  });
  it.each([
    draft.contentJson.replace('材料记载的事实', '被替换的事实'),
    JSON.stringify(content, null, 2),
    draft.contentJson.replace('案件', '\\u6848\\u4ef6'),
  ])('rejects changed original text even if history and receipt declare the same SHA', async raw => {
    const changed = { ...draft, contentJson: raw };
    const request = vi.fn().mockResolvedValueOnce([changed]).mockResolvedValueOnce(changed);
    await expect(loadBoundDraftRead(request, target, () => true)).rejects.toThrow(/正文原文与声明 SHA 不一致/);
    expect(request).toHaveBeenCalledTimes(2);
  });
  it('accepts uppercase hexadecimal declarations for the same original bytes', async () => {
    const upper = { ...draft, contentSha256: contentSha.toUpperCase() };
    const request = vi.fn().mockResolvedValueOnce([upper]).mockResolvedValueOnce(upper).mockResolvedValueOnce([]).mockResolvedValueOnce([]);
    await expect(loadBoundDraftRead(request, { id: draft.id, revision: draft.revision }, () => true)).resolves.toMatchObject({ current: upper });
  });
  it('fails closed when the digest operation rejects', async () => {
    vi.spyOn(webcrypto.subtle, 'digest').mockRejectedValueOnce(new Error('test-only digest failure'));
    const request = vi.fn().mockResolvedValueOnce([draft]).mockResolvedValueOnce(draft);
    await expect(loadBoundDraftRead(request, target, () => true)).rejects.toThrow(/正文摘要核验不可用/);
    expect(request).toHaveBeenCalledTimes(2);
  });
  it('fails closed if the browser has no secure digest API', async () => {
    vi.stubGlobal('crypto', {});
    const request = vi.fn().mockResolvedValueOnce([draft]).mockResolvedValueOnce(draft);
    await expect(loadBoundDraftRead(request, target, () => true)).rejects.toThrow(/正文摘要核验不可用/);
    expect(request).toHaveBeenCalledTimes(2);
  });
  it.each(['success', 'mismatch', 'failure'])('does not deliver or fetch related data after stale digest %s', async outcome => {
    let resolve!: (value: ArrayBuffer) => void, reject!: (cause: unknown) => void;
    vi.spyOn(webcrypto.subtle, 'digest').mockImplementationOnce(() => new Promise((yes, no) => { resolve = yes; reject = no; }));
    const request = vi.fn().mockResolvedValueOnce([draft]).mockResolvedValueOnce(draft);
    let active = true;
    const reading = loadBoundDraftRead(request, target, () => active);
    await vi.waitFor(() => expect(resolve).toBeTypeOf('function'));
    active = false;
    if (outcome === 'failure') reject(new Error('stale digest failure'));
    else resolve(outcome === 'success' ? Uint8Array.from(Buffer.from(contentSha, 'hex')).buffer : new ArrayBuffer(32));
    await expect(reading).resolves.toBeNull();
    expect(request).toHaveBeenCalledTimes(2);
  });

});
