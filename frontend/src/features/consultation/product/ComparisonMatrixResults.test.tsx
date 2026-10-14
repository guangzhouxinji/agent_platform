import {createHash,webcrypto} from 'node:crypto';
import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import ComparisonMatrixResults from './ComparisonMatrixResults';
import type {MatrixReport,MatrixSummary} from './comparisonMatrix';
const sha=(s:string)=>createHash('sha256').update(s).digest('hex');
function canonical(v:unknown):unknown{return Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.entries(v).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([k,x])=>[k,canonical(x)])):v;}
function fixture(){
 const candidate=sha('candidate'),inputSha=sha('input');
 const summary:MatrixSummary={taskId:'task',comparisonId:'comparison',inputSha,state:'COMMITTED',confirmedFeatures:[{featureId:'feature',userText:'确认输入特征'}],candidates:[{candidateId:candidate,status:'COMMITTED',attemptId:'attempt',executionState:'COMMITTED',errorCode:null,reportIds:['report']}]};
 const e={documentVersion:'document-v1',originalRef:'original:claims',originalSha:sha('original'),jsonPointer:'/claims',byteStart:0,byteEnd:6,spanSha:sha('原文'),text:'原文'};
 const result:MatrixReport['result']={taskId:'task',comparisonId:'comparison',candidateId:candidate,inputSha,attemptId:'attempt',token:1,reportId:'report',resultVersion:1,methodVersion:'PATENT_FEATURE_MATRIX_V1',workerSubject:'worker',configurationVersion:'config',approvalSha:sha('approved'),responseSha:sha('response'),createdAt:'2026-10-07T01:00:00Z',expiresAt:'2026-10-08T01:00:00Z',rows:[{candidateId:candidate,featureId:'feature',claimBranch:[1],relation:'PARTIAL',sameAspects:['词面对应'],differences:['范围不同'],reasoning:'工程端口返回的逐项理由',uncertainty:'未作专业验收',citations:[e],analyzedRange:[e]}]};
 const reportSha=sha(JSON.stringify(canonical(result)));
 const report:MatrixReport={result,reportSha,receipt:{taskId:'task',comparisonId:'comparison',candidateId:candidate,inputSha,attemptId:'attempt',token:1,reportId:'report',resultVersion:1,reportSha,status:'COMMITTED'}};
 return {summary,report};
}
const response=(value:unknown,status=200)=>({ok:status>=200&&status<300,status,json:async()=>value}) as Response;
function deferred<T>(){let resolve!:(v:T)=>void;let reject!:(e:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject};}
const props={token:'session',taskId:'task',disabled:false,onDenied:vi.fn()};
function start(){fireEvent.change(screen.getByLabelText('分析编号'),{target:{value:'comparison'}});fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));}
async function open(){fireEvent.click(await screen.findByRole('button',{name:'查看第1份矩阵'}));}
beforeEach(()=>{vi.spyOn(Date,'now').mockReturnValue(Date.parse('2026-10-07T02:00:00Z'));vi.stubGlobal('crypto',webcrypto);props.onDenied=vi.fn();});
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();});
describe('matrix product read-only result boundary',()=>{
 it('binds a newly created Input analysis ID without automatically fetching or executing',async()=>{
  const f=fixture(),fetch=vi.fn().mockResolvedValue(response(f.summary));vi.stubGlobal('fetch',fetch);
  const ui=render(<ComparisonMatrixResults {...props} defaultComparisonId="comparison"/>);
  expect(screen.getByLabelText('分析编号')).toHaveValue('comparison');expect(fetch).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await screen.findByRole('button',{name:'查看第1份矩阵'});
  ui.rerender(<ComparisonMatrixResults {...props} defaultComparisonId="new-input-comparison"/>);
  expect(screen.getByLabelText('分析编号')).toHaveValue('new-input-comparison');
  expect(screen.queryByRole('button',{name:'查看第1份矩阵'})).not.toBeInTheDocument();expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch.mock.calls[0][1]).toMatchObject({method:'GET',cache:'no-store'});
 });
 it('renders nonempty exact version, gaps, citations and uses only GET with no-store',async()=>{
  const f=fixture(),fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.report));vi.stubGlobal('fetch',fetch);render(<ComparisonMatrixResults {...props}/>);start();await open();await screen.findByText('工程端口返回的逐项理由');expect(screen.getByText('原文')).toBeInTheDocument();expect(document.body.textContent).not.toContain(f.report.result.candidateId);expect(document.body.textContent).not.toContain('attempt');expect(screen.getByText(/结果SHA/).closest('details')).not.toHaveAttribute('open');for(const coordinate of screen.getAllByText(/字节\[/))expect(coordinate.closest('details')).not.toHaveAttribute('open');expect(screen.getByText('确切矩阵版本 1')).toBeInTheDocument();expect(screen.getByText(/已提交不等于专业验收/)).toBeInTheDocument();
  expect(fetch.mock.calls.map(c=>c[0])).toEqual(['/v2/consultation/tasks/task/patent-comparisons/comparison/results','/v2/consultation/tasks/task/patent-comparisons/comparison/reports/report']);
  for(const [,options] of fetch.mock.calls){expect(options).toMatchObject({method:'GET',cache:'no-store',headers:{Authorization:'Bearer session'}});expect(options.body).toBeUndefined();expect(options.headers['Idempotency-Key']).toBeUndefined();}
 });
 it('shows PARTIAL success alongside B failure/reconcile without retry or model dispatch',async()=>{const f=fixture();f.summary.state='PARTIAL';f.summary.candidates.push({candidateId:sha('B'),status:'FAILED',attemptId:'b',executionState:'RECONCILE_REQUIRED',errorCode:'MODEL_INVOCATION_UNRESOLVED',reportIds:[]});const fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.report));vi.stubGlobal('fetch',fetch);render(<ComparisonMatrixResults {...props}/>);start();await screen.findByText(/部分完成：成功候选保留/);expect(screen.getByText(/不能盲重发/)).toHaveTextContent('不能据此推断未调用模型');expect(screen.getAllByRole('button',{name:/查看第.*份矩阵/})).toHaveLength(1);await open();await screen.findByText('工程端口返回的逐项理由');expect(fetch).toHaveBeenCalledTimes(2);});
 it.each(['task','input','candidate','sha','receipt'])('rejects %s result before display and permits GET reread',async kind=>{const f=fixture(),bad=structuredClone(f.report);switch(kind){case 'task':bad.result.taskId='foreign';break;case 'input':bad.result.inputSha=sha('foreign');break;case 'candidate':bad.result.candidateId=sha('foreign');break;case 'sha':bad.reportSha=sha('wrong');break;case 'receipt':bad.receipt.token=7;break;}const fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(bad)).mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.report));vi.stubGlobal('fetch',fetch);render(<ComparisonMatrixResults {...props}/>);start();await open();await screen.findByRole('alert');expect(screen.queryByText('工程端口返回的逐项理由')).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'查看第1份矩阵'})).not.toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await open();await screen.findByText('工程端口返回的逐项理由');expect(fetch.mock.calls.every(([,o])=>o.method==='GET')).toBe(true);});
 it.each([401,403,404,409,503,0])('clears existing results on failure %s and allows explicit reread',async status=>{const f=fixture(),fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.report));if(status===0)fetch.mockRejectedValueOnce(new Error('network'));else fetch.mockResolvedValueOnce(response({},status));fetch.mockResolvedValueOnce(response(f.summary));vi.stubGlobal('fetch',fetch);render(<ComparisonMatrixResults {...props}/>);start();await open();await screen.findByText('工程端口返回的逐项理由');fireEvent.click(screen.getByRole('button',{name:'查看第1份矩阵'}));await screen.findByRole('alert');expect(screen.queryByText('原文')).not.toBeInTheDocument();expect(props.onDenied).toHaveBeenCalledTimes(status===401||status===403||status===404?1:0);expect(screen.getByRole('button',{name:'读取对照结果'})).toBeEnabled();fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await screen.findByRole('button',{name:'查看第1份矩阵'});expect(fetch).toHaveBeenCalledTimes(4);});
 it.each(['task','token','disabled','unmount'])('ignores late summary after %s changes',async kind=>{const f=fixture(),pending=deferred<Response>(),fetch=vi.fn().mockReturnValue(pending.promise);vi.stubGlobal('fetch',fetch);const ui=render(<ComparisonMatrixResults {...props}/>);start();if(kind==='unmount')ui.unmount();else ui.rerender(<ComparisonMatrixResults {...props} {...(kind==='task'?{taskId:'other'}:kind==='token'?{token:'renewed'}:{disabled:true})}/>);await act(async()=>pending.resolve(response(f.summary)));expect(screen.queryByRole('button',{name:'查看第1份矩阵'})).not.toBeInTheDocument();expect(props.onDenied).not.toHaveBeenCalled();expect(fetch).toHaveBeenCalledTimes(1);});
 it.each(['task','token','disabled','unmount'])('ignores late report after %s changes',async kind=>{const f=fixture(),pending=deferred<Response>(),fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockReturnValueOnce(pending.promise);vi.stubGlobal('fetch',fetch);const ui=render(<ComparisonMatrixResults {...props}/>);start();await open();if(kind==='unmount')ui.unmount();else ui.rerender(<ComparisonMatrixResults {...props} {...(kind==='task'?{taskId:'other'}:kind==='token'?{token:'renewed'}:{disabled:true})}/>);await act(async()=>pending.resolve(response(f.report)));expect(screen.queryByText('工程端口返回的逐项理由')).not.toBeInTheDocument();expect(props.onDenied).not.toHaveBeenCalled();});
 it('does not call denial callback for an old session failure',async()=>{const pending=deferred<Response>();vi.stubGlobal('fetch',vi.fn().mockReturnValue(pending.promise));const ui=render(<ComparisonMatrixResults {...props}/>);start();ui.rerender(<ComparisonMatrixResults {...props} token="new"/>);await act(async()=>pending.resolve(response({},403)));expect(props.onDenied).not.toHaveBeenCalled();expect(screen.queryByRole('alert')).not.toBeInTheDocument();});
 it('invalidates a report while SHA verification is pending',async()=>{const f=fixture(),pending=deferred<ArrayBuffer>(),digest=vi.spyOn(webcrypto.subtle,'digest').mockReturnValueOnce(pending.promise);vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.report)));const ui=render(<ComparisonMatrixResults {...props}/>);start();await open();await waitFor(()=>expect(digest).toHaveBeenCalled());ui.rerender(<ComparisonMatrixResults {...props} disabled/>);const bytes=Uint8Array.from(Buffer.from(f.report.reportSha,'hex')).buffer;await act(async()=>pending.resolve(bytes));expect(screen.queryByText('工程端口返回的逐项理由')).not.toBeInTheDocument();ui.rerender(<ComparisonMatrixResults {...props}/>);expect(screen.queryByRole('button',{name:'查看第1份矩阵'})).not.toBeInTheDocument();});
 it('changing comparison clears earlier summary and report; disabled issues no reads',async()=>{const f=fixture(),fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.report));vi.stubGlobal('fetch',fetch);const ui=render(<ComparisonMatrixResults {...props}/>);start();await open();await screen.findByText('工程端口返回的逐项理由');fireEvent.change(screen.getByLabelText('分析编号'),{target:{value:'another'}});expect(screen.queryByText('原文')).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'查看第1份矩阵'})).not.toBeInTheDocument();ui.rerender(<ComparisonMatrixResults {...props} disabled/>);fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));expect(fetch).toHaveBeenCalledTimes(2);});
});


