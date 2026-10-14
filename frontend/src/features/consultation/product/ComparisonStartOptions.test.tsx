import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,describe,expect,it,vi} from 'vitest';
import ComparisonStartOptions,{recomputeSelection} from './ComparisonStartOptions';
import type {InputReceipt} from './comparisonInputContracts';
import type {MatrixSummary} from './comparisonMatrix';
import type {MatrixLineage} from './comparisonLineage';
const sha=(c:string)=>c.repeat(64),a=sha('a'),b=sha('b'),inputSha=sha('c'),configurationSha=sha('d');
function fixture(){
 const expiry=new Date(Date.now()+60000).toISOString();
 const input:InputReceipt={taskId:'task',comparisonId:'current',inputSha,taskVersion:2,featureRevisionId:'features',featureRevisionNumber:2,packageSha:sha('f'),purpose:'TECHNICAL_SIMILARITY',methodVersion:'PATENT_FEATURE_MATRIX_V1',expiresAt:expiry,candidates:[{candidateId:a,status:'QUEUED'},{candidateId:b,status:'QUEUED'}]};
 const summary:MatrixSummary={taskId:'task',comparisonId:'current',inputSha,state:'IN_PROGRESS',confirmedFeatures:[{featureId:'F1',userText:'特征一'}],candidates:input.candidates.map(c=>({...c,attemptId:null,errorCode:null,executionState:null,reportIds:[]}))};
 const rowA={candidateId:a,claimBranch:[1],featureId:'F1'},rowB={candidateId:b,claimBranch:[1],featureId:'F1'};
 const lineage:MatrixLineage={taskId:'task',comparisonId:'current',inputSha,previousComparisonId:'parent',parentInputSha:sha('e'),configurationVersion:'v1',configurationSha,expiresAt:expiry,expectedRows:[rowA,rowB],invalidated:[rowB],recompute:[rowB],carried:[{key:rowA,comparisonId:'parent',inputSha:sha('e'),reportId:'old',reportSha:sha('f'),rowSha:sha('f')}],ancestry:[{comparisonId:'parent',inputSha:sha('e'),previousComparisonId:null}]};
 return {input,summary,lineage};
}
const response=(data:unknown,status=200)=>({ok:status===200,status,json:async()=>data}) as Response;
const props=(input:InputReceipt)=>({token:'human-session',input,disabled:false,onDenied:vi.fn(),onResult:vi.fn()});
const root='/v2/consultation/tasks/task/patent-comparisons/current/candidates/'+b+'/start-commands';
function affected(){fireEvent.click(screen.getByRole('radio',{name:'受影响候选'}));fireEvent.click(screen.getByRole('button',{name:'读取受影响候选'}));}
async function select(){
 // The asynchronously mounted start component resets selection in its initial scope effect.
 await act(async()=>{});
 const candidate=screen.getByLabelText('计算候选');await waitFor(()=>expect(candidate).toBeEnabled());
 fireEvent.change(candidate,{target:{value:b}});
 const start=screen.getByRole('button',{name:'明确发起此候选计算'});
 await waitFor(()=>{expect(candidate).toHaveValue(b);expect(start).toBeEnabled()});fireEvent.click(start);
}
function receipt(key:string,status='RECONCILE_REQUIRED'){return {commandId:'command',taskId:'task',comparisonId:'current',candidateId:b,inputSha,requestId:key,attemptId:status==='COMMITTED'?'attempt':null,status,errorCode:null,expiresAt:new Date(Date.now()+60000).toISOString()};}
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();vi.useRealTimers();});
describe('affected-candidate start, using the original handoff and reconciliation',()=>{
 it('normal selection stays independent and never automatically reads projection',()=>{const f=fixture(),fetch=vi.fn();vi.stubGlobal('fetch',fetch);render(<ComparisonStartOptions {...props(f.input)}/>);expect(screen.getAllByRole('option')).toHaveLength(3);expect(fetch).not.toHaveBeenCalled();});
 it('A carried/B pending selects only B and posts once with exact configuration binding',async()=>{
  const f=fixture(),p=props(f.input),fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.lineage)).mockResolvedValueOnce(response({receipt:null})).mockImplementation(async(_url,init)=>response(receipt(init.headers['Idempotency-Key'],'COMMITTED')));vi.stubGlobal('fetch',fetch);render(<ComparisonStartOptions {...p}/>);affected();await screen.findByLabelText('计算候选');
  expect(screen.getAllByRole('option')).toHaveLength(2);expect(screen.getByRole('option',{name:'候选 2'})).toHaveValue(b);await select();await waitFor(()=>expect(p.onResult).toHaveBeenCalledTimes(1));
  expect(fetch.mock.calls.map(c=>c[0])).toEqual(['/v2/consultation/tasks/task/patent-comparisons/current/results','/v2/consultation/tasks/task/patent-comparisons/current/lineage',root+'/latest?inputSha='+inputSha,root+'/recompute']);
  expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({expectedTaskVersion:2,inputSha,configurationSha});expect(fetch.mock.calls.filter(([,o])=>o.method==='POST')).toHaveLength(1);
 });
 it('lost affected POST is reconciled through the original root GET, never reposted',async()=>{
  const f=fixture(),fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.lineage)).mockResolvedValueOnce(response({receipt:null})).mockRejectedValueOnce(new Error('network')).mockImplementation(async url=>response(receipt(new URL('https://product.invalid'+url).searchParams.get('requestId')!)));vi.stubGlobal('fetch',fetch);render(<ComparisonStartOptions {...props(f.input)}/>);affected();await screen.findByLabelText('计算候选');await select();await screen.findByRole('alert');const key=fetch.mock.calls[3][1].headers['Idempotency-Key'];
  fireEvent.click(screen.getByRole('button',{name:'查询原启动回执'}));await waitFor(()=>expect(fetch).toHaveBeenCalledTimes(5));expect(fetch.mock.calls[4][0]).toBe(root+'?requestId='+key);expect(fetch.mock.calls[4][1].method).toBe('GET');expect(fetch.mock.calls.filter(([,o])=>o.method==='POST')).toHaveLength(1);
 });
 it('restores an original unknown command without a new recompute POST',async()=>{
  const f=fixture(),fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.lineage)).mockResolvedValueOnce(response({receipt:receipt('original-key')}));vi.stubGlobal('fetch',fetch);render(<ComparisonStartOptions {...props(f.input)}/>);affected();await screen.findByLabelText('计算候选');await select();await screen.findByRole('button',{name:'查询原启动回执'});expect(fetch.mock.calls.every(([,o])=>o.method==='GET')).toBe(true);
 });
 it('no pending candidates has no new start, but normal mode remains available',async()=>{
  const f=fixture();f.lineage.recompute=[];f.lineage.invalidated=[];f.lineage.carried.push({...f.lineage.carried[0],key:f.lineage.expectedRows[1],reportId:'other'});
  vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.lineage)));render(<ComparisonStartOptions {...props(f.input)}/>);affected();await screen.findByText('当前没有待重新计算的候选。');expect(screen.queryByLabelText('计算候选')).not.toBeInTheDocument();fireEvent.click(screen.getByRole('radio',{name:'所选候选'}));expect(screen.getAllByRole('option')).toHaveLength(3);
 });
 it('rejects a mixed candidate instead of computing its carried rows',()=>{const f=fixture();f.lineage.expectedRows.push({...f.lineage.expectedRows[1],featureId:'F2'});expect(()=>recomputeSelection(f.lineage)).toThrow('混合');});
 it('a current committed B is not offered again even when the parent-difference plan includes B',async()=>{
  const f=fixture();f.summary.state='PARTIAL';Object.assign(f.summary.candidates[1],{status:'COMMITTED',attemptId:'attempt',reportIds:['new-report']});
  const fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.lineage));vi.stubGlobal('fetch',fetch);render(<ComparisonStartOptions {...props(f.input)}/>);affected();await screen.findByText('当前没有待重新计算的候选。');expect(screen.queryByLabelText('计算候选')).not.toBeInTheDocument();expect(fetch.mock.calls.every(([,o])=>o.method==='GET')).toBe(true);
 });
 it.each([401,403,404,409])('projection failure %s cannot authorize start',async status=>{
  const f=fixture(),p=props(f.input);vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response({},status)));render(<ComparisonStartOptions {...p}/>);affected();await screen.findByRole('alert');expect(screen.queryByLabelText('计算候选')).not.toBeInTheDocument();expect(p.onDenied).toHaveBeenCalledTimes([401,403,404].includes(status)?1:0);
 });
 it.each(['token','input','disabled','normal','unmount'])('late projection after %s cannot enable affected start',async kind=>{
  const f=fixture(),p=props(f.input);let resolve!:(r:Response)=>void;vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(f.summary)).mockReturnValueOnce(new Promise<Response>(yes=>{resolve=yes})));const ui=render(<ComparisonStartOptions {...p}/>);affected();await waitFor(()=>expect(resolve).toBeTypeOf('function'));
  if(kind==='unmount')ui.unmount();else if(kind==='normal')fireEvent.click(screen.getByRole('radio',{name:'所选候选'}));else ui.rerender(<ComparisonStartOptions {...p} {...(kind==='token'?{token:'new'}:kind==='disabled'?{disabled:true}:{input:{...f.input,inputSha:sha('f')}})}/>);
  await act(async()=>resolve(response(f.lineage)));
  if(kind==='normal')expect(screen.getAllByRole('option')).toHaveLength(3);else expect(screen.queryByRole('option',{name:'候选 2'})).toBeNull();
 });
 it('projection expiry removes new-start controls',async()=>{
  vi.useFakeTimers();const f=fixture();f.lineage.expiresAt=new Date(Date.now()+1000).toISOString();vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.lineage)));render(<ComparisonStartOptions {...props(f.input)}/>);await act(async()=>affected());expect(screen.getByLabelText('计算候选')).toBeInTheDocument();await act(async()=>vi.advanceTimersByTime(1000));expect(screen.queryByLabelText('计算候选')).not.toBeInTheDocument();expect(screen.getByRole('alert')).toHaveTextContent('到期');
 });
});
