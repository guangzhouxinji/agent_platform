import { cleanup,fireEvent,render,screen,waitFor } from "@testing-library/react";
import { afterEach,beforeEach,describe,it,expect,vi } from "vitest";
import ConsultationProductPage from "./ConsultationProductPage";
import * as api from "./api";
import { fixture } from "./agentResultFixtures";
vi.mock("./api",async()=>{const actual=await vi.importActual<typeof import("./api")>("./api");return {...actual,completeLogin:vi.fn(),request:vi.fn(),forgetLogin:vi.fn()}});
afterEach(cleanup);
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();vi.mocked(api.completeLogin).mockResolvedValue({token:'e30.'+btoa(JSON.stringify({iss:'https://identity.example.org',sub:'member'}))+'.signature',taskId:"task"})});
async function openRun(){
 render(<ConsultationProductPage/>);await screen.findByText("Agent 任务运行");
 fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"run"}});fireEvent.click(screen.getByText("读取当前运行"));
 await screen.findByText("查看本次咨询结果与来源");
}
describe("consultation real page result wiring (API fixtures, not product acceptance)",()=>{
 it("re-reads the same run and current workspace without generating a second answer",async()=>{
  const f=fixture();let reads=0;
  vi.mocked(api.request).mockImplementation(async(_token,url)=>url.includes("agent-runs")?f.run:++reads===1?{...f.workspace,result:null,dialogue:[]}:f.workspace);
  await openRun();expect(screen.queryByText(f.turn.output.answer!)).toBeNull();
  fireEvent.click(screen.getByText("查看本次咨询结果与来源"));await screen.findByText(f.turn.output.answer!);expect(screen.getByText("真实检索结果")).toBeTruthy();expect(screen.getByText("原始来源")).toBeTruthy();
  expect(vi.mocked(api.request).mock.calls.map(c=>c[1])).toEqual(["/v2/consultation/tasks/task/retrieval-workspace","/v2/consultation/tasks/task/agent-runs/run","/v2/consultation/tasks/task/agent-runs/run","/v2/consultation/tasks/task/retrieval-workspace"]);
 });
 it("clears an adopted answer when a subsequent read is denied",async()=>{
  const f=fixture();vi.mocked(api.request).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]}).mockResolvedValueOnce(f.run).mockResolvedValueOnce(f.run).mockResolvedValueOnce(f.workspace).mockRejectedValueOnce(new api.ConsultationError(403));
  await openRun();fireEvent.click(screen.getByText("查看本次咨询结果与来源"));await screen.findByText(f.turn.output.answer!);
  fireEvent.click(screen.getByText("读取当前运行"));expect(screen.queryByText(f.turn.output.answer!)).toBeNull();await screen.findByText(/当前会话或任务权限已不可用/);expect(screen.queryByText("真实检索结果")).toBeNull();
 });
 it("returns to formal login after an Agent session is rejected",async()=>{
  const f=fixture();vi.mocked(api.request).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]}).mockRejectedValueOnce(new api.ConsultationError(401));
  render(<ConsultationProductPage/>);await screen.findByText("Agent 任务运行");fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"run"}});fireEvent.click(screen.getByText("读取当前运行"));
  await screen.findByText("登录并打开任务");expect(api.forgetLogin).toHaveBeenCalled();expect(screen.queryByText("真实检索结果")).toBeNull();
 });
 it("rejects a changed live run version without showing its answer",async()=>{
  const f=fixture();vi.mocked(api.request).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]}).mockResolvedValueOnce(f.run).mockResolvedValueOnce({...f.run,checkpoint:{...f.run.checkpoint,version:13}}).mockResolvedValueOnce(f.workspace);
  await openRun();fireEvent.click(screen.getByText("查看本次咨询结果与来源"));await screen.findByText(/运行、目标或结果已变化/);expect(screen.queryByText(f.turn.output.answer!)).toBeNull();
 });
 it("preserves authorized current workspace results before any Agent request",async()=>{
  const f=fixture();vi.mocked(api.request).mockResolvedValue(f.workspace);
  render(<ConsultationProductPage/>);await screen.findByText(f.turn.output.answer!);expect(screen.getByText("真实检索结果")).toBeTruthy();
 });
 it("clears an adopted handoff when the query is edited",async()=>{
  const f=fixture();vi.mocked(api.request).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]}).mockResolvedValueOnce(f.run).mockResolvedValueOnce(f.run).mockResolvedValueOnce(f.workspace);
  await openRun();fireEvent.click(screen.getByText("查看本次咨询结果与来源"));await screen.findByText(f.turn.output.answer!);
  fireEvent.change(screen.getByLabelText("查询表达式（可修改）"),{target:{value:"new query"}});expect(screen.queryByText(f.turn.output.answer!)).toBeNull();expect(screen.queryByText("查看本次咨询结果与来源")).toBeNull();
 });
 it("ignores a workspace response arriving after unmount",async()=>{
  const f=fixture();let finish!:(value:typeof f.workspace)=>void;
  vi.mocked(api.request).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]}).mockResolvedValueOnce(f.run).mockResolvedValueOnce(f.run).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}));
  await openRun();fireEvent.click(screen.getByText("查看本次咨询结果与来源"));await waitFor(()=>expect(finish).toBeTruthy());
  // Unmount invalidates the active page request; this is not a browser acceptance test.
  cleanup();finish(f.workspace);await waitFor(()=>expect(screen.queryByText(f.turn.output.answer!)).toBeNull());
 });
 it("rejects another Task's workspace at initial login without displaying its answer or sources",async()=>{
  const f=fixture();vi.mocked(api.request).mockResolvedValue({...f.workspace,task:{...f.workspace.task,id:"other-task"}});
  render(<ConsultationProductPage/>);await screen.findByText(/任务响应与当前任务编号不一致/);
  expect(screen.queryByText(f.turn.output.answer!)).toBeNull();expect(screen.queryByText("Agent 任务运行")).toBeNull();expect(screen.queryByText("原始来源")).toBeNull();
 });
 it("does not offer result handoff for a wrong Task returned by the requested Run",async()=>{
  const f=fixture();vi.mocked(api.request).mockResolvedValueOnce({...f.workspace,result:null,dialogue:[]}).mockResolvedValueOnce({...f.run,checkpoint:{...f.run.checkpoint,task:{...f.run.checkpoint.task,id:"other-task"}}});
  render(<ConsultationProductPage/>);await screen.findByText("Agent 任务运行");fireEvent.change(screen.getByLabelText("运行编号"),{target:{value:"run"}});fireEvent.click(screen.getByText("读取当前运行"));
  await screen.findByText(/运行响应与当前任务或请求的运行编号不一致/);expect(screen.queryByText("查看本次咨询结果与来源")).toBeNull();expect(screen.queryByText(f.turn.output.answer!)).toBeNull();
 });
 it("keeps a missing or expired Task read as unavailable without restoring cached results",async()=>{
  const f=fixture();vi.mocked(api.request).mockResolvedValueOnce(f.workspace).mockRejectedValueOnce(new api.ConsultationError(404));
  render(<ConsultationProductPage/>);await screen.findByText(f.turn.output.answer!);fireEvent.click(screen.getByText("重新读取任务"));
  await screen.findByText(/任务或当前权限已不可用/);expect(screen.queryByText(f.turn.output.answer!)).toBeNull();expect(screen.queryByText("Agent 任务运行")).toBeNull();
 });

});
