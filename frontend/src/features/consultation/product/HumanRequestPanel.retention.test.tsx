import {act,fireEvent,render,screen} from '@testing-library/react';
import {describe,expect,it,vi} from 'vitest';
import HumanRequestPanel from './HumanRequestPanel';
import {fetchMock,wires,props,selection,json,view,submit} from './HumanRequestPanel.testFixtures';

describe('finite retention of submitted human drafts; browser component fixtures only',()=>{
 it('unknown submission clears the draft and selected coordinates when its fixed 180-day hint expires',async()=>{
  vi.useFakeTimers();
  const p=props(),until=new Date(Date.now()+365*86400000).toISOString();
  p.workspace.task.expiresAt=until;p.workspace.goal!.expiresAt=until;p.workspace.sources[0].expiresAt=until;
  p.selection=selection(p.workspace);p.comparison={comparisonId:'comparison-7',inputSha:'b'.repeat(64)};
  p.report={...p.comparison,reportId:'report-7',expiresAt:until};
  fetchMock.mockResolvedValue(json({},503));
  render(<HumanRequestPanel {...p}/>);
  fireEvent.click(screen.getByRole('checkbox',{name:'标题'}));
  fireEvent.click(screen.getByRole('checkbox',{name:'附当前已核对照输入'}));
  fireEvent.click(screen.getByRole('checkbox',{name:'附已选择的确切矩阵报告'}));submit('Sensitive submitted draft');
  await vi.waitFor(()=>expect(screen.getByRole('alert')).toHaveTextContent('尚未核实原请求结果'));
  expect(wires).toHaveLength(1);expect(wires[0].body?.reason).toBe('Sensitive submitted draft');
  expect(wires[0].body?.materials).toHaveLength(1);expect(wires[0].body?.comparison?.reportIds).toEqual(['report-7']);
  const raw=sessionStorage.getItem(sessionStorage.key(0)!)!,saved=JSON.parse(raw).record;
  expect(saved.expiresAt-saved.createdAt).toBe(180*86400000);
  expect(raw).not.toContain('Sensitive submitted draft');expect(raw).not.toContain('CN123');expect(raw).not.toContain('report-7');
  // The component caps individual timers; each step flushes the next scheduled finite timer.
  while(Date.now()<saved.expiresAt){
   const step=Math.min(2147483647,saved.expiresAt-Date.now());
   await act(async()=>{await vi.advanceTimersByTimeAsync(step)});
  }
  expect(screen.getByLabelText('请求理由')).toHaveValue('');
  expect(screen.getByRole('checkbox',{name:'标题'})).not.toBeChecked();
  expect(screen.getByRole('checkbox',{name:'附当前已核对照输入'})).not.toBeChecked();
  expect(screen.getByRole('checkbox',{name:'附已选择的确切矩阵报告'})).not.toBeChecked();
  expect(screen.getByRole('button',{name:'提交普通辅助请求'})).toBeDisabled();
  expect(screen.queryByRole('button',{name:'查回原请求'})).not.toBeInTheDocument();
  expect(sessionStorage.length).toBe(0);expect(wires).toHaveLength(1);
 });

 it.each(['\ud800','\udfff'])('unpaired UTF-16 input is editable after rejection and never creates a recovery hint or POST',async invalid=>{
  fetchMock.mockImplementation(async()=>json(await view(wires[0])));
  render(<HumanRequestPanel {...props()}/>);submit('Invalid '+invalid+' text');
  await screen.findByText('输入无法核验，尚未提交。请核对内容后重试。');
  expect(fetchMock).not.toHaveBeenCalled();expect(sessionStorage.length).toBe(0);
  expect(screen.getByLabelText('请求理由')).toBeEnabled();
  expect(screen.getByRole('button',{name:'提交普通辅助请求'})).toBeEnabled();
  submit('Corrected ordinary request');await screen.findByText('请求已记录，未证明受理');
  expect(wires).toHaveLength(1);expect(wires[0].body?.reason).toBe('Corrected ordinary request');
 });
});
