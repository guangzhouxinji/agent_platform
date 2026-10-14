import {cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import ConsultationProductPage from "./ConsultationProductPage";
import * as api from "./api";
import {fixture} from "./agentResultFixtures";
vi.mock("./api",async()=>({...await vi.importActual<typeof import("./api")>("./api"),completeLogin:vi.fn(),request:vi.fn(),forgetLogin:vi.fn(),login:vi.fn()}));
vi.mock("./prompt/PromptRevisionBrowser",()=>({default:({promptId,revisionId,onBack,onSessionExpired,onCopy}:{promptId:string;revisionId?:string;onBack:()=>void;onSessionExpired:()=>void;onCopy?:(source:typeof copySource)=>void})=><section><h2>所选提示词</h2><p>{promptId}/{revisionId}</p><button onClick={onBack}>返回咨询任务</button><button onClick={onSessionExpired}>模拟会话到期</button><button onClick={()=>onCopy?.(copySource)}>复制已读版本</button></section>}));
vi.mock("./trademark/ProductTrademarkWorkspace",()=>({default:({taskId,onBack}:{taskId:string;onBack:()=>void})=><section><h2>所选商标任务</h2><p>{taskId}</p><button onClick={onBack}>返回咨询任务</button></section>}));
vi.mock("./prompt/PromptCreateWorkspace",()=>({default:({workspaceId,onSessionExpired}:{workspaceId:string;onSessionExpired:()=>void})=><section><h2>创建草稿</h2><p>{workspaceId}</p><button onClick={onSessionExpired}>模拟创建会话到期</button></section>}));
vi.mock("./prompt/PromptSaveWorkspace",()=>({default:({workspaceId,promptId,onSessionExpired}:{workspaceId:string;promptId:string;onSessionExpired:()=>void})=><section><h2>保存新修订</h2><p>{workspaceId}/{promptId}</p><button onClick={onSessionExpired}>模拟保存会话到期</button></section>}));
vi.mock("./prompt/PromptCopyWorkspace",()=>({default:({token,source,onSessionExpired}:{token:string;source?:typeof copySource;onSessionExpired:()=>void})=><section><h2>{source?"复制确切版本":"查回原复制提交"}</h2><p>{token}</p>{source&&<p>{source.sourceWorkspaceId}/{source.sourcePromptId}/{source.sourceRevisionId}/{source.sourceContentSha}</p>}<button onClick={onSessionExpired}>模拟复制会话到期</button></section>}));
const promptId="00000000-0000-4000-8000-000000000001",revisionId="00000000-0000-4000-8000-000000000002";
const copySource={sourceWorkspaceId:revisionId,sourcePromptId:promptId,sourceRevisionId:revisionId,sourceContentSha:"a".repeat(64)};
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();history.replaceState(null,"","/app/consultation");});
afterEach(()=>{cleanup();history.replaceState(null,"","/");});
it("moves the authenticated exact READ selection into COPY without losing its token or reading a Task",async()=>{
  vi.mocked(api.completeLogin).mockResolvedValue({token:"same-session",taskId:"",destination:{view:"prompt",promptId,revisionId}});
  render(<ConsultationProductPage/>);fireEvent.click(await screen.findByRole("button",{name:"复制已读版本"}));
  await screen.findByRole("heading",{name:"复制确切版本"});expect(screen.getByText("same-session")).toBeInTheDocument();
  expect(screen.getByText(Object.values(copySource).join("/"))).toBeInTheDocument();
  expect(api.readConsultationDestination(location.search)).toEqual({view:"prompt-copy",...copySource});
  expect(api.request).not.toHaveBeenCalled();expect(api.login).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button",{name:"模拟复制会话到期"}));await screen.findByRole("button",{name:"登录并打开提示词"});
  expect(screen.queryByText("same-session")).toBeNull();
});
it("opens COPY recovery with no Task, source revision or successful READ",async()=>{
  history.replaceState(null,"","/app/consultation?view=prompt-copy");vi.mocked(api.completeLogin).mockResolvedValue(null);
  const first=render(<ConsultationProductPage/>);fireEvent.click(screen.getByRole("button",{name:"登录并打开提示词"}));
  await waitFor(()=>expect(api.login).toHaveBeenCalledWith("",{view:"prompt-copy"}));first.unmount();
  vi.mocked(api.completeLogin).mockResolvedValue({token:"recovery-session",taskId:"",destination:{view:"prompt-copy"}});
  render(<ConsultationProductPage/>);await screen.findByRole("heading",{name:"查回原复制提交"});
  expect(screen.queryByRole("heading",{name:"复制确切版本"})).toBeNull();expect(api.request).not.toHaveBeenCalled();
});
it("opens the SAVE-only target after login without loading a Task or an old draft",async()=>{
  vi.mocked(api.completeLogin).mockResolvedValue({token:"session",taskId:"",destination:{view:"prompt-save",workspaceId:revisionId,promptId}});
  render(<ConsultationProductPage/>);await screen.findByRole("heading",{name:"保存新修订"});
  expect(screen.getByText(revisionId+"/"+promptId)).toBeInTheDocument();expect(api.request).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button",{name:"模拟保存会话到期"}));
  await screen.findByRole("button",{name:"登录并打开提示词"});expect(screen.queryByRole("heading",{name:"保存新修订"})).toBeNull();
});
it("preserves workspace and prompt together for normal SAVE login",async()=>{
  history.replaceState(null,"","/app/consultation?view=prompt-save&workspace="+revisionId+"&prompt="+promptId);
  vi.mocked(api.completeLogin).mockResolvedValue(null);render(<ConsultationProductPage/>);
  fireEvent.click(screen.getByRole("button",{name:"登录并打开提示词"}));
  await waitFor(()=>expect(api.login).toHaveBeenCalledWith("",{view:"prompt-save",workspaceId:revisionId,promptId}));
  expect(api.request).not.toHaveBeenCalled();
});
it("opens the creating workspace after login without reading a Task or draft",async()=>{
  vi.mocked(api.completeLogin).mockResolvedValue({token:"session",taskId:"",destination:{view:"prompt-create",workspaceId:promptId}});
  render(<ConsultationProductPage/>);await screen.findByRole("heading",{name:"创建草稿"});
  expect(screen.getByText(promptId)).toBeInTheDocument();expect(api.request).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button",{name:"模拟创建会话到期"}));
  await screen.findByRole("button",{name:"登录并打开提示词"});expect(screen.queryByRole("heading",{name:"创建草稿"})).toBeNull();
});
it("preserves the selected workspace for the normal login entry",async()=>{
  history.replaceState(null,"","/app/consultation?view=prompt-create&workspace="+promptId);
  vi.mocked(api.completeLogin).mockResolvedValue(null);render(<ConsultationProductPage/>);
  fireEvent.click(screen.getByRole("button",{name:"登录并打开提示词"}));
  await waitFor(()=>expect(api.login).toHaveBeenCalledWith("",{view:"prompt-create",workspaceId:promptId}));
  expect(api.request).not.toHaveBeenCalled();
});
it("opens a prompt directly from the bound login destination without a patent Task read",async()=>{
  vi.mocked(api.completeLogin).mockResolvedValue({token:"session",taskId:"",destination:{view:"prompt",promptId,revisionId}});
  render(<ConsultationProductPage/>);await screen.findByRole("heading",{name:"所选提示词"});
  expect(screen.getByText(promptId+"/"+revisionId)).toBeInTheDocument();expect(api.request).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button",{name:"退出登录"}));
  await screen.findByRole("button",{name:"登录并打开提示词"});expect(screen.queryByText(promptId+"/"+revisionId)).toBeNull();
});
it("opens a trademark-only Task without requiring retrieval-workspace, then reads it only on return",async()=>{
  vi.mocked(api.completeLogin).mockResolvedValue({token:"session",taskId:"task",destination:{view:"trademarks"}});
  vi.mocked(api.request).mockResolvedValue(fixture().workspace);
  render(<ConsultationProductPage/>);await screen.findByRole("heading",{name:"所选商标任务"});expect(api.request).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button",{name:"返回咨询任务"}));
  await waitFor(()=>expect(api.request).toHaveBeenCalledWith("session","/v2/consultation/tasks/task/retrieval-workspace"));
});
it("preserves the prompt destination through login and clears it on session expiry",async()=>{
  history.replaceState(null,"","/app/consultation?prompt="+promptId+"&revision="+revisionId);
  vi.mocked(api.completeLogin).mockResolvedValue(null);
  const first=render(<ConsultationProductPage/>);fireEvent.click(screen.getByRole("button",{name:"登录并打开提示词"}));
  await waitFor(()=>expect(api.login).toHaveBeenCalledWith("",{view:"prompt",promptId,revisionId}));first.unmount();
  vi.mocked(api.completeLogin).mockResolvedValue({token:"session",taskId:"",destination:{view:"prompt",promptId}});
  render(<ConsultationProductPage/>);await screen.findByRole("heading",{name:"所选提示词"});
  fireEvent.click(screen.getByRole("button",{name:"模拟会话到期"}));await screen.findByRole("button",{name:"登录并打开提示词"});
  expect(screen.queryByRole("heading",{name:"所选提示词"})).toBeNull();expect(api.forgetLogin).toHaveBeenCalled();
});
it("rejects malformed deep links without mounting resource readers",async()=>{
  history.replaceState(null,"","/app/consultation?prompt=bad");vi.mocked(api.completeLogin).mockResolvedValue(null);
  render(<ConsultationProductPage/>);await screen.findByText(/页面地址不合法/);
  expect(screen.queryByRole("heading",{name:"所选提示词"})).toBeNull();expect(api.request).not.toHaveBeenCalled();
});
it("offers retry at the verified resource after callback token exchange fails",async()=>{
  history.replaceState(null,"","/app/consultation?code=code&state=state");
  vi.mocked(api.completeLogin).mockRejectedValue(new api.ConsultationLoginError("",{view:"prompt",promptId,revisionId}));
  render(<ConsultationProductPage/>);const retry=await screen.findByRole("button",{name:"登录并打开提示词"});expect(retry).toBeEnabled();
  fireEvent.click(retry);await waitFor(()=>expect(api.login).toHaveBeenCalledWith("",{view:"prompt",promptId,revisionId}));
  expect(api.request).not.toHaveBeenCalled();
});
it("returning during a pending login clears its busy state and ignores a late rejection",async()=>{
  history.replaceState(null,"","/app/consultation?task=task&prompt="+promptId);
  vi.mocked(api.completeLogin).mockResolvedValue(null);let reject!:(error:Error)=>void;
  vi.mocked(api.login).mockImplementation(()=>new Promise<void>((_resolve,no)=>{reject=no;}));
  render(<ConsultationProductPage/>);fireEvent.click(screen.getByRole("button",{name:"登录并打开提示词"}));
  fireEvent.click(screen.getByRole("button",{name:"返回咨询任务"}));expect(screen.getByRole("button",{name:"登录并打开任务"})).toBeEnabled();
  reject(new Error("late rejection"));await waitFor(()=>expect(screen.queryByText("late rejection")).toBeNull());
  expect(screen.getByRole("button",{name:"登录并打开任务"})).toBeEnabled();
});
