import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import PromptSaveWorkspace from './PromptSaveWorkspace';
const workspaceId='11111111-1111-4111-8111-111111111111',promptId='33333333-3333-4333-8333-333333333333';
const oldRevision='22222222-2222-4222-8222-222222222222',newRevision='44444444-4444-4444-8444-444444444444',otherId='55555555-5555-4555-8555-555555555555';
const receipt=()=>({receiptId:oldRevision,workspaceId,promptId,revisionId:newRevision,generation:'2',contentSha:'a'.repeat(64),resultSha:'b'.repeat(64),submittedAt:new Date(Date.now()-1000).toISOString(),auditUntil:new Date(Date.now()+3600000).toISOString()});
const ok=(data:unknown)=>({ok:true,status:200,json:async()=>data} as Response);
const rejected=(status:number,code?:string)=>({ok:false,status,json:async()=>({code})} as Response);
const props={token:'session',workspaceId,promptId,onBack:()=>{}};
function setup(){return render(<PromptSaveWorkspace {...props}/>);}
function fill(generation='1'){
  for(const [label,value] of [['已知版本',generation],['已知修订编号',oldRevision],['名称','完整新稿'],['已获许可来源编号',workspaceId],['受众引用','internal'],['语言','zh-CN'],['提示词正文','未保存敏感正文']])fireEvent.change(screen.getByLabelText(label,{exact:false}),{target:{value}});
}
function submit(){fireEvent.click(screen.getByRole('button',{name:'保存新修订'}));}
function locked(){return (screen.getByLabelText('提示词正文') as HTMLTextAreaElement).closest('fieldset')?.disabled;}
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.useRealTimers();});

