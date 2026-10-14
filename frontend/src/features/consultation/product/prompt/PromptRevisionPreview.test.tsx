import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import PromptRevisionBrowser from './PromptRevisionBrowser';
import {history,newerId,ok,promptId,revisionId} from './promptTestFixtures';
import {previewResponse,previewResult,previewRevision,sourceId} from './promptPreviewTestFixtures';
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.useRealTimers();});
function fill(){fireEvent.change(screen.getByLabelText('样例来源编号'),{target:{value:sourceId}});fireEvent.click(screen.getByLabelText('提供 amount 样例值'));fireEvent.change(screen.getByLabelText('amount 样例值',{exact:true}),{target:{value:'90071992547409931234567890'}});}
it.each([401,403])('PREVIEW %s closes parent body/history/copy with current session semantics',async status=>{
  const exact=previewRevision(),expired=vi.fn(),copy=vi.fn(),fetch=vi.fn(async(url:string)=>url.endsWith('/preview')?previewResponse({code:'PROMPT_PREVIEW_DENIED'},status):url.includes('?')?ok(history([exact])):ok(exact));vi.stubGlobal('fetch',fetch);
  render(<PromptRevisionBrowser token="session" promptId={promptId} revisionId={revisionId} onBack={()=>{}} onSessionExpired={expired} onCopy={copy}/>);await screen.findByLabelText('样例来源编号');expect(fetch).toHaveBeenCalledTimes(2);fill();fireEvent.click(screen.getByRole('button',{name:'预览样例'}));
  await waitFor(()=>expect(screen.queryByText(exact.body)).toBeNull());expect(screen.queryByRole('navigation',{name:'版本历史'})).toBeNull();expect(screen.queryByLabelText('样例来源编号')).toBeNull();expect(screen.queryByRole('button',{name:'复制此确切版本'})).toBeNull();expect(expired).toHaveBeenCalledTimes(status===401?1:0);expect(fetch).toHaveBeenCalledTimes(3);
});
it('switching exact revision unmounts samples and ignores pending old preview',async()=>{
  const old=previewRevision(),newer={...old,revisionId:newerId,version:2,contentSha:'d'.repeat(64),body:'new revision'},readHistory=history([newer,old]);let resolve!:(value:Response)=>void;
  const fetch=vi.fn(async(url:string)=>url.endsWith('/preview')?new Promise<Response>(r=>{resolve=r;}):url.includes('?')?ok(readHistory):ok(url.endsWith(newerId)?newer:old));vi.stubGlobal('fetch',fetch);
  render(<PromptRevisionBrowser token="session" promptId={promptId} revisionId={revisionId} onBack={()=>{}}/>);await screen.findByLabelText('样例来源编号');fill();fireEvent.click(screen.getByRole('button',{name:'预览样例'}));
  fireEvent.click(screen.getByRole('button',{name:/版本 2/}));await screen.findByText('new revision');expect(screen.getByLabelText('样例来源编号')).toHaveValue('');expect(screen.getByLabelText('提供 amount 样例值')).not.toBeChecked();await act(async()=>resolve(previewResponse(previewResult())));expect(screen.queryByRole('region',{name:'变量预览结果'})).toBeNull();
});
it('parent earliest history cutoff removes preview values alongside exact body',async()=>{
  vi.useFakeTimers();const exact=previewRevision(),old={...exact,revisionId:newerId,bodyUntil:new Date(Date.now()+1000).toISOString()};vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.includes('?')?ok(history([exact,old])):ok(exact)));
  await act(async()=>{render(<PromptRevisionBrowser token="session" promptId={promptId} revisionId={revisionId} onBack={()=>{}}/>);});fill();act(()=>vi.advanceTimersByTime(1000));expect(screen.queryByLabelText('样例来源编号')).toBeNull();expect(screen.queryByText(exact.body)).toBeNull();
});
it('explicit back closes samples/body even if navigation callback retains the browser',async()=>{
  const exact=previewRevision(),back=vi.fn();vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.includes('?')?ok(history([exact])):ok(exact)));render(<PromptRevisionBrowser token="session" promptId={promptId} onBack={back}/>);await screen.findByLabelText('样例来源编号');fill();fireEvent.click(screen.getByRole('button',{name:'返回咨询任务'}));
  expect(back).toHaveBeenCalledTimes(1);expect(screen.queryByLabelText('样例来源编号')).toBeNull();expect(screen.queryByText(exact.body)).toBeNull();
});
