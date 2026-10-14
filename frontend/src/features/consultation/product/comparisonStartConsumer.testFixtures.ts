import {afterEach, beforeEach, expect, vi} from 'vitest';
import {cleanup, fireEvent, screen} from '@testing-library/react';
import type {InputReceipt} from './comparisonInputContracts';
import type {StartReceipt} from './comparisonStartContracts';

/** Fixed engineering fixtures only: no Issuer/mTLS, business acceptance, database
 * COMMITTED row, external request, API mock, or direct service invocation. */
export const input: InputReceipt = {
  taskId: 'unit-task', comparisonId: 'unit-comparison', inputSha: 'b'.repeat(64),
  taskVersion: 2, featureRevisionId: 'unit-revision', featureRevisionNumber: 1,
  packageSha: 'c'.repeat(64), purpose: 'TECHNICAL_SIMILARITY',
  methodVersion: 'PATENT_FEATURE_MATRIX_V1', expiresAt: '2099-01-01T00:00:00Z',
  candidates: [{candidateId: 'a'.repeat(64), status: 'QUEUED'}],
};
export const candidate = input.candidates[0].candidateId;
export const root = '/v2/consultation/tasks/unit-task/patent-comparisons/unit-comparison'
  + '/candidates/' + candidate + '/start-commands';
export const latest = root + '/latest?inputSha=' + input.inputSha;
export const fetchMock = vi.fn<typeof fetch>();
type Wire = {method: string; path: string; body: unknown; key: string | null;
  cache: RequestCache | undefined; authorization: string | null; accept: string | null;
  contentType: string | null};
export let wires: Wire[];
export function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {status, headers: {'Content-Type': 'application/json'}});
}
export function receipt(key: string, status = 'COMMITTED', expiresAt = input.expiresAt): StartReceipt {
  return {commandId: 'unit-command', taskId: input.taskId, comparisonId: input.comparisonId,
    candidateId: candidate, inputSha: input.inputSha, requestId: key,
    attemptId: status === 'COMMITTED' ? 'unit-attempt' : null,
    status, errorCode: null, expiresAt};
}
export function props() {
  return {token: 'unit-session-not-an-issued-token', input, disabled: false,
    onDenied: vi.fn(), onResult: vi.fn()};
}
export function deferred() {
  let resolve!: (value: Response) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<Response>((done, fail) => {resolve = done; reject = fail;});
  return {promise, resolve, reject};
}
export function selectAndStart() {
  fireEvent.change(screen.getByLabelText('计算候选'), {target: {value: candidate}});
  fireEvent.click(screen.getByRole('button', {name: '明确发起此候选计算'}));
}
export function queryOriginal() {
  const button = screen.getByRole('button', {name: '查询原启动回执'});
  expect(button).toBeEnabled();
  fireEvent.click(button);
}
export function postKey() {
  const post = wires.find(w => w.method === 'POST');
  expect(post).toBeDefined();
  expect(post!.path).toBe(root);
  expect(post!.body).toEqual({expectedTaskVersion: 2, inputSha: input.inputSha});
  expect(post!.key).toEqual(expect.any(String));
  expect(post!.key!.length).toBeGreaterThan(0);
  return post!.key!;
}
export function originalReads(key: string, count: number) {
  const reads = wires.filter(w => w.path === root + '?requestId=' + encodeURIComponent(key));
  expect(reads).toHaveLength(count);
  for (const read of reads) {
    expect(read.method).toBe('GET'); expect(read.body).toBeUndefined(); expect(read.key).toBeNull();
  }
  expect(wires.filter(w => w.method === 'POST')).toHaveLength(1);
}
export function noNewStart() {
  expect(screen.getByRole('button', {name: '明确发起此候选计算'})).toBeDisabled();
}
beforeEach(() => {
  wires = []; fetchMock.mockReset();
  // All networking ends here; the real ./api.request constructs every captured request.
  vi.stubGlobal('fetch', (url: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    wires.push({method: init?.method ?? 'GET', path: String(url),
      body: init?.body === undefined ? undefined : JSON.parse(String(init.body)),
      key: headers.get('Idempotency-Key'), cache: init?.cache,
      authorization: headers.get('Authorization'), accept: headers.get('Accept'),
      contentType: headers.get('Content-Type')});
    return fetchMock(url, init);
  });
});
afterEach(() => {cleanup(); vi.unstubAllGlobals();});

