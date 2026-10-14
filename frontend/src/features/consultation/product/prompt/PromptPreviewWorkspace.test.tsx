import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import PromptPreviewWorkspace from './PromptPreviewWorkspace';
import {previewDiagnostic,previewResponse,previewResult,previewRevision,sourceId} from './promptPreviewTestFixtures';
import {newerId} from './promptTestFixtures';
const props=()=>({token:'session',revision:previewRevision(),onUnavailable:vi.fn()});
function fill(){fireEvent.change(screen.getByLabelText('样例来源编号'),{target:{value:sourceId}});fireEvent.click(screen.getByLabelText('提供 amount 样例值'));fireEvent.change(screen.getByLabelText('amount 样例值',{exact:true}),{target:{value:'90071992547409931234567890'}});}
function submit(){fireEvent.click(screen.getByRole('button',{name:'预览样例'}));}
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.useRealTimers();});
it('uses the selected schema without auto POST and renders exact typed segments as safe text',async()=>{
  const fetch=vi.fn().mockResolvedValue(previewResponse({...previewResult(),segments:[{kind:'LITERAL',text:'<script>attack</script>'},{kind:'DATA',name:'amount',type:'INTEGER',value:'90071992547409931234567890',sourceEvidenceId:'TEST:amount'}]}));vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);
  expect(fetch).not.toHaveBeenCalled();expect(screen.queryByLabelText('amount 样例值',{exact:true})).toBeNull();fill();submit();await screen.findByRole('region',{name:'变量预览结果'});
  expect(screen.getByText('"90071992547409931234567890"')).toBeInTheDocument();expect(screen.getByText('<script>attack</script>')).toBeInTheDocument();expect(document.querySelector('script')).toBeNull();
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({contentSha:'a'.repeat(64),sourceId,values:[{name:'amount',type:'INTEGER',value:'90071992547409931234567890'}]});
  expect(fetch).toHaveBeenCalledTimes(1);expect(localStorage.length).toBe(0);expect(sessionStorage.length).toBe(0);
  fireEvent.change(screen.getByLabelText('amount 样例值',{exact:true}),{target:{value:'90071992547409931234567891'}});expect(screen.queryByRole('region',{name:'变量预览结果'})).toBeNull();expect(fetch).toHaveBeenCalledTimes(1);
});
it('provides explicit boolean, real JSON array and empty text while unchecked variables are omitted',async()=>{
  const fetch=vi.fn().mockResolvedValue(previewResponse(previewResult()));vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);fill();
  fireEvent.click(screen.getByLabelText('提供 flag 样例值'));fireEvent.click(screen.getByLabelText('提供 list 样例值'));fireEvent.change(screen.getByLabelText('list 样例值（JSON 文本数组）'),{target:{value:'["first","{{amount}}",""]'}});fireEvent.click(screen.getByLabelText('提供 text 样例值'));submit();await screen.findByRole('region',{name:'变量预览结果'});
  expect(JSON.parse(fetch.mock.calls[0][1].body).values).toEqual([{name:'amount',type:'INTEGER',value:'90071992547409931234567890'},{name:'flag',type:'BOOLEAN',value:false},{name:'list',type:'STRING_LIST',value:['first','{{amount}}','']},{name:'text',type:'STRING',value:''}]);
});
it('diagnostics identify an omitted variable and focus the supply checkbox without showing partial result',async()=>{
  const fetch=vi.fn().mockResolvedValue(previewResponse(previewDiagnostic(),400));vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);fireEvent.change(screen.getByLabelText('样例来源编号'),{target:{value:sourceId}});submit();
  await screen.findByRole('alert');expect(screen.queryByRole('region',{name:'变量预览结果'})).toBeNull();const control=screen.getByLabelText('提供 amount 样例值');expect(control).toHaveAttribute('aria-invalid','true');expect(control).toHaveAttribute('aria-describedby','prompt-preview-variable-0-error');
  fireEvent.click(screen.getByRole('link',{name:'检查 amount'}));expect(control).toHaveFocus();fireEvent.click(control);expect(screen.queryByRole('alert')).toBeNull();expect(screen.getByLabelText('amount 样例值',{exact:true})).not.toHaveAttribute('aria-invalid');expect(fetch).toHaveBeenCalledTimes(1);
});
it('local validation links the actual invalid source or sample field with inline ARIA',()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);submit();const source=screen.getByLabelText('样例来源编号');expect(source).toHaveAttribute('aria-invalid','true');fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(source).toHaveFocus();
  fill();fireEvent.change(screen.getByLabelText('amount 样例值',{exact:true}),{target:{value:'1.00'}});submit();const number=screen.getByLabelText('amount 样例值',{exact:true});expect(number).toHaveAttribute('aria-invalid','true');expect(source).not.toHaveAttribute('aria-invalid');fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(number).toHaveFocus();expect(fetch).not.toHaveBeenCalled();
});
it('a schema variable named sourceId retains a distinct error and focus target',()=>{
  const value=props();value.revision={...value.revision,variables:[{...value.revision.variables[0],name:'sourceId'}]};vi.stubGlobal('fetch',vi.fn());render(<PromptPreviewWorkspace {...value}/>);fireEvent.change(screen.getByLabelText('样例来源编号'),{target:{value:sourceId}});fireEvent.click(screen.getByLabelText('提供 sourceId 样例值'));submit();
  expect(screen.getByLabelText('sourceId 样例值',{exact:true})).toHaveAttribute('aria-invalid','true');expect(screen.getByLabelText('样例来源编号')).not.toHaveAttribute('aria-invalid');
  fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(screen.getByLabelText('sourceId 样例值',{exact:true})).toHaveFocus();
});
it('editing a pending sample aborts and isolates old success without automatic resubmission',async()=>{
  let resolve!:(value:Response)=>void;const fetch=vi.fn().mockImplementation(()=>new Promise<Response>(r=>{resolve=r;}));vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);fill();submit();
  fireEvent.change(screen.getByLabelText('amount 样例值',{exact:true}),{target:{value:'22'}});expect(fetch.mock.calls[0][1]?.signal.aborted).toBe(true);
  await act(async()=>resolve(previewResponse(previewResult())));expect(screen.queryByRole('region',{name:'变量预览结果'})).toBeNull();expect(screen.getByLabelText('amount 样例值',{exact:true})).toHaveValue('22');expect(fetch).toHaveBeenCalledTimes(1);
});
it('editing the source isolates late diagnostics for the previous sample permission',async()=>{
  let resolve!:(value:Response)=>void;const fetch=vi.fn(()=>new Promise<Response>(r=>{resolve=r;}));vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);fill();submit();
  fireEvent.change(screen.getByLabelText('样例来源编号'),{target:{value:newerId}});await act(async()=>resolve(previewResponse(previewDiagnostic(),400)));expect(screen.queryByRole('alert')).toBeNull();expect(screen.queryByRole('link',{name:'检查 amount'})).toBeNull();
});
it('double click sends one POST and a network failure permits only explicit retry',async()=>{
  let reject!:(value:Error)=>void;const fetch=vi.fn().mockImplementationOnce(()=>new Promise<Response>((_,r)=>{reject=r;})).mockResolvedValueOnce(previewResponse(previewResult()));vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);fill();submit();submit();expect(fetch).toHaveBeenCalledTimes(1);
  await act(async()=>reject(new Error('offline')));expect(screen.getByRole('alert')).toHaveTextContent('重新提交');expect(fetch).toHaveBeenCalledTimes(1);submit();await screen.findByRole('region',{name:'变量预览结果'});expect(fetch).toHaveBeenCalledTimes(2);
});
it.each([401,403] as const)('server %s clears all samples and delegates parent body closure',async status=>{
  const value=props();vi.stubGlobal('fetch',vi.fn().mockResolvedValue(previewResponse({code:'PROMPT_PREVIEW_DENIED'},status)));render(<PromptPreviewWorkspace {...value}/>);fill();submit();
  await waitFor(()=>expect(value.onUnavailable).toHaveBeenCalledWith(status));expect(screen.queryByLabelText('样例来源编号')).toBeNull();expect(screen.queryByLabelText('amount 样例值',{exact:true})).toBeNull();expect(screen.queryByRole('region',{name:'变量预览结果'})).toBeNull();
});
it.each(['token','revisionId','contentSha'])('changing %s resets samples and ignores late result',async coordinate=>{
  let resolve!:(value:Response)=>void;vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(r=>{resolve=r;})));const value=props(),view=render(<PromptPreviewWorkspace {...value}/>);fill();submit();
  view.rerender(<PromptPreviewWorkspace {...value} {...(coordinate==='token'?{token:'new-session'}:{revision:{...value.revision,[coordinate]:coordinate==='contentSha'?'d'.repeat(64):newerId}})}/>);
  expect(screen.getByLabelText('样例来源编号')).toHaveValue('');expect(screen.getByLabelText('提供 amount 样例值')).not.toBeChecked();await act(async()=>resolve(previewResponse(previewResult())));expect(screen.queryByRole('region',{name:'变量预览结果'})).toBeNull();
});
it.each(['success','diagnostic'])('%s effective expiry clears both input and display without HTTP',async kind=>{
  vi.useFakeTimers();const value=kind==='success'?previewResult():previewDiagnostic();value.expiresAt=new Date(Date.now()+1000).toISOString();const fetch=vi.fn().mockResolvedValue(previewResponse(value,kind==='success'?200:400));vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);
  await act(async()=>{fill();submit();});expect(kind==='success'?screen.getByRole('region',{name:'变量预览结果'}):screen.getByRole('link',{name:'检查 amount'})).toBeInTheDocument();act(()=>vi.advanceTimersByTime(1000));
  expect(screen.queryByRole('region',{name:'变量预览结果'})).toBeNull();expect(screen.queryByRole('link',{name:'检查 amount'})).toBeNull();expect(screen.getByLabelText('样例来源编号')).toHaveValue('');expect(screen.getByLabelText('提供 amount 样例值')).not.toBeChecked();expect(screen.getByRole('alert')).toHaveTextContent('有效期限');expect(fetch).toHaveBeenCalledTimes(1);
});
it('clear action removes in-memory source, provided values and result and cancels pending work',async()=>{
  let resolve!:(value:Response)=>void;const fetch=vi.fn(()=>new Promise<Response>(r=>{resolve=r;}));vi.stubGlobal('fetch',fetch);render(<PromptPreviewWorkspace {...props()}/>);fill();submit();fireEvent.click(screen.getByRole('button',{name:'清除样例'}));
  expect(screen.getByLabelText('样例来源编号')).toHaveValue('');expect(screen.getByLabelText('提供 amount 样例值')).not.toBeChecked();await act(async()=>resolve(previewResponse(previewResult())));expect(screen.queryByRole('region',{name:'变量预览结果'})).toBeNull();expect(fetch).toHaveBeenCalledTimes(1);
});
