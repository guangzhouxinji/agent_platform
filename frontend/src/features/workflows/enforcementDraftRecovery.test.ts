import { describe, expect, it } from "vitest";
import { EnforcementDraftRecovery } from "./enforcementDraftRecovery";
import { EnforcementAccessDenied, EnforcementConflict, EnforcementRequestFailure } from "./enforcementNextV2Api";
describe("draft command memory fence",()=>{
  it("snapshots the exact request and refuses another write",()=>{
    const fence=new EnforcementDraftRecovery();const body={reason:"original",revision:7};
    const request=fence.begin("/draft/revisions",body,"same-key");body.reason="changed";
    expect(JSON.parse(request.bodyJson)).toEqual({reason:"original",revision:7});
    expect(Object.isFrozen(request)).toBe(true);
    expect(()=>fence.begin("/other",{},"new-key")).toThrow("对账");
  });
  it.each([new TypeError("network"),new EnforcementRequestFailure(408,"timeout"),new EnforcementRequestFailure(500,"failed"),new EnforcementRequestFailure(503,"failed"),new SyntaxError("invalid response")])("retains unknown outcome %s",cause=>{
    const fence=new EnforcementDraftRecovery();const r=fence.begin("/draft",{},"key");fence.fail(r,cause);expect(fence.request).toBe(r);
  });
  it.each([new EnforcementAccessDenied(401),new EnforcementAccessDenied(),new EnforcementConflict(),new EnforcementRequestFailure(400,"bad"),new EnforcementRequestFailure(404,"missing"),new EnforcementRequestFailure(422,"invalid")])("releases definitive rejection %s",cause=>{
    const fence=new EnforcementDraftRecovery();const r=fence.begin("/draft",{},"key");fence.fail(r,cause);expect(fence.request).toBeNull();
  });
  it("a stale completion cannot release a different request",()=>{
    const fence=new EnforcementDraftRecovery();const old=fence.begin("/draft",{},"old");fence.clear();const next=fence.begin("/draft",{},"next");expect(fence.confirm(old)).toBe(false);expect(fence.request).toBe(next);expect(fence.confirm(next)).toBe(true);expect(fence.request).toBeNull();
  });
});
