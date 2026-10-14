import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,beforeEach,describe,expect,it,vi} from "vitest";
import ConsultationProductPage from "./ConsultationProductPage";
import * as api from "./api";
import {fixture} from "./agentResultFixtures";
vi.mock("./api",async()=>({...await vi.importActual<typeof import("./api")>("./api"),completeLogin:vi.fn(),request:vi.fn(),forgetLogin:vi.fn()}));
const identity=(sub="member")=>"header."+btoa(JSON.stringify({iss:"https://product.example",sub}))+".signature";
function context(){const f=fixture();f.workspace.task.expiresAt="2099-01-01T02:00:00Z";f.workspace.goal!.expiresAt="2099-01-01T01:00:00Z";return f;}
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();vi.mocked(api.completeLogin).mockResolvedValue({token:identity(),taskId:"task"});});
afterEach(cleanup);
// Transport fixtures exercise the actual page, not formal product acceptance.
describe("consultation operation chain engineering contract",()=>{
 it("confirms, creates once, executes once and reads the same Task/Run answer and sources",async()=>{
  const f=context(),ready={...f.workspace,result:null,dialogue:[]};let confirmed=false,finished=false;
  vi.mocked(api.request).mockImplementation(async(_token,url,body,key)=>{
   if(url.endsWith("retrieval-workspace"))return confirmed?(finished?f.workspace:ready):{...ready,task:{...ready.task,version:1,state:"AWAITING_GOAL"},goal:null};
   if(url.endsWith("goals/confirm")){expect(body).toMatchObject({expectedTaskVersion:1,confirmed:true,queryObject:"patent",requestedFields:["TITLE"]});confirmed=true;return {};}
   if(url.endsWith("agent-runs")){expect(body).toEqual({expectedTaskVersion:2});expect(key).toBeTruthy();return {...f.run,checkpoint:{...f.run.checkpoint,phase:"OBSERVE",outcome:null}};}
   if(url.endsWith("/execute")){finished=true;return f.run;}
   if(url.endsWith("agent-runs/run"))return f.run;
   throw new Error("unexpected endpoint: "+url);
  });
  render(<ConsultationProductPage/>);await screen.findByText("确认条件");
  fireEvent.change(screen.getByLabelText("公开非保密检索目标／补充条件"),{target:{value:"目标"}});
  fireEvent.change(screen.getByLabelText("查询表达式（可修改）"),{target:{value:"patent"}});
  fireEvent.click(screen.getByText("确认条件"));await screen.findByText("Agent 任务运行");
  fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("观察当前输入");
  fireEvent.click(screen.getByText("执行至当前出口"));await screen.findByText("查看本次咨询结果与来源");
  fireEvent.click(screen.getByText("查看本次咨询结果与来源"));await screen.findByText(f.turn.output.answer!);expect(screen.getByText("原始来源")).toBeTruthy();
  expect(vi.mocked(api.request).mock.calls.map(c=>c[1])).toEqual(["/v2/consultation/tasks/task/retrieval-workspace","/v2/consultation/tasks/task/goals/confirm","/v2/consultation/tasks/task/retrieval-workspace","/v2/consultation/tasks/task/agent-runs","/v2/consultation/tasks/task/agent-runs/run/execute","/v2/consultation/tasks/task/agent-runs/run","/v2/consultation/tasks/task/retrieval-workspace"]);
  const raw=sessionStorage.getItem(sessionStorage.key(0)!)!;expect(JSON.parse(raw).state).toEqual({runId:"run",creation:null});expect(raw).not.toContain(f.turn.output.answer!);
 });
 it("unknown confirmation requires workspace reconciliation rather than blindly confirming again",async()=>{
  const f=context();vi.mocked(api.request).mockResolvedValueOnce({...f.workspace,goal:null,result:null,dialogue:[]}).mockRejectedValueOnce(new Error("confirmation response lost")).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]});
  render(<ConsultationProductPage/>);await screen.findByText("确认条件");
  fireEvent.change(screen.getByLabelText("公开非保密检索目标／补充条件"),{target:{value:"目标"}});fireEvent.change(screen.getByLabelText("查询表达式（可修改）"),{target:{value:"patent"}});
  fireEvent.click(screen.getByText("确认条件"));await screen.findByText("confirmation response lost");expect(screen.queryByText("确认条件")).toBeNull();
  fireEvent.click(screen.getByText("重新读取任务"));await screen.findByText("Agent 任务运行");expect(vi.mocked(api.request).mock.calls.map(c=>c[1])).toEqual(["/v2/consultation/tasks/task/retrieval-workspace","/v2/consultation/tasks/task/goals/confirm","/v2/consultation/tasks/task/retrieval-workspace"]);
 });
 it("unknown creation survives same-identity login with the original key and without automatic POST",async()=>{
  const f=context(),ready={...f.workspace,result:null,dialogue:[]};let creates=0;
  vi.mocked(api.request).mockImplementation(async(_token,url)=>{if(url.endsWith("retrieval-workspace"))return ready;if(++creates===1)throw new Error("create response lost");return f.run;});
  const ui=render(<ConsultationProductPage/>);await screen.findByText("启动当前目标");fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("create response lost");const original=vi.mocked(api.request).mock.calls[1];ui.unmount();
  vi.mocked(api.completeLogin).mockResolvedValue({token:identity()+"renewed",taskId:"task"});render(<ConsultationProductPage/>);await screen.findByText("对账同一创建请求");expect(creates).toBe(1);
  fireEvent.click(screen.getByText("对账同一创建请求"));await screen.findByText("查看本次咨询结果与来源");expect(vi.mocked(api.request).mock.calls[3].slice(1)).toEqual(original.slice(1));expect(creates).toBe(2);
 });
 it("unknown execute restores only a Run hint and requires GET before executing again",async()=>{
  const f=context(),ready={...f.workspace,result:null,dialogue:[]};
  vi.mocked(api.request).mockImplementation(async(_token,url)=>{if(url.endsWith("retrieval-workspace"))return ready;if(url.endsWith("/execute"))throw new Error("execute response lost");return {...f.run,checkpoint:{...f.run.checkpoint,phase:"ACT",outcome:null}};});
  const ui=render(<ConsultationProductPage/>);await screen.findByText("启动当前目标");fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("执行工具");fireEvent.click(screen.getByText("执行至当前出口"));await screen.findByText("execute response lost");ui.unmount();
  render(<ConsultationProductPage/>);await screen.findByText("Agent 任务运行");expect((screen.getByLabelText("运行编号") as HTMLInputElement).value).toBe("run");expect((screen.getByText("执行至当前出口") as HTMLButtonElement).disabled).toBe(true);
  const before=vi.mocked(api.request).mock.calls.length;fireEvent.click(screen.getByText("读取当前运行"));await screen.findByText("执行工具");expect(vi.mocked(api.request).mock.calls.slice(before).map(c=>c[1])).toEqual(["/v2/consultation/tasks/task/agent-runs/run"]);
 });
 it("a different current identity never reuses a pending creation",async()=>{
  const f=context();vi.mocked(api.request).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]}).mockRejectedValueOnce(new Error("lost")).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]});
  const ui=render(<ConsultationProductPage/>);await screen.findByText("启动当前目标");fireEvent.click(screen.getByText("启动当前目标"));await screen.findByText("lost");ui.unmount();
  vi.mocked(api.completeLogin).mockResolvedValue({token:identity("other"),taskId:"task"});render(<ConsultationProductPage/>);await screen.findByText("启动当前目标");expect(screen.queryByText("对账同一创建请求")).toBeNull();expect(vi.mocked(api.request).mock.calls).toHaveLength(3);
 });
});
