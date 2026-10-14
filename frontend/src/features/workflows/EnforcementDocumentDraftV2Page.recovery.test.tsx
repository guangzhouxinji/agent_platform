import { createHash, webcrypto } from "node:crypto";
import "@testing-library/jest-dom/vitest";
import React from "react";
import {afterEach,beforeEach,describe,it,expect,vi} from "vitest";
import {act,cleanup,fireEvent,render,screen,waitFor,within} from "@testing-library/react";
import EnforcementDocumentDraftV2Page from "./EnforcementDocumentDraftV2Page";
import {EnforcementAccessDenied, EnforcementConflict, EnforcementRequestFailure} from "./enforcementNextV2Api";
const api=vi.hoisted(()=>({login:vi.fn(),identity:vi.fn(),request:vi.fn(),logout:vi.fn()}));
vi.mock("./enforcementNextV2Api",async original=>({...await original<typeof import("./enforcementNextV2Api")>(),completeEnforcementLogin:api.login,readEnforcementIdentity:api.identity,enforcementRequest:api.request,endEnforcementLogin:api.logout}));
// Focused wiring test: native controls replace Ant Design presentation only; real shared AgentTaskPanel is exercised.
vi.mock("antd",()=>{
 const Input=Object.assign((p:any)=><input {...p}/>,{TextArea:(p:any)=><textarea {...p}/>});
 const Form=Object.assign(({children}:any)=><div>{children}</div>,{Item:({children,label}:any)=><div><span>{label}</span>{children}</div>});
 return {Input,Form,Button:({children,onClick,disabled}:any)=><button onClick={onClick} disabled={disabled}>{children}</button>,
 Select:({options,value,onChange,disabled,...p}:any)=><select aria-label={p["aria-label"]} disabled={disabled} value={value??""} onChange={e=>onChange(/^\d+$/.test(e.target.value)?Number(e.target.value):e.target.value)}><option value="">请选择</option>{options.map((o:any)=><option key={o.value} value={o.value}>{o.label}</option>)}</select>,
 Alert:({title,description,role}:any)=><div role={role}>{title}{description}</div>,Empty:({description}:any)=><p>{description}</p>,
 Typography:{Title:({children}:any)=><h2>{children}</h2>},Descriptions:()=>null,Table:()=>null};
});

