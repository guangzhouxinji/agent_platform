import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,describe,expect,it,vi} from 'vitest';
import ComparisonMatrixLineage from './ComparisonMatrixLineage';
import ComparisonMatrixResults from './ComparisonMatrixResults';
import {bindMatrixLineage,type MatrixLineage} from './comparisonLineage';
import type {MatrixSummary} from './comparisonMatrix';
const hash=(c:string)=>c.repeat(64);
const summary:MatrixSummary={taskId:'task',comparisonId:'current',inputSha:hash('a'),state:'IN_PROGRESS',confirmedFeatures:[{featureId:'F1',userText:'特征一'}],candidates:[{candidateId:hash('b'),status:'QUEUED',attemptId:null,errorCode:null,executionState:null,reportIds:[]}]};
function fixture():MatrixLineage {const key={candidateId:hash('b'),claimBranch:[1],featureId:'F1'};return {taskId:'task',comparisonId:'current',inputSha:hash('a'),previousComparisonId:'parent',parentInputSha:hash('c'),configurationVersion:'v1',configurationSha:hash('d'),expiresAt:new Date(Date.now()+60000).toISOString(),expectedRows:[key],invalidated:[],recompute:[],carried:[{key,comparisonId:'parent',inputSha:hash('c'),reportId:'report',reportSha:hash('e'),rowSha:hash('f')}],ancestry:[{comparisonId:'parent',inputSha:hash('c'),previousComparisonId:null}]};}
const response=(data:unknown,status=200)=>({ok:status===200,status,json:async()=>data}) as Response;
const props=()=>({token:'session',summary,disabled:false,onDenied:vi.fn()});
function start(){fireEvent.click(screen.getByRole('button',{name:'读取沿用关系'}));}
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();vi.useRealTimers();});
describe('lineage transport closure',()=>{
 it('accepts original ancestor coordinates without rebasing to parent',()=>{const v=fixture();v.ancestry[0].previousComparisonId='root';v.ancestry.push({comparisonId:'root',inputSha:hash('e'),previousComparisonId:null});v.carried[0].comparisonId='root';v.carried[0].inputSha=hash('e');expect(bindMatrixLineage(summary,v).carried[0].comparisonId).toBe('root')});
 it.each(['missing','cycle','sha','gap','limit'])('rejects ancestor %s',kind=>{const v=fixture();switch(kind){case 'missing':v.ancestry=[];break;case 'cycle':v.ancestry[0].previousComparisonId='current';break;case 'sha':v.ancestry[0].inputSha=hash('f');break;case 'gap':v.ancestry.push({comparisonId:'root',inputSha:hash('f'),previousComparisonId:null});break;case 'limit':v.ancestry=Array(64).fill(v.ancestry[0]);break}expect(()=>bindMatrixLineage(summary,v)).toThrow()});
 it('accepts exact references, with no new machine result or status',()=>{expect(bindMatrixLineage(summary,fixture()).carried).toHaveLength(1)});
 it.each(['task','input','parent','sha','expiry','duplicate','missing','intersection','reference','candidate','feature','branch','invalidated'])('refuses %s',kind=>{
  const v=fixture();switch(kind){case 'task':v.taskId='other';break;case 'input':v.inputSha=hash('d');break;case 'parent':v.previousComparisonId='current';break;case 'sha':v.configurationSha='wrong';break;case 'expiry':v.expiresAt=new Date(0).toISOString();break;case 'duplicate':v.expectedRows.push(v.expectedRows[0]);break;case 'missing':v.carried=[];break;case 'intersection':v.recompute=[v.expectedRows[0]];break;case 'reference':v.carried[0].comparisonId='foreign';break;case 'candidate':v.expectedRows[0].candidateId=hash('f');break;case 'feature':v.expectedRows[0].featureId='foreign';break;case 'branch':v.expectedRows[0].claimBranch=[2,1];break;case 'invalidated':v.invalidated=[v.expectedRows[0]];break;}
  expect(()=>bindMatrixLineage(summary,v)).toThrow('未展示');
 });
 it('requires every confirmed feature in every candidate branch',()=>{const s={...summary,confirmedFeatures:[...summary.confirmedFeatures,{featureId:'F2',userText:'二'}]};expect(()=>bindMatrixLineage(s,fixture())).toThrow();});
 it('permits invalidated removed rows but does not add them to current pending domain',()=>{const v=fixture();v.invalidated=[{candidateId:hash('f'),featureId:'old',claimBranch:[1]}];expect(bindMatrixLineage(summary,v).invalidated).toHaveLength(1)});
});
describe('explicit read-only lineage workflow',()=>{
 it.each(['COMMITTED','CANCELLED'])('does not offer current %s parent-difference rows as pending recompute',async status=>{
  const v=fixture();v.recompute=[v.expectedRows[0]];v.invalidated=[v.expectedRows[0]];v.carried=[];
  const s={...summary,candidates:[{...summary.candidates[0],status}]};const fetch=vi.fn().mockResolvedValue(response(v));vi.stubGlobal('fetch',fetch);
  render(<ComparisonMatrixLineage {...props()} summary={s}/>);start();await screen.findByText('沿用参考 0 · 已失效 1 · 待重新计算 0');
  expect(screen.getByText(status==='COMMITTED'?'本版已提交（父版差异）':'本版已取消（父版差异）')).toBeInTheDocument();expect(fetch).toHaveBeenCalledTimes(1);
 });
 it('does not fetch automatically and only GETs pinned current Input',async()=>{
  const fetch=vi.fn().mockResolvedValue(response(fixture()));vi.stubGlobal('fetch',fetch);render(<ComparisonMatrixLineage {...props()}/>);expect(fetch).not.toHaveBeenCalled();start();await screen.findByText('沿用参考 1 · 已失效 0 · 待重新计算 0');
  expect(fetch).toHaveBeenCalledTimes(1);expect(fetch.mock.calls[0][0]).toBe('/v2/consultation/tasks/task/patent-comparisons/current/lineage');expect(fetch.mock.calls[0][1]).toMatchObject({method:'GET',cache:'no-store',headers:{Authorization:'Bearer session'}});expect(fetch.mock.calls[0][1].body).toBeUndefined();expect(screen.queryByText('已提交')).not.toBeInTheDocument();
 });
 it.each([401,403,404,409,503])('failure %s clears references and only authority failures notify denial',async status=>{
  const p=props();vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(fixture())).mockResolvedValueOnce(response({},status)));render(<ComparisonMatrixLineage {...p}/>);start();await screen.findByRole('status');start();await screen.findByRole('alert');expect(screen.queryByRole('status')).not.toBeInTheDocument();expect(p.onDenied).toHaveBeenCalledTimes([401,403,404].includes(status)?1:0);
 });
 it.each(['token','task','input','disabled','unmount'])('discards late response after %s',async kind=>{
  let resolve!:(r:Response)=>void;vi.stubGlobal('fetch',vi.fn().mockReturnValue(new Promise<Response>(yes=>{resolve=yes})));const p=props(),ui=render(<ComparisonMatrixLineage {...p}/>);start();
  if(kind==='unmount')ui.unmount();else ui.rerender(<ComparisonMatrixLineage {...p} {...(kind==='token'?{token:'new'}:kind==='disabled'?{disabled:true}:{summary:{...summary,...(kind==='task'?{taskId:'other'}:{inputSha:hash('e')})}})}/>);
  await act(async()=>resolve(response(fixture())));expect(screen.queryByRole('status')).not.toBeInTheDocument();expect(p.onDenied).not.toHaveBeenCalled();
 });
 it('withdraws visible projection at its bound deadline',async()=>{
  vi.useFakeTimers();const v=fixture();v.expiresAt=new Date(Date.now()+1000).toISOString();vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(v)));render(<ComparisonMatrixLineage {...props()}/>);
  await act(async()=>start());expect(screen.getByRole('status')).toBeInTheDocument();await act(async()=>vi.advanceTimersByTime(1000));expect(screen.queryByRole('status')).not.toBeInTheDocument();expect(screen.getByRole('alert')).toHaveTextContent('到期');
 });
 it('configuration unavailable leaves normal saved results readable; denied lineage clears them',async()=>{
  const p=props(),fetch=vi.fn().mockResolvedValueOnce(response(summary)).mockResolvedValueOnce(response({},409)).mockResolvedValueOnce(response({},404));vi.stubGlobal('fetch',fetch);
  render(<ComparisonMatrixResults token={p.token} taskId="task" disabled={false} onDenied={p.onDenied} defaultComparisonId="current" defaultInputSha={summary.inputSha}/>);
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await screen.findByText('特征一');start();await screen.findByRole('alert');expect(screen.getByText('特征一')).toBeInTheDocument();expect(p.onDenied).not.toHaveBeenCalled();start();await waitFor(()=>expect(p.onDenied).toHaveBeenCalledTimes(1));
  expect(screen.queryByText('特征一')).not.toBeInTheDocument();expect(p.onDenied).toHaveBeenCalledTimes(1);
 });
});
