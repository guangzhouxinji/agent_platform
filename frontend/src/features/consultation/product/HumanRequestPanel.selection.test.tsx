import {act,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {describe,expect,it,vi} from 'vitest';
import HumanRequestPanel from './HumanRequestPanel';
import {fetchMock,wires,props,selection,json,view,submit} from './HumanRequestPanel.testFixtures';

describe('human request selection and expiry boundaries; simulated HTTP',()=>{
 it('sends only explicitly selected material fields and exact selected current report',async()=>{
  const p=props();p.selection=selection(p.workspace);p.comparison={comparisonId:'comparison-7',inputSha:'b'.repeat(64)};
  p.report={...p.comparison,reportId:'report-7',expiresAt:p.workspace.task.expiresAt};
  fetchMock.mockImplementation(async()=>json(await view(wires[0])));
  render(<HumanRequestPanel {...p}/>);
  fireEvent.click(screen.getByRole('checkbox',{name:'标题'}));
  fireEvent.click(screen.getByRole('checkbox',{name:'附当前已核对照输入'}));
  fireEvent.click(screen.getByRole('checkbox',{name:'附已选择的确切矩阵报告'}));submit();
  await screen.findByText('请求已记录，未证明受理');
  expect(wires[0].body?.materials).toEqual([{sourceId:'source',documentId:'CN123',documentVersion:'raw-v1',fields:['TITLE']}]);
  expect(wires[0].body?.comparison).toEqual({comparisonId:'comparison-7',reportIds:['report-7']});
  const saved=sessionStorage.getItem(sessionStorage.key(0)!)!;expect(saved).not.toContain('CN123');expect(saved).not.toContain('report-7');
 });

 it.each(['comparison','sha','expired'])('a report with %s mismatch cannot be attached',fault=>{
  const p=props();p.comparison={comparisonId:'comparison-7',inputSha:'b'.repeat(64)};
  p.report={...p.comparison,reportId:'report-7',expiresAt:p.workspace.task.expiresAt};
  if(fault==='comparison')p.report.comparisonId='another';
  if(fault==='sha')p.report.inputSha='c'.repeat(64);
  if(fault==='expired')p.report.expiresAt=new Date(Date.now()-1000).toISOString();
  render(<HumanRequestPanel {...p}/>);fireEvent.click(screen.getByRole('checkbox',{name:'附当前已核对照输入'}));
  expect(screen.getByRole('checkbox',{name:'附已选择的确切矩阵报告'})).toBeDisabled();
  expect(screen.queryByText('已选择报告 report-7')).not.toBeInTheDocument();
 });

 it('new Goal revokes draft material/report choices before a new request',async()=>{
  const p=props();p.selection=selection(p.workspace);p.comparison={comparisonId:'comparison-7',inputSha:'b'.repeat(64)};
  p.report={...p.comparison,reportId:'report-7',expiresAt:p.workspace.task.expiresAt};
  const ui=render(<HumanRequestPanel {...p}/>);
  fireEvent.click(screen.getByRole('checkbox',{name:'标题'}));fireEvent.click(screen.getByRole('checkbox',{name:'附当前已核对照输入'}));
  fireEvent.click(screen.getByRole('checkbox',{name:'附已选择的确切矩阵报告'}));
  fireEvent.change(screen.getByLabelText('请求理由'),{target:{value:'Old draft'}});
  ui.rerender(<HumanRequestPanel {...p} workspace={{...p.workspace,task:{...p.workspace.task,version:8},goal:{...p.workspace.goal!,inputId:'input-8',taskVersion:8}}} comparison={null} report={null}/>);
  expect(screen.getByLabelText('请求理由')).toHaveValue('');expect(screen.queryByRole('checkbox',{name:'标题'})).not.toBeInTheDocument();
  fetchMock.mockImplementation(async()=>json(await view(wires[0])));submit('New goal request');
  await screen.findByText('请求已记录，未证明受理');expect(wires[0].body?.materials).toEqual([]);expect(wires[0].body?.comparison).toBeNull();
 });

 it('expired source fields cannot be selected as current materials',()=>{
  const p=props();p.selection=selection(p.workspace);p.workspace.sources[0].expiresAt=new Date(Date.now()-1000).toISOString();
  render(<HumanRequestPanel {...p}/>);expect(screen.queryByRole('checkbox',{name:'标题'})).not.toBeInTheDocument();
 });

 it('body deadline clears the reason while retaining only the finite receipt',async()=>{
  vi.useFakeTimers();
  let resultReady=false;
  fetchMock.mockImplementation(async()=>{
   const result=await view(wires[0]);result.body!.bodyAccessUntil=new Date(Date.now()+1000).toISOString();resultReady=true;return json(result);
  });
  render(<HumanRequestPanel {...props()}/>);submit('Short lived body');
  await vi.waitFor(()=>expect(resultReady).toBe(true));
  await act(async()=>{await Promise.resolve();await Promise.resolve()});
  await vi.waitFor(()=>expect(screen.getByText('请求理由：Short lived body')).toBeInTheDocument());
  await act(async()=>{await vi.advanceTimersByTimeAsync(1000)});
  expect(screen.queryByText('请求理由：Short lived body')).not.toBeInTheDocument();expect(screen.getByText('仅保留请求回执')).toBeInTheDocument();
 });
});
