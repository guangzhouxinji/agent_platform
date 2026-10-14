import {useEffect,useRef,useState,type FormEvent} from 'react';
import type {PromptRevisionView} from './promptApi';
import {emptyPromptPreviewInputs,encodePromptPreview,type PromptPreviewInput} from './promptPreviewCommand';
import {previewPrompt,PromptPreviewError,type PromptPreviewDiagnostic,type PromptPreviewResult} from './promptPreviewApi';
import {PromptCommandError} from './promptCreateCommand';
import './promptCreate.css';

export interface PromptPreviewWorkspaceProps {token:string;revision:PromptRevisionView;onUnavailable:(status:401|403)=>void}
const issueText={MISSING_VARIABLE:'缺少样例值或可用默认值。',UNDEFINED_VARIABLE:'变量未在当前定义中声明。',VARIABLE_TYPE_MISMATCH:'样例值类型不匹配。',VARIABLE_EMPTY_NOT_ALLOWED:'此变量不允许空值。'};
const typeText={STRING:'文本',BOOLEAN:'布尔值',INTEGER:'整数',DECIMAL:'小数',STRING_LIST:'文本列表'};
export default function PromptPreviewWorkspace(props:PromptPreviewWorkspaceProps){
  const value=props.revision;
  return <PreviewSession key={JSON.stringify([props.token,value.workspaceId,value.promptId,value.revisionId,value.contentSha,value.bodyUntil])} {...props}/>;
}
function PreviewSession({token,revision,onUnavailable}:PromptPreviewWorkspaceProps){
  const [sourceId,setSourceId]=useState(''),[inputs,setInputs]=useState(()=>emptyPromptPreviewInputs(revision));
  const [result,setResult]=useState<PromptPreviewResult|null>(null),[diagnostic,setDiagnostic]=useState<PromptPreviewDiagnostic|null>(null);
  const [error,setError]=useState(''),[errorField,setErrorField]=useState(''),[pending,setPending]=useState(false),[closed,setClosed]=useState(false);
  const epoch=useRef(0),busy=useRef(false),controller=useRef<AbortController|null>(null),errorRef=useRef<HTMLDivElement>(null);
  const unavailable=useRef(onUnavailable);unavailable.current=onUnavailable;
  useEffect(()=>()=>{epoch.current++;controller.current?.abort();busy.current=false;},[]);
  useEffect(()=>{if(error)errorRef.current?.focus();},[error,errorField,diagnostic]);
  function invalidate(){epoch.current++;controller.current?.abort();controller.current=null;busy.current=false;setPending(false);setResult(null);setDiagnostic(null);setError('');setErrorField('');}
  function clear(){invalidate();setSourceId('');setInputs(emptyPromptPreviewInputs(revision));}
  useEffect(()=>{
    const until=Math.min(Date.parse(revision.bodyUntil),result?Date.parse(result.expiresAt):Infinity,diagnostic?Date.parse(diagnostic.expiresAt):Infinity);
    let timer:ReturnType<typeof setTimeout>|undefined;
    const close=()=>{const left=until-Date.now();if(left<=0){clear();setClosed(Date.parse(revision.bodyUntil)<=Date.now());setError('预览已到有效期限，已清除样例与结果。');}
      else timer=setTimeout(close,Math.min(left,2147483647));};
    close();return()=>clearTimeout(timer);
  },[revision.bodyUntil,result,diagnostic]);
  function change(name:string,update:Partial<PromptPreviewInput>){
    if(closed)return;invalidate();setInputs(previous=>({...previous,[name]:{...previous[name],...update}}));
  }
  function id(field:string){if(field==='sourceId')return 'prompt-preview-source';const name=field.slice('variable:'.length),index=revision.variables.findIndex(variable=>variable.name===name);
    return index<0?'':'prompt-preview-variable-'+index+(inputs[name]?.provided?'-value':'-provided');}
  const fieldMessage=(field:string)=>errorField===field?error:field.startsWith('variable:')?diagnostic?.issues.filter(issue=>issue.variable===field.slice('variable:'.length)).map(issue=>issueText[issue.code]).join(' ')??'':'';
  const errorId=(field:string)=>field==='sourceId'?'prompt-preview-source-error':'prompt-preview-variable-'+revision.variables.findIndex(variable=>variable.name===field.slice('variable:'.length))+'-error';
  function attributes(name:string){const message=fieldMessage(name);return {'aria-invalid':Boolean(message)||undefined,'aria-describedby':message?errorId(name):undefined};}
  const locate=(name:string)=>{const target=id(name);if(target)document.getElementById(target)?.focus();};
  async function submit(event:FormEvent){
    event.preventDefault();if(busy.current||closed)return;
    let serialized:string;
    try{serialized=encodePromptPreview(revision,sourceId,inputs);}catch(failure){setResult(null);setDiagnostic(null);setErrorField(failure instanceof PromptCommandError?failure.field:'');setError(failure instanceof PromptCommandError?failure.message:'无法准备预览，请检查样例输入。');errorRef.current?.focus();return;}
    invalidate();busy.current=true;setPending(true);const current=++epoch.current,abort=new AbortController();controller.current=abort;
    try{const value=await previewPrompt(token,revision,serialized,abort.signal);if(current!==epoch.current||abort.signal.aborted)return;setResult(value);}
    catch(failure){if(current!==epoch.current||abort.signal.aborted)return;
      const status=failure instanceof PromptPreviewError?failure.status:503;
      if(status===401||status===403){clear();setClosed(true);unavailable.current(status);}
      else{setDiagnostic(failure instanceof PromptPreviewError?failure.diagnostic??null:null);}
      setError(failure instanceof PromptPreviewError?failure.message:'预览结果无法核验，请重新提交。');
    }finally{if(current===epoch.current){busy.current=false;controller.current=null;setPending(false);}}
  }
  return <section className="prompt-create prompt-create-recovery" aria-labelledby="prompt-preview-heading">
    <h3 id="prompt-preview-heading">变量预览</h3>
    {error&&<div id="prompt-preview-error" className="prompt-error" role="alert" tabIndex={-1} ref={errorRef}>{error}
      {errorField&&id(errorField)&&<> <a href={'#'+id(errorField)} onClick={event=>{event.preventDefault();locate(errorField);}}>定位需检查的字段</a></>}
      {diagnostic&&<ul>{diagnostic.issues.map((issue,index)=><li key={index}>{issue.variable}：{issueText[issue.code]}{id('variable:'+issue.variable)&&<> <a href={'#'+id('variable:'+issue.variable)} onClick={event=>{event.preventDefault();locate('variable:'+issue.variable);}}>检查 {issue.variable}</a></>}</li>)}</ul>}
    </div>}
    <p role="status" aria-live="polite">{pending?'正在核验并预览…':result?'变量预览已完成。':closed?'此版本的预览已关闭。':''}</p>
    {!closed&&<form onSubmit={submit} noValidate autoComplete="off">
      <label htmlFor="prompt-preview-source">样例来源编号<input id="prompt-preview-source" value={sourceId} autoCapitalize="none" spellCheck={false} onChange={event=>{invalidate();setSourceId(event.target.value);}} {...attributes('sourceId')}/></label>
      {fieldMessage('sourceId')&&<p id={errorId('sourceId')}>{fieldMessage('sourceId')}</p>}
      {revision.variables.map((variable,index)=>{
        const input=inputs[variable.name],prefix='prompt-preview-variable-'+index,field='variable:'+variable.name,message=fieldMessage(field);
        return <fieldset key={variable.name}><legend>{variable.name} · {typeText[variable.type]}</legend>
          <div className="prompt-create-checks"><label htmlFor={prefix+'-provided'}><input id={prefix+'-provided'} type="checkbox" checked={input.provided}
            onChange={event=>change(variable.name,event.target.checked?{provided:true}:{provided:false,text:variable.type==='STRING_LIST'?'[]':'',booleanValue:false})}
            {...(!input.provided?attributes(field):{})}/>提供 {variable.name} 样例值</label></div>
          {input.provided&&(variable.type==='BOOLEAN'?<div className="prompt-create-checks"><label htmlFor={prefix+'-value'}><input id={prefix+'-value'} type="checkbox" checked={input.booleanValue} onChange={event=>change(variable.name,{booleanValue:event.target.checked})} {...attributes(field)}/>{variable.name} 样例布尔值</label></div>:
            <label htmlFor={prefix+'-value'}>{variable.name} 样例值{variable.type==='STRING_LIST'?'（JSON 文本数组）':''}{variable.type==='STRING_LIST'||variable.type==='STRING'?
              <textarea id={prefix+'-value'} value={input.text} rows={variable.type==='STRING_LIST'?3:2} onChange={event=>change(variable.name,{text:event.target.value})} spellCheck={false} {...attributes(field)}/>:
              <input id={prefix+'-value'} value={input.text} inputMode={variable.type==='INTEGER'?'numeric':'decimal'} onChange={event=>change(variable.name,{text:event.target.value})} spellCheck={false} {...attributes(field)}/>}</label>)}
          {message&&<p id={errorId(field)}>{message}</p>}
        </fieldset>;
      })}
      <div className="prompt-create-actions"><button type="submit" disabled={pending}>预览样例</button><button type="button" onClick={clear}>清除样例</button></div>
    </form>}
    {result&&<section aria-label="变量预览结果"><h4>变量预览结果</h4><p className="consultation-muted">有效至 <time dateTime={result.expiresAt}>{new Date(result.expiresAt).toLocaleString('zh-CN')}</time></p>
      <ol>{result.segments.map((segment,index)=><li key={index}>{segment.kind==='LITERAL'?<pre className="prompt-body">{segment.text}</pre>:
        <div><h5>{segment.name} · {typeText[segment.type]}</h5><pre className="prompt-body">{JSON.stringify(segment.value)}</pre><p className="consultation-muted">来源依据编号：<code>{segment.sourceEvidenceId}</code></p></div>}</li>)}</ol>
    </section>}
    {diagnostic&&<p className="consultation-muted">诊断有效至 <time dateTime={diagnostic.expiresAt}>{new Date(diagnostic.expiresAt).toLocaleString('zh-CN')}</time></p>}
  </section>;
}
