import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest';
import {cleanup,render,screen,fireEvent,waitFor} from '@testing-library/react';
import ComparisonStart from './ComparisonStart';
import {bindStart,startPacket,type StartReceipt} from './comparisonStartContracts';
import type {InputReceipt} from './comparisonInputContracts';
const request=vi.fn();vi.mock('./api',async()=>{const actual=await vi.importActual('./api');return {...actual,request:(...args:unknown[])=>request(...args)}});
const input:InputReceipt={taskId:'task',comparisonId:'comparison',inputSha:'b'.repeat(64),taskVersion:2,featureRevisionId:'rev',featureRevisionNumber:1,packageSha:'c'.repeat(64),purpose:'TECHNICAL_SIMILARITY',methodVersion:'PATENT_FEATURE_MATRIX_V1',expiresAt:'2026-10-08T00:00:00Z',candidates:[{candidateId:'a'.repeat(64),status:'QUEUED'},{candidateId:'c'.repeat(64),status:'COMMITTED'}]};
function props(){return {token:'session',input,disabled:false,onDenied:vi.fn(),onResult:vi.fn()}}
function receipt(key:string,status='COMMITTED'):StartReceipt{return {commandId:'command',taskId:input.taskId,comparisonId:input.comparisonId,candidateId:input.candidates[0].candidateId,inputSha:input.inputSha,requestId:key,attemptId:status==='COMMITTED'?'attempt':null,status,errorCode:null,expiresAt:input.expiresAt}}
beforeEach(()=>{request.mockReset();request.mockResolvedValueOnce({receipt:null});});afterEach(()=>{cleanup();});
describe('single candidate explicit start',()=>{
 it('posts once, binds exact receipt and links results without polling',async()=>{request.mockImplementation(async(_token,_url,_body,key)=>receipt(key));const p=props();render(<ComparisonStart {...p}/>);fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});fireEvent.click(screen.getByRole('button',{name:'明确发起此候选计算'}));await waitFor(()=>expect(p.onResult).toHaveBeenCalledOnce());expect(request).toHaveBeenCalledTimes(2);const call=request.mock.calls[1];expect(call[2]).toEqual({expectedTaskVersion:2,inputSha:input.inputSha});expect(call[1]).toContain(input.candidates[0].candidateId);expect(screen.getByRole('button',{name:'明确发起此候选计算'})).toBeDisabled();});
 it('lost POST response retains key and queries GET only, even if reads fail',async()=>{request.mockRejectedValueOnce(new Error('network interrupted')).mockRejectedValueOnce(new Error('GET interrupted')).mockImplementation(async(_token,url)=>receipt(new URL('https://product.invalid'+url).searchParams.get('requestId')!));const p=props();render(<ComparisonStart {...p}/>);fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});fireEvent.click(screen.getByRole('button',{name:'明确发起此候选计算'}));await screen.findByRole('alert');const key=request.mock.calls[1][3];fireEvent.click(screen.getByRole('button',{name:'查询原启动回执'}));await waitFor(()=>expect(screen.getByRole('alert')).toHaveTextContent('GET interrupted'));fireEvent.click(screen.getByRole('button',{name:'查询原启动回执'}));await waitFor(()=>expect(p.onResult).toHaveBeenCalledOnce());expect(request.mock.calls.slice(2).every(c=>c[2]===undefined&&c[1].endsWith('requestId='+encodeURIComponent(key)))).toBe(true);});
 it('cross-input late response is never shown',async()=>{let resolve!:(v:StartReceipt)=>void;request.mockImplementation(()=>new Promise(r=>{resolve=r}));const p=props(),ui=render(<ComparisonStart {...p}/>);fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});fireEvent.click(screen.getByRole('button',{name:'明确发起此候选计算'}));await waitFor(()=>expect(request).toHaveBeenCalledTimes(2));const key=request.mock.calls[1][3];ui.rerender(<ComparisonStart {...p} input={{...input,inputSha:'d'.repeat(64)}}/>);resolve(receipt(key));await waitFor(()=>expect(screen.queryByText(/原请求/)).not.toBeInTheDocument());expect(p.onResult).not.toHaveBeenCalled();});
 it('reopening restores the original unknown command without another POST or new key',async()=>{request.mockReset();request.mockResolvedValue({receipt:receipt('retained-key','RECONCILE_REQUIRED')});const p=props();render(<ComparisonStart {...p}/>);fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});fireEvent.click(screen.getByRole('button',{name:'恢复此候选已有回执'}));await screen.findByText('处理结果尚未确认，请查询原启动回执');expect(request).toHaveBeenCalledTimes(1);expect(request.mock.calls[0][2]).toBeUndefined();expect(screen.getByRole('button',{name:'明确发起此候选计算'})).toBeDisabled();}); it('rejects wrong SHA, foreign candidate and committed candidate restart',()=>{const packet=startPacket(input,input.candidates[0].candidateId);expect(()=>bindStart(input,packet,{...receipt(packet.key),inputSha:'e'.repeat(64)})).toThrow();expect(()=>startPacket(input,input.candidates[1].candidateId)).toThrow();expect(()=>bindStart(input,packet,{...receipt(packet.key),candidateId:'foreign'})).toThrow();});
 it.each([
  ['CANDIDATE_FAILED_BEFORE_MODEL_DISPATCH','此候选已确认在模型调用前失败，未提交矩阵。'],
  ['EXECUTION_WINDOW_INSUFFICIENT','剩余执行窗口不足，模型请求未发出，未提交矩阵。'],
  ['MATRIX_RESPONSE_INVALID','已收到模型响应但未通过矩阵校验，未提交结果；实际调用记录仍保留。'],
 ])('restores confirmed failure %s with no new dispatch or fabricated result',async(code,message)=>{
  request.mockReset();request.mockResolvedValue({receipt:{...receipt('original-failed-key','FAILED'),attemptId:'original-attempt',errorCode:code}});
  const p=props();render(<ComparisonStart {...p}/>);
  fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});
  fireEvent.click(screen.getByRole('button',{name:'恢复此候选已有回执'}));
  await screen.findByText(new RegExp(message));expect(request).toHaveBeenCalledOnce();
  expect(request.mock.calls[0][2]).toBeUndefined();expect(p.onResult).not.toHaveBeenCalled();
  expect(screen.getByRole('button',{name:'查询原启动回执'})).toBeEnabled();
 });

 it('confirmed handoff without admission is not calculation or a result and GET retains the original key',async()=>{
  request.mockReset();request.mockResolvedValue({receipt:{...receipt('retained-handoff-key','ADMISSION_REQUIRED'),attemptId:null,errorCode:'COMPARISON_MODEL_ADMISSION_REQUIRED'}});
  const p=props();render(<ComparisonStart {...p}/>);fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});
  fireEvent.click(screen.getByRole('button',{name:'恢复此候选已有回执'}));await screen.findByText(/受管服务已接收此候选交接/);
  expect(p.onResult).not.toHaveBeenCalled();expect(screen.getByRole('button',{name:'返回候选选择'})).toBeEnabled();
  request.mockResolvedValue({...receipt('retained-handoff-key','ADMISSION_REQUIRED'),attemptId:null,errorCode:'COMPARISON_MODEL_ADMISSION_REQUIRED'});
  fireEvent.click(screen.getByRole('button',{name:'查询原启动回执'}));await waitFor(()=>expect(request).toHaveBeenCalledTimes(2));
  expect(request.mock.calls[1][1]).toContain('requestId=retained-handoff-key');expect(request.mock.calls[1][2]).toBeUndefined();expect(p.onResult).not.toHaveBeenCalled();
 });
 it('rejects fake admission receipt with an attempt or without the stable reason',()=>{
  const packet=startPacket(input,input.candidates[0].candidateId),r={...receipt(packet.key,'ADMISSION_REQUIRED'),errorCode:'COMPARISON_MODEL_ADMISSION_REQUIRED'};
  expect(()=>bindStart(input,packet,{...r,attemptId:'attempt'})).toThrow();expect(()=>bindStart(input,packet,{...r,errorCode:null})).toThrow();
 });

 it('only explicit return and current proven unclaimed receipt allows a new same-candidate key',async()=>{
  const old={...receipt('old-key','ADMISSION_REQUIRED'),attemptId:null,errorCode:'COMPARISON_MODEL_ADMISSION_REQUIRED'};
  request.mockReset();request.mockResolvedValue({receipt:old});const p=props();render(<ComparisonStart {...p}/>);
  fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});fireEvent.click(screen.getByRole('button',{name:'恢复此候选已有回执'}));await screen.findByText(/受管服务已接收/);
  fireEvent.click(screen.getByRole('button',{name:'返回候选选择'}));fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});
  request.mockImplementation(async(_t,_url,body,key)=>body?{...old,commandId:'new-command',requestId:key}:{receipt:old});
  fireEvent.click(screen.getByRole('button',{name:'明确发起此候选计算'}));await waitFor(()=>expect(request).toHaveBeenCalledTimes(3));
  expect(request.mock.calls[1][2]).toBeUndefined();expect(request.mock.calls[2][3]).not.toBe('old-key');expect(p.onResult).not.toHaveBeenCalled();
 });
 it('changed latest unknown receipt blocks a new key even after explicit admission retry choice',async()=>{
  const old={...receipt('old-key','ADMISSION_REQUIRED'),attemptId:null,errorCode:'COMPARISON_MODEL_ADMISSION_REQUIRED'};
  request.mockReset();request.mockResolvedValue({receipt:old});const p=props();render(<ComparisonStart {...p}/>);
  fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});fireEvent.click(screen.getByRole('button',{name:'恢复此候选已有回执'}));await screen.findByText(/受管服务已接收/);
  fireEvent.click(screen.getByRole('button',{name:'返回候选选择'}));fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});
  request.mockResolvedValue({receipt:receipt('unknown-key','RECONCILE_REQUIRED')});fireEvent.click(screen.getByRole('button',{name:'明确发起此候选计算'}));await screen.findByText('处理结果尚未确认，请查询原启动回执');
  expect(request).toHaveBeenCalledTimes(2);expect(request.mock.calls[1][2]).toBeUndefined();expect(p.onResult).not.toHaveBeenCalled();
 });

 it('uses bound document labels and hides request/attempt/hash without altering the original wire key',async()=>{
  const old=receipt('retained-private-key');request.mockReset();request.mockResolvedValue({receipt:old});const p=props();
  render(<ComparisonStart {...p} candidateDocuments={{[input.candidates[0].candidateId]:{documentId:'CN123',documentVersion:'v2'}}}/>);
  expect(screen.getByRole('option',{name:'CN123 · v2'})).toHaveValue(input.candidates[0].candidateId);
  fireEvent.change(screen.getByLabelText('计算候选'),{target:{value:input.candidates[0].candidateId}});
  fireEvent.click(screen.getByRole('button',{name:'恢复此候选已有回执'}));await screen.findByText('结果已提交，可查看对照矩阵');
  expect(document.body.textContent).not.toContain('retained-private-key');expect(document.body.textContent).not.toContain('attempt');
  expect(document.body.textContent).not.toContain(input.candidates[0].candidateId);expect(document.body.textContent).not.toContain('COMMITTED');
  request.mockResolvedValue(old);fireEvent.click(screen.getByRole('button',{name:'查询原启动回执'}));await waitFor(()=>expect(request).toHaveBeenCalledTimes(2));
  expect(request.mock.calls[1][1]).toContain('requestId=retained-private-key');expect(request.mock.calls[1][2]).toBeUndefined();
 });

});





it("has an exact accessible candidate control independent of option text",()=>{render(<ComparisonStart {...props()} candidateDocuments={{[input.candidates[0].candidateId]:{documentId:"P2",documentVersion:"RAW_SHA256:"+"d".repeat(64)}}}/>);expect(screen.getByRole("combobox",{name:"计算候选"})).toBeInTheDocument();});
