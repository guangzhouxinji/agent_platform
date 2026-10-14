import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import PromptCreateWorkspace from './PromptCreateWorkspace';
const workspaceId='11111111-1111-4111-8111-111111111111';
const otherWorkspace='55555555-5555-4555-8555-555555555555';
const receipt=(workspace=workspaceId)=>({receiptId:'22222222-2222-4222-8222-222222222222',workspaceId:workspace,promptId:'33333333-3333-4333-8333-333333333333',revisionId:'44444444-4444-4444-8444-444444444444',generation:1,contentSha:'a'.repeat(64),resultSha:'b'.repeat(64),submittedAt:new Date(Date.now()-1000).toISOString(),auditUntil:new Date(Date.now()+3600000).toISOString()});
const ok=(data:unknown)=>({ok:true,status:200,json:async()=>data} as Response);
const denied=(status:number)=>({ok:false,status} as Response);
function setup(token='session'){return render(<PromptCreateWorkspace token={token} workspaceId={workspaceId} onBack={()=>{}}/>);}
function fill(){for(const [label,value] of [['名称','新草稿'],['已获许可来源编号',workspaceId],['受众引用','internal'],['语言','zh-CN'],['提示词正文','敏感草稿正文']])fireEvent.change(screen.getByLabelText(label,{exact:false}),{target:{value}});}
function submit(){fireEvent.click(screen.getByRole('button',{name:'创建草稿'}));}
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.useRealTimers();});
it('CREATE-only does not read workspace; success shows receipt and explicit read link without automatic READ',async()=>{
  const fetch=vi.fn(async()=>ok(receipt()));vi.stubGlobal('fetch',fetch);setup();expect(fetch).not.toHaveBeenCalled();fill();submit();
  await screen.findByText('草稿已创建');expect(fetch).toHaveBeenCalledTimes(1);expect(screen.getByRole('link',{name:/打开确切版本/}).getAttribute('href')).toContain('revision=44444444');
  expect(screen.queryByDisplayValue('敏感草稿正文')).toBeNull();expect(localStorage.length).toBe(0);
});
it('unknown result locks editing; GET recovery then explicit retry keeps same key and exact numeric body',async()=>{
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(denied(503)).mockResolvedValueOnce(ok(receipt()));vi.stubGlobal('fetch',fetch);setup();fill();
  fireEvent.click(screen.getByRole('button',{name:'添加变量'}));fireEvent.change(screen.getByLabelText('变量名称'),{target:{value:'amount'}});fireEvent.change(screen.getByLabelText('变量来源引用'),{target:{value:'owned'}});fireEvent.change(screen.getByLabelText('值类型'),{target:{value:'DECIMAL'}});fireEvent.click(screen.getByLabelText('设置默认值'));fireEvent.change(screen.getByLabelText(/^默认值/),{target:{value:'90071992547409931234567890.00100'}});submit();
  await screen.findByRole('button',{name:'查询原提交结果'});expect((screen.getByLabelText('提示词正文') as HTMLTextAreaElement).closest('fieldset')?.disabled).toBe(true);
  fireEvent.click(screen.getByRole('button',{name:'查询原提交结果'}));await screen.findByRole('button',{name:'按原内容重试'});fireEvent.click(screen.getByRole('button',{name:'按原内容重试'}));await screen.findByText('草稿已创建');
  const original=fetch.mock.calls[0][1],query=fetch.mock.calls[1][1],retry=fetch.mock.calls[2][1];expect(original.body).toContain('90071992547409931234567890.00100');expect(query.method).toBe('GET');expect(query.body).toBeUndefined();expect(retry.body).toBe(original.body);expect(retry.headers['Idempotency-Key']).toBe(original.headers['Idempotency-Key']);expect(query.headers['Idempotency-Key']).toBe(original.headers['Idempotency-Key']);
});
it('double click while request is pending sends once',async()=>{
  let resolve!:(value:Response)=>void;const fetch=vi.fn(()=>new Promise<Response>(r=>{resolve=r;}));vi.stubGlobal('fetch',fetch);setup();fill();submit();submit();expect(fetch).toHaveBeenCalledTimes(1);
  await act(async()=>resolve(ok(receipt())));await screen.findByText('草稿已创建');
});
it.each([401,403])('clears body and variables on %s and closes writing',async status=>{
  const expired=vi.fn();vi.stubGlobal('fetch',vi.fn(async()=>denied(status)));render(<PromptCreateWorkspace token="session" workspaceId={workspaceId} onBack={()=>{}} onSessionExpired={expired}/>);fill();submit();
  await waitFor(()=>expect(screen.queryByLabelText('提示词正文')).toBeNull());expect(screen.queryByRole('button',{name:'按原内容重试'})).toBeNull();expect(expired).toHaveBeenCalledTimes(status===401?1:0);
});
it('refresh recovery accepts known key only GET, never resurrects or retries unknown original body',async()=>{
  const fetch=vi.fn(async()=>denied(503));vi.stubGlobal('fetch',fetch);setup();fireEvent.change(screen.getByLabelText('原提交编号'),{target:{value:'saved-original-key'}});fireEvent.click(screen.getByRole('button',{name:'查询已有提交'}));await screen.findByRole('button',{name:'查询原提交结果'});
  expect(fetch).toHaveBeenCalledTimes(1);expect((fetch.mock.calls as unknown[][])[0][1]).toMatchObject({method:'GET',headers:{'Idempotency-Key':'saved-original-key'}});expect(screen.queryByRole('button',{name:'按原内容重试'})).toBeNull();expect(screen.getByLabelText('提示词正文')).toHaveValue('');
});
it('workspace change discards original content and late success',async()=>{
  let resolve!:(value:Response)=>void;vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(r=>{resolve=r;})));const view=setup();fill();submit();view.rerender(<PromptCreateWorkspace token="session" workspaceId={otherWorkspace} onBack={()=>{}}/>);
  expect(screen.getByLabelText('提示词正文')).toHaveValue('');await act(async()=>resolve(ok(receipt())));expect(screen.queryByText('草稿已创建')).toBeNull();expect(screen.queryByText(/提交编号：/)).toBeNull();
});
it('session change discards original content and late success',async()=>{
  let resolve!:(value:Response)=>void;vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(r=>{resolve=r;})));const view=setup();fill();submit();view.rerender(<PromptCreateWorkspace token="new-session" workspaceId={workspaceId} onBack={()=>{}}/>);
  await act(async()=>resolve(ok(receipt())));expect(screen.getByLabelText('提示词正文')).toHaveValue('');expect(screen.queryByText('草稿已创建')).toBeNull();
});
it('400 returns to editing but 409 keeps original-key query and prevents retry',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(denied(400)).mockResolvedValueOnce(denied(409));vi.stubGlobal('fetch',fetch);setup();fill();submit();await screen.findByText('输入未被受理，请检查字段和内容大小。');expect(screen.getByLabelText('提示词正文')).toHaveValue('敏感草稿正文');submit();
  await screen.findByRole('button',{name:'查询原提交结果'});expect(screen.queryByRole('button',{name:'按原内容重试'})).toBeNull();expect(fetch.mock.calls[1][1].headers['Idempotency-Key']).not.toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);
});
it('wrong receipt is unknown and never enables exact-version read',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok(receipt(otherWorkspace))));setup();fill();submit();await screen.findByRole('button',{name:'查询原提交结果'});expect(screen.queryByRole('link')).toBeNull();
});
it.each([400,413,415])('unknown POST followed by rejected retry %s retains original key and locked bytes',async status=>{
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(denied(status)).mockResolvedValueOnce(ok(receipt()));vi.stubGlobal('fetch',fetch);setup();fill();submit();
  fireEvent.click(await screen.findByRole('button',{name:'按原内容重试'}));await screen.findByText('本次重试被拒绝，但此前提交结果仍未确认；请保留原提交编号并查询结果。');
  expect(screen.queryByRole('button',{name:'新建另一份草稿'})).toBeNull();expect((screen.getByLabelText('提示词正文') as HTMLTextAreaElement).closest('fieldset')?.disabled).toBe(true);
  fireEvent.click(screen.getByRole('button',{name:'查询原提交结果'}));await screen.findByText('草稿已创建');
  expect(fetch.mock.calls[1][1].body).toBe(fetch.mock.calls[0][1].body);expect(fetch.mock.calls[1][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);expect(fetch.mock.calls[2][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);
});
it('validation summary locates and describes the name and exact variable default controls',async()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);setup();submit();
  const name=screen.getByLabelText('名称');expect(name).toHaveAttribute('aria-invalid','true');expect(name).toHaveAttribute('aria-describedby','prompt-create-error');fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(name).toHaveFocus();
  fill();fireEvent.click(screen.getByRole('button',{name:'添加变量'}));fireEvent.change(screen.getByLabelText('变量名称'),{target:{value:'amount'}});fireEvent.change(screen.getByLabelText('变量来源引用'),{target:{value:'approved'}});fireEvent.change(screen.getByLabelText('值类型'),{target:{value:'INTEGER'}});fireEvent.click(screen.getByLabelText('设置默认值'));fireEvent.change(screen.getByLabelText('默认值'),{target:{value:'1.1'}});submit();
  const amount=screen.getByLabelText('默认值');expect(amount).toHaveAttribute('aria-invalid','true');expect(name).not.toHaveAttribute('aria-invalid');fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(amount).toHaveFocus();expect(fetch).not.toHaveBeenCalled();
});
