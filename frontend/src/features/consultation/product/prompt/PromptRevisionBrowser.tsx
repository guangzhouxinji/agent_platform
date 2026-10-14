import {useEffect,useRef,useState} from 'react';
import {PromptReadError,readPromptHistory,readPromptRevision,type PromptHistoryView,type PromptRevisionView} from './promptApi';
import type {PromptCopySource} from './promptCopyCommand';
import PromptPreviewWorkspace from './PromptPreviewWorkspace';
import './prompt.css';

export interface PromptRevisionBrowserProps{
  token:string|null;
  promptId?:string;
  revisionId?:string;
  onSessionExpired?:()=>void;
  onBack:()=>void;
  onCopy?:(source:PromptCopySource)=>void;
}
export default function PromptRevisionBrowser(props:PromptRevisionBrowserProps){
  if(!props.promptId||!props.token)return <section className="consultation-panel prompt-browser" aria-label="提示词版本">
    <h2>提示词版本</h2><p>{props.promptId?'请登录后查看所选提示词。':'尚未选择提示词，请从工作区或提示词链接打开。'}</p>
    <button type="button" onClick={props.onBack}>返回咨询任务</button></section>;
  return <PromptSession key={`${props.token}:${props.promptId}:${props.revisionId??''}`} {...props} token={props.token} promptId={props.promptId}/>;
}
function PromptSession({token,promptId,revisionId,onSessionExpired,onBack,onCopy}:PromptRevisionBrowserProps&{token:string;promptId:string}){
  const [page,setPage]=useState(1),[refresh,setRefresh]=useState(0),[selected,setSelected]=useState(revisionId??'');
  const [history,setHistory]=useState<PromptHistoryView|null>(null),[revision,setRevision]=useState<PromptRevisionView|null>(null);
  const [pending,setPending]=useState(true),[error,setError]=useState('');
  const epoch=useRef(0),errorRef=useRef<HTMLDivElement>(null),sessionExpired=useRef(onSessionExpired);
  sessionExpired.current=onSessionExpired;
  useEffect(()=>{
    const current=++epoch.current,controller=new AbortController();
    setPending(true);setError('');setHistory(null);setRevision(null);
    (async()=>{
      const versions=await readPromptHistory(token,promptId,page,20,controller.signal);
      if(epoch.current!==current||controller.signal.aborted)return;
      const exact=selected||versions.revisions[0]?.revisionId;
      const value=exact?await readPromptRevision(token,promptId,exact,controller.signal):null;
      if(epoch.current!==current||controller.signal.aborted)return;
      setHistory(versions);setRevision(value);
    })().catch(failure=>{
      if(epoch.current!==current||controller.signal.aborted)return;
      setHistory(null);setRevision(null);setError(failure instanceof PromptReadError?failure.message:'当前提示词资料无法核验，请重新读取。');
      if(failure instanceof PromptReadError&&failure.status===401)sessionExpired.current?.();
    }).finally(()=>{if(epoch.current===current&&!controller.signal.aborted)setPending(false);});
    return()=>{epoch.current++;controller.abort();};
  },[token,promptId,page,selected,refresh]);
  useEffect(()=>{if(error)errorRef.current?.focus();},[error]);
  useEffect(()=>{
    const deadlines=[...(revision?[Date.parse(revision.bodyUntil)]:[]),...(history?.revisions.map(row=>Date.parse(row.bodyUntil))??[])];
    if(deadlines.length===0)return;
    const until=Math.min(...deadlines);
    let timer:ReturnType<typeof setTimeout>|undefined;
    const close=()=>{const left=until-Date.now();
      if(left<=0){epoch.current++;setRevision(null);setHistory(null);setError('显示的版本已到保存期限，请重新读取可用版本。');setPending(false);}
      else timer=setTimeout(close,Math.min(left,2147483647));};
    close();return()=>clearTimeout(timer);
  },[revision,history]);
  const change=(action:()=>void)=>{epoch.current++;setRevision(null);setHistory(null);setPending(true);setError('');action();};
  const previewUnavailable=(status:401|403)=>{epoch.current++;setRevision(null);setHistory(null);setPending(false);
    setError(status===401?'会话已失效，请重新登录。':'当前预览权限或所选版本已不可用，已关闭正文和样例。');
    if(status===401)sessionExpired.current?.();};
  const displayTime=(value:string)=>new Date(value).toLocaleString('zh-CN',{hour12:false});
  const variableType={STRING:'文本',BOOLEAN:'布尔值',INTEGER:'整数',DECIMAL:'小数',STRING_LIST:'文本列表'};
  return <section className="consultation-panel prompt-browser" aria-labelledby="prompt-version-heading">
    <div className="prompt-title"><div><p className="consultation-muted">提示词 · 版本记录</p><h2 id="prompt-version-heading">{revision?.name??'所选提示词'}</h2></div>
      <button type="button" onClick={()=>{epoch.current++;setRevision(null);setHistory(null);setPending(false);onBack();}}>返回咨询任务</button></div>
    <div className="prompt-toolbar"><button type="button" disabled={pending} onClick={()=>change(()=>setRefresh(value=>value+1))}>重新读取</button>
      <p role="status" aria-live="polite">{pending?'正在核验并读取版本…':history?`本页 ${history.revisions.length} 个可读取版本`:'未展示历史缓存'}</p></div>
    {error&&<div className="prompt-error" role="alert" tabIndex={-1} ref={errorRef}>{error}</div>}
    <div className="prompt-columns">
      {history&&<nav className="prompt-history" aria-label="版本历史"><h3>历史版本</h3>
        {history.revisions.length===0?<p>本页没有可读取的版本。</p>:<ul>{history.revisions.map(item=><li key={item.revisionId}>
          <button type="button" aria-current={item.revisionId===revision?.revisionId?'true':undefined}
            onClick={()=>change(()=>setSelected(item.revisionId))} disabled={pending||item.revisionId===revision?.revisionId}>
            <strong>版本 {item.version}</strong><time dateTime={item.submittedAt}>{displayTime(item.submittedAt)}</time>
          </button></li>)}</ul>}
        <div className="prompt-pagination"><button type="button" disabled={pending||page<=1} onClick={()=>change(()=>{setSelected('');setPage(value=>value-1);})}>上一页</button>
          <span>第 {page} 页</span><button type="button" disabled={pending||page*20>=history.total} onClick={()=>change(()=>{setSelected('');setPage(value=>value+1);})}>下一页</button></div>
      </nav>}
      {revision&&<article className="prompt-document" aria-label={`提示词版本 ${revision.version}`}>
        <h3>{revision.kind==='SYSTEM'?'系统提示词':'引导提示词'} · 版本 {revision.version}</h3>
        <p className="consultation-muted">提交于 <time dateTime={revision.submittedAt}>{displayTime(revision.submittedAt)}</time> · {revision.language}</p>
        <h4>提示词正文</h4><pre className="prompt-body">{revision.body}</pre>
        <h4>变量定义</h4>{revision.variables.length===0?<p>此版本未定义变量。</p>:<dl className="prompt-variables">{revision.variables.map(variable=><div key={variable.name}>
          <dt>{variable.name} · {variableType[variable.type]} · {variable.required?'必填':'选填'}</dt>
          <dd>{variable.allowDefault?`默认值：${typeof variable.defaultValue==='string'?variable.defaultValue:JSON.stringify(variable.defaultValue)}`:'未设置默认值'}；{variable.allowEmpty?'允许空值':'不允许空值'}</dd>
        </div>)}</dl>}
        <p className="consultation-muted">此版本可保存至 <time dateTime={revision.bodyUntil}>{displayTime(revision.bodyUntil)}</time></p>
        {onCopy&&!pending&&<button type="button" onClick={()=>{if(Date.parse(revision.bodyUntil)>Date.now())onCopy({sourceWorkspaceId:revision.workspaceId,
          sourcePromptId:revision.promptId,sourceRevisionId:revision.revisionId,sourceContentSha:revision.contentSha});}}>复制此确切版本</button>}
        {!pending&&<PromptPreviewWorkspace token={token} revision={revision} onUnavailable={previewUnavailable}/>}
      </article>}
    </div>
  </section>;
}
