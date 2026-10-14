import {afterEach,describe,expect,it,vi} from "vitest";
import {ConsultationError,request} from "./api";
afterEach(()=>vi.unstubAllGlobals());
describe("consultation transport contract (no real business calls)",()=>{
 it("GET revalidates authorization without cache or a submission key",async()=>{
  const fetcher=vi.fn().mockResolvedValue(new Response(JSON.stringify({ok:true}),{status:200}));vi.stubGlobal("fetch",fetcher);
  await request("current-token","/v2/consultation/tasks/task/agent-runs/run");
  expect(fetcher.mock.calls[0][1]).toEqual({method:"GET",headers:{Authorization:"Bearer current-token",Accept:"application/json"},body:undefined,cache:"no-store"});
 });
 it("POST preserves the exact original reconciliation key and body",async()=>{
  const fetcher=vi.fn().mockResolvedValue(new Response("{}",{status:200}));vi.stubGlobal("fetch",fetcher);
  await request("current-token","/v2/consultation/tasks/task/agent-runs",{expectedTaskVersion:2},"original-key");
  expect(fetcher.mock.calls[0][1]).toMatchObject({method:"POST",cache:"no-store",headers:{"Idempotency-Key":"original-key"},body:'{"expectedTaskVersion":2}'});
 });
 it("connection loss and unreadable success are unknown submissions, not safe creation failures",async()=>{
  vi.stubGlobal("fetch",vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(new Response("incomplete",{status:200})));
  for(let n=0;n<2;n++)await expect(request("token","/v2/consultation/tasks/task/agent-runs",{},"same-key")).rejects.toMatchObject({status:0});
 });
 it("permission and unavailable source responses remain explicit failures without retry",async()=>{
  const fetcher=vi.fn().mockResolvedValueOnce(new Response("",{status:403})).mockResolvedValueOnce(new Response("",{status:503}));vi.stubGlobal("fetch",fetcher);
  await expect(request("token","/v2/consultation/tasks/task/agent-runs/run")).rejects.toBeInstanceOf(ConsultationError);
  await expect(request("token","/v2/consultation/tasks/task/agent-runs/run")).rejects.toMatchObject({status:503});expect(fetcher).toHaveBeenCalledTimes(2);
 });
 it("rejects foreign paths before network access",async()=>{
  const fetcher=vi.fn();vi.stubGlobal("fetch",fetcher);await expect(request("token","https://foreign.example/task")).rejects.toThrow(/非法/);expect(fetcher).not.toHaveBeenCalled();
 });
});
