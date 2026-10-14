import "@testing-library/jest-dom/vitest";
import React from "react";
import { createHash, webcrypto } from "node:crypto";
import { Blob } from "node:buffer";
import { EnforcementAccessDenied } from "./enforcementNextV2Api";
import {afterEach,beforeEach,describe,it,expect,vi} from "vitest";
import {act,cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import EnforcementDocumentDraftV2Page from "./EnforcementDocumentDraftV2Page";
const api=vi.hoisted(()=>({login:vi.fn(),identity:vi.fn(),request:vi.fn(),logout:vi.fn(),download:vi.fn()}));
vi.mock("./enforcementNextV2Api",async original=>({...await original<typeof import("./enforcementNextV2Api")>(),completeEnforcementLogin:api.login,readEnforcementIdentity:api.identity,enforcementRequest:api.request,endEnforcementLogin:api.logout,enforcementDownload:api.download}));
// Focused wiring test: native controls replace Ant Design presentation only; real shared AgentTaskPanel is exercised.
vi.mock("antd",()=>{
 const Input=Object.assign((p:any)=><input {...p}/>,{TextArea:(p:any)=><textarea {...p}/>});
 const Form=Object.assign(({children}:any)=><div>{children}</div>,{Item:({children,label}:any)=><div><span>{label}</span>{children}</div>});
 return {Input,Form,Button:({children,onClick,disabled}:any)=><button onClick={onClick} disabled={disabled}>{children}</button>,
 Select:({options,value,onChange,disabled,...p}:any)=><select aria-label={p["aria-label"]} disabled={disabled} value={value??""} onChange={e=>onChange(/^\d+$/.test(e.target.value)?Number(e.target.value):e.target.value)}><option value="">请选择</option>{options.map((o:any)=><option key={o.value} value={o.value}>{o.label}</option>)}</select>,
 Alert:({title,description,role}:any)=><div role={role}><div>{title}</div><div>{description}</div></div>,Empty:({description}:any)=><p>{description}</p>,
 Typography:{Title:({children}:any)=><h2>{children}</h2>},Descriptions:({items}:any)=><div>{items.map((item:any)=><p key={item.key}>{item.label}: {item.children}</p>)}</div>,Table:({dataSource,columns}:any)=><table><tbody>{dataSource.map((row:any,index:number)=><tr key={index}>{columns.map((column:any,cell:number)=><td key={cell}>{column.render?column.render(row[column.dataIndex],row,index):row[column.dataIndex]}</td>)}</tr>)}</tbody></table>};
});

const sha="a".repeat(64),other="b".repeat(64);
const ref=(id:string)=>({id,revision:1,sha256:sha});
const manifest={organization:"org",type:"FACT_RECORD",caseInput:ref("case"),template:ref("template"),rule:ref("rule"),evidence:[ref("evidence")],legalReferences:[]};
const contentJson=JSON.stringify({profile:{title:"待导出原稿",classification:"NON_OFFICIAL",revision:1},sections:[]});
const contentSha=createHash("sha256").update(contentJson,"utf8").digest("hex");
const draft={id:"draft",revision:1,manifest,contentSha256:contentSha,inputManifestHash:sha,reviewState:"APPROVED",missingItems:[],author:"contract-test-actor",createdAt:"2026-10-07",contentJson};
const bytes=new TextEncoder().encode("isolated export test bytes, not a business DOCX");
let artifactSha:string,metadataPatch:Record<string,unknown>,failStage:string|null,metadataRead:(()=>Promise<unknown>)|null;
const writeMock=vi.fn(),click=vi.fn(),objectUrl=vi.fn();
const originalBlob=()=>new Blob([bytes]) as unknown as globalThis.Blob;
let exportReceipt:Record<string,unknown>;
const metadata=()=>({id:"export-original",draftId:"draft",revision:1,contentSha256:contentSha,manifest,requestId:exportReceipt.requestId,artifactSha256:artifactSha,rendererVersion:"renderer",actor:"contract-test-actor",createdAt:"2026-10-07",...metadataPatch});
const posts=()=>api.request.mock.calls.filter(call=>call[2]!==undefined);
const exportGets=()=>api.request.mock.calls.filter(call=>call[1]==="/document-drafts/exports/export-original");
const retry=()=>screen.getByRole("button",{name:"仅重读原导出 export-original"});
async function setup(){
 render(<EnforcementDocumentDraftV2Page/>);await screen.findByText("退出");
 fireEvent.change(screen.getByLabelText("文书标识"),{target:{value:"draft"}});
 fireEvent.click(screen.getByText("查询文书"));await screen.findByText("待导出原稿");
 await waitFor(()=>expect(screen.getByText("导出本审核版 DOCX")).not.toBeDisabled());
}
async function exportNow(){fireEvent.click(screen.getByText("导出本审核版 DOCX"));await waitFor(()=>expect(posts()).toHaveLength(1));}
beforeEach(async()=>{
 vi.clearAllMocks();vi.stubGlobal("crypto",webcrypto);
 artifactSha=Buffer.from(await webcrypto.subtle.digest("SHA-256",bytes)).toString("hex");
 metadataPatch={};failStage=null;metadataRead=null;exportReceipt={};
 api.login.mockResolvedValue("test-only-token");api.identity.mockResolvedValue({grants:[{resourceType:"DOCUMENT_DRAFT",organization:"org"}]});api.logout.mockResolvedValue(undefined);
 writeMock.mockReset();writeMock.mockImplementation(async(_path,_body,key)=>exportReceipt={operation:"EXPORT",draftId:"draft",revision:1,contentSha256:contentSha,requestId:key,exportId:"export-original"});
 api.request.mockImplementation(async(_token,path,body,key)=>{
  if(body!==undefined)return writeMock(path,body,key);
  if(path==="/document-drafts/exports/export-original"){
   if(metadataRead)return metadataRead();if(failStage==="metadata")throw new Error("metadata-read-failed");return metadata();
  }
  if(path.endsWith("/history"))return [draft];if(path.includes("/candidates"))return [];
  if(path.includes("/reviews"))return [{revision:1,contentSha256:contentSha,outcome:"APPROVED",actor:"contract-test-actor",reason:"contract-test",createdAt:"2026-10-07"}];
  return draft;
 });
 api.download.mockReset();api.download.mockImplementation(async()=>{if(failStage==="file")throw new Error("file-read-failed");return originalBlob();});
 objectUrl.mockReturnValue("blob:test-export");
 vi.stubGlobal("URL",Object.assign(URL,{createObjectURL:objectUrl,revokeObjectURL:vi.fn()}));
 vi.spyOn(HTMLAnchorElement.prototype,"click").mockImplementation(click);
});
afterEach(()=>{cleanup();vi.restoreAllMocks();vi.unstubAllGlobals();});
describe("enforcement document export exact binding and GET-only recovery",()=>{
 it("downloads only the verified original, using original receipt exportId and request key",async()=>{
  await setup();await exportNow();await waitFor(()=>expect(click).toHaveBeenCalledTimes(1));
  expect(posts()[0][1]).toBe("/document-drafts/draft/exports");expect(posts()[0][2]).toEqual({revision:1,contentSha:contentSha});expect(exportReceipt.requestId).toBe(posts()[0][3]);
  expect(exportGets()).toHaveLength(1);expect(api.download).toHaveBeenCalledExactlyOnceWith("test-only-token","/document-drafts/exports/export-original/file");
  expect(objectUrl).toHaveBeenCalledTimes(1);expect((click.mock.contexts[0] as HTMLAnchorElement).download).toBe("document-export-original.docx");expect(screen.queryByText("导出写入已确认，文件尚未交付")).toBeNull();
 });
 it.each([{id:"other-export"},{draftId:"other-draft"},{revision:2},{contentSha256:other},{requestId:"other-key"},{manifest:{...manifest,caseInput:ref("other-case")}},{artifactSha256:"bad"}])("rejects metadata mismatch %j before file read and retains confirmed export",async patch=>{
  metadataPatch=patch;await setup();await exportNow();await screen.findByText(/DOCX 导出绑定失配/);
  expect(api.download).not.toHaveBeenCalled();expect(click).not.toHaveBeenCalled();expect(objectUrl).not.toHaveBeenCalled();
  expect(retry()).not.toBeDisabled();expect(screen.getByText("导出本审核版 DOCX")).toBeDisabled();expect(posts()).toHaveLength(1);
  expect(screen.getByText("EXPORT 写入已确认回执（不代表结果读取成功）")).toBeTruthy();
 });
 it.each(["metadata","file","sha"])("recovers %s failure with GET-only same exportId, never POST again",async stage=>{
  if(stage==="sha")metadataPatch={artifactSha256:other};else failStage=stage;
  await setup();await exportNow();await screen.findByText(stage==="sha"?/DOCX 下载原件 SHA/:stage==="metadata"?"metadata-read-failed":"file-read-failed");
  expect(click).not.toHaveBeenCalled();expect(screen.queryByText("对账同一文书请求")).toBeNull();
  metadataPatch={};failStage=null;fireEvent.click(retry());await waitFor(()=>expect(click).toHaveBeenCalledTimes(1));
  expect(posts()).toHaveLength(1);expect(exportGets()).toHaveLength(2);expect(screen.queryByText("导出写入已确认，文件尚未交付")).toBeNull();
 });
 it("retains only original export recovery after repeated GET failures",async()=>{
  failStage="metadata";await setup();await exportNow();await screen.findByText("metadata-read-failed");
  fireEvent.click(retry());await waitFor(()=>expect(exportGets()).toHaveLength(2));await waitFor(()=>expect(retry()).not.toBeDisabled());
  expect(posts()).toHaveLength(1);expect(click).not.toHaveBeenCalled();
 });
 it.each([{revision:2},{contentSha256:other},{exportId:42}])("keeps uncertain-command fence for invalid EXPORT receipt %j",async patch=>{
  writeMock.mockImplementation(async(_path,_body,key)=>({...exportReceipt,operation:"EXPORT",draftId:"draft",revision:1,contentSha256:contentSha,requestId:key,exportId:"export-original",...patch}));
  await setup();await exportNow();await screen.findByText(/请对账原请求/);
  expect(exportGets()).toHaveLength(0);expect(api.download).not.toHaveBeenCalled();expect(screen.getByText("对账同一文书请求")).not.toBeDisabled();
  writeMock.mockImplementation(async(_path,_body,key)=>exportReceipt={operation:"EXPORT",draftId:"draft",revision:1,contentSha256:contentSha,requestId:key,exportId:"export-original"});
  fireEvent.click(screen.getByText("对账同一文书请求"));await waitFor(()=>expect(click).toHaveBeenCalledTimes(1));
  expect(posts()).toHaveLength(2);expect(posts()[1].slice(1)).toEqual(posts()[0].slice(1));
 });
 it("revokes session and clears sensitive recovery on real download access denial",async()=>{
  api.download.mockRejectedValue(new EnforcementAccessDenied());await setup();await exportNow();await screen.findByText("请通过产品身份入口登录");
  expect(screen.queryByText("导出写入已确认，文件尚未交付")).toBeNull();expect(click).not.toHaveBeenCalled();
 });
 it("clears old confirmed export recovery when document context changes",async()=>{
  failStage="metadata";await setup();await exportNow();await screen.findByText("metadata-read-failed");
  fireEvent.change(screen.getByLabelText("文书标识"),{target:{value:"different"}});
  expect(screen.queryByText("导出写入已确认，文件尚未交付")).toBeNull();expect(posts()).toHaveLength(1);
 });
 it("clears confirmed recovery and receipt on logout after GET failure",async()=>{
  failStage="metadata";await setup();await exportNow();await screen.findByText("metadata-read-failed");
  fireEvent.click(screen.getByText("退出"));await screen.findByText("请通过产品身份入口登录");
  expect(screen.queryByText("导出写入已确认，文件尚未交付")).toBeNull();expect(screen.queryByText("EXPORT 写入已确认回执（不代表结果读取成功）")).toBeNull();expect(posts()).toHaveLength(1);
 });
 it("ignores late export metadata after unmount without fetching or downloading file",async()=>{
  let resolve!:(value:unknown)=>void;metadataRead=()=>new Promise(r=>{resolve=r;});
  await setup();await exportNow();await waitFor(()=>expect(exportGets()).toHaveLength(1));
  const oldMetadata=metadata();cleanup();await act(async()=>resolve(oldMetadata));
  expect(api.download).not.toHaveBeenCalled();expect(click).not.toHaveBeenCalled();
 });
 it("ignores late SHA completion after unmount without object URL or download",async()=>{
  await setup();const digest=webcrypto.subtle.digest.bind(webcrypto.subtle);let resolve!:(value:ArrayBuffer)=>void;
  const digestSpy=vi.spyOn(webcrypto.subtle,"digest").mockImplementation(()=>new Promise(r=>{resolve=r;}));
  await exportNow();await waitFor(()=>expect(digestSpy).toHaveBeenCalledTimes(1));expect(objectUrl).not.toHaveBeenCalled();expect(click).not.toHaveBeenCalled();cleanup();
  await act(async()=>resolve(await digest("SHA-256",bytes)));expect(objectUrl).not.toHaveBeenCalled();expect(click).not.toHaveBeenCalled();
 });
});
