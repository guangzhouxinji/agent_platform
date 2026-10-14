import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,describe,expect,it,vi} from "vitest";
import AgentTaskPanel,{type AgentRecoveryPort,type AgentRecoveryState,type AgentRunView} from "./AgentTaskPanel";
afterEach(cleanup);
const run:AgentRunView={goalText:"当前已核验目标",checkpoint:{runId:"existing-run",task:{id:"task"},version:1,phase:"ACT",cursor:0,invocations:0,replans:0,pending:null,plan:null,observations:[],outcome:null},events:[]};
function memory(){let state:AgentRecoveryState|null=null;return {load:vi.fn(()=>state),save:vi.fn((next:AgentRecoveryState)=>{state=structuredClone(next)}),clear:vi.fn(()=>{state=null})};}
describe("optional browser receipt recovery",()=>{
 it("remount restores an uncertain creation with the original key and input, with no automatic POST",async()=>{
  const recovery=memory(),request=vi.fn().mockRejectedValueOnce(new Error("connection lost")).mockResolvedValueOnce(run);
  const ui=render(<AgentTaskPanel scope="same" expectedTaskId="task" startInput={{expectedTaskVersion:2}} recovery={recovery} request={request} onDenied={()=>{}}/>);
  fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("connection lost");const packet=request.mock.calls[0];ui.unmount();
  render(<AgentTaskPanel scope="same" expectedTaskId="task" startInput={{expectedTaskVersion:99}} recovery={recovery} request={request} onDenied={()=>{}}/>);
  expect(request).toHaveBeenCalledTimes(1);expect(screen.getByText("对账同一创建请求")).toBeTruthy();fireEvent.click(screen.getByText("对账同一创建请求"));await screen.findByText(run.goalText);
  expect(request.mock.calls[1]).toEqual(packet);expect(recovery.load()).toEqual({runId:"existing-run",creation:null});
 });
 it("restores a known Run only as a hint, requiring GET before any execute or result display",async()=>{
  const recovery=memory();recovery.save({runId:"existing-run",creation:null});const request=vi.fn().mockResolvedValue(run);
  render(<AgentTaskPanel scope="same" expectedTaskId="task" startInput={{expectedTaskVersion:2}} recovery={recovery} request={request} onDenied={()=>{}}/>);
  expect(request).not.toHaveBeenCalled();expect(screen.queryByText(run.goalText)).toBeNull();expect((screen.getByText("启动当前目标") as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByText("读取当前运行"));await screen.findByText(run.goalText);expect(request.mock.calls[0]).toEqual(["/existing-run"]);
 });
 it("cannot send a create before its recovery packet has been saved",async()=>{
  const recovery:AgentRecoveryPort={load:()=>null,save:()=>{throw new Error("storage unavailable")},clear:()=>{}};const request=vi.fn();
  render(<AgentTaskPanel scope="same" expectedTaskId="task" startInput={{expectedTaskVersion:2}} recovery={recovery} request={request} onDenied={()=>{}}/>);
  fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("storage unavailable");expect(request).not.toHaveBeenCalled();expect(screen.getByText("对账同一创建请求")).toBeTruthy();
 });
 it("permission rejection clears projection but retains the uncertain receipt for normal reauthentication",async()=>{
  const recovery=memory(),request=vi.fn().mockRejectedValueOnce(new Error("connection lost")).mockRejectedValueOnce(Object.assign(new Error("denied"),{status:401}));const denied=vi.fn();
  render(<AgentTaskPanel scope="same" startInput={{expectedTaskVersion:2}} recovery={recovery} request={request} onDenied={denied}/>);
  fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("connection lost");const original=recovery.load();fireEvent.click(screen.getByText("对账同一创建请求"));await screen.findByText("denied");
  expect(denied).toHaveBeenCalledOnce();expect(recovery.load()).toEqual(original);expect(screen.queryByText(run.goalText)).toBeNull();
 });
 it("a failed clear after a rejection keeps the original packet and fails closed",async()=>{
  const recovery=memory();recovery.clear.mockImplementation(()=>{throw new Error("cannot clear receipt")});
  const request=vi.fn().mockRejectedValue(Object.assign(new Error("not found"),{status:404}));
  render(<AgentTaskPanel scope="same" startInput={{expectedTaskVersion:2}} recovery={recovery} request={request} onDenied={()=>{}}/>);
  fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("cannot clear receipt");expect(screen.getByText("对账同一创建请求")).toBeTruthy();expect(request).toHaveBeenCalledOnce();
 });
 it("a rejected identity cannot generate a new creation key before reauthentication",async()=>{
  const recovery=memory(),request=vi.fn().mockRejectedValue(Object.assign(new Error("denied"),{status:401}));
  render(<AgentTaskPanel scope="same" startInput={{expectedTaskVersion:2}} recovery={recovery} request={request} onDenied={()=>{}}/>);
  fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("denied");expect((screen.getByText("启动当前目标") as HTMLButtonElement).disabled).toBe(true);expect(recovery.load()?.creation?.key).toBe(request.mock.calls[0][2]);
 });

});