describe('confirmed Input SHA to matrix read binding',()=>{
 it('selects only the bound live report and withdraws selection when input changes',async()=>{
  const f=fixture(),onReport=vi.fn();vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.report)));
  const ui=render(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={f.summary.inputSha} onReport={onReport}/>);
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await open();
  fireEvent.click(await screen.findByRole('button',{name:'选择此矩阵用于人工请求'}));
  expect(onReport).toHaveBeenLastCalledWith({comparisonId:'comparison',inputSha:f.summary.inputSha,reportId:'report',expiresAt:f.report.result.expiresAt});
  ui.rerender(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={sha('new-input')} onReport={onReport}/>);
  expect(onReport).toHaveBeenLastCalledWith(null);expect(screen.queryByText('工程端口返回的逐项理由')).not.toBeInTheDocument();
 });
 it('does not display or select a report that expires while the response is in flight',async()=>{
  const f=fixture(),late=deferred<Response>(),onReport=vi.fn();vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(f.summary)).mockReturnValueOnce(late.promise));
  render(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={f.summary.inputSha} onReport={onReport}/>);
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await open();
  vi.mocked(Date.now).mockReturnValue(Date.parse(f.report.result.expiresAt));
  await act(async()=>late.resolve(response(f.report)));
  expect(screen.queryByText('工程端口返回的逐项理由')).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'选择此矩阵用于人工请求'})).not.toBeInTheDocument();
  expect(onReport.mock.calls.every(([value])=>value===null)).toBe(true);
 });
 it('accepts the exact confirmed Input without a write or model request',async()=>{
  const f=fixture(),fetch=vi.fn().mockResolvedValueOnce(response(f.summary)).mockResolvedValueOnce(response(f.report));vi.stubGlobal('fetch',fetch);
  render(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={f.summary.inputSha}/>);
  expect(screen.getByLabelText('分析编号')).toHaveAttribute('readonly');
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await open();await screen.findByText('工程端口返回的逐项理由');
  expect(fetch).toHaveBeenCalledTimes(2);expect(fetch.mock.calls.every(([,init])=>init.method==='GET')).toBe(true);
 });
 it('rejects a same-comparison summary from another Input',async()=>{
  const f=fixture(),fetch=vi.fn().mockResolvedValue(response({...f.summary,inputSha:sha('other-input')}));vi.stubGlobal('fetch',fetch);
  render(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={f.summary.inputSha}/>);
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await screen.findByRole('alert');
  expect(screen.queryByText('确认输入特征')).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'查看第1份矩阵'})).not.toBeInTheDocument();expect(fetch).toHaveBeenCalledTimes(1);
 });
 it('withdraws results when the Input changes under the same comparison',async()=>{
  const f=fixture(),fetch=vi.fn().mockResolvedValue(response(f.summary));vi.stubGlobal('fetch',fetch);
  const ui=render(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={f.summary.inputSha}/>);
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await screen.findByRole('button',{name:'查看第1份矩阵'});
  ui.rerender(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={sha('new-input')}/>);
  expect(screen.queryByText('确认输入特征')).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'查看第1份矩阵'})).not.toBeInTheDocument();expect(fetch).toHaveBeenCalledTimes(1);
 });
 it('discards a late summary from the previous Input',async()=>{
  const f=fixture(),late=deferred<Response>(),fetch=vi.fn().mockReturnValue(late.promise);vi.stubGlobal('fetch',fetch);
  const ui=render(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={f.summary.inputSha}/>);
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));
  ui.rerender(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={sha('new-input')}/>);
  await act(async()=>late.resolve(response(f.summary)));expect(screen.queryByText('确认输入特征')).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'查看第1份矩阵'})).not.toBeInTheDocument();
 });
 it('recovers a failed matrix read only with GET against the confirmed Input',async()=>{
  const f=fixture(),fetch=vi.fn().mockResolvedValueOnce(response({},503)).mockResolvedValueOnce(response(f.summary));vi.stubGlobal('fetch',fetch);
  render(<ComparisonMatrixResults {...props} defaultComparisonId="comparison" defaultInputSha={f.summary.inputSha}/>);
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await screen.findByRole('alert');
  fireEvent.click(screen.getByRole('button',{name:'读取对照结果'}));await screen.findByRole('button',{name:'查看第1份矩阵'});
  expect(fetch).toHaveBeenCalledTimes(2);expect(fetch.mock.calls.every(([,init])=>init.method==='GET')).toBe(true);
 });
});