it('SAVE-only opens blank without READ and success clears content with only explicit exact-version link',async()=>{
  const fetch=vi.fn(async()=>ok(receipt()));vi.stubGlobal('fetch',fetch);setup();expect(fetch).not.toHaveBeenCalled();expect(screen.getByLabelText('提示词正文')).toHaveValue('');
  fill();submit();await screen.findByText('新修订已保存');expect(fetch).toHaveBeenCalledTimes(1);
  expect(screen.queryByDisplayValue('未保存敏感正文')).toBeNull();expect(screen.getByRole('link',{name:/打开确切版本/}).getAttribute('href')).toContain(newRevision);
  expect(localStorage.length).toBe(0);expect(sessionStorage.length).toBe(0);
  fireEvent.click(screen.getByRole('button',{name:'填写下一份完整新稿'}));expect(screen.getByLabelText('已知版本')).toHaveValue('2');expect(screen.getByLabelText('已知修订编号')).toHaveValue(newRevision);
  expect(screen.getByLabelText('提示词正文')).toHaveValue('');expect(screen.getByLabelText('已获许可来源编号',{exact:false})).toHaveValue('');expect(fetch).toHaveBeenCalledTimes(1);
});
it('first definitive base conflict preserves content for correction and next explicit submit uses a new key',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(rejected(409,'PROMPT_SAVE_BASE_CONFLICT')).mockResolvedValueOnce(ok({...receipt(),generation:'3'}));vi.stubGlobal('fetch',fetch);setup();fill();submit();
  await screen.findByText(/已知版本、修订编号或提示词类型已不匹配/);expect(locked()).toBe(false);expect(screen.getByLabelText('提示词正文')).toHaveValue('未保存敏感正文');
  expect(screen.getByLabelText('已知版本')).toHaveAttribute('aria-invalid','true');fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(screen.getByLabelText('已知版本')).toHaveFocus();
  fireEvent.change(screen.getByLabelText('已知版本'),{target:{value:'2'}});fireEvent.change(screen.getByLabelText('已知修订编号'),{target:{value:newRevision}});submit();
  await screen.findByText('新修订已保存');expect(fetch.mock.calls[1][1].headers['Idempotency-Key']).not.toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);
  expect(fetch.mock.calls[1][1].body).toContain('"expectedGeneration":2,');
});
it.each(['PROMPT_SAVE_BASE_CONFLICT','PROMPT_SAVE_KEY_CONFLICT',undefined])('unknown then 409 %s preserves frozen key and never releases editing',async code=>{
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(rejected(409,code)).mockResolvedValueOnce(ok(receipt()));vi.stubGlobal('fetch',fetch);setup();fill();submit();
  fireEvent.click(await screen.findByRole('button',{name:'按原内容重试'}));await screen.findByText(/原提交结果仍需核对/);expect(locked()).toBe(true);expect(screen.queryByRole('button',{name:'按原内容重试'})).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:'查询原提交结果'}));await screen.findByText('新修订已保存');
  expect(fetch.mock.calls[1][1].body).toBe(fetch.mock.calls[0][1].body);expect(fetch.mock.calls[1][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);expect(fetch.mock.calls[2][1]).toMatchObject({method:'GET',headers:{'Idempotency-Key':fetch.mock.calls[0][1].headers['Idempotency-Key']}});
});
it('first key conflict stays locked and offers only original-key lookup',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>rejected(409,'PROMPT_SAVE_KEY_CONFLICT')));setup();fill();submit();
  await screen.findByRole('button',{name:'查询原提交结果'});expect(locked()).toBe(true);expect(screen.queryByRole('button',{name:'按原内容重试'})).toBeNull();
});
it('large CAS and decimal tokens remain exact across unknown, GET failure and explicit retry',async()=>{
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(rejected(503)).mockResolvedValueOnce(ok({...receipt(),generation:'9223372036854775807'}));vi.stubGlobal('fetch',fetch);setup();fill('9223372036854775806');
  fireEvent.click(screen.getByRole('button',{name:'添加变量'}));fireEvent.change(screen.getByLabelText('变量名称'),{target:{value:'amount'}});fireEvent.change(screen.getByLabelText('变量来源引用'),{target:{value:'approved'}});fireEvent.change(screen.getByLabelText('值类型'),{target:{value:'DECIMAL'}});fireEvent.click(screen.getByLabelText('设置默认值'));fireEvent.change(screen.getByLabelText(/^默认值/),{target:{value:'90071992547409931234567890.00100'}});submit();
  fireEvent.click(await screen.findByRole('button',{name:'查询原提交结果'}));fireEvent.click(await screen.findByRole('button',{name:'按原内容重试'}));await screen.findByText('新修订已保存');
  expect(fetch.mock.calls[0][1].body).toContain('"expectedGeneration":9223372036854775806,');expect(fetch.mock.calls[0][1].body).toContain('90071992547409931234567890.00100');
  expect(fetch.mock.calls[2][1].body).toBe(fetch.mock.calls[0][1].body);expect(fetch.mock.calls[2][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);expect(fetch.mock.calls[1][1]).not.toHaveProperty('body');
});
it('double click cannot send a second pending save',async()=>{
  let resolve!:(response:Response)=>void;const fetch=vi.fn(()=>new Promise<Response>(r=>{resolve=r;}));vi.stubGlobal('fetch',fetch);setup();fill();submit();submit();expect(fetch).toHaveBeenCalledTimes(1);
  await act(async()=>resolve(ok(receipt())));await screen.findByText('新修订已保存');
});
it.each([400,413,415])('unknown then input rejection %s remains frozen',async status=>{
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(rejected(status));vi.stubGlobal('fetch',fetch);setup();fill();submit();
  fireEvent.click(await screen.findByRole('button',{name:'按原内容重试'}));await screen.findByText(/原提交结果仍需核对/);expect(locked()).toBe(true);
  expect(fetch.mock.calls[1][1].body).toBe(fetch.mock.calls[0][1].body);expect(fetch.mock.calls[1][1].headers['Idempotency-Key']).toBe(fetch.mock.calls[0][1].headers['Idempotency-Key']);
});
it.each([401,403])('clears content and prevents further writes on %s',async status=>{
  const expired=vi.fn();vi.stubGlobal('fetch',vi.fn(async()=>rejected(status)));render(<PromptSaveWorkspace {...props} onSessionExpired={expired}/>);fill();submit();
  await waitFor(()=>expect(screen.queryByLabelText('提示词正文')).toBeNull());expect(screen.queryByRole('button',{name:'按原内容重试'})).toBeNull();expect(expired).toHaveBeenCalledTimes(status===401?1:0);
});
it.each(['token','workspaceId','promptId'])('%s change discards unsaved input and a late success',async coordinate=>{
  let resolve!:(response:Response)=>void;vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(r=>{resolve=r;})));const view=setup();fill();submit();
  view.rerender(<PromptSaveWorkspace {...props} {...{[coordinate]:otherId}}/>);expect(screen.getByLabelText('提示词正文')).toHaveValue('');expect(screen.getByLabelText('已知版本')).toHaveValue('');
  await act(async()=>resolve(ok(receipt())));expect(screen.queryByText('新修订已保存')).toBeNull();expect(screen.queryByText(/提交编号：/)).toBeNull();
});
it('refresh lookup only uses known key and accepts old receipt independently of currently entered CAS',async()=>{
  const fetch=vi.fn(async()=>ok({...receipt(),generation:'7'}));vi.stubGlobal('fetch',fetch);setup();fill('99');fireEvent.change(screen.getByLabelText('原提交编号'),{target:{value:'historical-save'}});fireEvent.click(screen.getByRole('button',{name:'查询已有保存'}));
  await screen.findByText('新修订已保存');expect(fetch).toHaveBeenCalledTimes(1);expect((fetch.mock.calls as unknown[][])[0][1]).toMatchObject({method:'GET',headers:{'Idempotency-Key':'historical-save'}});expect((fetch.mock.calls as unknown[][])[0][1]).not.toHaveProperty('body');
});
it('another prompt original-key receipt is not displayed as the selected target',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok({...receipt(),promptId:otherId})));setup();fireEvent.change(screen.getByLabelText('原提交编号'),{target:{value:'other-prompt-key'}});fireEvent.click(screen.getByRole('button',{name:'查询已有保存'}));
  await screen.findByText('该编号结果与当前所选提示词不一致，当前页面无法核验。');expect(screen.queryByRole('link',{name:/打开确切版本/})).toBeNull();expect(screen.queryByRole('button',{name:'按原内容重试'})).toBeNull();
});
it('wrong target generation on frozen original-key lookup remains unknown',async()=>{
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(ok({...receipt(),generation:'3'}));vi.stubGlobal('fetch',fetch);setup();fill();submit();
  fireEvent.click(await screen.findByRole('button',{name:'查询原提交结果'}));await screen.findByText(/保存结果尚未确认/);expect(locked()).toBe(true);expect(screen.queryByText('新修订已保存')).toBeNull();
});
it('validation summary locates the exact CAS input before any transport',()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);setup();submit();const input=screen.getByLabelText('已知版本');
  expect(input).toHaveAttribute('aria-invalid','true');expect(input).toHaveAttribute('aria-describedby','prompt-save-error');fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(input).toHaveFocus();expect(fetch).not.toHaveBeenCalled();
});
it('known-key error locates its input and clears its attributes when validation moves to CAS',()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);setup();fireEvent.click(screen.getByRole('button',{name:'查询已有保存'}));
  const keyInput=screen.getByLabelText('原提交编号');expect(keyInput).toHaveAttribute('aria-invalid','true');expect(keyInput).toHaveAttribute('aria-describedby','prompt-save-error');
  fireEvent.click(screen.getByRole('link',{name:'定位需检查的字段'}));expect(keyInput).toHaveFocus();
  submit();expect(keyInput).not.toHaveAttribute('aria-invalid');expect(keyInput).not.toHaveAttribute('aria-describedby');
  expect(screen.getByLabelText('已知版本')).toHaveAttribute('aria-invalid','true');expect(fetch).not.toHaveBeenCalled();
});
it('receipt expires without reading or extending its deadline',async()=>{
  vi.useFakeTimers();const data={...receipt(),auditUntil:new Date(Date.now()+1000).toISOString()};const fetch=vi.fn(async()=>ok(data));vi.stubGlobal('fetch',fetch);setup();
  await act(async()=>{fill();submit();});expect(screen.getByText('新修订已保存')).toBeInTheDocument();
  act(()=>vi.advanceTimersByTime(1000));expect(screen.queryByText('新修订已保存')).toBeNull();expect(screen.getByText('该回执已到追溯期限，已关闭显示。')).toBeInTheDocument();expect(fetch).toHaveBeenCalledTimes(1);
});
