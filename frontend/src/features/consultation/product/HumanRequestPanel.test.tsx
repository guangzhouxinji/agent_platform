import {act,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {describe,expect,it,vi} from 'vitest';
import {useLayoutEffect} from 'react';
import HumanRequestPanel from './HumanRequestPanel';
import {fetchMock,wires,props,token,json,view,submit,lookup,deferred} from './HumanRequestPanel.testFixtures';

describe('ordinary human request panel with real protocol and request transport, simulated HTTP only',()=>{
 it('persists minimum recovery before one POST and displays a request without asserting acceptance',async()=>{
  const p=props();
  fetchMock.mockImplementation(async()=>{
   expect(sessionStorage.length).toBe(1);
   const stored=sessionStorage.getItem(sessionStorage.key(0)!)!;
   expect(stored).not.toContain('Explain the cited technical issue');expect(stored).not.toContain(p.token);
   expect(stored).not.toContain('documentId');expect(stored).not.toContain('reportIds');
   return json(await view(wires[0]));
  });
  render(<HumanRequestPanel {...p}/>);submit();
  await screen.findByText('请求已记录，未证明受理');
  expect(wires).toHaveLength(1);expect(wires[0].method).toBe('POST');
  expect(wires[0].authorization).toBe('Bearer '+p.token);
  expect(wires[0].body).toEqual({expectedTaskVersion:7,comparison:null,materials:[],reason:'Explain the cited technical issue',purpose:'ORDINARY_ASSISTANCE'});
  expect(p.onLockChange).toHaveBeenLastCalledWith(false);
  expect(screen.getByRole('button',{name:'开始另一请求'})).toBeEnabled();
 });

 it('storage failure sends zero HTTP writes',async()=>{
  vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('quota denied')});
  render(<HumanRequestPanel {...props()}/>);submit();
  await screen.findByRole('alert');expect(fetchMock).not.toHaveBeenCalled();
  expect(screen.getByRole('button',{name:'提交普通辅助请求'})).toBeDisabled();
 });

 it.each(['network','503','409','malformed','wrongTask','wrongSha'])('unknown %s keeps the original key and only permits GET recovery',async fault=>{
  fetchMock.mockImplementationOnce(async()=>{
   if(fault==='network')throw new TypeError('network disconnected');
   if(fault==='503'||fault==='409')return json({},Number(fault));
   if(fault==='malformed')return new Response('not JSON',{status:200});
   const result=await view(wires[0]);
   if(fault==='wrongTask')result.receipt.taskId='other';else result.receipt.requestSha='0'.repeat(64);
   return json(result);
  });
  const p=props();render(<HumanRequestPanel {...p}/>);submit();
  await screen.findByRole('alert');const original=wires[0];
  expect(p.onLockChange).toHaveBeenLastCalledWith(true);
  expect(screen.queryByRole('button',{name:'提交普通辅助请求'})).not.toBeInTheDocument();
  expect(screen.getByRole('button',{name:'查回原请求'})).toBeEnabled();
  fetchMock.mockResolvedValueOnce(json({},404));lookup();
  await waitFor(()=>expect(fetchMock).toHaveBeenCalledTimes(2));
  await waitFor(()=>expect(screen.getByRole('button',{name:'查回原请求'})).toBeEnabled());
  expect(screen.queryByRole('button',{name:'开始另一请求'})).not.toBeInTheDocument();
  fetchMock.mockResolvedValueOnce(json(await view(original)));lookup();
  await screen.findByText('请求已记录，未证明受理');
  expect(wires.map(w=>w.method)).toEqual(['POST','GET','GET']);
  expect(wires.slice(1).every(w=>w.url.endsWith('requestId='+encodeURIComponent(original.key!))&&w.body===undefined)).toBe(true);
 });

 it('refresh under a new Goal retains original key and reads the old version without any new POST',async()=>{
  fetchMock.mockRejectedValueOnce(new TypeError('lost response'));
  const p=props(),ui=render(<HumanRequestPanel {...p}/>);submit();await screen.findByRole('alert');
  const original=wires[0],saved=sessionStorage.getItem(sessionStorage.key(0)!)!;ui.unmount();
  const newer={...p,workspace:{...p.workspace,task:{...p.workspace.task,version:8},goal:{...p.workspace.goal!,inputId:'input-8',taskVersion:8,queryObject:'New goal'}}};
  render(<HumanRequestPanel {...newer}/>);
  expect(wires).toHaveLength(1);expect(screen.getByRole('button',{name:'查回原请求'})).toBeEnabled();
  fetchMock.mockResolvedValueOnce(json(await view(original)));lookup();
  await screen.findByText('原任务版本 7');expect(wires.map(w=>w.method)).toEqual(['POST','GET']);
  expect(sessionStorage.getItem(sessionStorage.key(0)!)).toBe(saved);
 });

 it('another principal cannot inherit a prior principal recovery key',async()=>{
  fetchMock.mockRejectedValueOnce(new TypeError('lost response'));
  const p=props(),ui=render(<HumanRequestPanel {...p}/>);submit();await screen.findByRole('alert');
  const originalKey=wires[0].key;ui.rerender(<HumanRequestPanel {...p} token={token('other-person')}/>);
  expect(screen.queryByRole('button',{name:'查回原请求'})).not.toBeInTheDocument();
  expect(screen.getByLabelText('请求理由')).toHaveValue('');expect(document.body.textContent).not.toContain(originalKey);
  expect(wires).toHaveLength(1);expect(sessionStorage.length).toBe(1);
 });

 it.each([401,403])('current %s denial clears content and retains only the original recovery hint',async status=>{
  fetchMock.mockImplementationOnce(async()=>json(await view(wires[0])));
  const p=props();render(<HumanRequestPanel {...p}/>);submit('A private submitted reason');
  await screen.findByText('请求理由：A private submitted reason');
  fetchMock.mockResolvedValueOnce(json({},status));lookup();
  await waitFor(()=>expect(p.onDenied).toHaveBeenCalledOnce());
  expect(screen.queryByText('请求理由：A private submitted reason')).not.toBeInTheDocument();
  expect(sessionStorage.length).toBe(1);expect(sessionStorage.getItem(sessionStorage.key(0)!)).not.toContain('A private submitted reason');
 });

 it('late success after task change never restores the prior body',async()=>{
  const pending=deferred();fetchMock.mockReturnValueOnce(pending.promise);
  const p=props(),ui=render(<HumanRequestPanel {...p}/>);submit('Prior task reason');
  await waitFor(()=>expect(wires).toHaveLength(1));const original=wires[0];
  ui.rerender(<HumanRequestPanel {...p} workspace={{...p.workspace,task:{...p.workspace.task,id:'other-task'}}}/>);
  await act(async()=>pending.resolve(json(await view(original))));
  expect(screen.queryByText('请求已记录，未证明受理')).not.toBeInTheDocument();
  expect(document.body.textContent).not.toContain('Prior task reason');expect(screen.getByLabelText('请求理由')).toHaveValue('');
 });

 it('explicit new request is possible only after a verified result and uses a different key',async()=>{
  fetchMock.mockImplementation(async()=>json(await view(wires[wires.length-1])));
  render(<HumanRequestPanel {...props()}/>);submit();await screen.findByText('请求已记录，未证明受理');
  const first=wires[0].key;fireEvent.click(screen.getByRole('button',{name:'开始另一请求'}));submit('A second ordinary request');
  await screen.findByText('请求理由：A second ordinary request');
  expect(wires).toHaveLength(2);expect(wires[1].key).not.toBe(first);
 });

 it.each(['identity','task'])('a changed %s cannot expose old content even before passive effects run',async change=>{
  const p=props(),snapshots:string[]=[];
  function Observed({changed}:{changed:boolean}){
   useLayoutEffect(()=>{if(changed)snapshots.push(document.body.textContent??'')},[changed]);
   return <HumanRequestPanel {...p} token={changed&&change==='identity'?token('other'):p.token}
    workspace={changed&&change==='task'?{...p.workspace,task:{...p.workspace.task,id:'other-task'}}:p.workspace}/>;
  }
  fetchMock.mockImplementationOnce(async()=>json(await view(wires[0])));
  const ui=render(<Observed changed={false}/>);submit('Private owner content');await screen.findByText('请求理由：Private owner content');
  ui.rerender(<Observed changed/>);
  expect(snapshots.length).toBeGreaterThan(0);expect(snapshots.every(s=>!s.includes('Private owner content')&&!s.includes('人工包 package'))).toBe(true);
 });

 it('expiry during asynchronous response digest cannot render expired body',async()=>{
  let current=Date.now(),armed=false;
  vi.spyOn(Date,'now').mockImplementation(()=>current);
  const digest=crypto.subtle.digest.bind(crypto.subtle);
  vi.spyOn(crypto.subtle,'digest').mockImplementation(async(...args)=>{
   const value=await digest(...args);if(armed){armed=false;current+=2000;}return value;
  });
  fetchMock.mockImplementationOnce(async()=>{
   const result=await view(wires[0]);result.body!.bodyAccessUntil=new Date(current+1000).toISOString();armed=true;return json(result);
  });
  render(<HumanRequestPanel {...props()}/>);submit('Expired during hash');
  await screen.findByText('仅保留请求回执');
  expect(screen.queryByText('请求理由：Expired during hash')).not.toBeInTheDocument();
 });
});
