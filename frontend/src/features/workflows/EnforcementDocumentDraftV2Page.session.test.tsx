import "@testing-library/jest-dom/vitest";
import React from "react";
import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import EnforcementDocumentDraftV2Page from "./EnforcementDocumentDraftV2Page";
import { EnforcementDraftSession } from "./enforcementDraftSessionHelper";
import { EnforcementAccessDenied } from "./enforcementNextV2Api";
const api=vi.hoisted(()=>({login:vi.fn(),identity:vi.fn(),request:vi.fn(),logout:vi.fn(),begin:vi.fn()}));
vi.mock("./enforcementNextV2Api",async original=>({...await original<typeof import("./enforcementNextV2Api")>(),completeEnforcementLogin:api.login,readEnforcementIdentity:api.identity,enforcementRequest:api.request,endEnforcementLogin:api.logout,beginEnforcementLogin:api.begin}));
// Isolate the page's response lifecycle; this test port can deliver current-session revocation while an operation is pending.
vi.mock("../agent/AgentTaskPanel",()=>({default:({onDenied}:any)=><button onClick={onDenied}>测试端口失权</button>}));
vi.mock("antd",()=>{
 const Input=Object.assign((p:any)=><input {...p}/>,{TextArea:(p:any)=><textarea {...p}/>});
 const Form=Object.assign(({children}:any)=><div>{children}</div>,{Item:({children,label}:any)=><div><span>{label}</span>{children}</div>});
 return {Input,Form,Button:({children,onClick,disabled}:any)=><button onClick={onClick} disabled={disabled}>{children}</button>,
 Select:({options,value,onChange,disabled,...p}:any)=><select aria-label={p["aria-label"]} disabled={disabled} value={value??""} onChange={e=>onChange(/^\d+$/.test(e.target.value)?Number(e.target.value):e.target.value)}><option value="">请选择</option>{options.map((o:any)=><option key={o.value} value={o.value}>{o.label}</option>)}</select>,
 Alert:({title,description,role}:any)=><div role={role}><div>{title}</div><div>{description}</div></div>,Empty:({description}:any)=><p>{description}</p>,
 Typography:{Title:({children}:any)=><h2>{children}</h2>},Descriptions:({items}:any)=><div>{items.map((item:any)=><p key={item.key}>{item.label}: {item.children}</p>)}</div>,Table:({dataSource,columns}:any)=><table><tbody>{dataSource.map((row:any,index:number)=><tr key={index}>{columns.map((column:any,cell:number)=><td key={cell}>{column.render?column.render(row[column.dataIndex],row,index):row[column.dataIndex]}</td>)}</tr>)}</tbody></table>};
});

const sha="a".repeat(64),other="b".repeat(64);
function deferred<T>() {
  let resolve!: (value:T)=>void, reject!: (cause:unknown)=>void;
  const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no;});
  return {promise,resolve,reject};
}
const identity={grants:[{resourceType:"DOCUMENT_DRAFT",organization:"session-org"}]};
async function setup() {
  render(<EnforcementDocumentDraftV2Page/>);
  await screen.findByRole("button",{name:"退出"});
  await waitFor(()=>expect(screen.getByText("读取项目内置模板基线")).not.toBeDisabled());
  fireEvent.change(screen.getByLabelText("文书标识"),{target:{value:"draft"}});
}
const settle=async(action:()=>void)=>{await act(async()=>{action();await Promise.resolve();});};
beforeEach(()=>{
  vi.clearAllMocks();
  api.login.mockResolvedValue("test-only-session");api.identity.mockResolvedValue(identity);
  api.logout.mockResolvedValue(undefined);api.begin.mockResolvedValue(undefined);api.request.mockResolvedValue([]);
});
afterEach(()=>{cleanup();vi.restoreAllMocks();});

