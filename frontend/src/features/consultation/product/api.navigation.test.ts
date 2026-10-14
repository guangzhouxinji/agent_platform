import {afterEach,beforeEach,describe,expect,it,vi} from "vitest";
import {completeLogin,ConsultationLoginError,consultationLocation,forgetLogin,readConsultationDestination} from "./api";

const promptId="00000000-0000-4000-8000-000000000001",revisionId="00000000-0000-4000-8000-000000000002";
beforeEach(()=>{forgetLogin();sessionStorage.clear();vi.stubEnv("VITE_CONSULTATION_CLIENT_ID","consultation");
  vi.stubGlobal("location",{origin:"https://consultation.example",search:"?code=code&state=bound-state"});
  vi.stubGlobal("history",{replaceState:vi.fn()});
  vi.stubGlobal("fetch",vi.fn().mockResolvedValue(new Response(JSON.stringify({access_token:"signed-session"}))));
});
afterEach(()=>{forgetLogin();sessionStorage.clear();vi.unstubAllGlobals();vi.unstubAllEnvs();});
function record(destination:unknown,state="bound-state"){
  sessionStorage.setItem("consultation-pkce",JSON.stringify({verifier:"original-verifier",state,taskId:"task",destination}));
}
describe("formal resource navigation binds the initiating PKCE record",()=>{
  it("restores exact prompt and revision without accepting callback resource parameters",async()=>{
    record({view:"prompt",promptId,revisionId});
    location.search="?code=code&state=bound-state&view=trademarks&prompt=attacker";
    const session=await completeLogin();
    expect(session).toEqual({token:"signed-session",taskId:"task",destination:{view:"prompt",promptId,revisionId}});
    expect(history.replaceState).toHaveBeenLastCalledWith(null,"",consultationLocation("task",session!.destination));
    expect(sessionStorage.getItem("consultation-pkce")).toBeNull();
    expect(String(vi.mocked(fetch).mock.calls[0][1]?.body)).toContain("code_verifier=original-verifier");
  });
  it("restores the trademark destination without granting any new scope",async()=>{
    record({view:"trademarks"});expect((await completeLogin())?.destination).toEqual({view:"trademarks"});
    expect(String(vi.mocked(fetch).mock.calls[0][1]?.body)).not.toContain("scope");
  });
  it("binds the creating workspace to PKCE without taking a callback workspace or granting READ",async()=>{
    record({view:"prompt-create",workspaceId:promptId});
    location.search="?code=code&state=bound-state&view=prompt-create&workspace="+revisionId;
    const session=await completeLogin();
    expect(session?.destination).toEqual({view:"prompt-create",workspaceId:promptId});
    expect(history.replaceState).toHaveBeenLastCalledWith(null,"","https://consultation.example/app/consultation?task=task&view=prompt-create&workspace="+promptId);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem("consultation-pkce")).toBeNull();
  });
  it("binds both SAVE coordinates to the original PKCE destination",async()=>{
    record({view:"prompt-save",workspaceId:revisionId,promptId});
    location.search="?code=code&state=bound-state&view=prompt-save&workspace="+promptId+"&prompt="+revisionId;
    const session=await completeLogin();
    expect(session?.destination).toEqual({view:"prompt-save",workspaceId:revisionId,promptId});
    expect(history.replaceState).toHaveBeenLastCalledWith(null,"","https://consultation.example/app/consultation?task=task&view=prompt-save&workspace="+revisionId+"&prompt="+promptId);
    expect(fetch).toHaveBeenCalledTimes(1);expect(sessionStorage.getItem("consultation-pkce")).toBeNull();
  });
  it.each([{view:"prompt-save",workspaceId:promptId},{view:"prompt-save",workspaceId:"bad",promptId},
    {view:"prompt-save",workspaceId:revisionId,promptId,revisionId}])("rejects malformed SAVE login destinations: %j",async destination=>{
    record(destination);await expect(completeLogin()).rejects.toThrow(/地址/);expect(fetch).not.toHaveBeenCalled();
  });
  it.each([{view:"prompt-create",workspaceId:"bad"},{view:"prompt-create",workspaceId:promptId,grant:"CREATE"},
    {view:"prompt-create",workspaceId:promptId,promptId}])("rejects malformed CREATE destinations before token exchange: %j",async destination=>{
    record(destination);await expect(completeLogin()).rejects.toThrow(/地址/);expect(fetch).not.toHaveBeenCalled();
  });
  it.each(["network","denied","missing-token"])("retains only verified resource coordinates when login fails: %s",async failure=>{
    record({view:"prompt",promptId,revisionId});
    if(failure==="network")vi.mocked(fetch).mockRejectedValueOnce(new Error("offline"));
    if(failure==="denied")location.search="?error=access_denied&state=bound-state";
    if(failure==="missing-token")vi.mocked(fetch).mockResolvedValueOnce(new Response("{}"));
    await expect(completeLogin()).rejects.toMatchObject({taskId:"task",destination:{view:"prompt",promptId,revisionId}});
    await expect(completeLogin()).rejects.toBeInstanceOf(ConsultationLoginError);
    if(failure==="denied")expect(fetch).not.toHaveBeenCalled();
  });
  it("does not use callback destinations when the original login was a task login",async()=>{
    record(undefined);location.search="?code=code&state=bound-state&view=trademarks";
    expect(await completeLogin()).toEqual({token:"signed-session",taskId:"task"});
  });
  it("rejects wrong state before restoring the resource or requesting a token",async()=>{
    record({view:"prompt",promptId},"wrong-state");
    await expect(completeLogin()).rejects.toThrow(/登录状态/);expect(fetch).not.toHaveBeenCalled();
    expect(history.replaceState).toHaveBeenCalledExactlyOnceWith(null,"","https://consultation.example/app/consultation");
  });
  it.each([{view:"prompt",promptId:"../private"},{view:"prompt",promptId,revisionId:"invalid"},{view:"trademarks",grant:"READ"}])("rejects malformed stored navigation before token exchange: %j",async value=>{
    record(value);await expect(completeLogin()).rejects.toThrow(/地址/);expect(fetch).not.toHaveBeenCalled();
  });
  it("parses exact deep links and rejects ambiguous or partial resource coordinates",()=>{
    expect(readConsultationDestination("?prompt="+promptId+"&revision="+revisionId)).toEqual({view:"prompt",promptId,revisionId});
    expect(readConsultationDestination("?task=t&view=trademarks")).toEqual({view:"trademarks"});
    for(const query of ["?revision="+revisionId,"?view=prompt","?view=trademarks&prompt="+promptId,"?prompt="+promptId+"&prompt="+promptId,"?view=unknown"])
      expect(()=>readConsultationDestination(query)).toThrow(/地址/);
  });
  it("parses the exact workspace CREATE link and rejects mixed or duplicate coordinates",()=>{
    const query="?view=prompt-create&workspace="+promptId;
    expect(readConsultationDestination(query)).toEqual({view:"prompt-create",workspaceId:promptId});
    for(const invalid of ["?view=prompt-create","?workspace="+promptId,query+"&workspace="+revisionId,
      query+"&prompt="+revisionId,query+"&revision="+revisionId,query+"&view=prompt","?view=trademarks&workspace="+promptId])
      expect(()=>readConsultationDestination(invalid)).toThrow(/地址/);
  });
  it("parses exact SAVE links and refuses incomplete, mixed and duplicate coordinates",()=>{
    const query="?view=prompt-save&workspace="+revisionId+"&prompt="+promptId;
    expect(readConsultationDestination(query)).toEqual({view:"prompt-save",workspaceId:revisionId,promptId});
    for(const invalid of ["?view=prompt-save","?view=prompt-save&workspace="+revisionId,
      "?view=prompt-save&prompt="+promptId,query+"&workspace="+promptId,query+"&prompt="+revisionId,
      query+"&revision="+revisionId,query+"&view=prompt-create"])
      expect(()=>readConsultationDestination(invalid)).toThrow(/地址/);
  });
  it.each([false,true])("binds COPY selection or recovery-only navigation to PKCE: %s",async exact=>{
    const destination=exact?{view:"prompt-copy" as const,sourceWorkspaceId:promptId,sourcePromptId:revisionId,sourceRevisionId:promptId,sourceContentSha:"a".repeat(64)}:{view:"prompt-copy" as const};
    record(destination);location.search="?code=code&state=bound-state&view=prompt-copy&sourcePrompt="+promptId;
    const session=await completeLogin();expect(session?.destination).toEqual(destination);
    expect(readConsultationDestination(new URL(consultationLocation("task",destination)).search)).toEqual(destination);
    expect(fetch).toHaveBeenCalledTimes(1);expect(sessionStorage.getItem("consultation-pkce")).toBeNull();
  });
  it("normalizes complete COPY coordinates and rejects mixed, duplicate and partial routes",()=>{
    const query="?view=prompt-copy&sourceWorkspace="+promptId+"&sourcePrompt="+revisionId+"&sourceRevision="+promptId+"&sourceContentSha="+"A".repeat(64);
    expect(readConsultationDestination(query)).toEqual({view:"prompt-copy",sourceWorkspaceId:promptId,sourcePromptId:revisionId,sourceRevisionId:promptId,sourceContentSha:"a".repeat(64)});
    expect(readConsultationDestination("?view=prompt-copy")).toEqual({view:"prompt-copy"});
    for(const invalid of ["?view=prompt-copy&sourceWorkspace="+promptId,query+"&sourcePrompt="+revisionId,
      query+"&sourceContentSha="+"a".repeat(64),query+"&workspace="+promptId,query+"&revision="+revisionId,
      query+"&prompt="+promptId,query+"&unknown=x",query+"&task=a&task=b",query.replace("view=prompt-copy","view=prompt"),
      query.replace("A".repeat(64),"bad"),"?sourceWorkspace="+promptId])expect(()=>readConsultationDestination(invalid)).toThrow(/地址/);
  });
  it.each([{view:"prompt-copy",sourceWorkspaceId:promptId},{view:"prompt-copy",permission:"COPY"},
    {view:"prompt-copy",sourceWorkspaceId:promptId,sourcePromptId:revisionId,sourceRevisionId:promptId,sourceContentSha:"bad"}])("rejects invalid stored COPY selection before token exchange: %j",async destination=>{
    record(destination);await expect(completeLogin()).rejects.toThrow(/地址/);expect(fetch).not.toHaveBeenCalled();
  });
});
