import type {ReactNode,RefObject} from "react";
import {Alert,Button,ConfigProvider,Input,Space,Typography} from "antd";
import zhCN from "antd/locale/zh_CN";
import type {ConsultationDestination} from "./api";
import PromptRevisionBrowser from "./prompt/PromptRevisionBrowser";
import PromptCreateWorkspace from "./prompt/PromptCreateWorkspace";
import PromptSaveWorkspace from "./prompt/PromptSaveWorkspace";
import PromptCopyWorkspace from "./prompt/PromptCopyWorkspace";
import ProductTrademarkWorkspace from "./trademark/ProductTrademarkWorkspace";

export function ConsultationFrame({title,token,onLogout,children}:{title:string;token:string|null;onLogout:()=>void;children:ReactNode}){
  return <ConfigProvider locale={zhCN} theme={{token:{colorPrimary:"#315e52",borderRadius:8,fontFamily:'"Segoe UI", "Microsoft YaHei", sans-serif',fontSize:16}}}>
    <main className="consultation-product">
      <header className="consultation-header"><div><p className="consultation-eyebrow">知识产权智能服务</p><h1>{title}</h1></div>
        {token&&<Button onClick={onLogout}>退出登录</Button>}</header>
      {children}
    </main>
  </ConfigProvider>;
}
export function ConsultationTaskAccess({taskId,token,busy,inputLocked,version,state,onTaskChange,onLogin,onRefresh}:{
  taskId:string;token:string|null;busy:boolean;inputLocked:boolean;version?:number;state?:string;
  onTaskChange:(value:string)=>void;onLogin:()=>void;onRefresh:()=>void;
}){
  return <section className="consultation-panel" aria-labelledby="task-heading"><h2 id="task-heading">当前咨询任务</h2>
    <label htmlFor="consultation-task-id">任务编号</label><Input id="consultation-task-id" value={taskId} disabled={inputLocked} onChange={e=>onTaskChange(e.target.value.trim())}/>
    <Space wrap className="consultation-actions">
      {!token?<Button type="primary" disabled={busy||!taskId} onClick={onLogin}>登录并打开任务</Button>:<Button disabled={busy||!taskId} onClick={onRefresh}>重新读取任务</Button>}
      {version!==undefined&&<Typography.Text>任务版本 {version} · {state==="IN_PROGRESS"?"进行中":state}</Typography.Text>}
    </Space>
  </section>;
}
interface Props{
  destination:ConsultationDestination;taskId:string;token:string|null;pending:string;error:string;
  errorRef:RefObject<HTMLDivElement|null>;onLogout:()=>void;onBack:()=>void;onLogin:()=>void;
  onDestination?:(destination:ConsultationDestination)=>void;
}
export default function ConsultationResourcePage({destination,taskId,token,pending,error,errorRef,onLogout,onBack,onLogin,onDestination}:Props){
  const promptView=destination.view==="prompt"||destination.view==="prompt-create"||destination.view==="prompt-save"||destination.view==="prompt-copy";
  return <ConsultationFrame title={promptView?"提示词草稿":"商标资料"} token={token} onLogout={onLogout}>
    {error&&<div ref={errorRef} tabIndex={-1}><Alert type="error" showIcon title={error}/></div>}
    {!token?<section className="consultation-panel"><p>{destination.view==="prompt-create"?"登录后按当前权限创建草稿或查询原提交结果。":destination.view==="prompt-save"?"登录后按当前权限保存新修订或查询原提交结果。":destination.view==="prompt-copy"?"登录后按当前权限复制所选版本或查回原复制提交。":"登录后按当前权限读取所选资料。"}</p>
      {destination.view==="trademarks"&&!taskId&&<p role="alert">商标资料链接缺少任务编号，请返回咨询任务。</p>}
      <Space wrap><Button type="primary" disabled={!!pending||destination.view==="trademarks"&&!taskId} onClick={onLogin}>登录并打开{promptView?"提示词":"商标资料"}</Button><Button onClick={onBack}>返回咨询任务</Button></Space>
      <div role="status" aria-live="polite">{pending}</div>
    </section>:destination.view==="prompt"?<PromptRevisionBrowser key={token+":"+destination.promptId} token={token} promptId={destination.promptId} revisionId={destination.revisionId} onSessionExpired={onLogout} onBack={onBack} onCopy={onDestination?source=>onDestination({view:"prompt-copy",...source}):undefined}/>
      :destination.view==="prompt-create"?<PromptCreateWorkspace key={token+":"+destination.workspaceId} token={token} workspaceId={destination.workspaceId} onSessionExpired={onLogout} onBack={onBack}/>
      :destination.view==="prompt-save"?<PromptSaveWorkspace key={token+":"+destination.workspaceId+":"+destination.promptId} token={token} workspaceId={destination.workspaceId} promptId={destination.promptId} onSessionExpired={onLogout} onBack={onBack}/>
      :destination.view==="prompt-copy"?<PromptCopyWorkspace token={token} source={"sourceWorkspaceId" in destination?{sourceWorkspaceId:destination.sourceWorkspaceId,sourcePromptId:destination.sourcePromptId,sourceRevisionId:destination.sourceRevisionId,sourceContentSha:destination.sourceContentSha}:undefined} onSessionExpired={onLogout} onBack={onBack}/>
      :<ProductTrademarkWorkspace key={token+":"+taskId} token={token} taskId={taskId} onSessionExpired={onLogout} onBack={onBack}/>}
  </ConsultationFrame>;
}
