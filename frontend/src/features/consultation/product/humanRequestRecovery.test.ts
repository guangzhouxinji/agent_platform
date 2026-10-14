import {beforeEach,describe,expect,it,vi} from "vitest";
import {humanRequestRecovery,type HumanRecoveryRecord} from "./humanRequestRecovery";
const token=(sub="member")=>"header."+btoa(JSON.stringify({iss:"https://product.example",sub}))+".signature";
const start=Date.parse("2026-10-13T01:00:00Z"),until=new Date(start+86400000).toISOString();
const record:HumanRecoveryRecord={key:"original-key",requestSha:"a".repeat(64),createdAt:start,expiresAt:start+10000};
beforeEach(()=>sessionStorage.clear());
describe("bounded body-free human request recovery",()=>{
  it("stores only bound minimum record and refreshed tokens restore without changing expiry",()=>{
    let now=start;const port=humanRequestRecovery(token(),"task",until,sessionStorage,()=>now);port.save(record);now++;
    expect(port.load()).toEqual(record);expect(humanRequestRecovery(token()+"x","task",until,sessionStorage,()=>now).load()).toEqual(record);
    const raw=sessionStorage.getItem(sessionStorage.key(0)!)!;expect(raw).not.toContain(token());expect(raw).not.toContain("reason");
    expect(Object.keys(JSON.parse(raw)).sort()).toEqual(["binding","record"]);
    expect(humanRequestRecovery(token("other"),"task",until,sessionStorage,()=>now).load()).toBeNull();
    expect(humanRequestRecovery(token(),"other",until,sessionStorage,()=>now).load()).toBeNull();
  });
  it("cannot overwrite an unresolved key or extend an existing expiry",()=>{
    const port=humanRequestRecovery(token(),"task",until,sessionStorage,()=>start);port.save(record);port.save(record);
    expect(()=>port.save({...record,key:"other"})).toThrow();expect(()=>port.save({...record,expiresAt:record.expiresAt+1})).toThrow();
    expect(port.load()).toEqual(record);port.clear();port.save({...record,key:"other"});expect(port.load()?.key).toBe("other");
  });
  it("valid expiry removes hints, while corrupt state remains fail-closed across refresh",()=>{
    let now=start;const port=humanRequestRecovery(token(),"task",until,sessionStorage,()=>now);port.save(record);
    const key=sessionStorage.key(0)!;sessionStorage.setItem(key,"bad json");expect(()=>port.load()).toThrow();
    expect(sessionStorage.getItem(key)).toBe("bad json");
    expect(()=>humanRequestRecovery(token(),"task",until,sessionStorage,()=>now).save(record)).toThrow();
    port.clear();port.save(record);now=record.expiresAt;expect(port.load()).toBeNull();expect(sessionStorage.length).toBe(0);
  });
  it("rejects extension beyond task/180-day bounds and unexpected stored fields",()=>{
    const port=humanRequestRecovery(token(),"task",until,sessionStorage,()=>start);
    expect(()=>port.save({...record,expiresAt:Date.parse(until)+1})).toThrow();
    const long=humanRequestRecovery(token(),"task",new Date(start+400*86400000).toISOString(),sessionStorage,()=>start);
    expect(()=>long.save({...record,expiresAt:start+180*86400000+1})).toThrow();
    port.save(record);const key=sessionStorage.key(0)!,raw=JSON.parse(sessionStorage.getItem(key)!);raw.record.reason="never permitted";
    sessionStorage.setItem(key,JSON.stringify(raw));expect(()=>port.load()).toThrow();expect(sessionStorage.getItem(key)).toContain("never permitted");
  });
  it("storage denial and silent failed writes cannot report a saved recovery record",()=>{
    const denied={getItem:vi.fn(()=>{throw Error("denied")})} as unknown as Storage;
    expect(()=>humanRequestRecovery(token(),"task",until,denied,()=>start).save(record)).toThrow();
    const silent={getItem:vi.fn(()=>null),setItem:vi.fn()} as unknown as Storage;
    expect(()=>humanRequestRecovery(token(),"task",until,silent,()=>start).save(record)).toThrow(/未可靠保存/);
  });
});
