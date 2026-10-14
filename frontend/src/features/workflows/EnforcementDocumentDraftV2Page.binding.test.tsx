import { createHash, webcrypto } from "node:crypto";
import "@testing-library/jest-dom/vitest";
import React from "react";
import {afterEach,beforeEach,describe,it,expect,vi} from "vitest";
import {act,cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import EnforcementDocumentDraftV2Page from "./EnforcementDocumentDraftV2Page";
const api=vi.hoisted(()=>({login:vi.fn(),identity:vi.fn(),request:vi.fn(),logout:vi.fn()}));
vi.mock("./enforcementNextV2Api",async original=>({...await original<typeof import("./enforcementNextV2Api")>(),completeEnforcementLogin:api.login,readEnforcementIdentity:api.identity,enforcementRequest:api.request,endEnforcementLogin:api.logout}));
// Focused wiring test: native controls replace Ant Design presentation only; real shared AgentTaskPanel is exercised.
vi.mock("antd",()=>{
 const Input=Object.assign((p:any)=><input {...p}/>,{TextArea:(p:any)=><textarea {...p}/>});
 const Form=Object.assign(({children}:any)=><div>{children}</div>,{Item:({children,label}:any)=><div><span>{label}</span>{children}</div>});
 return {Input,Form,Button:({children,onClick,disabled}:any)=><button onClick={onClick} disabled={disabled}>{children}</button>,
 Select:({options,value,onChange,disabled,...p}:any)=><select aria-label={p["aria-label"]} disabled={disabled} value={value??""} onChange={e=>onChange(/^\d+$/.test(e.target.value)?Number(e.target.value):e.target.value)}><option value="">请选择</option>{options.map((o:any)=><option key={o.value} value={o.value}>{o.label}</option>)}</select>,
 Alert:({title,description,role}:any)=><div role={role}><div>{title}</div><div>{description}</div></div>,Empty:({description}:any)=><p>{description}</p>,
 Typography:{Title:({children}:any)=><h2>{children}</h2>},Descriptions:({items}:any)=><div>{items.map((item:any)=><p key={item.key}>{item.label}: {item.children}</p>)}</div>,Table:({dataSource,columns}:any)=><table><tbody>{dataSource.map((row:any,index:number)=><tr key={index}>{columns.map((column:any,cell:number)=><td key={cell}>{column.render?column.render(row[column.dataIndex],row,index):row[column.dataIndex]}</td>)}</tr>)}</tbody></table>};
});

const sha="a".repeat(64), otherSha="b".repeat(64);
const ref=(id:string)=>({id,revision:1,sha256:sha});
const manifest={organization:"org",type:"FACT_RECORD",caseInput:ref("case"),template:ref("template"),rule:ref("rule"),evidence:[ref("evidence")],legalReferences:[]};
const nativeContent={profile:{title:"案件事实记录草稿",classification:"NON_OFFICIAL",revision:1},sections:[{id:"facts",visibility:"VISIBLE",fields:[{definition:{id:"fact",label:"事实",editableText:true,repeated:false},value:"材料记载的事实",missing:false,source:{sourceRef:"evidence",sourceVersion:1,sourceSha256:sha,locator:"第1页",value:"材料记载的事实"}}]}]};
const contentSha = createHash("sha256").update(JSON.stringify(nativeContent), "utf8").digest("hex");
const draft={id:"draft",revision:1,manifest,contentSha256: contentSha,inputManifestHash:sha,reviewState:"DRAFT",missingItems:[],author:"operator",createdAt:"2026-10-07T10:00:00Z",contentJson:JSON.stringify(nativeContent)};
const prepared={ready:true,inputManifestHash:sha,diagnostics:[]};
const writeMock=vi.fn();
let history:unknown[], fetched:unknown, candidateValues:unknown[], reviewValues:unknown[];
let failRead=false;
const receipt=(key:string,revision=1)=>({operation:"GENERATE",draftId:"draft",revision,contentSha256: contentSha,requestId:key});
const posts=()=>api.request.mock.calls.filter(c=>c[2]!==undefined);
async function setup(){render(<EnforcementDocumentDraftV2Page/>);await screen.findByText("退出");fireEvent.change(screen.getByLabelText("文书标识"),{target:{value:"draft"}});fireEvent.click(screen.getByText("读取可消费输入"));await screen.findByRole("option",{name:/case/});fireEvent.change(screen.getByLabelText("运行库输入组合"),{target:{value:"0"}});}
async function read(){fireEvent.click(screen.getByText("查询文书"));await screen.findByText("案件事实记录草稿");await waitFor(()=>expect(screen.getByText("查询文书")).not.toBeDisabled());}
async function generate(){fireEvent.click(screen.getByText("准备"));await screen.findByText(/输入完整/);fireEvent.click(screen.getByText("生成草稿"));}
beforeEach(()=>{
 vi.clearAllMocks();vi.stubGlobal("crypto",webcrypto);history=[draft];fetched=draft;candidateValues=[];reviewValues=[];failRead=false;
 api.login.mockResolvedValue("test-only-token");api.identity.mockResolvedValue({grants:[{resourceType:"DOCUMENT_DRAFT",organization:"org"}]});api.logout.mockResolvedValue(undefined);
 writeMock.mockReset();writeMock.mockImplementation(async(path,_body,key)=>path.endsWith("/prepare")?prepared:receipt(key));
 api.request.mockImplementation(async(_token,path,body,key)=>{
  if(body!==undefined)return writeMock(path,body,key);
  if(path.includes("input-diagnostics"))return {organization:"org",type:"FACT_RECORD",checkedAt:"2026-10-07",inputs:[manifest],checks:[]};
  if(failRead)throw new Error("后置 GET 失败");
  if(path.endsWith("/history"))return history;
  if(path.includes("/candidates"))return candidateValues;
  if(path.includes("/reviews"))return reviewValues;
  return fetched;
 });
});
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();});
describe("document draft atomic exact binding",()=>{
 it("renders nonempty native structure and source only after all reads bind",async()=>{
  candidateValues=[{id:"candidate",draftId:"draft",baseRevision:1,baseContentSha256: contentSha,contentSha256:otherSha,differences:[{field:"fact",beforeJson:"旧事实",afterJson:"候选事实"}]}];
  reviewValues=[{draftId:"draft",revision:1,contentSha256: contentSha,outcome:"SUBMITTED",actor:"operator",reason:"复核事实",createdAt:"time"}];
  await setup();await read();expect(screen.getByLabelText("事实修订文本")).toBeTruthy();expect(screen.getByText(/第1页/)).toBeTruthy();expect(screen.getByText(/复核事实/)).toBeTruthy();expect(screen.getByText("显式采用此候选")).not.toBeDisabled();
 });
 it.each([{id:"other"},{revision:2},{contentSha256:otherSha},{contentSha256:"bad"}])("withdraws old content on wrong fetched draft %j",async patch=>{
  await setup();await read();fetched={...draft,...patch};fireEvent.click(screen.getByText("查询文书"));await screen.findByText(/文书读取绑定失配/);expect(screen.queryByText("案件事实记录草稿")).toBeNull();expect(screen.queryByText("提交本版审核")).toBeNull();expect(screen.getByText(/旧稿已撤下，未交接人工复核/)).toBeTruthy();
 });
 it("rejects cross-manifest handoff even if id, revision and SHA match",async()=>{
  await setup();fetched={...draft,manifest:{...manifest,caseInput:ref("another-case")}};fireEvent.click(screen.getByText("查询文书"));await screen.findByText(/输入版本不一致/);expect(screen.queryByText("案件事实记录草稿")).toBeNull();
 });
 it.each([{baseRevision:2},{baseContentSha256:otherSha},{draftId:"other"}])("rejects entire result on wrong candidate %j",async patch=>{
  await setup();candidateValues=[{id:"candidate",baseRevision:1,baseContentSha256: contentSha,contentSha256:otherSha,differences:[],...patch}];fireEvent.click(screen.getByText("查询文书"));await screen.findByText(/文书读取绑定失配：候选/);expect(screen.queryByText("案件事实记录草稿")).toBeNull();expect(screen.queryByText("显式采用此候选")).toBeNull();
 });
 it.each([{revision:2},{contentSha256:otherSha},{draftId:"other"}])("rejects entire result on wrong review %j",async patch=>{
  await setup();reviewValues=[{revision:1,contentSha256: contentSha,outcome:"APPROVED",actor:"operator",reason:"wrong",createdAt:"time",...patch}];fireEvent.click(screen.getByText("查询文书"));await screen.findByText(/文书读取绑定失配：复核/);expect(screen.queryByText("案件事实记录草稿")).toBeNull();expect(screen.queryByText("导出本审核版 DOCX")).toBeNull();
 });
 it("rejects cross-draft history before fetching any document",async()=>{
  await setup();history=[{...draft,id:"other"}];fireEvent.click(screen.getByText("查询文书"));await screen.findByText(/历史文书标识/);expect(api.request.mock.calls.some(c=>String(c[1]).includes("?revision="))).toBe(false);expect(screen.queryByText("案件事实记录草稿")).toBeNull();
 });
 it("rejects a different revision after a valid confirmed receipt",async()=>{
  await setup();writeMock.mockImplementation(async(path,_body,key)=>path.endsWith("/prepare")?prepared:receipt(key,2));history=[draft,{...draft,revision:2}];await generate();await screen.findByText(/草稿标识、版本或 SHA 不一致/);expect(screen.queryByText("案件事实记录草稿")).toBeNull();expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(screen.getByText(/写入已确认回执/)).toBeTruthy();
 });
 it("keeps malformed write receipt unknown for exact same-key reconciliation",async()=>{
  await setup();writeMock.mockResolvedValueOnce(prepared).mockImplementationOnce(async(_path,_body,key)=>({...receipt(key),draftId:"other"}));await generate();await screen.findByText("对账同一文书请求");const original=posts().at(-1);expect(api.request.mock.calls.some(c=>String(c[1]).endsWith("/history"))).toBe(false);fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText("案件事实记录草稿");expect(posts().at(-1)).toEqual(original);expect(screen.queryByText("对账同一文书请求")).toBeNull();
 });
 it("checks confirmed receipt SHA even when GET agrees with its history",async()=>{
  await setup();fetched={...draft,contentSha256:otherSha};history=[fetched];await generate();await screen.findByText(/草稿与已确认回执 SHA 不一致/);expect(screen.getByText(/写入已确认回执/)).toBeTruthy();expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(screen.queryByText("案件事实记录草稿")).toBeNull();expect(screen.getByText("生成草稿")).toBeDisabled();
 });
 it("keeps confirmed write after failed GET and retries only the receipt revision GET",async()=>{
  await setup();failRead=true;await generate();await screen.findByText("后置 GET 失败");expect(screen.getByText(/写入已确认回执/)).toBeTruthy();expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(screen.getByText("生成草稿")).toBeDisabled();
  const before=posts().length;failRead=false;history=[draft,{...draft,revision:2,contentSha256:otherSha}];await read();expect(posts()).toHaveLength(before);expect(api.request.mock.calls.filter(c=>c[1].includes("?revision="))[0][1]).toBe("/document-drafts/draft?revision=1");
 });
 it("removes the prior draft when a confirmed revision read fails",async()=>{
  await setup();await read();writeMock.mockImplementation(async(_path,_body,key)=>({...receipt(key,2),operation:"SUBMIT_REVIEW"}));failRead=true;fireEvent.click(screen.getByText("提交本版审核"));await screen.findByText("后置 GET 失败");expect(screen.queryByText("案件事实记录草稿")).toBeNull();expect(screen.getByText(/写入已确认回执/)).toBeTruthy();expect(screen.queryByText("对账同一文书请求")).toBeNull();
 });
 it("retains original request path/body/key while unknown, then confirms before rejecting GET",async()=>{
  await setup();writeMock.mockImplementation(async(path,_body,key)=>path.endsWith("/prepare")?prepared:receipt(key));writeMock.mockResolvedValueOnce(prepared).mockRejectedValueOnce(new TypeError("写入响应丢失"));await generate();await screen.findByText("对账同一文书请求");const original=posts().at(-1);expect(screen.getByLabelText("文书标识")).toBeDisabled();failRead=true;
  fireEvent.click(screen.getByText("对账同一文书请求"));await screen.findByText("后置 GET 失败");expect(posts().at(-1)).toEqual(original);expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(screen.getByText(/写入已确认回执/)).toBeTruthy();
 });
 it.each(["/history","/candidates","/reviews"])("ignores late %s responses after unmount",async stage=>{
  await setup();let resolve!:(value:unknown)=>void;const base=api.request.getMockImplementation()!;
  api.request.mockImplementation(async(...args)=>String(args[1]).includes(stage)?new Promise(r=>{resolve=r}):base(...args));
  fireEvent.click(screen.getByText("查询文书"));await waitFor(()=>expect(resolve).toBeTypeOf("function"));
  // Logout is disabled during a document read; unmount exercises the same epoch boundary.
  const count=api.request.mock.calls.length;cleanup();await act(async()=>{resolve(stage==="/history"?[draft]:[]);});expect(api.request.mock.calls).toHaveLength(count);expect(screen.queryByText("案件事实记录草稿")).toBeNull();
 });
 it("withdraws prior content and stops related GETs when raw text changes under the declared SHA",async()=>{
  await setup();await read();
  fetched={...draft,contentJson:draft.contentJson.replace("材料记载的事实","被替换的事实")};history=[fetched];api.request.mockClear();
  fireEvent.click(screen.getByText("查询文书"));await screen.findByText(/正文原文与声明 SHA 不一致/);
  expect(screen.queryByText("案件事实记录草稿")).toBeNull();expect(screen.queryByText("提交本版审核")).toBeNull();
  expect(api.request.mock.calls.some(c=>/candidates|reviews/.test(c[1]))).toBe(false);expect(posts()).toHaveLength(0);
 });
 it("keeps a confirmed write after original-text SHA failure and recovers with only exact revision GETs",async()=>{
  await setup();fetched={...draft,contentJson:JSON.stringify(nativeContent,null,2)};history=[fetched];await generate();
  await screen.findByText(/正文原文与声明 SHA 不一致/);expect(screen.getByText(/写入已确认回执/)).toBeTruthy();
  expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(screen.getByText("生成草稿")).toBeDisabled();expect(screen.queryByText("提交本版审核")).toBeNull();
  const before=posts().length;fetched=draft;history=[draft,{...draft,revision:2,contentSha256:otherSha}];
  api.request.mockClear();await read();expect(posts()).toHaveLength(0);expect(before).toBe(2);
  expect(api.request.mock.calls.map(c=>c[1])).toEqual(["/document-drafts/draft/history","/document-drafts/draft?revision=1","/document-drafts/draft/candidates?revision=1","/document-drafts/draft/reviews?revision=1"]);
 });
 it("displays exact whitespace and escaped-Unicode original text after hashing its actual bytes",async()=>{
  await setup();const raw=" \n"+JSON.stringify(nativeContent,null,2).replace("案件","\\u6848\\u4ef6")+"\n";
  fetched={...draft,contentJson:raw,contentSha256:createHash("sha256").update(raw,"utf8").digest("hex")};history=[fetched];
  await read();expect(screen.getByLabelText("事实修订文本")).toBeTruthy();expect(screen.getByText("提交本版审核")).not.toBeDisabled();
 });
 it.each(["success","mismatch","failure"])("ignores late digest %s after unmount without fetching candidate/review data",async outcome=>{
  await setup();let resolve!:(value:ArrayBuffer)=>void,reject!:(cause:unknown)=>void;
  vi.spyOn(webcrypto.subtle,"digest").mockImplementationOnce(()=>new Promise((yes,no)=>{resolve=yes;reject=no;}));
  fireEvent.click(screen.getByText("查询文书"));await waitFor(()=>expect(resolve).toBeTypeOf("function"));
  expect(screen.queryByText("案件事实记录草稿")).toBeNull();const count=api.request.mock.calls.length;cleanup();
  await act(async()=>{if(outcome==="failure")reject(new Error("stale digest failure"));else resolve(outcome==="success"?Uint8Array.from(Buffer.from(contentSha,"hex")).buffer:new ArrayBuffer(32));});
  expect(api.request.mock.calls).toHaveLength(count);expect(screen.queryByText("案件事实记录草稿")).toBeNull();expect(screen.queryByRole("alert")).toBeNull();
 });

});
