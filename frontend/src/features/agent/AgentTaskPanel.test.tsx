import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import AgentTaskPanel, { type AgentRunView } from "./AgentTaskPanel";
afterEach(cleanup);
const run:AgentRunView={goalText:"检查当前证据",checkpoint:{runId:"run-1",version:7,phase:"EVALUATE",task:{id:"task-1"},cursor:0,invocations:1,replans:0,pending:null,plan:{version:"1",steps:[{id:"s1",skill:"Search",tool:"search"}]},observations:[{reference:"runtime-failure/SOURCE_UNAVAILABLE/run-1/1",sha256:"a".repeat(64)}],outcome:null},events:[{version:7,phase:"EVALUATE",invocationId:"run-1/1",stopReason:null}]};
function open(){fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"run-1"}});fireEvent.click(screen.getByText("读取当前运行"));}
describe("shared real-run projection",()=>{
 it.each([401,403])("definitive denial %s clears earlier uncertain creation state",async(status)=>{
  const request=vi.fn().mockRejectedValueOnce(new Error("network")).mockRejectedValueOnce(Object.assign(new Error("denied"),{status}));
  const denied=vi.fn();
  render(<AgentTaskPanel scope="s" request={request} startInput={{version:1}} onDenied={denied}/>);
  fireEvent.click(screen.getByText("启动当前目标")); await screen.findByText("network");
  expect(screen.getByText(/请求结果尚未确认/)).toBeTruthy();
  fireEvent.click(screen.getByText("对账同一创建请求")); await screen.findByText("denied");
  expect(denied).toHaveBeenCalledOnce();
  expect(screen.queryByText(/请求结果尚未确认/)).toBeNull();
  expect(screen.queryByText("对账同一创建请求")).toBeNull();
  expect((screen.getByLabelText("运行编号") as HTMLInputElement).value).toBe("");
  expect((screen.getByText("执行下一步") as HTMLButtonElement).disabled).toBe(true);
  expect(request.mock.calls[1]).toEqual(request.mock.calls[0]); expect(request).toHaveBeenCalledTimes(2);
 });
 it("never counts a failed step as completed and displays actual evidence",async()=>{render(<AgentTaskPanel scope="s" request={vi.fn().mockResolvedValue(run)} onDenied={()=>{}}/>);open();await screen.findByText("检查当前证据");expect(screen.queryByText("已完成")).toBeNull();expect(screen.getByText(run.checkpoint.observations[0].reference)).toBeTruthy();});
 it("human stop disables execution without granting review actions",async()=>{render(<AgentTaskPanel scope="s" request={vi.fn().mockResolvedValue({...run,checkpoint:{...run.checkpoint,phase:"STOP",outcome:{reason:"NEED_HUMAN"}}})} onDenied={()=>{}}/>);open();await screen.findByText("需要人工复核");expect((screen.getByText("执行下一步") as HTMLButtonElement).disabled).toBe(true);});
 it("revocation clears retained goal and invokes the domain denial handler",async()=>{const request=vi.fn().mockResolvedValueOnce(run).mockRejectedValueOnce(Object.assign(new Error("denied"),{status:403}));const denied=vi.fn();render(<AgentTaskPanel scope="s" request={request} onDenied={denied}/>);open();await screen.findByText("检查当前证据");fireEvent.click(screen.getByText("执行下一步"));await waitFor(()=>expect(denied).toHaveBeenCalledOnce());expect(screen.queryByText("检查当前证据")).toBeNull();});
 it("task switch ignores late previous-task responses",async()=>{let resolve!:(v:AgentRunView)=>void;const request=vi.fn().mockImplementation(()=>new Promise<AgentRunView>(r=>{resolve=r}));const ui=render(<AgentTaskPanel scope="a" request={request} onDenied={()=>{}}/>);open();ui.rerender(<AgentTaskPanel scope="b" request={request} onDenied={()=>{}}/>);resolve(run);await waitFor(()=>expect((screen.getByLabelText("运行编号") as HTMLInputElement).value).toBe(""));expect(screen.queryByText("检查当前证据")).toBeNull();});
 it("unconfirmed creation reuses exact key and input even after caller version changes",async()=>{const request=vi.fn().mockRejectedValueOnce(new Error("network")).mockResolvedValueOnce(run);const ui=render(<AgentTaskPanel scope="s" request={request} startInput={{version:1}} onDenied={()=>{}}/>);fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("network");ui.rerender(<AgentTaskPanel scope="s" request={request} startInput={{version:2}} onDenied={()=>{}}/>);fireEvent.click(screen.getByText("对账同一创建请求"));await screen.findByText("检查当前证据");expect(request.mock.calls[1]).toEqual(request.mock.calls[0]);});
 it("continuous execution sends one execute request and uses actual stopped result",async()=>{
  const stopped={...run,checkpoint:{...run.checkpoint,phase:"STOP",outcome:{reason:"COMPLETED",reference:"answer:actual"}}};
  const request=vi.fn().mockResolvedValueOnce(run).mockResolvedValueOnce(stopped);const onView=vi.fn(),onAdvanced=vi.fn();
  render(<AgentTaskPanel scope="s" request={request} onDenied={()=>{}} onView={onView} onAdvanced={onAdvanced}/>);
  open();await screen.findByText("检查当前证据");fireEvent.click(screen.getByText("执行至当前出口"));
  await screen.findByText("完成条件已满足");expect(request).toHaveBeenCalledTimes(2);expect(request.mock.calls[1][0]).toBe("/run-1/execute");
  expect(onView).toHaveBeenLastCalledWith(stopped);expect(onAdvanced).toHaveBeenCalledOnce();expect((screen.getByText("执行至当前出口") as HTMLButtonElement).disabled).toBe(true);
 });
 it("uncertain execute is read-reconciled instead of browser retries or advance loops",async()=>{
  const request=vi.fn().mockResolvedValueOnce(run).mockRejectedValueOnce(new Error("connection lost")).mockResolvedValueOnce(run);
  render(<AgentTaskPanel scope="s" request={request} onDenied={()=>{}}/>);open();await screen.findByText("检查当前证据");
  fireEvent.click(screen.getByText("执行至当前出口"));await screen.findByText("connection lost");
  expect((screen.getByText("执行至当前出口") as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByText("读取当前运行"));await screen.findByText("检查当前证据");expect(request.mock.calls.map(c=>c[0])).toEqual(["/run-1","/run-1/execute","/run-1"]);
 });
 it("scope switch clears parent result and ignores late execute completion",async()=>{
  let resolve!:(v:AgentRunView)=>void;const request=vi.fn().mockResolvedValueOnce(run).mockImplementationOnce(()=>new Promise<AgentRunView>(r=>{resolve=r}));const onView=vi.fn();
  const ui=render(<AgentTaskPanel scope="a" request={request} onDenied={()=>{}} onView={onView}/>);open();await screen.findByText("检查当前证据");
  fireEvent.click(screen.getByText("执行至当前出口"));ui.rerender(<AgentTaskPanel scope="b" request={request} onDenied={()=>{}} onView={onView}/>);
  resolve(run);await waitFor(()=>expect(onView).toHaveBeenLastCalledWith(null));expect(screen.queryByText("检查当前证据")).toBeNull();
 });

 it("rejects a different Run returned for an explicit read",async()=>{
  const request=vi.fn().mockResolvedValue({...run,checkpoint:{...run.checkpoint,runId:"other-run"}});const onView=vi.fn();
  render(<AgentTaskPanel scope="s" request={request} onDenied={()=>{}} onView={onView}/>);open();
  await screen.findByText(/运行响应与当前任务或请求的运行编号不一致/);
  expect(screen.queryByText(run.goalText)).toBeNull();expect(onView).not.toHaveBeenCalledWith(expect.objectContaining({goalText:run.goalText}));
  expect(screen.queryByText(/请求结果尚未确认/)).toBeNull();expect((screen.getByText("执行下一步") as HTMLButtonElement).disabled).toBe(true);
 });
 it.each(["advance","execute"])("rejects a different Run returned for %s",async(action)=>{
  const request=vi.fn().mockResolvedValueOnce(run).mockResolvedValueOnce({...run,checkpoint:{...run.checkpoint,runId:"other-run"}});
  render(<AgentTaskPanel scope="s" request={request} onDenied={()=>{}}/>);open();await screen.findByText(run.goalText);
  fireEvent.click(screen.getByText(action==="advance"?"执行下一步":"执行至当前出口"));
  await screen.findByText(/运行响应与当前任务或请求的运行编号不一致/);expect(screen.queryByText(run.goalText)).toBeNull();expect(screen.queryByText(/请求结果尚未确认/)).toBeNull();
 });
 it("rejects a response for a different explicitly bound Task",async()=>{
  render(<AgentTaskPanel scope="s" expectedTaskId="task-expected" request={vi.fn().mockResolvedValue(run)} onDenied={()=>{}}/>);open();
  await screen.findByText(/运行响应与当前任务或请求的运行编号不一致/);expect(screen.queryByText(run.goalText)).toBeNull();
 });
 it("clears the projection when the explicit Task changes even if scope is unchanged",async()=>{
  let resolve!:(v:AgentRunView)=>void;const request=vi.fn().mockImplementation(()=>new Promise<AgentRunView>(r=>{resolve=r}));const onView=vi.fn();
  const ui=render(<AgentTaskPanel scope="s" expectedTaskId="task-1" request={request} onDenied={()=>{}} onView={onView}/>);open();
  ui.rerender(<AgentTaskPanel scope="s" expectedTaskId="task-2" request={request} onDenied={()=>{}} onView={onView}/>);resolve(run);
  await waitFor(()=>expect((screen.getByLabelText("运行编号") as HTMLInputElement).value).toBe(""));expect(onView).toHaveBeenLastCalledWith(null);
 });

 it("reconciles a mismatched creation response with the original key instead of starting another Run",async()=>{
  const request=vi.fn().mockResolvedValueOnce({...run,checkpoint:{...run.checkpoint,task:{id:"other-task"}}}).mockResolvedValueOnce(run);
  const ui=render(<AgentTaskPanel scope="s" expectedTaskId="task-1" request={request} startInput={{version:1}} onDenied={()=>{}}/>);
  fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText(/运行响应与当前任务或请求的运行编号不一致/);
  expect(screen.queryByText(run.goalText)).toBeNull();expect(screen.getByText(/请求结果尚未确认/)).toBeTruthy();
  ui.rerender(<AgentTaskPanel scope="s" expectedTaskId="task-1" request={request} startInput={{version:2}} onDenied={()=>{}}/>);
  fireEvent.click(screen.getByText("对账同一创建请求"));await screen.findByText(run.goalText);expect(request.mock.calls[1]).toEqual(request.mock.calls[0]);
 });

});
