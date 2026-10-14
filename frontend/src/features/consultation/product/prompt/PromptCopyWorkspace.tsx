import {useEffect,useRef,useState,type FormEvent} from 'react';
import {copyPrompt,recoverPromptCopy,PromptCopyError,type PromptCopyReceipt} from './promptCopyApi';
import {encodePromptCopy,promptCopyUuid,validPromptCopySource,type PromptCopySource,type PromptCopyInput} from './promptCopyCommand';
import {validPromptRequestKey} from './promptCreateApi';
import {PromptCommandError} from './promptCreateCommand';
import './prompt.css';
import './promptCreate.css';

export interface PromptCopyWorkspaceProps {token:string|null;source?:PromptCopySource;onSessionExpired?:()=>void;onBack:()=>void}
type Mode='editing'|'sending'|'unknown'|'success'|'denied';
type Lookup={sourceWorkspaceId:string;targetWorkspaceId:string;key:string};
type Frozen=Lookup&{token:string;command?:{source:PromptCopySource;sourceId:string;serialized:string};uncertain:boolean;lookupOnly:boolean};
const emptyInput=():PromptCopyInput=>({targetWorkspaceId:'',sourceId:''});
const emptyLookup=():Lookup=>({sourceWorkspaceId:'',targetWorkspaceId:'',key:''});
const fieldId=(field:string)=>'prompt-copy-'+field;
export default function PromptCopyWorkspace(props:PromptCopyWorkspaceProps){
  if(!props.token)return <section className="consultation-panel prompt-browser"><h2>复制提示词确切版本</h2><p>请登录后复制或查询原提交。</p><button type="button" onClick={props.onBack}>返回咨询任务</button></section>;
  return <CopySession key={JSON.stringify([props.token,props.source?.sourceWorkspaceId,props.source?.sourcePromptId,props.source?.sourceRevisionId,props.source?.sourceContentSha])} {...props} token={props.token}/>;
}
function CopySession({token,source,onSessionExpired,onBack}:PromptCopyWorkspaceProps&{token:string}){
  const [input,setInput]=useState(emptyInput),[lookup,setLookup]=useState(emptyLookup),[mode,setMode]=useState<Mode>('editing');
  const [error,setError]=useState(''),[errorField,setErrorField]=useState(''),[key,setKey]=useState('');
  const [receipt,setReceipt]=useState<PromptCopyReceipt|null>(null),[recovered,setRecovered]=useState(false);
  const frozen=useRef<Frozen|null>(null),busy=useRef(false),epoch=useRef(0),controller=useRef<AbortController|null>(null);
  const errorRef=useRef<HTMLDivElement>(null),expired=useRef(onSessionExpired);expired.current=onSessionExpired;
  const canCopy=source!==undefined&&validPromptCopySource(source);
  useEffect(()=>()=>{epoch.current++;controller.current?.abort();frozen.current=null;busy.current=false;},[]);
  useEffect(()=>{if(error)errorRef.current?.focus();},[error]);
  useEffect(()=>{if(!receipt)return;let timer:ReturnType<typeof setTimeout>|undefined;
    const close=()=>{const left=Date.parse(receipt.auditUntil)-Date.now();if(left<=0){setReceipt(null);setKey('');setMode('editing');setError('该回执已到追溯期限，已关闭显示。');}else timer=setTimeout(close,Math.min(left,2147483647));};
    close();return()=>clearTimeout(timer);
  },[receipt]);
  function clear(){epoch.current++;controller.current?.abort();controller.current=null;frozen.current=null;busy.current=false;
    setInput(emptyInput());setLookup(emptyLookup());setReceipt(null);setKey('');setErrorField('');}
  function back(){clear();setMode('denied');onBack();}
  function validation(failure:unknown){setErrorField(failure instanceof PromptCommandError?failure.field:'');
    setError(failure instanceof PromptCommandError?failure.message:'无法准备复制，请检查浏览器安全连接和输入。');}
  async function execute(action:'post'|'get',original:Frozen){
    if(busy.current||mode==='denied'||action==='post'&&(!original.command||original.lookupOnly))return;
    busy.current=true;const current=++epoch.current,abort=new AbortController();controller.current=abort;frozen.current=original;
    setKey(original.key);setError('');setErrorField('');setReceipt(null);setMode('sending');
    try{
      const result=action==='post'?await copyPrompt(original.token,original.command!.source,original.targetWorkspaceId,original.key,original.command!.serialized,abort.signal):
        await recoverPromptCopy(original.token,original.sourceWorkspaceId,original.targetWorkspaceId,original.key,
          original.lookupOnly?undefined:original.command?.source.sourceContentSha,abort.signal);
      if(current!==epoch.current||abort.signal.aborted)return;
      setReceipt(result);setRecovered(action==='get');setInput(emptyInput());setLookup(emptyLookup());frozen.current=null;setMode('success');
    }catch(failure){
      if(current!==epoch.current||abort.signal.aborted)return;
      const status=failure instanceof PromptCopyError?failure.status:503;
      setError(failure instanceof PromptCopyError?failure.message:'复制结果尚未确认，请查询原提交编号。');
      if(status===401||status===403){clear();setMode('denied');if(status===401)expired.current?.();}
      else if(action==='post'&&[400,413,415].includes(status)&&!original.uncertain){
        frozen.current=null;setKey('');setMode('editing');
      }else if(original.command){
        original.uncertain=true;if(status===409)original.lookupOnly=true;setMode('unknown');
        if(action==='post'&&[400,413,415,409].includes(status))setError('本次请求被拒绝，但原提交结果仍需核对；请保留原提交编号并查询结果。');
      }else{frozen.current=null;setKey('');setMode('editing');}
    }finally{if(current===epoch.current){busy.current=false;controller.current=null;}}
  }
  function submit(event:FormEvent){
    event.preventDefault();if(busy.current||mode!=='editing'||!source||!canCopy||frozen.current?.command)return;
    try{
      const snapshot={...source},serialized=encodePromptCopy(snapshot,input),requestKey=crypto.randomUUID();
      const original:Frozen={token,sourceWorkspaceId:snapshot.sourceWorkspaceId,targetWorkspaceId:input.targetWorkspaceId,key:requestKey,
        command:{source:snapshot,sourceId:input.sourceId,serialized},uncertain:false,lookupOnly:false};
      void execute('post',original);
    }catch(failure){validation(failure);}
  }
  function recover(){
    if(busy.current||mode==='denied'||frozen.current?.command)return;
    try{
      if(!promptCopyUuid.test(lookup.sourceWorkspaceId))throw new PromptCommandError('recover-sourceWorkspaceId','请输入原复制提交的来源工作区编号（小写 UUID）。');
      if(!promptCopyUuid.test(lookup.targetWorkspaceId))throw new PromptCommandError('recover-targetWorkspaceId','请输入原复制提交的目标工作区编号（小写 UUID）。');
      if(!validPromptRequestKey(lookup.key))throw new PromptCommandError('recover-key','请输入已记录的提交编号（1～64个非空白 ASCII 字符）。');
      void execute('get',{...lookup,token,uncertain:false,lookupOnly:true});
    }catch(failure){validation(failure);}
  }
  function changeLookup(field:keyof Lookup,value:string){
    if(frozen.current?.command||mode==='denied')return;
    epoch.current++;controller.current?.abort();controller.current=null;busy.current=false;frozen.current=null;
    setLookup(previous=>({...previous,[field]:value}));setReceipt(null);setKey('');setError('');setErrorField('');setMode('editing');
  }
  const invalid=(field:string)=>({'aria-invalid':errorField===field||undefined,'aria-describedby':errorField===field?'prompt-copy-error':undefined});
  const original=frozen.current,independent=!original?.command;
  return <section className="consultation-panel prompt-browser prompt-create" aria-labelledby="prompt-copy-heading">
    <div className="prompt-title"><div><p className="consultation-muted">提示词 · 复制工作台</p><h2 id="prompt-copy-heading">复制提示词确切版本</h2></div><button type="button" onClick={back}>返回咨询任务</button></div>
    {error&&<div id="prompt-copy-error" className="prompt-error" role="alert" tabIndex={-1} ref={errorRef}>{error}{errorField&&<> <a href={'#'+fieldId(errorField)} onClick={event=>{event.preventDefault();document.getElementById(fieldId(errorField))?.focus();}}>定位需检查的字段</a></>}</div>}
    <p role="status" aria-live="polite">{mode==='sending'?'正在核验复制提交，请稍候…':mode==='unknown'?'结果尚未确认，请查询原提交。':mode==='denied'?'当前权限不可用。':mode==='success'?'复制回执已确认。':''}</p>
    {key&&mode!=='denied'&&<p>提交编号：<code>{key}</code></p>}
    {mode!=='denied'&&mode!=='success'&&canCopy&&source&&<>
      <p className="consultation-muted">来源工作区：<code>{source.sourceWorkspaceId}</code></p><p className="consultation-muted">来源提示词：<code>{source.sourcePromptId}</code></p>
      <p className="consultation-muted">来源修订：<code>{source.sourceRevisionId}</code></p><p className="consultation-muted">内容摘要：<code>{source.sourceContentSha}</code></p>
      <form onSubmit={submit} noValidate autoComplete="off"><fieldset disabled={mode!=='editing'}><legend>复制目标与来源许可</legend><div className="prompt-create-grid">
        <label htmlFor={fieldId('targetWorkspaceId')}>目标工作区编号<input id={fieldId('targetWorkspaceId')} value={input.targetWorkspaceId} onChange={e=>setInput(previous=>({...previous,targetWorkspaceId:e.target.value}))} autoCapitalize="none" spellCheck={false} {...invalid('targetWorkspaceId')}/></label>
        <label htmlFor={fieldId('sourceId')}>已获许可来源编号<input id={fieldId('sourceId')} value={input.sourceId} onChange={e=>setInput(previous=>({...previous,sourceId:e.target.value}))} autoCapitalize="none" spellCheck={false} {...invalid('sourceId')}/></label>
      </div><button type="submit">复制确切版本</button></fieldset></form>
    </>}
    {source&&!canCopy&&mode!=='denied'&&<p className="prompt-error">复制来源链接不正确，请重新打开确切版本。</p>}
    {mode==='unknown'&&original?.command&&<div className="prompt-create-actions"><button type="button" onClick={()=>void execute('get',original)}>查询原提交结果</button>
      {!original.lookupOnly&&<button type="button" onClick={()=>void execute('post',original)}>按原内容重试</button>}</div>}
    {mode!=='denied'&&mode!=='success'&&independent&&<section className="prompt-create-recovery" aria-labelledby="prompt-copy-recovery-heading"><h3 id="prompt-copy-recovery-heading">查回已有复制</h3>
      <div className="prompt-create-grid"><label htmlFor={fieldId('recover-sourceWorkspaceId')}>原来源工作区编号<input id={fieldId('recover-sourceWorkspaceId')} value={lookup.sourceWorkspaceId} onChange={e=>changeLookup('sourceWorkspaceId',e.target.value)} autoCapitalize="none" spellCheck={false} {...invalid('recover-sourceWorkspaceId')}/></label>
        <label htmlFor={fieldId('recover-targetWorkspaceId')}>原目标工作区编号<input id={fieldId('recover-targetWorkspaceId')} value={lookup.targetWorkspaceId} onChange={e=>changeLookup('targetWorkspaceId',e.target.value)} autoCapitalize="none" spellCheck={false} {...invalid('recover-targetWorkspaceId')}/></label></div>
      <label htmlFor={fieldId('recover-key')}>原提交编号<input id={fieldId('recover-key')} value={lookup.key} onChange={e=>changeLookup('key',e.target.value)} autoCapitalize="none" spellCheck={false} {...invalid('recover-key')}/></label>
      <button type="button" disabled={busy.current} onClick={recover}>查询已有复制</button></section>}
    {receipt&&<section className="prompt-create-receipt" aria-label="复制回执"><h3>{recovered?'已查回原复制提交':'确切版本已复制'}</h3><dl>
      <dt>回执编号</dt><dd>{receipt.receiptId}</dd><dt>目标工作区编号</dt><dd>{receipt.workspaceId}</dd><dt>提示词编号</dt><dd>{receipt.promptId}</dd><dt>修订编号</dt><dd>{receipt.revisionId}</dd><dt>版本</dt><dd>{receipt.generation}</dd>
      <dt>内容摘要</dt><dd>{receipt.contentSha}</dd><dt>结果摘要</dt><dd>{receipt.resultSha}</dd><dt>提交时间</dt><dd><time dateTime={receipt.submittedAt}>{new Date(receipt.submittedAt).toLocaleString('zh-CN')}</time></dd>
      <dt>追溯截止</dt><dd><time dateTime={receipt.auditUntil}>{new Date(receipt.auditUntil).toLocaleString('zh-CN')}</time></dd></dl>
      <a href={`/app/consultation?view=prompt&prompt=${receipt.promptId}&revision=${receipt.revisionId}`}>打开确切版本（需要读取权限）</a></section>}
    {mode==='success'&&<button type="button" onClick={()=>{clear();setError('');setMode('editing');}}>返回复制表单</button>}
  </section>;
}
