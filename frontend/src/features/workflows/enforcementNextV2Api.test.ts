import { afterEach, describe, expect, it, vi } from "vitest";
import { enforcementRequest, readEnforcementIdentity, EnforcementAccessDenied, EnforcementConflict } from "./enforcementNextV2Api";

afterEach(() => vi.unstubAllGlobals());

describe("enforcement V2 product request", () => {
  it("reads the trusted product identity context", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ issuer: "https://issuer",
      subject: "member-1", sessionId: "sid-1", grants: [{ organization: "ORG-1", domain: "ENFORCEMENT",
        resourceType: "ENFORCEMENT_JOB_PACK", resourceId: "PACK-1", action: "READ", purpose: "JOB_PACK" }] }),
      { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetcher);
    await expect(readEnforcementIdentity("token-1")).resolves.toMatchObject({ subject: "member-1" });
    expect(fetcher).toHaveBeenCalledWith("/v2/identity/me", { headers: {
      Authorization: "Bearer token-1", Accept: "application/json" } });
  });

  it("uses the authenticated V2 route and exact idempotency key", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ resultId: "R-1", version: 1 }), { status: 200 }));
    vi.stubGlobal("fetch", fetcher);
    await expect(enforcementRequest("access", "/procedure-checks", { caseRef: "C-1" }, "fixed-key"))
      .resolves.toEqual({ resultId: "R-1", version: 1 });
    expect(fetcher).toHaveBeenCalledWith("/v2/enforcement/procedure-checks", expect.objectContaining({
      method: "POST", headers: expect.objectContaining({ Authorization: "Bearer access", "Idempotency-Key": "fixed-key" }),
      body: '{"caseRef":"C-1"}'
    }));
  });

  it.each([401, 403])("makes %i revoke the view", async status => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status })));
    await expect(enforcementRequest("access", "/conduct-rules/R/versions/1"))
      .rejects.toBeInstanceOf(EnforcementAccessDenied);
  });

  it("keeps a conflicting version distinct from service failures", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 409 })));
    await expect(enforcementRequest("access", "/procedure-checks/R/rechecks", {}, "key"))
      .rejects.toBeInstanceOf(EnforcementConflict);
  });

  it("surfaces a product failure without creating a success receipt", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: "上游日历不可用" }),
      { status: 503, headers: { "Content-Type": "application/json" } })));
    await expect(enforcementRequest("access", "/procedure-rules/R/versions/1/trials", {}, "key"))
      .rejects.toThrow("上游日历不可用");
  });
});

describe("request failure status contract",()=>{
  it.each([400,404,422,429,500,503])("preserves real status %i and message",async status=>{
    vi.stubGlobal("fetch",vi.fn().mockResolvedValue(new Response(JSON.stringify({error:{message:"原产品错误"}}),{status})));
    await expect(enforcementRequest("token","/document-drafts/draft/revisions",{},"key")).rejects.toMatchObject({status,message:"原产品错误"});
  });
  it.each([401,403])("preserves access denied status %i",async status=>{
    vi.stubGlobal("fetch",vi.fn().mockResolvedValue(new Response(null,{status})));
    await expect(enforcementRequest("token","/document-drafts/draft/history")).rejects.toMatchObject({status,message:"当前授权不可用，请重新登录"});
  });
  it("does not turn broken success JSON into a receipt",async()=>{
    vi.stubGlobal("fetch",vi.fn().mockResolvedValue(new Response("broken",{status:200})));
    await expect(enforcementRequest("token","/document-drafts/draft/generate",{},"key")).rejects.toBeInstanceOf(SyntaxError);
  });
});
