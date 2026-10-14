import {fireEvent,render,screen,waitFor} from '@testing-library/react';
import {expect,it,vi} from 'vitest';
import ConsultationProductPage from './ConsultationProductPage';
import * as api from './api';
import {fetchMock,wires,props,json,view,submit,lookup} from './HumanRequestPanel.testFixtures';

vi.mock('./api',async()=>({...await vi.importActual<typeof import('./api')>('./api'),completeLogin:vi.fn(),forgetLogin:vi.fn()}));

it('unknown human request locks sibling writes while its GET recovery and logout stay available',async()=>{
 const p=props();vi.mocked(api.completeLogin).mockResolvedValue({token:p.token,taskId:'task'});
 fetchMock.mockImplementation(async(_url,init)=>{
  const url=String(_url);
  if(url.endsWith('/retrieval-workspace'))return json(p.workspace);
  if(url.includes('/human-requests')){
   if(init?.method==='POST')return json({},503);
   return json(await view(wires.find(w=>w.method==='POST'&&w.url.endsWith('/human-requests'))!));
  }
  throw new Error('Unexpected component HTTP route '+url);
 });
 render(<ConsultationProductPage/>);await screen.findByRole('heading',{name:'普通辅助意见请求'});submit();
 await screen.findByText('尚未核实原请求结果。已保留原请求编号，请查回原请求；不会重复提交。');
 expect(screen.getByLabelText('任务编号')).toBeDisabled();
 expect(screen.getByRole('button',{name:'执行已确认的本轮检索'})).toBeDisabled();
 expect(screen.getByRole('button',{name:'查回原请求'})).toBeEnabled();
 expect(screen.getByRole('button',{name:'退出登录'})).toBeEnabled();
 lookup();await screen.findByText('请求已记录，未证明受理');
 expect(wires.filter(w=>w.url.includes('/human-requests')).map(w=>w.method)).toEqual(['POST','GET']);
 // The opened Task identifier stays immutable; the released operation lock permits a current-state read.
 await waitFor(()=>expect(screen.getByRole('button',{name:'重新读取任务'})).toBeEnabled());
 fireEvent.click(screen.getByRole('button',{name:'退出登录'}));
 await screen.findByText('登录并打开任务');expect(screen.queryByText('请求已记录，未证明受理')).not.toBeInTheDocument();
});