describe("FIX-ENF-SESSION-RESPONSE-01 page lifecycle",()=>{
  it.each(["success","failure"])("discards login %s after unmount and does not read old identity",async outcome=>{
    const pending=deferred<string|null>();api.login.mockReturnValueOnce(pending.promise);
    const old=render(<EnforcementDocumentDraftV2Page/>);old.unmount();
    api.login.mockResolvedValueOnce(null);render(<EnforcementDocumentDraftV2Page/>);
    await settle(()=>outcome==="success"?pending.resolve("old-session"):pending.reject(new Error("old-login-error")));
    expect(api.identity).not.toHaveBeenCalled();expect(screen.queryByText("退出")).toBeNull();expect(screen.queryByRole("alert")).toBeNull();
  });
  it.each(["success","failure","denied"])("discards identity %s from logout initiation, not logout completion",async outcome=>{
    const pending=deferred<typeof identity>(),logout=deferred<void>();api.identity.mockReturnValueOnce(pending.promise);api.logout.mockReturnValueOnce(logout.promise);
    render(<EnforcementDocumentDraftV2Page/>);await screen.findByText("退出");
    fireEvent.click(screen.getByText("退出"));expect(screen.queryByLabelText("文书标识")).toBeNull();
    await settle(()=>outcome==="success"?pending.resolve(identity):pending.reject(outcome==="denied"?new EnforcementAccessDenied(403):new Error("old-identity-error")));
    expect(screen.queryByText("退出")).toBeNull();expect(screen.queryByRole("alert")).toBeNull();expect(screen.getByText("登录")).toBeDisabled();
    await settle(()=>logout.resolve());expect(screen.getByText("登录")).not.toBeDisabled();expect(api.logout).toHaveBeenCalledWith("test-only-session");
  });
  it.each(["success","failure"])("discards identity %s after unmount",async outcome=>{
    const pending=deferred<typeof identity>();api.identity.mockReturnValueOnce(pending.promise);
    const old=render(<EnforcementDocumentDraftV2Page/>);await screen.findByText("退出");old.unmount();
    api.login.mockResolvedValueOnce(null);render(<EnforcementDocumentDraftV2Page/>);
    await settle(()=>outcome==="success"?pending.resolve(identity):pending.reject(new Error("old-identity-error")));
    expect(screen.queryByText("退出")).toBeNull();expect(screen.queryByRole("alert")).toBeNull();
  });
  it("keeps current identity denial effective",async()=>{
    api.identity.mockRejectedValueOnce(new EnforcementAccessDenied(401));render(<EnforcementDocumentDraftV2Page/>);
    await screen.findByRole("alert");expect(screen.queryByText("退出")).toBeNull();expect(screen.queryByLabelText("文书标识")).toBeNull();
  });
  it("keeps current login failure visible",async()=>{
    api.login.mockRejectedValueOnce(new Error("current-login-error"));render(<EnforcementDocumentDraftV2Page/>);
    await screen.findByText("current-login-error");expect(screen.queryByText("退出")).toBeNull();
  });
  it.each(["success","failure","denied"])("isolates old operation %s and finally from a new login operation",async outcome=>{
    await setup();const pending=deferred<unknown>(),login=deferred<void>();api.request.mockReturnValueOnce(pending.promise);api.begin.mockReturnValueOnce(login.promise);
    fireEvent.click(screen.getByText("读取项目内置模板基线"));expect(screen.getByText("退出")).toBeDisabled();
    fireEvent.click(screen.getByText("测试端口失权"));expect(screen.getByText("登录")).not.toBeDisabled();
    fireEvent.click(screen.getByText("登录"));expect(screen.getByText("登录")).toBeDisabled();
    await settle(()=>outcome==="success"?pending.resolve([{id:"old",title:"旧会话模板",revision:1,classification:"NON_OFFICIAL"}]):pending.reject(outcome==="denied"?new EnforcementAccessDenied(403):new Error("old-operation-error")));
    expect(screen.getByText("登录")).toBeDisabled();expect(screen.queryByRole("alert")).toBeNull();expect(screen.queryByText(/旧会话模板/)).toBeNull();
    await settle(()=>login.resolve());expect(screen.getByText("登录")).not.toBeDisabled();
  });
  it.each(["success","failure","denied"])("does not revive a cleared write from late %s",async outcome=>{
    await setup();const sha="a".repeat(64),ref=(id:string)=>({id,revision:1,sha256:sha});
    const manifest={organization:"session-org",type:"FACT_RECORD",caseInput:ref("case"),template:ref("template"),rule:ref("rule"),evidence:[],legalReferences:[]};
    api.request.mockResolvedValueOnce({organization:"session-org",type:"FACT_RECORD",checkedAt:"test",inputs:[manifest],checks:[]});
    fireEvent.click(screen.getByText("读取可消费输入"));await screen.findByRole("option",{name:/case/});
    fireEvent.change(screen.getByLabelText("运行库输入组合"),{target:{value:"0"}});
    const pending=deferred<unknown>();api.request.mockReturnValueOnce(pending.promise);fireEvent.click(screen.getByText("准备"));
    await screen.findByText("对账同一文书请求");fireEvent.click(screen.getByText("测试端口失权"));
    await settle(()=>outcome==="success"?pending.resolve({ready:true,inputManifestHash:sha,diagnostics:[]}):pending.reject(outcome==="denied"?new EnforcementAccessDenied(401):new TypeError("old-write-unknown")));
    expect(screen.queryByText("对账同一文书请求")).toBeNull();expect(screen.queryByText(/输入完整/)).toBeNull();expect(screen.queryByRole("alert")).toBeNull();expect(screen.getByText("登录")).not.toBeDisabled();
    expect(api.request.mock.calls.filter(call=>call[2]!==undefined)).toHaveLength(1);
  });
  it("keeps current operation errors and releases busy",async()=>{
    await setup();api.request.mockRejectedValueOnce(new Error("current-operation-error"));fireEvent.click(screen.getByText("读取项目内置模板基线"));
    await screen.findByText("current-operation-error");expect(screen.getByText("退出")).not.toBeDisabled();
  });
  it("keeps successful current operations busy until completion",async()=>{
    await setup();const pending=deferred<unknown>();api.request.mockReturnValueOnce(pending.promise);fireEvent.click(screen.getByText("读取项目内置模板基线"));
    expect(screen.getByText("退出")).toBeDisabled();await settle(()=>pending.resolve([{id:"current",title:"当前模板",revision:1,classification:"NON_OFFICIAL"}]));
    expect(screen.getByRole("option",{name:/当前模板/})).toBeInTheDocument();expect(screen.getByText("退出")).not.toBeDisabled();
  });
  it.each(["success","failure"])("unmount invalidates operation %s callbacks",async outcome=>{
    await setup();const pending=deferred<unknown>();api.request.mockReturnValueOnce(pending.promise);fireEvent.click(screen.getByText("读取项目内置模板基线"));
    cleanup();api.login.mockResolvedValueOnce(null);render(<EnforcementDocumentDraftV2Page/>);
    await settle(()=>outcome==="success"?pending.resolve([]):pending.reject(new Error("unmounted-operation-error")));
    expect(screen.queryByRole("alert")).toBeNull();expect(screen.getByText("登录")).not.toBeDisabled();
  });
});

