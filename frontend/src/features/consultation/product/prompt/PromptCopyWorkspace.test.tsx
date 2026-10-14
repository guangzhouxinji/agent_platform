import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import PromptCopyWorkspace from './PromptCopyWorkspace';
const sourceWorkspaceId='11111111-1111-4111-8111-111111111111',targetWorkspaceId='22222222-2222-4222-8222-222222222222',otherId='33333333-3333-4333-8333-333333333333';
const source={sourceWorkspaceId,sourcePromptId:sourceWorkspaceId,sourceRevisionId:sourceWorkspaceId,sourceContentSha:'a'.repeat(64)};
const props={token:'session',source,onBack:()=>{}};
const receipt=()=>({receiptId:sourceWorkspaceId,workspaceId:targetWorkspaceId,promptId:otherId,revisionId:targetWorkspaceId,generation:'1',contentSha:'a'.repeat(64),resultSha:'b'.repeat(64),submittedAt:new Date(Date.now()-1000).toISOString(),auditUntil:new Date(Date.now()+3600000).toISOString()});
const ok=(data:unknown)=>new Response(JSON.stringify(data),{status:200});
const reject=(status:number)=>new Response(JSON.stringify({code:'PROMPT_COPY_KEY_CONFLICT'}),{status});
function fill(){fireEvent.change(screen.getByLabelText('目标工作区编号',{exact:true}),{target:{value:targetWorkspaceId}});fireEvent.change(screen.getByLabelText('已获许可来源编号'),{target:{value:sourceWorkspaceId}});}
function submit(){fireEvent.click(screen.getByRole('button',{name:'复制确切版本'}));}
function lookup(sourceId=sourceWorkspaceId,target=targetWorkspaceId,key='old-copy'){
  fireEvent.change(screen.getByLabelText('原来源工作区编号'),{target:{value:sourceId}});fireEvent.change(screen.getByLabelText('原目标工作区编号'),{target:{value:target}});fireEvent.change(screen.getByLabelText('原提交编号'),{target:{value:key}});
}
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.useRealTimers();});
it('copies only fixed source plus target/permission and shows finite receipt without automatic READ or storage',async()=>{
  const fetch=vi.fn().mockResolvedValue(ok(receipt()));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);
  expect(fetch).not.toHaveBeenCalled();expect(screen.queryByLabelText('提示词正文')).toBeNull();expect(screen.queryByLabelText('名称')).toBeNull();fill();submit();
  await screen.findByText('确切版本已复制');expect(fetch).toHaveBeenCalledTimes(1);expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({expectedGeneration:0,targetWorkspaceId,sourceRevisionId:source.sourceRevisionId,sourceContentSha:source.sourceContentSha,sourceId:sourceWorkspaceId});
  expect(screen.getByRole('link',{name:/打开确切版本/})).toHaveAttribute('href',`/app/consultation?view=prompt&prompt=${otherId}&revision=${targetWorkspaceId}`);expect(localStorage.length).toBe(0);expect(sessionStorage.length).toBe(0);
});
it('unknown preserves exact POST bytes/key and isolates independent recovery until resolution',async()=>{
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(reject(503)).mockResolvedValueOnce(ok(receipt()));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);fill();submit();
  fireEvent.click(await screen.findByRole('button',{name:'查询原提交结果'}));fireEvent.click(await screen.findByRole('button',{name:'按原内容重试'}));await screen.findByText('确切版本已复制');
  expect(fetch.mock.calls[2][1].body).toBe(fetch.mock.calls[0][1].body);expect(fetch.mock.calls[2][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);
  expect(fetch.mock.calls[1][1]).toMatchObject({method:'GET',headers:{'Idempotency-Key':fetch.mock.calls[0][1].headers['Idempotency-Key']}});expect(fetch.mock.calls[1][1]).not.toHaveProperty('body');
});
it('KEY409 enters lookup-only and accepts an original valid receipt with another SHA',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(reject(409)).mockResolvedValueOnce(ok({...receipt(),contentSha:'c'.repeat(64)}));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);fill();submit();
  fireEvent.click(await screen.findByRole('button',{name:'查询原提交结果'}));await screen.findByText('已查回原复制提交');expect(screen.queryByText('确切版本已复制')).toBeNull();expect(screen.queryByRole('button',{name:'按原内容重试'})).toBeNull();
  expect(fetch.mock.calls[1][1].method).toBe('GET');expect(fetch.mock.calls[1][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);
});
it.each([400,413,415])('first definitive %s lets the user correct input but a later UNKNOWN rejection remains frozen',async status=>{
  const fetch=vi.fn().mockResolvedValueOnce(reject(status)).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(reject(status));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);fill();submit();
  await screen.findByText(/输入未被受理/);expect(screen.getByLabelText('目标工作区编号',{exact:true}).closest('fieldset')).not.toBeDisabled();submit();
  fireEvent.click(await screen.findByRole('button',{name:'按原内容重试'}));await screen.findByText(/原提交结果仍需核对/);
  expect(screen.getByLabelText('目标工作区编号',{exact:true}).closest('fieldset')).toBeDisabled();expect(screen.queryByLabelText('原提交编号')).toBeNull();
  expect(fetch.mock.calls[2][1].body).toBe(fetch.mock.calls[1][1].body);expect(fetch.mock.calls[2][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[1][1].headers['Idempotency-Key']);
});
it('UNKNOWN lookup rejects another SHA without releasing original POST context',async()=>{
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(ok({...receipt(),contentSha:'c'.repeat(64)}));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);fill();submit();
  fireEvent.click(await screen.findByRole('button',{name:'查询原提交结果'}));await screen.findByText(/复制结果尚未确认，请使用原提交编号/);expect(screen.queryByText('已查回原复制提交')).toBeNull();expect(screen.getByRole('button',{name:'按原内容重试'})).toBeInTheDocument();
});
it('no-coordinate page offers only independent original-key GET and no READ or POST',async()=>{
  const fetch=vi.fn().mockResolvedValue(ok({...receipt(),contentSha:'c'.repeat(64)}));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace token="session" onBack={()=>{}}/>);
  expect(fetch).not.toHaveBeenCalled();expect(screen.queryByRole('button',{name:'复制确切版本'})).toBeNull();lookup();fireEvent.click(screen.getByRole('button',{name:'查询已有复制'}));
  await screen.findByText('已查回原复制提交');expect(fetch).toHaveBeenCalledTimes(1);expect(fetch.mock.calls[0][1].method).toBe('GET');
});
it('standalone recovery does not inherit source route SHA or workspace',async()=>{
  const fetch=vi.fn().mockResolvedValue(ok({...receipt(),contentSha:'d'.repeat(64)}));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);lookup(otherId);fireEvent.click(screen.getByRole('button',{name:'查询已有复制'}));
  await screen.findByText('已查回原复制提交');expect(fetch.mock.calls[0][0]).toBe(`/v2/consultation/prompt-workspaces/${targetWorkspaceId}/requests/COPY?sourceWorkspaceId=${otherId}`);
});
it('editing standalone query coordinates isolates a late 200 and allows a fresh query',async()=>{
  let resolve!:(response:Response)=>void;const fetch=vi.fn().mockImplementationOnce(()=>new Promise<Response>(r=>{resolve=r;})).mockResolvedValueOnce(ok({...receipt(),workspaceId:otherId}));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);lookup();fireEvent.click(screen.getByRole('button',{name:'查询已有复制'}));
  fireEvent.change(screen.getByLabelText('原目标工作区编号'),{target:{value:otherId}});await act(async()=>resolve(ok(receipt())));expect(screen.queryByText('已查回原复制提交')).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'查询已有复制'}));await screen.findByText('已查回原复制提交');expect(fetch.mock.calls[1][0]).toContain(`/prompt-workspaces/${otherId}/requests/COPY`);
});
it('double click and hand recovery cannot overwrite a pending new command',async()=>{
  let resolve!:(response:Response)=>void;const fetch=vi.fn(()=>new Promise<Response>(r=>{resolve=r;}));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);fill();submit();submit();
  expect(fetch).toHaveBeenCalledTimes(1);expect(screen.queryByLabelText('原提交编号')).toBeNull();await act(async()=>resolve(ok(receipt())));await screen.findByText('确切版本已复制');
});
it.each([401,403])('denial %s clears both command and recovery and closes all operations',async status=>{
  const expired=vi.fn(),fetch=vi.fn().mockResolvedValue(reject(status));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props} onSessionExpired={expired}/>);fill();submit();
  await waitFor(()=>expect(screen.queryByLabelText('目标工作区编号',{exact:true})).toBeNull());expect(screen.queryByLabelText('原提交编号')).toBeNull();expect(screen.queryByText(/提交编号：/)).toBeNull();expect(expired).toHaveBeenCalledTimes(status===401?1:0);
});
it.each(['token','sourceWorkspaceId','sourcePromptId','sourceRevisionId','sourceContentSha'])('%s change clears memory and discards a late submitted receipt',async coordinate=>{
  let resolve!:(response:Response)=>void;vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(r=>{resolve=r;})));const view=render(<PromptCopyWorkspace {...props}/>);fill();submit();
  view.rerender(<PromptCopyWorkspace {...props} {...(coordinate==='token'?{token:'new-session'}:{source:{...source,[coordinate]:coordinate==='sourceContentSha'?'c'.repeat(64):otherId}})}/>);
  expect(screen.getByLabelText('目标工作区编号',{exact:true})).toHaveValue('');await act(async()=>resolve(ok(receipt())));expect(screen.queryByText('确切版本已复制')).toBeNull();
});
it.each([401,403])('independent lookup %s clears hand-entered recovery and cannot send a POST',async status=>{
  const fetch=vi.fn().mockResolvedValue(reject(status));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);lookup(otherId);fireEvent.click(screen.getByRole('button',{name:'查询已有复制'}));
  await waitFor(()=>expect(screen.queryByLabelText('原提交编号')).toBeNull());expect(screen.queryByRole('button',{name:'复制确切版本'})).toBeNull();expect(fetch).toHaveBeenCalledTimes(1);expect(fetch.mock.calls[0][1].method).toBe('GET');
});
it('independent lookup refuses a receipt from another target workspace',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(ok({...receipt(),workspaceId:otherId})));render(<PromptCopyWorkspace {...props}/>);lookup();fireEvent.click(screen.getByRole('button',{name:'查询已有复制'}));
  await screen.findByText('原回执与本次查询目标不一致，无法核验。');expect(screen.queryByText('已查回原复制提交')).toBeNull();expect(screen.queryByRole('link',{name:/打开确切版本/})).toBeNull();
});
it('exit aborts and does not display late success even when owner callback keeps the component mounted',async()=>{
  let resolve!:(response:Response)=>void;const back=vi.fn();vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(r=>{resolve=r;})));render(<PromptCopyWorkspace {...props} onBack={back}/>);fill();submit();fireEvent.click(screen.getByRole('button',{name:'返回咨询任务'}));
  await act(async()=>resolve(ok(receipt())));expect(back).toHaveBeenCalledTimes(1);expect(screen.queryByText('确切版本已复制')).toBeNull();expect(screen.queryByLabelText('原提交编号')).toBeNull();
});
it('field errors locate target, permission and independent key with declarative ARIA',()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);submit();const target=screen.getByLabelText('目标工作区编号',{exact:true});
  expect(target).toHaveAttribute('aria-invalid','true');expect(target).toHaveAttribute('aria-describedby','prompt-copy-error');fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(target).toHaveFocus();
  fireEvent.change(target,{target:{value:targetWorkspaceId}});submit();expect(screen.getByLabelText('已获许可来源编号')).toHaveAttribute('aria-invalid','true');expect(target).not.toHaveAttribute('aria-invalid');
  lookup();fireEvent.change(screen.getByLabelText('原提交编号'),{target:{value:' bad key '}});fireEvent.click(screen.getByRole('button',{name:'查询已有复制'}));expect(screen.getByLabelText('原提交编号')).toHaveAttribute('aria-describedby','prompt-copy-error');expect(fetch).not.toHaveBeenCalled();
});
it('receipt audit expiry closes display and exact READ link without another HTTP operation',async()=>{
  vi.useFakeTimers();const fetch=vi.fn().mockResolvedValue(ok({...receipt(),auditUntil:new Date(Date.now()+1000).toISOString()}));vi.stubGlobal('fetch',fetch);render(<PromptCopyWorkspace {...props}/>);
  await act(async()=>{fill();submit();});expect(screen.getByText('确切版本已复制')).toBeInTheDocument();act(()=>vi.advanceTimersByTime(1000));
  expect(screen.queryByText('确切版本已复制')).toBeNull();expect(screen.queryByRole('link',{name:/打开确切版本/})).toBeNull();expect(screen.getByText('该回执已到追溯期限，已关闭显示。')).toBeInTheDocument();expect(fetch).toHaveBeenCalledTimes(1);
});
