import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,expect,it,vi} from 'vitest';
import PromptRevisionBrowser from './PromptRevisionBrowser';
import {history,newerId,ok,otherPrompt,promptId,revision,revisionId,workspaceId} from './promptTestFixtures';

afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.useRealTimers();});
function props(){return {token:'session',promptId,onBack:vi.fn(),onSessionExpired:vi.fn()};}
function source(){const fetch=vi.fn(async(url:string,_init?:RequestInit)=>url.includes('?')?ok(history()):ok(revision(url.endsWith(newerId)?newerId:revisionId,url.endsWith(newerId)?2:1)));vi.stubGlobal('fetch',fetch);return fetch;}
it('opens actual selected resource, switches to old version and refreshes only through GET',async()=>{
  const fetch=source();const p=props();render(<PromptRevisionBrowser {...p}/>);await screen.findByText('新版本正文');
  expect(screen.queryByRole('textbox',{name:'提示词正文'})).not.toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'复制此确切版本'})).toBeNull();
  fireEvent.click(screen.getByRole('button',{name:/版本 1/}));await screen.findByText('原版本正文');expect(screen.queryByText('新版本正文')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button',{name:'重新读取'}));await screen.findByText('原版本正文');
  expect(fetch.mock.calls.every(([,init])=>(init as RequestInit|undefined)?.method==='GET')).toBe(true);
  fireEvent.click(screen.getByRole('button',{name:'返回咨询任务'}));expect(p.onBack).toHaveBeenCalledOnce();
});
it('opens a precise deep-linked old version rather than substituting the latest',async()=>{
  source();render(<PromptRevisionBrowser {...props()} revisionId={revisionId}/>);await screen.findByText('原版本正文');expect(screen.queryByText('新版本正文')).not.toBeInTheDocument();
});
it('history pagination makes a fresh exact read',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
    if(url.includes('?')){const value=history(url.includes('page=2')?[revision()]:[revision(newerId,2)]);value.page=url.includes('page=2')?2:1;value.total=21;return ok(value);}
    return ok(url.endsWith(newerId)?revision(newerId,2):revision());
  }));render(<PromptRevisionBrowser {...props()}/>);await screen.findByText('新版本正文');
  fireEvent.click(screen.getByRole('button',{name:'下一页'}));await screen.findByText('原版本正文');expect(screen.getByText('第 2 页')).toBeInTheDocument();
});
it.each([401,403,503])('failure %s clears old text and history before retry',async status=>{
  const fetch=source();const p=props();render(<PromptRevisionBrowser {...p}/>);await screen.findByText('新版本正文');
  fetch.mockImplementation(async()=>new Response('{}',{status}));fireEvent.click(screen.getByRole('button',{name:'重新读取'}));await screen.findByRole('alert');
  expect(screen.queryByText('新版本正文')).not.toBeInTheDocument();expect(screen.queryByRole('navigation',{name:'版本历史'})).not.toBeInTheDocument();
  expect(p.onSessionExpired).toHaveBeenCalledTimes(status===401?1:0);
});
it.each(['token','prompt'])('switching %s discards late responses from previous selection',async target=>{
  let resolve!:(response:Response)=>void;const fetch=vi.fn(()=>new Promise<Response>(r=>{resolve=r;}));vi.stubGlobal('fetch',fetch);
  const p=props();const view=render(<PromptRevisionBrowser {...p}/>);const old=resolve;
  fetch.mockImplementation(async()=>new Response('{}',{status:403}));
  view.rerender(<PromptRevisionBrowser {...p} {...(target==='token'?{token:'new-session'}:{promptId:otherPrompt})}/>);
  await screen.findByRole('alert');await act(async()=>old(ok(history())));
  expect(screen.queryByText('新版本正文')).not.toBeInTheDocument();expect(screen.queryByRole('navigation',{name:'版本历史'})).not.toBeInTheDocument();
});
it('the earliest displayed history expiry clears later selected body too',async()=>{
  vi.useFakeTimers();const exact=revision(newerId,2),old=revision();old.bodyUntil=new Date(Date.now()+1000).toISOString();
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.includes('?')?ok(history([exact,old])):ok(exact)));
  await act(async()=>{render(<PromptRevisionBrowser {...props()} revisionId={newerId}/>);});
  expect(screen.getByText('新版本正文')).toBeInTheDocument();await act(async()=>{vi.advanceTimersByTime(1001);});
  expect(screen.queryByText('新版本正文')).not.toBeInTheDocument();expect(screen.getByRole('alert')).toHaveTextContent('保存期限');
});
it('losing the session unmounts all text immediately',async()=>{
  source();const p=props();const view=render(<PromptRevisionBrowser {...p}/>);await screen.findByText('新版本正文');
  view.rerender(<PromptRevisionBrowser {...p} token={null}/>);expect(screen.queryByText('新版本正文')).not.toBeInTheDocument();expect(screen.getByText('请登录后查看所选提示词。')).toBeInTheDocument();
});
it('unknown revision response never appears as the requested body',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.includes('?')?ok(history()):ok({...revision(),promptId:otherPrompt})));
  render(<PromptRevisionBrowser {...props()} revisionId={revisionId}/>);await screen.findByRole('alert');expect(screen.queryByText('原版本正文')).not.toBeInTheDocument();
});
it('no selected resource does not query or expose technical ID fields',async()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);render(<PromptRevisionBrowser {...props()} promptId={undefined}/>);
  expect(screen.getByText(/尚未选择提示词/)).toBeInTheDocument();await waitFor(()=>expect(fetch).not.toHaveBeenCalled());expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
});
it('copy entry supplies only the currently authenticated exact READ four coordinates',async()=>{
  source();const onCopy=vi.fn();render(<PromptRevisionBrowser {...props()} onCopy={onCopy}/>);await screen.findByText('新版本正文');
  fireEvent.click(screen.getByRole('button',{name:'复制此确切版本'}));expect(onCopy).toHaveBeenLastCalledWith({sourceWorkspaceId:workspaceId,sourcePromptId:promptId,sourceRevisionId:newerId,sourceContentSha:'b'.repeat(64)});
  fireEvent.click(screen.getByRole('button',{name:/版本 1/}));expect(screen.queryByRole('button',{name:'复制此确切版本'})).toBeNull();await screen.findByText('原版本正文');
  fireEvent.click(screen.getByRole('button',{name:'复制此确切版本'}));expect(onCopy).toHaveBeenLastCalledWith({sourceWorkspaceId:workspaceId,sourcePromptId:promptId,sourceRevisionId:revisionId,sourceContentSha:'a'.repeat(64)});
});
it.each([401,403])('copy entry disappears after current READ authorization fails %s',async status=>{
  const fetch=source();render(<PromptRevisionBrowser {...props()} onCopy={vi.fn()}/>);await screen.findByRole('button',{name:'复制此确切版本'});
  fetch.mockImplementation(async()=>new Response('{}',{status}));fireEvent.click(screen.getByRole('button',{name:'重新读取'}));await screen.findByRole('alert');expect(screen.queryByRole('button',{name:'复制此确切版本'})).toBeNull();
});
it('expiry closes the old copy entry',async()=>{
  vi.useFakeTimers();const exact={...revision(),bodyUntil:new Date(Date.now()+1000).toISOString()};const onCopy=vi.fn();
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.includes('?')?ok(history([exact])):ok(exact)));
  await act(async()=>{render(<PromptRevisionBrowser {...props()} onCopy={onCopy}/>);});expect(screen.getByRole('button',{name:'复制此确切版本'})).toBeInTheDocument();
  act(()=>vi.advanceTimersByTime(1000));expect(screen.queryByRole('button',{name:'复制此确切版本'})).toBeNull();expect(onCopy).not.toHaveBeenCalled();
});
