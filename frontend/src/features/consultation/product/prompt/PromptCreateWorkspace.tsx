import {useEffect,useRef,useState,type FormEvent} from 'react';
import {createPrompt,recoverPromptCreate,PromptCreateError,validPromptRequestKey,type PromptCreateReceipt} from './promptCreateApi';
import {emptyPromptInput,encodePromptCreate,PromptCommandError,type PromptCreateInput} from './promptCreateCommand';
import PromptDraftFields,{promptDraftFieldId} from './PromptDraftFields';
import './prompt.css';
import './promptCreate.css';

export interface PromptCreateWorkspaceProps {token:string|null;workspaceId:string;onSessionExpired?:()=>void;onBack:()=>void}
type Frozen={key:string;serialized:string;uncertain:boolean};
type Mode='editing'|'sending'|'unknown'|'success'|'denied';
const fieldId=(field:string)=>promptDraftFieldId(field);
export default function PromptCreateWorkspace(props:PromptCreateWorkspaceProps){
  if(!props.token)return <section className="consultation-panel prompt-browser"><h2>创建提示词草稿</h2><p>请登录后创建草稿。</p><button type="button" onClick={props.onBack}>返回咨询任务</button></section>;
  return <CreateSession key={props.token+':'+props.workspaceId} {...props} token={props.token}/>;
}
function CreateSession({token,workspaceId,onSessionExpired,onBack}:PromptCreateWorkspaceProps&{token:string}){
  const [draft,setDraft]=useState(emptyPromptInput),[mode,setMode]=useState<Mode>('editing'),[error,setError]=useState(''),[errorField,setErrorField]=useState('');
  const [key,setKey]=useState(''),[lookupKey,setLookupKey]=useState(''),[receipt,setReceipt]=useState<PromptCreateReceipt|null>(null),[retryAllowed,setRetryAllowed]=useState(false);
  const frozen=useRef<Frozen|null>(null),busy=useRef(false),epoch=useRef(0),controller=useRef<AbortController|null>(null),errorRef=useRef<HTMLDivElement>(null),expired=useRef(onSessionExpired);
  expired.current=onSessionExpired;
  useEffect(()=>()=>{epoch.current++;controller.current?.abort();frozen.current=null;busy.current=false;},[]);
  useEffect(()=>{if(error)errorRef.current?.focus();},[error]);
  useEffect(()=>{
    if(!errorField)return;const target=document.getElementById(fieldId(errorField));if(!target)return;
    target.setAttribute('aria-invalid','true');target.setAttribute('aria-describedby','prompt-create-error');
    return()=>{target.removeAttribute('aria-invalid');target.removeAttribute('aria-describedby');};
  },[errorField,error,mode]);
  useEffect(()=>{if(!receipt)return;let timer:ReturnType<typeof setTimeout>|undefined;
    const close=()=>{const left=Date.parse(receipt.auditUntil)-Date.now();if(left<=0){setReceipt(null);setError('该回执已到追溯期限，已关闭显示。');}else timer=setTimeout(close,Math.min(left,2147483647));};close();return()=>clearTimeout(timer);
  },[receipt]);
  const change=<K extends keyof PromptCreateInput>(field:K,value:PromptCreateInput[K])=>setDraft(previous=>({...previous,[field]:value}));
  async function execute(action:'post'|'get',requestKey:string,serialized?:string){
    if(busy.current||mode==='denied')return;
    busy.current=true;const current=++epoch.current,abort=new AbortController();controller.current=abort;
    setKey(requestKey);setError('');setErrorField('');setMode('sending');
    try{const result=action==='post'?await createPrompt(token,workspaceId,requestKey,serialized!,abort.signal):await recoverPromptCreate(token,workspaceId,requestKey,abort.signal);
      if(current!==epoch.current||abort.signal.aborted)return;
      setReceipt(result);setDraft(emptyPromptInput());frozen.current=null;setRetryAllowed(false);setLookupKey('');setMode('success');
    }catch(failure){if(current!==epoch.current||abort.signal.aborted)return;
      const status=failure instanceof PromptCreateError?failure.status:503;
      setError(failure instanceof PromptCreateError?failure.message:'提交结果尚未确认，请查询原提交编号。');
      if(status===401||status===403){setDraft(emptyPromptInput());setReceipt(null);frozen.current=null;setRetryAllowed(false);setLookupKey('');setMode('denied');epoch.current++;if(status===401)expired.current?.();}
      else if(action==='post'&&[400,413,415].includes(status)&&frozen.current&&!frozen.current.uncertain){frozen.current=null;setKey('');setRetryAllowed(false);setMode('editing');}
      else {if(frozen.current)frozen.current.uncertain=true;setMode('unknown');if(status===409)setRetryAllowed(false);
        if(action==='post'&&[400,413,415].includes(status))setError('本次重试被拒绝，但此前提交结果仍未确认；请保留原提交编号并查询结果。');}
    }finally{if(current===epoch.current){busy.current=false;controller.current=null;}}
  }
  function submit(event:FormEvent){event.preventDefault();if(busy.current||mode!=='editing')return;
    try{const serialized=encodePromptCreate(draft),requestKey=crypto.randomUUID();frozen.current={key:requestKey,serialized,uncertain:false};setRetryAllowed(true);void execute('post',requestKey,serialized);}
    catch(failure){setErrorField(failure instanceof PromptCommandError?failure.field:'');setError(failure instanceof PromptCommandError?failure.message:'无法准备提交，请检查浏览器安全连接和输入。');}
  }
  function lookup(){if(busy.current||mode==='denied')return;const requestKey=key||lookupKey;
    if(!validPromptRequestKey(requestKey)){setErrorField('prompt-create-known-key');setError('请输入已记录的提交编号（1～64个非空白 ASCII 字符）。');return;}
    void execute('get',requestKey);
  }
  function newDraft(){if(busy.current||mode!=='success')return;setDraft(emptyPromptInput());setReceipt(null);setKey('');setLookupKey('');setError('');setErrorField('');setMode('editing');}
  return <section className="consultation-panel prompt-browser prompt-create" aria-labelledby="prompt-create-heading">
    <div className="prompt-title"><div><p className="consultation-muted">提示词 · 草稿工作台</p><h2 id="prompt-create-heading">创建提示词草稿</h2></div><button type="button" onClick={onBack}>返回咨询任务</button></div>
    <p className="consultation-muted">工作区编号：{workspaceId}</p>
    {error&&<div id="prompt-create-error" className="prompt-error" role="alert" tabIndex={-1} ref={errorRef}>{error}{errorField&&<> <a href={'#'+fieldId(errorField)} onClick={event=>{event.preventDefault();document.getElementById(fieldId(errorField))?.focus();}}>定位需检查的字段</a></>}</div>}
    <div role="status" aria-live="polite">{mode==='sending'?'正在核验提交，请稍候…':mode==='unknown'?'结果尚未确认，请查询原提交。':mode==='success'?'提交回执已确认。':mode==='denied'?'当前权限不可用。':'请填写草稿。创建不会授予读取或发布权限。'}</div>
    {key&&<div className="prompt-create-recovery"><p>提交编号：<code>{key}</code></p><p>请记录此编号。刷新会清除未保存的正文，之后可凭编号查询结果。</p></div>}
    {(mode==='editing'||mode==='sending'||mode==='unknown')&&<form onSubmit={submit} noValidate autoComplete="off">
      <fieldset disabled={mode!=='editing'} className="prompt-create-fields"><legend>草稿内容</legend>
        <PromptDraftFields draft={draft} onChange={change}/>
        <div className="prompt-create-actions"><button type="submit">创建草稿</button></div>
      </fieldset>
    </form>}
    {mode==='unknown'&&<div className="prompt-create-actions"><button type="button" onClick={lookup}>查询原提交结果</button>{retryAllowed&&frozen.current&&<button type="button" onClick={()=>{const original=frozen.current;if(original)void execute('post',original.key,original.serialized);}}>按原内容重试</button>}</div>}
    {mode==='editing'&&<section className="prompt-create-recovery" aria-labelledby="prompt-create-recovery-heading"><h3 id="prompt-create-recovery-heading">查回已有提交</h3><p>如刷新前已提交，请先使用记录的编号查询。</p><label htmlFor="prompt-create-known-key">原提交编号<input id="prompt-create-known-key" value={lookupKey} onChange={e=>setLookupKey(e.target.value)} autoCapitalize="none" spellCheck={false}/></label><button type="button" onClick={lookup}>查询已有提交</button></section>}
    {receipt&&<section className="prompt-create-receipt" aria-label="提交回执"><h3>草稿已创建</h3><dl><dt>回执编号</dt><dd>{receipt.receiptId}</dd><dt>提示词编号</dt><dd>{receipt.promptId}</dd><dt>修订编号</dt><dd>{receipt.revisionId}</dd><dt>版本</dt><dd>{receipt.generation}</dd><dt>提交时间</dt><dd><time dateTime={receipt.submittedAt}>{new Date(receipt.submittedAt).toLocaleString('zh-CN')}</time></dd><dt>追溯截止</dt><dd><time dateTime={receipt.auditUntil}>{new Date(receipt.auditUntil).toLocaleString('zh-CN')}</time></dd></dl><a href={`/app/consultation?view=prompt&prompt=${receipt.promptId}&revision=${receipt.revisionId}`}>打开确切版本（需要读取权限）</a></section>}
    {mode==='success'&&<button type="button" onClick={newDraft}>新建另一份草稿</button>}
  </section>;
}