const sha="a".repeat(64);
const ref=(id:string)=>({id,revision:1,sha256:sha});
const manifest={organization:"org",type:"FACT_RECORD",caseInput:ref("case"),template:ref("template"),rule:ref("rule"),evidence:[],legalReferences:[]};
const prepared={ready:true,inputManifestHash:sha,diagnostics:[]};
const contentJson=JSON.stringify({profile:{title:"真实草稿",classification:"NON_OFFICIAL",revision:1},sections:[]});
const contentSha=createHash("sha256").update(contentJson,"utf8").digest("hex");
const draft={id:"draft",revision:1,manifest,contentSha256:contentSha,inputManifestHash:sha,reviewState:"DRAFT",missingItems:[],author:"actor",createdAt:"time",contentJson};
const writeMock=vi.fn();let failRead=false;
const receipt=(key:string)=>({operation:"GENERATE",draftId:"draft",revision:1,contentSha256:contentSha,requestId:key});
async function setup(){render(<EnforcementDocumentDraftV2Page/>);await screen.findByText("退出");fireEvent.change(screen.getByLabelText("文书标识"),{target:{value:"draft"}});fireEvent.click(screen.getByText("读取可消费输入"));await screen.findByRole("option",{name:/case/});fireEvent.change(screen.getByLabelText("运行库输入组合"),{target:{value:"0"}});}
async function prepare(){fireEvent.click(screen.getByText("准备"));await screen.findByText(/输入完整/);}
const posts=()=>api.request.mock.calls.filter(c=>c[2]!==undefined);
beforeEach(()=>{
 vi.clearAllMocks();vi.stubGlobal("crypto",webcrypto);failRead=false;api.login.mockResolvedValue("test-only-token");api.identity.mockResolvedValue({grants:[{resourceType:"DOCUMENT_DRAFT",organization:"org"}]});api.logout.mockResolvedValue(undefined);
 writeMock.mockReset();writeMock.mockImplementation(async(path,_body,key)=>path.endsWith("/prepare")?prepared:{...receipt(key),operation:path.endsWith("/submit-review")?"SUBMIT_REVIEW":"GENERATE"});
 api.request.mockImplementation(async(_token,path,body,key)=>{
  if(body!==undefined)return writeMock(path,body,key);
  if(path.includes("input-diagnostics"))return {organization:"org",type:"FACT_RECORD",checkedAt:"time",inputs:[manifest],checks:[]};
  if(failRead)throw new Error("后置读取失败");
  if(path.endsWith("/history"))return [draft];
  if(path.includes("/candidates")||path.includes("/reviews"))return [];
  return draft;
 });
});
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();});
describe("document draft request reconciliation",()=>{
 it.each([
  ["generate",new EnforcementConflict()],
  ["generate",new EnforcementRequestFailure(422,"latest prepare rejected")],
  ["candidates",new EnforcementConflict()],
  ["candidates",new EnforcementRequestFailure(422,"latest prepare rejected")]
 ])("withdraws prior prepare after latest rejection: %s %s",async(mode,cause)=>{
  await setup();
  if(mode==="candidates"){
   fireEvent.click(screen.getByText("查询文书"));await screen.findByText("真实草稿");
   await waitFor(()=>expect(screen.getByText("准备")).not.toBeDisabled());
  }
  await prepare();await waitFor(()=>expect(screen.getByText("准备")).not.toBeDisabled());
  const next=screen.getByText(mode==="generate"?"生成草稿":"重生成候选");
  expect(next).not.toBeDisabled();
  writeMock.mockRejectedValueOnce(cause);fireEvent.click(screen.getByText("准备"));
  await screen.findByText((cause as Error).message,{exact:false});
  expect(posts().filter(c=>c[1].endsWith("/prepare"))).toHaveLength(2);
  expect(screen.queryByText("对账同一文书请求")).toBeNull();
  expect(next).toBeDisabled();
  expect(screen.queryByText(/输入完整/)).toBeNull();
 });
 it.each(["generate","candidates"])("withdraws prepare while pending and allows current incomplete result: %s",async mode=>{
  await setup();
  if(mode==="candidates"){
   fireEvent.click(screen.getByText("查询文书"));await screen.findByText("真实草稿");
   await waitFor(()=>expect(screen.getByText("准备")).not.toBeDisabled());
  }
  await prepare();await waitFor(()=>expect(screen.getByText("准备")).not.toBeDisabled());
  let resolve!:(value:typeof prepared)=>void;
  writeMock.mockImplementationOnce(()=>new Promise<typeof prepared>(yes=>{resolve=yes;}));
  fireEvent.click(screen.getByText("准备"));
  expect(screen.queryByText("准备结果")).toBeNull();
  const next=screen.getByText(mode==="generate"?"生成草稿":"重生成候选");
  expect(next).toBeDisabled();
  const latest={...prepared,ready:false,inputManifestHash:"b".repeat(64)};
  await act(async()=>{resolve(latest);});
  await screen.findByText(/存在缺项或待处理项/);expect(next).not.toBeDisabled();
  writeMock.mockImplementationOnce(async(_path,_body,key)=>({...receipt(key),operation:mode==="generate"?"GENERATE":"REGENERATE"}));
  fireEvent.click(next);await waitFor(()=>expect(screen.queryByText("对账同一文书请求")).toBeNull());
  expect(posts().at(-1)?.[2]).toMatchObject({preparedManifestHash:latest.inputManifestHash});
 });
 it.each([
  ["generate",new TypeError("renewed prepare unknown")],
  ["generate",new EnforcementRequestFailure(503,"renewed prepare unavailable")],
  ["candidates",new TypeError("renewed prepare unknown")],
  ["candidates",new EnforcementRequestFailure(503,"renewed prepare unavailable")]
 ])("keeps old prepare withdrawn until original-key reconciliation: %s %s",async(mode,cause)=>{
  await setup();
  if(mode==="candidates"){
   fireEvent.click(screen.getByText("查询文书"));await screen.findByText("真实草稿");
   await waitFor(()=>expect(screen.getByText("准备")).not.toBeDisabled());
  }
  await prepare();await waitFor(()=>expect(screen.getByText("准备")).not.toBeDisabled());
  writeMock.mockRejectedValueOnce(cause);fireEvent.click(screen.getByText("准备"));
  await screen.findByText("对账同一文书请求");const original=posts().at(-1);
  const next=screen.getByText(mode==="generate"?"生成草稿":"重生成候选");
  expect(next).toBeDisabled();expect(screen.queryByText("准备结果")).toBeNull();
  writeMock.mockResolvedValueOnce({...prepared,ready:false});
  fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText(/存在缺项或待处理项/);
  expect(posts().at(-1)).toEqual(original);expect(next).not.toBeDisabled();
  expect(screen.queryByText("对账同一文书请求")).toBeNull();
 });
 it("does not restore old prepare from an invalid renewed response",async()=>{
  await setup();await prepare();await waitFor(()=>expect(screen.getByText("准备")).not.toBeDisabled());
  writeMock.mockResolvedValueOnce({...prepared,inputManifestHash:"invalid"});fireEvent.click(screen.getByText("准备"));
  await screen.findByText("对账同一文书请求");const original=posts().at(-1);
  expect(screen.queryByText("准备结果")).toBeNull();expect(screen.getByText("生成草稿")).toBeDisabled();
  fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText(/输入完整/);
  expect(posts().at(-1)).toEqual(original);expect(screen.getByText("生成草稿")).not.toBeDisabled();
 });
 it.each([new TypeError("网络未知"),new EnforcementRequestFailure(503,"服务未知")])("reconciles prepare with original request: %s",async cause=>{
  await setup();writeMock.mockRejectedValueOnce(cause);fireEvent.click(screen.getByText("准备"));await screen.findByText("对账同一文书请求");
  expect(screen.getByLabelText("文书标识")).toBeDisabled();expect(screen.getByLabelText("运行库输入组合")).toBeDisabled();expect(screen.getByText("准备")).toBeDisabled();
  fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText(/输入完整/);expect(posts()).toHaveLength(2);expect(posts()[1]).toEqual(posts()[0]);expect(screen.queryByText("对账同一文书请求")).toBeNull();
 });
 it("reconciles generate before reading",async()=>{
  await setup();await prepare();writeMock.mockRejectedValueOnce(new TypeError("lost"));fireEvent.click(screen.getByText("生成草稿"));await screen.findByText("对账同一文书请求");
  const original=posts().at(-1);fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText("真实草稿");expect(posts().at(-1)).toEqual(original);expect(screen.queryByText("对账同一文书请求")).toBeNull();
 });
 it("clears fence before a failing follow-up GET",async()=>{
  await setup();await prepare();failRead=true;fireEvent.click(screen.getByText("生成草稿"));await screen.findByText(/后置读取失败/);
  expect(screen.queryByText("对账同一文书请求")).toBeNull();const count=posts().length;failRead=false;fireEvent.click(screen.getByText("查询文书"));await screen.findByText("真实草稿");expect(posts()).toHaveLength(count);
 });
 it.each([new EnforcementConflict(),new EnforcementRequestFailure(422,"确定拒绝")])("releases definitive rejection: %s",async cause=>{
  await setup();writeMock.mockRejectedValueOnce(cause);fireEvent.click(screen.getByText("准备"));await screen.findByText(cause.message,{exact:false});expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(screen.getByLabelText("文书标识")).not.toBeDisabled();
 });
 it("revocation clears sensitive context",async()=>{
  await setup();writeMock.mockRejectedValueOnce(new TypeError("lost"));fireEvent.click(screen.getByText("准备"));await screen.findByText("对账同一文书请求");writeMock.mockRejectedValueOnce(new EnforcementAccessDenied(401));fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText("登录");expect(screen.queryByLabelText("文书标识")).toBeNull();expect(screen.queryByText("对账同一文书请求")).toBeNull();
 });
 it("logout clears memory without claiming server cancellation",async()=>{
  await setup();writeMock.mockRejectedValueOnce(new TypeError("lost"));fireEvent.click(screen.getByText("准备"));await screen.findByText("对账同一文书请求");expect(screen.getByText(/不表示服务器命令已取消/)).toBeTruthy();fireEvent.click(screen.getByText("退出"));await screen.findByText("登录");expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(api.logout).toHaveBeenCalledWith("test-only-token");
 });
 it("wrong receipt does not release original write",async()=>{
  await setup();await prepare();writeMock.mockImplementationOnce(async(_path,_body,key)=>({...receipt(key),draftId:"other"}));fireEvent.click(screen.getByText("生成草稿"));await screen.findByText("对账同一文书请求");expect(screen.queryByText("真实草稿")).toBeNull();const original=posts().at(-1);fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText("真实草稿");expect(posts().at(-1)).toEqual(original);
 });
 it("repeated unknown reconciliation retains the original key",async()=>{
  await setup();writeMock.mockRejectedValueOnce(new TypeError("lost")).mockRejectedValueOnce(new EnforcementRequestFailure(500,"still unknown"));
  fireEvent.click(screen.getByText("准备"));await screen.findByText("对账同一文书请求");const original=posts()[0];
  fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText(/still unknown/);expect(posts()[1]).toEqual(original);expect(screen.getByLabelText("文书标识")).toBeDisabled();
  fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText(/输入完整/);expect(posts()[2]).toEqual(original);
 });
 it.each(["wrong-key","missing-sha","missing-operation","wrong-operation"])("rejects invalid receipt %s",async kind=>{
  await setup();await prepare();writeMock.mockImplementationOnce(async(_path,_body,key)=>({...receipt(key),...(kind==="wrong-key"?{requestId:"other"}:kind==="missing-sha"?{contentSha256:""}:kind==="wrong-operation"?{operation:"REVIEW"}:{operation:""})}));
  fireEvent.click(screen.getByText("生成草稿"));await screen.findByText("对账同一文书请求");expect(screen.getByLabelText("文书标识")).toBeDisabled();expect(screen.queryByText("真实草稿")).toBeNull();
 });
 it("review submission preserves revision and SHA during reconciliation",async()=>{
  await setup();fireEvent.click(screen.getByText("查询文书"));await screen.findByText("真实草稿");writeMock.mockRejectedValueOnce(new TypeError("lost"));
  fireEvent.click(screen.getByText("提交本版审核"));await screen.findByText("对账同一文书请求");const original=posts().at(-1);
  expect(original?.[2]).toEqual({revision:1,contentSha:contentSha});
  fireEvent.click(screen.getByText("对账同一文书请求"));await waitFor(()=>expect(screen.queryByText("对账同一文书请求")).toBeNull());expect(posts().at(-1)).toEqual(original);
 });
 it("does not store pending bodies or tokens",async()=>{
  const storage=vi.spyOn(Storage.prototype,"setItem");try{
   await setup();writeMock.mockRejectedValueOnce(new TypeError("lost"));fireEvent.click(screen.getByText("准备"));await screen.findByText("对账同一文书请求");expect(storage).not.toHaveBeenCalled();
  }finally{storage.mockRestore();}
 });
 it("failed server logout still clears local sensitive context without claiming remote cancellation",async()=>{
  await setup();writeMock.mockRejectedValueOnce(new TypeError("lost"));fireEvent.click(screen.getByText("准备"));await screen.findByText("对账同一文书请求");api.logout.mockRejectedValueOnce(new Error("退出服务未确认"));
  fireEvent.click(screen.getByText("退出"));await screen.findByText("登录");expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(screen.queryByLabelText("文书标识")).toBeNull();await screen.findByText(/退出服务未确认/);
 });

});