describe("session helper callback ownership",()=>{
  it.each(["success","failure"])("never commits %s catch/finally after invalidation",async outcome=>{
    const session=new EnforcementDraftSession(),pending=deferred<void>();const callbacks={start:vi.fn(),fail:vi.fn(),finish:vi.fn()};
    const task=session.run(()=>pending.promise,callbacks);session.invalidate();
    if(outcome==="success")pending.resolve();else pending.reject(new Error("late"));await task;
    expect(callbacks.start).toHaveBeenCalledOnce();expect(callbacks.fail).not.toHaveBeenCalled();expect(callbacks.finish).not.toHaveBeenCalled();
  });
  it("does not let an earlier operation finish a later busy interval",async()=>{
    const session=new EnforcementDraftSession(),first=deferred<void>(),second=deferred<void>();
    const old={start:vi.fn(),fail:vi.fn(),finish:vi.fn()},current={start:vi.fn(),fail:vi.fn(),finish:vi.fn()};
    const a=session.run(()=>first.promise,old),b=session.run(()=>second.promise,current);first.reject(new Error("earlier"));await a;
    expect(old.fail).not.toHaveBeenCalled();expect(old.finish).not.toHaveBeenCalled();expect(current.finish).not.toHaveBeenCalled();
    second.resolve();await b;expect(current.finish).toHaveBeenCalledOnce();
  });
});
