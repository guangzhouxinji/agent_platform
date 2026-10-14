import {webcrypto,createHash} from "node:crypto";
import {afterAll,beforeAll,describe,expect,it,vi} from "vitest";
import {bindHumanRequest,humanRequestSha,type HumanRequestCommand,type HumanRequestView} from "./humanRequest";
const token=(sub="member")=>"header."+btoa(JSON.stringify({iss:"https://product.example",sub}))+".signature";
const now=Date.parse("2026-10-13T01:00:00Z"),sha="a".repeat(64);
const command:HumanRequestCommand={expectedTaskVersion:7,comparison:null,materials:[],reason:"请协助",purpose:"ORDINARY_ASSISTANCE"};
async function fixture():Promise<HumanRequestView>{
  return {kind:"REQUEST_RECORD",receipt:{taskId:"task",packageId:"package",packageRevision:1,manifestSha:sha,eventSequence:1,
    requestSha:await humanRequestSha(token(),"task","key",command),createdAt:new Date(now-1000).toISOString(),auditUntil:new Date(now+100000).toISOString()},
    body:{taskVersion:7,reason:command.reason,purpose:command.purpose,goal:{type:"GOAL",id:"goal",revision:1,sha},
      message:{type:"USER_MESSAGE",id:"message",revision:1,sha},input:{type:"SEARCH_INPUT",id:"input",revision:1,sha},materials:[],
      comparisonId:null,reportIds:[],gaps:["NO_COMPARISON_SELECTED","NO_REPORT_SELECTED"],bodyAccessUntil:new Date(now+10000).toISOString()}};
}
beforeAll(()=>vi.stubGlobal("crypto",webcrypto));afterAll(()=>vi.unstubAllGlobals());
describe("ordinary request transport identity and exact immutable command",()=>{
  it("matches independently spelled Java canonical command and real actor field names",async()=>{
    const canonical='{"actor":{"issuer":"https://product.example","kind":"HUMAN","subject":"member"},"command":{"comparison":null,"expectedTaskVersion":7,"materials":[],"purpose":"ORDINARY_ASSISTANCE","reason":"请协助"},"requestId":"key","taskId":"task"}';
    expect(await humanRequestSha(token(),"task","key",command)).toBe(createHash("sha256").update(canonical).digest("hex"));
  });
  // Fixed values from the owner's JDK 21 / Jackson 2.17.2 canonical-byte probe, 2026-10-13.
  it.each([
    ["a\u001fb","22944cd2a9ec61c98a954bd187f383f19f0516a60bd47452bfd348d8b4cf0f6d"],
    [String.raw`a\u001fb`,"2fe87c93a437acdfa07d8b3c86a5704328036676d7862a23b5b942b9bd009131"],
    ["a\u000b\u000e\u000f\u001a\u001b\u001c\u001d\u001e\u001f\b\t\n\f\rb","c431a4f61fb18faeb99e6f74119749cf605a12fa89df25dc26bacf4335a4096d"],
    ["emoji:😀","cafec682d293b64d34bc3c634e3504e11709c234330895d175e2653a4ad74de1"],
  ])("matches the actual Java probe for %j",async(reason,expected)=>{
    expect(await humanRequestSha(token(),"task","key",{...command,reason})).toBe(expected);
  });
  it.each([
    {label:"uppercase control hex",reason:"a\u000b\u000e\u000f\u001a\u001b\u001c\u001d\u001e\u001fb",encoded:String.raw`"a\u000B\u000E\u000F\u001A\u001B\u001C\u001D\u001E\u001Fb"`},
    {label:"literal escape text",reason:String.raw`a\u001fb`,encoded:String.raw`"a\\u001fb"`},
    {label:"named controls",reason:"a\b\t\n\f\rb",encoded:String.raw`"a\b\t\n\f\rb"`},
    {label:"quote slash and backslash",reason:'a"/\\b',encoded:String.raw`"a\"/\\b"`},
    {label:"valid Unicode pair",reason:"请协助😀",encoded:'"请协助😀"'},
    {label:"remaining controls",reason:"a\u0000\u0001\u0007\u0010\u0019b",encoded:String.raw`"a\u0000\u0001\u0007\u0010\u0019b"`},
  ])("matches Java canonical UTF-8 bytes: $label",async({reason,encoded})=>{
    const canonical='{"actor":{"issuer":"https://product.example","kind":"HUMAN","subject":"member"},"command":{"comparison":null,"expectedTaskVersion":7,"materials":[],"purpose":"ORDINARY_ASSISTANCE","reason":'+encoded+'},"requestId":"key","taskId":"task"}';
    expect(await humanRequestSha(token(),"task","key",{...command,reason})).toBe(createHash("sha256").update(canonical,"utf8").digest("hex"));
  });
  it("matches Java UTF-16 ordering with an explicit complete canonical vector",async()=>{
    const c:HumanRequestCommand={...command,comparison:{comparisonId:"comparison",reportIds:["z","a"]},materials:[
      {sourceId:"\ue000",documentId:"d",documentVersion:"v",fields:["TITLE","CLAIMS"]},
      {sourceId:"😀",documentId:"d",documentVersion:"v",fields:["ABSTRACT"]}]};
    const canonical='{"actor":{"issuer":"https://product.example","kind":"HUMAN","subject":"member"},"command":{"comparison":{"comparisonId":"comparison","reportIds":["a","z"]},"expectedTaskVersion":7,"materials":[{"documentId":"d","documentVersion":"v","fields":["ABSTRACT"],"sourceId":"😀"},{"documentId":"d","documentVersion":"v","fields":["CLAIMS","TITLE"],"sourceId":"\ue000"}],"purpose":"ORDINARY_ASSISTANCE","reason":"请协助"},"requestId":"key","taskId":"task"}';
    expect(await humanRequestSha(token(),"task","key",c)).toBe(createHash("sha256").update(canonical,"utf8").digest("hex"));
  });
  it.each(["a\ud800b","a\udc00b","a\ud800\ud83d\ude00b"])("rejects isolated surrogate reason %j before producing a hash",async reason=>{
    await expect(humanRequestSha(token(),"task","key",{...command,reason})).rejects.toThrow();
  });
  it("rejects malformed actor and material text instead of hashing Java's lossy replacement",async()=>{
    await expect(humanRequestSha(token("member\ud800"),"task","key",command)).rejects.toThrow();
    await expect(humanRequestSha(token(),"task","key",{...command,materials:[{sourceId:"source",documentId:"d\udc00",documentVersion:"v",fields:["TITLE"]}]})).rejects.toThrow();
  });
  it("keeps actual control characters distinct from user-written escape sequences",async()=>{
    expect(await humanRequestSha(token(),"task","key",{...command,reason:"a\u001fb"}))
      .not.toBe(await humanRequestSha(token(),"task","key",{...command,reason:String.raw`a\u001fb`}));
  });
  it("normalizes materials fields and reports without locale ordering or input mutation",async()=>{
    const c:HumanRequestCommand={...command,comparison:{comparisonId:"comparison",reportIds:["z","a"]},materials:[
      {sourceId:"\ue000",documentId:"d",documentVersion:"v",fields:["TITLE","CLAIMS"]},
      {sourceId:"😀",documentId:"d",documentVersion:"v",fields:["ABSTRACT"]}]};
    const before=JSON.stringify(c),reverse={...c,comparison:{comparisonId:"comparison",reportIds:["a","z"]},materials:[...c.materials].reverse()};
    expect(await humanRequestSha(token(),"task","key",c)).toBe(await humanRequestSha(token(),"task","key",reverse));
    expect(JSON.stringify(c)).toBe(before);
  });
  it("binds original body despite a caller having moved to another task version",async()=>{
    const v=await fixture();expect(await bindHumanRequest(v,"task",v.receipt.requestSha,token(),"key",now)).toEqual(v);
  });
  it.each(["task","sha","reason","kind","scope","gap","type","deadline","audit","actor","key"])("rejects %s drift",async change=>{
    const v=await fixture(),expected=v.receipt.requestSha;
    switch(change){case "task":v.receipt.taskId="other";break;case "sha":v.receipt.requestSha="b".repeat(64);break;
      case "reason":v.body!.reason="changed";break;case "kind":v.kind="RECEIPT_ONLY";break;
      case "scope":Object.assign(v.receipt,{scopeId:"internal"});break;case "gap":v.body!.gaps=[];break;
      case "type":v.body!.message.type="GOAL";break;case "deadline":v.body!.bodyAccessUntil=new Date(now).toISOString();break;
      case "audit":v.receipt.auditUntil=new Date(now).toISOString();break;}
    await expect(bindHumanRequest(v,"task",expected,token(change==="actor"?"other":"member"),change==="key"?"other":"key",now)).rejects.toThrow();
  });
  it("receipt-only accepts known digest but never hidden body or expired audit",async()=>{
    const v=await fixture();v.kind="RECEIPT_ONLY";v.body=null;
    expect(await bindHumanRequest(v,"task",v.receipt.requestSha,token(),"key",now)).toEqual(v);
    await expect(bindHumanRequest({...v,body:{reason:"hidden"}},"task",v.receipt.requestSha,token(),"key",now)).rejects.toThrow();
  });
});
