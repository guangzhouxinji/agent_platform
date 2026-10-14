import {beforeEach,describe,expect,it,vi} from "vitest";
import {consultationAgentRecovery} from "./agentRecovery";
import {fixture} from "./agentResultFixtures";
const token=(sub="member")=>"header."+btoa(JSON.stringify({iss:"https://product.example",sub}))+".signature";
function context(){const f=fixture();f.workspace.task.expiresAt="2099-01-01T02:00:00Z";f.workspace.goal!.expiresAt="2099-01-01T01:00:00Z";return f.workspace;}
const pending={runId:"",creation:{key:"original-key",body:{expectedTaskVersion:2}}};
beforeEach(()=>sessionStorage.clear());
describe("consultation receipt hints (not authorization)",()=>{
 it("stores exact pending key/version without token, goal, result or source data",()=>{
  const workspace=context(),port=consultationAgentRecovery(token(),workspace)!;port.save(pending);
  expect(port.load()).toEqual(pending);const raw=sessionStorage.getItem(sessionStorage.key(0)!)!;
  expect(raw).not.toContain(token());expect(raw).not.toContain(workspace.goal!.messageText);expect(raw).not.toContain("resultSha");
  expect(JSON.parse(raw).state.creation.body).toEqual({expectedTaskVersion:2});
 });
 it("isolates another user, Task, Input and version but survives a refreshed token for the same identity",()=>{
  const w=context();consultationAgentRecovery(token(),w)!.save(pending);
  expect(consultationAgentRecovery(token("other"),w)!.load()).toBeNull();
  expect(consultationAgentRecovery(token(),{...w,task:{...w.task,id:"other-task"}})!.load()).toBeNull();
  expect(consultationAgentRecovery(token(),{...w,goal:{...w.goal!,inputId:"other-input"}})!.load()).toBeNull();
  expect(consultationAgentRecovery(token(),{...w,task:{...w.task,version:3}})!.load()).toBeNull();
  expect(consultationAgentRecovery(token()+"renewed",w)!.load()).toEqual(pending);
 });
 it("caps hints at Goal expiry and never permits a save after that point",()=>{
  const w=context();let clock=Date.parse("2099-01-01T00:00:00Z");const port=consultationAgentRecovery(token(),w,sessionStorage,()=>clock)!;
  port.save(pending);clock=Date.parse(w.goal!.expiresAt!);expect(port.load()).toBeNull();expect(sessionStorage.length).toBe(0);expect(()=>port.save(pending)).toThrow(/不可核验/);
 });
 it("uses the shorter Task expiry instead of extending it to Goal",()=>{
  const w=context();w.task.expiresAt="2099-01-01T00:30:00Z";const port=consultationAgentRecovery(token(),w)!;port.save(pending);
  expect(JSON.parse(sessionStorage.getItem(sessionStorage.key(0)!)!).expires).toBe(Date.parse(w.task.expiresAt));
 });
 it("rejects extra business input, a modified version, malformed record and expanded expiry",()=>{
  const w=context(),port=consultationAgentRecovery(token(),w)!;
  expect(()=>port.save({...pending,creation:{key:"original-key",body:{expectedTaskVersion:2,messageText:"not permitted"}}})).toThrow();
  expect(()=>port.save({...pending,creation:{key:"original-key",body:{expectedTaskVersion:3}}})).toThrow();
  port.save(pending);const key=sessionStorage.key(0)!;const record=JSON.parse(sessionStorage.getItem(key)!);record.expires++;sessionStorage.setItem(key,JSON.stringify(record));expect(()=>port.load()).toThrow();
  sessionStorage.setItem(key,"broken");expect(()=>port.load()).toThrow();
 });
 it("does not enable persistence without current identity and actual Goal deadline",()=>{
  const w=context();expect(consultationAgentRecovery("invalid",w)).toBeUndefined();delete w.goal!.expiresAt;expect(consultationAgentRecovery(token(),w)).toBeUndefined();
 });
 it("storage failure propagates rather than claiming the request key was retained",()=>{
  const storage={getItem:vi.fn(()=>{throw new Error("storage unavailable")}),setItem:vi.fn(()=>{throw new Error("storage unavailable")}),removeItem:vi.fn()} as unknown as Storage;
  const port=consultationAgentRecovery(token(),context(),storage)!;expect(()=>port.save(pending)).toThrow("storage unavailable");expect(()=>port.load()).toThrow("storage unavailable");
 });
 it("an unavailable storage getter fails closed instead of crashing page construction",()=>{
  const getter=vi.spyOn(window,"sessionStorage","get").mockImplementation(()=>{throw new Error("policy denies storage")});
  try {const port=consultationAgentRecovery(token(),context())!;expect(port).toBeDefined();expect(()=>port.load()).toThrow(/存储不可用/);expect(()=>port.save(pending)).toThrow(/存储不可用/);}finally{getter.mockRestore();}
 });
 it("unexpected top-level payload is rejected, never treated as authority",()=>{
  const port=consultationAgentRecovery(token(),context())!;port.save(pending);const key=sessionStorage.key(0)!;
  const record=JSON.parse(sessionStorage.getItem(key)!);record.token="unexpected";sessionStorage.setItem(key,JSON.stringify(record));expect(()=>port.load()).toThrow(/不可核验/);
 });

});
