import {act,cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import ConsultationProductPage from './ConsultationProductPage';
import * as api from './api';
import {fixture} from './agentResultFixtures';
import type { Selection } from './types';
vi.mock('./api',async()=>({...await vi.importActual<typeof import('./api')>('./api'),completeLogin:vi.fn(),request:vi.fn(),forgetLogin:vi.fn()}));
const receipt=():Selection=>({selectionId:'saved',taskId:'task',revision:1,taskVersion:2,resultSetId:'result',resultSetVersion:1,resultSha:'b'.repeat(64),actorSubject:'member',createdAt:'2026-10-06T10:00:00Z',expiresAt:'2027-01-01T00:00:00Z',members:[{memberId:'member',ordinal:0,sourceId:'source',documentId:'patent',documentVersion:'1',sourceVersion:'1',rawSha:'a'.repeat(64),locatorRef:'json-pointer=/title'}]});
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();vi.mocked(api.completeLogin).mockResolvedValue({token:'e30.'+btoa(JSON.stringify({iss:'https://identity.example.org',sub:'member'}))+'.signature',taskId:'task'});});afterEach(cleanup);
async function save(){
 await screen.findByText('真实检索结果');await act(async()=>{});
 const candidate=screen.getByRole('checkbox',{name:'1. patent'});
 await waitFor(()=>expect(candidate).toBeEnabled());fireEvent.click(candidate);
 await waitFor(()=>expect(candidate).toBeChecked());
 const saveButton=screen.getByRole('button',{name:'保存所选文献（1）'});
 await waitFor(()=>expect(saveButton).toBeEnabled());fireEvent.click(saveButton);
}
async function identifiers(id='saved',result='result'){await screen.findByText('读取已保存选择');fireEvent.change(screen.getByLabelText('选择所属结果集编号'),{target:{value:result}});fireEvent.change(screen.getByLabelText('选择编号'),{target:{value:id}});}
describe('persisted selection operation chain; API fixtures, no live authorization',()=>{
 it('saves and displays actual selection identity, versions and members',async()=>{
  vi.mocked(api.request).mockResolvedValueOnce(fixture().workspace).mockResolvedValueOnce(receipt());render(<ConsultationProductPage/>);await save();await screen.findByText('已保存的文献选择');expect(screen.getByText('saved')).toBeTruthy();const call=vi.mocked(api.request).mock.calls[1];expect(call[1]).toBe('/v2/consultation/tasks/task/patent-searches/result/selections');expect(call[2]).toEqual({expectedRevision:0,resultSha:'b'.repeat(64),memberIds:['member']});expect(call[3]).toEqual(expect.any(String));expect(sessionStorage.length).toBe(0);
 });
 it('reopens a requested historical selection only via GET',async()=>{
  vi.mocked(api.request).mockResolvedValueOnce(fixture().workspace).mockResolvedValueOnce({...receipt(),taskVersion:1});render(<ConsultationProductPage/>);await identifiers();fireEvent.click(screen.getByText('读取已保存文献'));await screen.findByText('已保存的文献选择');expect(screen.getByText('task · 保存时任务版本 1')).toBeTruthy();expect(vi.mocked(api.request).mock.calls[1].slice(1)).toEqual(['/v2/consultation/tasks/task/patent-searches/result/selections/saved']);expect((screen.getByRole('checkbox',{name:'1. patent'})as HTMLInputElement).checked).toBe(false);
 });
 it('unknown save retains original body/key and never automatically retries',async()=>{
  vi.mocked(api.request).mockResolvedValueOnce(fixture().workspace).mockRejectedValueOnce(new api.ConsultationError(0)).mockResolvedValueOnce(receipt());render(<ConsultationProductPage/>);await save();await screen.findByText('对账同一文献保存请求');const original=vi.mocked(api.request).mock.calls[1];expect(vi.mocked(api.request).mock.calls).toHaveLength(2);expect((screen.getByLabelText('任务编号')as HTMLInputElement).disabled).toBe(true);fireEvent.click(screen.getByText('对账同一文献保存请求'));await screen.findByText('已保存的文献选择');expect(vi.mocked(api.request).mock.calls[2].slice(1)).toEqual(original.slice(1));
 });
 it('missing unrelated read does not release an unresolved save fence',async()=>{
  vi.mocked(api.request).mockResolvedValueOnce(fixture().workspace).mockRejectedValueOnce(new api.ConsultationError(0)).mockRejectedValueOnce(new api.ConsultationError(404));render(<ConsultationProductPage/>);await save();await screen.findByText('对账同一文献保存请求');await identifiers('missing');fireEvent.click(screen.getByText('读取已保存文献'));await screen.findByText('任务或当前权限已不可用，已清空结果。');expect(screen.getByText('对账同一文献保存请求')).toBeTruthy();
 });
 it('rejects a foreign-task selection before displaying documents',async()=>{
  vi.mocked(api.request).mockResolvedValueOnce(fixture().workspace).mockResolvedValueOnce({...receipt(),taskId:'other'});render(<ConsultationProductPage/>);await identifiers();fireEvent.click(screen.getByText('读取已保存文献'));await screen.findByText('文献选择响应与请求或文献版本不一致，未展示该回执。');expect(screen.queryByText('已保存的文献选择')).toBeNull();
 });
 it('known conflict is not shown as an unknown write',async()=>{
  vi.mocked(api.request).mockResolvedValueOnce(fixture().workspace).mockRejectedValueOnce(new api.ConsultationError(409));render(<ConsultationProductPage/>);await save();await screen.findByText('任务或结果版本已变化，请重新读取并核对条件。');expect(screen.queryByText('对账同一文献保存请求')).toBeNull();
 });
 it('lost authorization removes the uncertain command and content',async()=>{
  vi.mocked(api.request).mockResolvedValueOnce(fixture().workspace).mockRejectedValueOnce(new api.ConsultationError(0)).mockRejectedValueOnce(new api.ConsultationError(401));render(<ConsultationProductPage/>);await save();await screen.findByText('对账同一文献保存请求');fireEvent.click(screen.getByText('对账同一文献保存请求'));await screen.findByText('登录并打开任务');expect(screen.queryByText('对账同一文献保存请求')).toBeNull();expect(api.forgetLogin).toHaveBeenCalled();
 });
 it('does not adopt a delayed response after unmount',async()=>{
  let finish!:(value:Selection)=>void;vi.mocked(api.request).mockResolvedValueOnce(fixture().workspace).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}));render(<ConsultationProductPage/>);await identifiers();fireEvent.click(screen.getByText('读取已保存文献'));expect(screen.getByRole('button',{name:'退出登录'})).toBeEnabled();cleanup();finish(receipt());await waitFor(()=>expect(screen.queryByText('已保存的文献选择')).toBeNull());
 });
});
