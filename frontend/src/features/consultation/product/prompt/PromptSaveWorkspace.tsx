import {useEffect,useRef,useState,type FormEvent} from 'react';
import {savePrompt,recoverPromptSave,PromptSaveError,type PromptSaveReceipt} from './promptSaveApi';
import {validPromptRequestKey} from './promptCreateApi';
import {emptyPromptInput,PromptCommandError,type PromptCreateInput} from './promptCreateCommand';
import {emptyPromptSaveBase,encodePromptSave} from './promptSaveCommand';
import PromptDraftFields,{promptDraftFieldId} from './PromptDraftFields';
import './prompt.css';
import './promptCreate.css';

export interface PromptSaveWorkspaceProps{token:string|null;workspaceId:string;promptId:string;onSessionExpired?:()=>void;onBack:()=>void}
type Frozen={key:string;serialized:string;targetGeneration:string;uncertain:boolean};
type Mode='editing'|'sending'|'unknown'|'success'|'denied';
const fieldId=(field:string)=>field==='expectedGeneration'?'prompt-save-generation':field==='expectedRevisionId'?'prompt-save-revision':promptDraftFieldId(field,'prompt-save-');
const ownedErrorFields=new Set(['expectedGeneration','expectedRevisionId','prompt-save-known-key']);
export default function PromptSaveWorkspace(props:PromptSaveWorkspaceProps){
  if(!props.token)return <section className="consultation-panel prompt-browser"><h2>保存提示词新修订</h2><p>请登录后保存草稿。</p><button type="button" onClick={props.onBack}>返回咨询任务</button></section>;
  return <SaveSession key={props.token+':'+props.workspaceId+':'+props.promptId} {...props} token={props.token}/>;
}
function SaveSession({token,workspaceId,promptId,onSessionExpired,onBack}:PromptSaveWorkspaceProps&{token:string}){
  const [draft,setDraft]=useState(emptyPromptInput),[base,setBase]=useState(emptyPromptSaveBase),[mode,setMode]=useState<Mode>('editing');
  const [error,setError]=useState(''),[errorField,setErrorField]=useState(''),[key,setKey]=useState(''),[lookupKey,setLookupKey]=useState('');
  const [receipt,setReceipt]=useState<PromptSaveReceipt|null>(null),[retryAllowed,setRetryAllowed]=useState(false);
  const frozen=useRef<Frozen|null>(null),busy=useRef(false),epoch=useRef(0),controller=useRef<AbortController|null>(null),errorRef=useRef<HTMLDivElement>(null),expired=useRef(onSessionExpired);
  expired.current=onSessionExpired;
  useEffect(()=>()=>{epoch.current++;controller.current?.abort();frozen.current=null;busy.current=false;},[]);
  useEffect(()=>{if(error)errorRef.current?.focus();},[error]);
  useEffect(()=>{
    if(!errorField||ownedErrorFields.has(errorField))return;const target=document.getElementById(fieldId(errorField));if(!target)return;
    target.setAttribute('aria-invalid','true');target.setAttribute('aria-describedby','prompt-save-error');
    return()=>{target.removeAttribute('aria-invalid');target.removeAttribute('aria-describedby');};
  },[errorField,error,mode]);
  useEffect(()=>{if(!receipt)return;let timer:ReturnType<typeof setTimeout>|undefined;
    const close=()=>{const left=Date.parse(receipt.auditUntil)-Date.now();if(left<=0){setReceipt(null);setError('该回执已到追溯期限，已关闭显示。');}else timer=setTimeout(close,Math.min(left,2147483647));};close();return()=>clearTimeout(timer);
  },[receipt]);
  const change=<K extends keyof PromptCreateInput>(field:K,value:PromptCreateInput[K])=>setDraft(previous=>({...previous,[field]:value}));
  async function execute(action:'post'|'get',requestKey:string){
    if(busy.current||mode==='denied')return;
    const original=frozen.current;
    if(action==='post'&&!original)return;
    busy.current=true;const current=++epoch.current,abort=new AbortController();controller.current=abort;
    setKey(requestKey);setError('');setErrorField('');setMode('sending');
    try{
      const result=action==='post'?await savePrompt(token,workspaceId,promptId,requestKey,original!.serialized,original!.targetGeneration,abort.signal):
        await recoverPromptSave(token,workspaceId,promptId,requestKey,original?.targetGeneration,abort.signal);
      if(current!==epoch.current||abort.signal.aborted)return;
      setReceipt(result);setDraft(emptyPromptInput());setBase(emptyPromptSaveBase());frozen.current=null;setRetryAllowed(false);setLookupKey('');setMode('success');
    }catch(failure){
      if(current!==epoch.current||abort.signal.aborted)return;
      const status=failure instanceof PromptSaveError?failure.status:503;
      const baseConflict=failure instanceof PromptSaveError&&failure.code==='PROMPT_SAVE_BASE_CONFLICT';
      setError(failure instanceof PromptSaveError?failure.message:'保存结果尚未确认，请查询原提交编号。');
      if(status===401||status===403){
        setDraft(emptyPromptInput());setBase(emptyPromptSaveBase());setReceipt(null);frozen.current=null;setRetryAllowed(false);setLookupKey('');setMode('denied');epoch.current++;if(status===401)expired.current?.();
      }else if(action==='post'&&([400,413,415].includes(status)||status===409&&baseConflict)&&original&&!original.uncertain){
        frozen.current=null;setKey('');setRetryAllowed(false);setMode('editing');if(baseConflict)setErrorField('expectedGeneration');
      }else{
        if(frozen.current)frozen.current.uncertain=true;setMode('unknown');if(status===409)setRetryAllowed(false);
        if(action==='post'&&([400,413,415].includes(status)||status===409))setError('本次请求被拒绝，但原提交结果仍需核对；请保留原提交编号并查询结果。');
      }
    }finally{if(current===epoch.current){busy.current=false;controller.current=null;}}
  }
  function submit(event:FormEvent){
    event.preventDefault();if(busy.current||mode!=='editing')return;
    try{
      const serialized=encodePromptSave(draft,base),requestKey=crypto.randomUUID(),targetGeneration=(BigInt(base.expectedGeneration)+1n).toString();
      frozen.current={key:requestKey,serialized,targetGeneration,uncertain:false};setRetryAllowed(true);void execute('post',requestKey);
    }catch(failure){setErrorField(failure instanceof PromptCommandError?failure.field:'');setError(failure instanceof PromptCommandError?failure.message:'无法准备保存，请检查浏览器安全连接和输入。');}
  }
  function lookup(){
    if(busy.current||mode==='denied')return;const requestKey=key||lookupKey;
    if(!validPromptRequestKey(requestKey)){setErrorField('prompt-save-known-key');setError('请输入已记录的提交编号（1～64个非空白 ASCII 字符）。');return;}
    void execute('get',requestKey);
  }
  function newDraft(){
    if(busy.current||mode!=='success')return;
    setBase(receipt?{expectedGeneration:receipt.generation,expectedRevisionId:receipt.revisionId}:emptyPromptSaveBase());
    setDraft(emptyPromptInput());setReceipt(null);setKey('');setLookupKey('');setError('');setErrorField('');setMode('editing');
  }
  return <section className="consultation-panel prompt-browser prompt-create" aria-labelledby="prompt-save-heading">
    <div className="prompt-title"><div><p className="consultation-muted">提示词 · 草稿工作台</p><h2 id="prompt-save-heading">保存提示词新修订</h2></div><button type="button" onClick={onBack}>返回咨询任务</button></div>
    <p className="consultation-muted">工作区编号：<code>{workspaceId}</code></p><p className="consultation-muted">提示词编号：<code>{promptId}</code></p>
    {error&&<div id="prompt-save-error" className="prompt-error" role="alert" tabIndex={-1} ref={errorRef}>{error}{errorField&&<> <a href={'#'+fieldId(errorField)} onClick={event=>{event.preventDefault();document.getElementById(fieldId(errorField))?.focus();}}>定位需检查的字段</a></>}</div>}
    <div role="status" aria-live="polite">{mode==='sending'?'正在核验保存，请稍候…':mode==='unknown'?'结果尚未确认，请查询原提交。':mode==='success'?'保存回执已确认。':mode==='denied'?'当前权限不可用。':'填写完整新稿及准确的已知版本。保存后查看正文仍需读取权限。'}</div>
    {key&&<div className="prompt-create-recovery"><p>提交编号：<code>{key}</code></p><p>请记录此编号。刷新会清除未保存的正文，之后可凭编号查询结果。</p></div>}
    {(mode==='editing'||mode==='sending'||mode==='unknown')&&<form onSubmit={submit} noValidate autoComplete="off">
      <fieldset disabled={mode!=='editing'} className="prompt-create-fields"><legend>完整新稿与已知版本</legend>
        <div className="prompt-create-grid">
          <label htmlFor="prompt-save-generation">已知版本<input id="prompt-save-generation" value={base.expectedGeneration} inputMode="numeric" aria-invalid={errorField==='expectedGeneration'||undefined} aria-describedby={errorField==='expectedGeneration'?'prompt-save-error':undefined} onChange={e=>setBase(previous=>({...previous,expectedGeneration:e.target.value}))}/></label>
          <label htmlFor="prompt-save-revision">已知修订编号<input id="prompt-save-revision" value={base.expectedRevisionId} autoCapitalize="none" spellCheck={false} aria-invalid={errorField==='expectedRevisionId'||undefined} aria-describedby={errorField==='expectedRevisionId'?'prompt-save-error':undefined} onChange={e=>setBase(previous=>({...previous,expectedRevisionId:e.target.value}))}/></label>
        </div>
        <p className="consultation-muted">请核对同一份已知版本及修订编号；已有回执中的版本也可能已被后续保存更新。</p>
        <PromptDraftFields draft={draft} onChange={change} prefix="prompt-save-"/>
        <div className="prompt-create-actions"><button type="submit">保存新修订</button></div>
      </fieldset>
    </form>}
    {mode==='unknown'&&<div className="prompt-create-actions"><button type="button" onClick={lookup}>查询原提交结果</button>{retryAllowed&&frozen.current&&<button type="button" onClick={()=>{const original=frozen.current;if(original)void execute('post',original.key);}}>按原内容重试</button>}</div>}
    {mode==='editing'&&<section className="prompt-create-recovery" aria-labelledby="prompt-save-recovery-heading"><h3 id="prompt-save-recovery-heading">查回已有保存</h3><p>仅查询当前提示词的原提交编号，查回不会重复保存。</p><label htmlFor="prompt-save-known-key">原提交编号<input id="prompt-save-known-key" value={lookupKey} onChange={e=>setLookupKey(e.target.value)} autoCapitalize="none" spellCheck={false} aria-invalid={errorField==='prompt-save-known-key'||undefined} aria-describedby={errorField==='prompt-save-known-key'?'prompt-save-error':undefined}/></label><button type="button" onClick={lookup}>查询已有保存</button></section>}
    {receipt&&<section className="prompt-create-receipt" aria-label="保存回执"><h3>新修订已保存</h3><dl><dt>回执编号</dt><dd>{receipt.receiptId}</dd><dt>提示词编号</dt><dd>{receipt.promptId}</dd><dt>修订编号</dt><dd>{receipt.revisionId}</dd><dt>版本</dt><dd>{receipt.generation}</dd><dt>提交时间</dt><dd><time dateTime={receipt.submittedAt}>{new Date(receipt.submittedAt).toLocaleString('zh-CN')}</time></dd><dt>追溯截止</dt><dd><time dateTime={receipt.auditUntil}>{new Date(receipt.auditUntil).toLocaleString('zh-CN')}</time></dd></dl><a href={`/app/consultation?view=prompt&prompt=${receipt.promptId}&revision=${receipt.revisionId}`}>打开确切版本（需要读取权限）</a></section>}
    {mode==='success'&&<button type="button" onClick={newDraft}>填写下一份完整新稿</button>}
  </section>;
}
