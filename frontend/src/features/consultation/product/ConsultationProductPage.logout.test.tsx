import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import ConsultationProductPage from './ConsultationProductPage';
import * as api from './api';
import {fixture} from './agentResultFixtures';
import type {Workspace} from './types';

vi.mock('./api',async()=>({...await vi.importActual<typeof import('./api')>('./api'),completeLogin:vi.fn(),request:vi.fn(),forgetLogin:vi.fn()}));
const identity='e30.'+btoa(JSON.stringify({iss:'https://identity.example.org',sub:'member'}))+'.signature';
function workspace(){const value=fixture().workspace;const until=new Date(Date.now()+600_000).toISOString();
 value.task.expiresAt=until;value.goal!.expiresAt=until;value.sources.forEach(s=>{s.expiresAt=until});return value;}
function deferred<T>(){let resolve!:(value:T)=>void,reject!:(reason:unknown)=>void;
 const promise=new Promise<T>((done,fail)=>{resolve=done;reject=fail});return {promise,resolve,reject};}
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();vi.mocked(api.completeLogin).mockResolvedValue({token:identity,taskId:'task'});});
afterEach(cleanup);

describe('logout invalidates pending page work; controlled API promises only',()=>{
 it.each(['success','failure'])('late initial workspace %s cannot restore content or errors after logout',async outcome=>{
  const pending=deferred<Workspace>();vi.mocked(api.request).mockReturnValueOnce(pending.promise);
  render(<ConsultationProductPage/>);await screen.findByRole('button',{name:'退出登录'});
  await waitFor(()=>expect(api.request).toHaveBeenCalledOnce());
  fireEvent.click(screen.getByRole('button',{name:'退出登录'}));await screen.findByRole('button',{name:'登录并打开任务'});
  await act(async()=>{if(outcome==='success')pending.resolve(workspace());else pending.reject(new Error('private initial failure'));});
  expect(screen.queryByRole('heading',{name:'普通辅助意见请求'})).not.toBeInTheDocument();
  expect(screen.queryByText('真实检索结果')).not.toBeInTheDocument();
  expect(screen.queryByText('private initial failure')).not.toBeInTheDocument();
  expect(screen.getByRole('button',{name:'登录并打开任务'})).toBeEnabled();expect(api.request).toHaveBeenCalledOnce();
 });

 it('late confirmation cannot send a workspace follow-up or publish success after logout',async()=>{
  const pending=deferred<unknown>(),current=workspace();
  vi.mocked(api.request).mockImplementation(async(_token,url)=>url.endsWith('/goals/confirm')?pending.promise:current);
  render(<ConsultationProductPage/>);await screen.findByRole('heading',{name:'普通辅助意见请求'});
  const confirm=screen.getByRole('button',{name:/^确认条件/});await waitFor(()=>expect(confirm).toBeEnabled());fireEvent.click(confirm);
  await waitFor(()=>expect(api.request).toHaveBeenCalledTimes(2));
  expect(screen.getByRole('button',{name:'退出登录'})).toBeEnabled();fireEvent.click(screen.getByRole('button',{name:'退出登录'}));
  await act(async()=>pending.resolve({}));
  expect(api.request).toHaveBeenCalledTimes(2);
  expect(screen.queryByRole('heading',{name:'普通辅助意见请求'})).not.toBeInTheDocument();
  expect(screen.queryByText('查询条件已正式确认。请执行本轮检索。')).not.toBeInTheDocument();
  expect(screen.getByRole('button',{name:'登录并打开任务'})).toBeEnabled();
 });
});
