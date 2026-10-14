import {describe,it,expect} from 'vitest';
import {candidateLabel,candidateStatus,startStatus,failureReason} from './comparisonPresentation';
describe('final product display, not new persisted states',()=>{
 it.each([
 ['ACCEPTED','请求已接收，尚未确认计算开始'],['DISPATCHING','正在交接计算请求'],
 ['RECONCILE_REQUIRED','处理结果尚未确认，请查询原启动回执'],
 ['ADMISSION_REQUIRED','服务已接收，计算条件尚未满足'],
 ['COMMITTED','结果已提交，可查看对照矩阵'],['FAILED','此次计算请求失败，请查看原因'],
 ['CANCELLED','任务已取消，不会重新发起计算'],
 ])('preserves meaning of %s', (state,label)=>expect(startStatus(state)).toBe(label));
 it.each([undefined,'UNKNOWN_FUTURE_STATE'])('never presents unverified %s as success',state=>expect(startStatus(state)).toContain('尚未确认'));
 it.each([['QUEUED','待计算'],['RUNNING','计算中，尚未提交结果'],['COMMITTED','结果已提交'],['FAILED','计算失败'],['CANCELLED','已取消']])('preserves candidate %s', (state,label)=>expect(candidateStatus(state)).toBe(label));
 it('uses exact document identity or distinct input ordinal, never a fabricated document',()=>{
  expect(candidateLabel(3,{documentId:'CN123',documentVersion:'v2'})).toBe('CN123 · v2');expect(candidateLabel(3)).toBe('候选 4');
 });
 it('does not render internal unknown errors or claim no charge',()=>{
  expect(failureReason('SENSITIVE_INTERNAL_ERROR')).not.toContain('SENSITIVE_INTERNAL_ERROR');expect(failureReason(null)).toContain('不能据此推断未调用模型');
 });
 it('unknown candidate is not completed',()=>expect(candidateStatus('NEW')).toContain('尚未核实'));
});
