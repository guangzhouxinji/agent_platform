import {describe, expect, it} from 'vitest';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react';
import ComparisonStart from './ComparisonStart';
import {input,candidate,root,latest,fetchMock,wires,json,receipt,props,deferred,selectAndStart,queryOriginal,postKey,originalReads,noNewStart} from './comparisonStartConsumer.testFixtures';

describe('ComparisonStart real request/fetch consumer boundary (engineering unit only)', () => {
  async function chooseFailedRetry(code = 'CANDIDATE_FAILED_BEFORE_MODEL_DISPATCH') {
    const old = {...receipt('unit-failed-key', 'FAILED'), attemptId: 'unit-failed-attempt', errorCode: code};
    fetchMock.mockResolvedValueOnce(json({receipt: old}));
    const p = props(); render(<ComparisonStart {...p}/>);
    fireEvent.change(screen.getByLabelText('计算候选'), {target: {value: candidate}});
    fireEvent.click(screen.getByRole('button', {name: '恢复此候选已有回执'}));
    await screen.findByRole('button', {name: '返回候选选择'});
    expect(wires.map(w => w.method)).toEqual(['GET']);
    fireEvent.click(screen.getByRole('button', {name: '返回候选选择'}));
    fireEvent.change(screen.getByLabelText('计算候选'), {target: {value: candidate}});
    return {old, p};
  }

  it.each(['CANDIDATE_FAILED_BEFORE_MODEL_DISPATCH', 'EXECUTION_WINDOW_INSUFFICIENT', 'MATRIX_RESPONSE_INVALID'])(
    'explicit retry of confirmed %s rechecks latest then sends one new key without deleting history', async code => {
      const {old, p} = await chooseFailedRetry(code);
      fetchMock.mockResolvedValueOnce(json({receipt: old}));
      fetchMock.mockImplementationOnce(async () => json({...receipt(postKey()), commandId: 'unit-new-command'}));
      fireEvent.click(screen.getByRole('button', {name: '明确发起此候选计算'}));
      await waitFor(() => expect(p.onResult).toHaveBeenCalledOnce());
      expect(wires.map(w => [w.method, w.path])).toEqual([['GET', latest], ['GET', latest], ['POST', root]]);
      expect(postKey()).not.toBe(old.requestId); expect(wires[2].authorization).toBe('Bearer ' + p.token);
      noNewStart();
    });

  it.each(['unknown', 'committed', 'other-command', 'other-attempt'])(
    'a fresh %s receipt cancels the prior explicit failure retry proof', async change => {
      const {old, p} = await chooseFailedRetry();
      const current = change === 'unknown' ? {...old, status: 'RECONCILE_REQUIRED'}
        : change === 'committed' ? {...old, status: 'COMMITTED'}
        : change === 'other-command' ? {...old, commandId: 'unit-other-command', requestId: 'unit-other-key'}
        : {...old, attemptId: 'unit-other-attempt'};
      fetchMock.mockResolvedValueOnce(json({receipt: current}));
      fireEvent.click(screen.getByRole('button', {name: '明确发起此候选计算'}));
      await screen.findByRole('button', {name: '查询原启动回执'});
      expect(wires.map(w => w.method)).toEqual(['GET', 'GET']); noNewStart();
      expect(p.onResult).toHaveBeenCalledTimes(change === 'committed' ? 1 : 0);
    });

  it('failed latest recheck never sends and explicit recovery reads only', async () => {
    const {old, p} = await chooseFailedRetry();
    fetchMock.mockRejectedValueOnce(new TypeError('unit latest interrupted'));
    fireEvent.click(screen.getByRole('button', {name: '明确发起此候选计算'}));
    await screen.findByRole('alert'); noNewStart();
    fetchMock.mockResolvedValueOnce(json({receipt: old}));
    fireEvent.click(screen.getByRole('button', {name: '恢复此候选已有回执'}));
    await screen.findByRole('button', {name: '查询原启动回执'});
    expect(wires.map(w => w.method)).toEqual(['GET', 'GET', 'GET']); expect(p.onResult).not.toHaveBeenCalled();
  });

  it('lost retry POST retains the new key and only reconciles it, never the old failed request', async () => {
    const {old, p} = await chooseFailedRetry();
    fetchMock.mockResolvedValueOnce(json({receipt: old})).mockRejectedValueOnce(new TypeError('unit retry interrupted'));
    fireEvent.click(screen.getByRole('button', {name: '明确发起此候选计算'}));
    await screen.findByRole('alert'); const key = postKey(); expect(key).not.toBe(old.requestId); noNewStart();
    fetchMock.mockResolvedValueOnce(json({...receipt(key, 'RECONCILE_REQUIRED'), commandId: 'unit-new-command'}));
    queryOriginal(); await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('处理结果尚未确认'));
    originalReads(key, 1); expect(p.onResult).not.toHaveBeenCalled();
  });

  it('explicit null alone permits one POST with exact body/key and Bearer/no-store semantics', async () => {
    fetchMock.mockResolvedValueOnce(json({receipt: null}));
    fetchMock.mockImplementationOnce(async () => json(receipt(postKey())));
    const p = props(); render(<ComparisonStart {...p}/>); selectAndStart();
    await waitFor(() => expect(p.onResult).toHaveBeenCalledOnce());
    expect(wires.map(w => [w.method, w.path])).toEqual([['GET', latest], ['POST', root]]);
    expect(wires[0].body).toBeUndefined(); expect(wires[0].key).toBeNull();
    for (const wire of wires) {
      expect(wire.authorization).toBe('Bearer ' + p.token);
      expect(wire.accept).toBe('application/json'); expect(wire.cache).toBe('no-store');
      expect(wire.contentType).toBe(wire.method === 'POST' ? 'application/json' : null);
    }
    expect(screen.getByRole('status')).toHaveTextContent('结果已提交'); noNewStart();
  });

  it('unknown POST survives temporary disabled true/false and reconciles only the original key', async () => {
    fetchMock.mockResolvedValueOnce(json({receipt: null})).mockRejectedValueOnce(new TypeError('unit lost POST'));
    const p = props(), ui = render(<ComparisonStart {...p}/>); selectAndStart();
    await screen.findByRole('alert'); const key = postKey();
    ui.rerender(<ComparisonStart {...p} disabled/>);
    ui.rerender(<ComparisonStart {...p}/>);
    noNewStart(); expect(screen.getByRole('status')).not.toHaveTextContent(key);
    fetchMock.mockResolvedValueOnce(json(receipt(key, 'RECONCILE_REQUIRED'))); queryOriginal();
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('处理结果尚未确认'));
    originalReads(key, 1); expect(p.onResult).not.toHaveBeenCalled();
  });

  it('disabled invalidates an in-flight POST response without losing its key or leaving an execution lock', async () => {
    const late = deferred();
    fetchMock.mockResolvedValueOnce(json({receipt: null})).mockReturnValueOnce(late.promise);
    const p = props(), ui = render(<ComparisonStart {...p}/>); selectAndStart();
    await waitFor(() => expect(wires).toHaveLength(2)); const key = postKey();
    ui.rerender(<ComparisonStart {...p} disabled/>); ui.rerender(<ComparisonStart {...p}/>);
    noNewStart();
    // An old unresolved request must not lock the restored coordinate GET.
    fetchMock.mockResolvedValueOnce(json(receipt(key, 'RECONCILE_REQUIRED'))); queryOriginal();
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('处理结果尚未确认'));
    await act(async () => {late.resolve(json(receipt(key))); await late.promise;});
    expect(p.onResult).not.toHaveBeenCalled();
    expect(screen.getByRole('status')).not.toHaveTextContent('结果已提交'); originalReads(key, 1);
  });

  // Existing ComparisonStart.test.tsx covers cross-Input SHA; do not duplicate it here.
  it.each(['taskVersion', 'taskId', 'session'] as const)(
    'rejects a delayed POST after %s changes (version keeps the same Task/Input SHA)', async change => {
      const late = deferred();
      fetchMock.mockResolvedValueOnce(json({receipt: null})).mockReturnValueOnce(late.promise);
      const p = props(), ui = render(<ComparisonStart {...p}/>); selectAndStart();
      await waitFor(() => expect(wires).toHaveLength(2)); const key = postKey();
      const next = change === 'session' ? {...p, token: 'unit-other-session'}
        : {...p, input: {...input, ...(change === 'taskVersion' ? {taskVersion: 3} : {taskId: 'unit-other-task'})}};
      ui.rerender(<ComparisonStart {...next}/>);
      await act(async () => {late.resolve(json(receipt(key))); await late.promise;});
      expect(p.onResult).not.toHaveBeenCalled(); expect(p.onDenied).not.toHaveBeenCalled();
      expect(screen.queryByRole('status')).not.toBeInTheDocument();
      expect(wires).toHaveLength(2);
    });

  it.each([input.expiresAt, '2000-01-01T00:00:00Z'])(
    'CANCELLED (%s) permits coordinate GET but no selection return, new POST or result callback', async expiresAt => {
      const key = 'unit-cancelled-key';
      fetchMock.mockResolvedValueOnce(json({receipt: receipt(key, 'CANCELLED', expiresAt)}));
      const p = props(); render(<ComparisonStart {...p}/>);
      fireEvent.change(screen.getByLabelText('计算候选'), {target: {value: candidate}});
      fireEvent.click(screen.getByRole('button', {name: '恢复此候选已有回执'}));
      await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('任务已取消'));
      expect(screen.getByRole('status')).not.toHaveTextContent(key); noNewStart();
      expect(screen.queryByRole('button', {name: '返回候选选择'})).not.toBeInTheDocument();
      fetchMock.mockResolvedValueOnce(json(receipt(key, 'CANCELLED', expiresAt))); queryOriginal();
      await waitFor(() => expect(wires).toHaveLength(2));
      await waitFor(() => expect(screen.getByRole('button', {name: '查询原启动回执'})).toBeEnabled());
      expect(wires.map(w => [w.method, w.path])).toEqual([
        ['GET', latest], ['GET', root + '?requestId=' + key],
      ]);
      expect(p.onResult).not.toHaveBeenCalled(); expect(p.onDenied).not.toHaveBeenCalled(); noNewStart();
    });

  it.each(['409', 'network'])(
    'clears prior COMMITTED display at GET start and after %s failure, retaining the original key', async failure => {
      fetchMock.mockResolvedValueOnce(json({receipt: null}));
      fetchMock.mockImplementationOnce(async () => json(receipt(postKey())));
      const p = props(); render(<ComparisonStart {...p}/>); selectAndStart();
      await waitFor(() => expect(p.onResult).toHaveBeenCalledOnce()); const key = postKey();
      const read = deferred(); fetchMock.mockReturnValueOnce(read.promise); queryOriginal();
      expect(screen.getByRole('status')).not.toHaveTextContent('结果已提交');
      expect(screen.queryByRole('button', {name: '返回候选选择'})).not.toBeInTheDocument();
      if (failure === '409') {
        await act(async () => {read.resolve(json({unitError: 'conflict'}, 409)); await read.promise;});
      } else {
        await act(async () => {
          read.reject(new TypeError('unit GET network interrupted'));
          await read.promise.catch(() => undefined);
        });
      }
      await screen.findByRole('alert');
      expect(screen.getByRole('status')).not.toHaveTextContent('结果已提交'); noNewStart();
      expect(p.onResult).toHaveBeenCalledOnce(); expect(p.onDenied).not.toHaveBeenCalled();
      fetchMock.mockResolvedValueOnce(json(receipt(key, 'RECONCILE_REQUIRED'))); queryOriginal();
      await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('处理结果尚未确认'));
      originalReads(key, 2); expect(p.onResult).toHaveBeenCalledOnce();
    });

  it.each([401, 403, 404])('HTTP %s locks this mounted context after onDenied, with no new POST', async status => {
    fetchMock.mockResolvedValueOnce(json({receipt: null})).mockResolvedValueOnce(json({unitError: 'denied'}, status));
    const p = props(); render(<ComparisonStart {...p}/>); selectAndStart();
    await waitFor(() => expect(p.onDenied).toHaveBeenCalledOnce());
    expect(p.onResult).not.toHaveBeenCalled(); expect(screen.queryByRole('status')).not.toBeInTheDocument();
    // Parent deliberately remains mounted; callback alone is not an authorization lock.
    noNewStart(); expect(screen.getByLabelText('计算候选')).toBeDisabled();
    const restore = screen.queryByRole('button', {name: '恢复此候选已有回执'});
    if (restore) {expect(restore).toBeDisabled(); fireEvent.click(restore);}
    fireEvent.click(screen.getByRole('button', {name: '明确发起此候选计算'}));
    expect(wires).toHaveLength(2); expect(wires.filter(w => w.method === 'POST')).toHaveLength(1);
  });

  it.each(['false', 'undefined', 'wrong-binding'])(
    'latest %s is not null: blocks POST, and subsequent manual recovery reads latest only', async invalid => {
      const value = invalid === 'false' ? {receipt: false} : invalid === 'undefined' ? {}
        : {receipt: {...receipt('unit-foreign-key'), inputSha: 'd'.repeat(64)}};
      fetchMock.mockResolvedValueOnce(json(value));
      const p = props(); render(<ComparisonStart {...p}/>); selectAndStart();
      await screen.findByRole('alert'); noNewStart();
      expect(wires.map(w => [w.method, w.path])).toEqual([['GET', latest]]);
      expect(p.onResult).not.toHaveBeenCalled(); expect(screen.queryByRole('status')).not.toBeInTheDocument();
      fetchMock.mockResolvedValueOnce(json({receipt: receipt('unit-retained-key', 'RECONCILE_REQUIRED')}));
      const restore = screen.getByRole('button', {name: '恢复此候选已有回执'});
      expect(restore).toBeEnabled(); fireEvent.click(restore);
      await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('处理结果尚未确认'));
      expect(wires.map(w => [w.method, w.path])).toEqual([['GET', latest], ['GET', latest]]);
      expect(wires.every(w => w.body === undefined && w.key === null)).toBe(true);
      expect(p.onResult).not.toHaveBeenCalled(); noNewStart();
    });
});

