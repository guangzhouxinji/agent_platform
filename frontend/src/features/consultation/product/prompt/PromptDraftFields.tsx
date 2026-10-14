import {emptyPromptVariable,type PromptCreateInput,type PromptVariableInput,type PromptVariableType} from './promptCreateCommand';

export function promptDraftFieldId(field:string,prefix='prompt-create-'){
  const suffix:Record<string,string>={kind:'kind',name:'name',sourceId:'source',audienceRef:'audience',language:'language',displayOrder:'order',body:'body'};
  return suffix[field]?prefix+suffix[field]:field;
}
function StringRows({label,id,values,onChange,max=64}:{label:string;id:string;values:string[];onChange:(values:string[])=>void;max?:number}){
  return <div className="prompt-create-rows" id={id} tabIndex={-1}><h4>{label}</h4>{values.map((value,index)=><div className="prompt-create-row" key={index}>
    <label htmlFor={`${id}-${index}`}>{label} {index+1}</label><input id={`${id}-${index}`} value={value} onChange={e=>onChange(values.map((v,i)=>i===index?e.target.value:v))}/>
    <button type="button" aria-label={`移除${label} ${index+1}`} onClick={()=>onChange(values.filter((_,i)=>i!==index))}>移除</button>
  </div>)}<button type="button" disabled={values.length>=max} onClick={()=>onChange([...values,''])}>添加{label}</button></div>;
}
function VariableEditor({value,index,onChange,onRemove}:{value:PromptVariableInput;index:number;onChange:(value:PromptVariableInput)=>void;onRemove:()=>void}){
  const id=`variables-${index}`,change=<K extends keyof PromptVariableInput>(key:K,next:PromptVariableInput[K])=>onChange({...value,[key]:next});
  return <fieldset className="prompt-create-variable"><legend>变量 {index+1}</legend><div className="prompt-create-grid">
    <label htmlFor={`${id}-name`}>变量名称<input id={`${id}-name`} value={value.name} onChange={e=>change('name',e.target.value)}/></label>
    <label htmlFor={`${id}-type`}>值类型<select id={`${id}-type`} value={value.type} onChange={e=>onChange({...value,type:e.target.value as PromptVariableType,defaultText:'',defaultBoolean:false,defaultList:[]})}>
      <option value="STRING">文本</option><option value="BOOLEAN">布尔值</option><option value="INTEGER">整数</option><option value="DECIMAL">小数</option><option value="STRING_LIST">文本列表</option>
    </select></label>
    <label htmlFor={`${id}-sourceRef`}>变量来源引用<input id={`${id}-sourceRef`} value={value.sourceRef} onChange={e=>change('sourceRef',e.target.value)}/></label>
  </div><div className="prompt-create-checks">
    <label><input type="checkbox" checked={value.required} onChange={e=>change('required',e.target.checked)}/>必填变量</label>
    <label><input type="checkbox" checked={value.allowEmpty} onChange={e=>change('allowEmpty',e.target.checked)}/>允许空值</label>
    <label><input type="checkbox" checked={value.allowDefault} onChange={e=>onChange({...value,allowDefault:e.target.checked,defaultText:'',defaultBoolean:false,defaultList:[]})}/>设置默认值</label>
  </div>{value.allowDefault&&(value.type==='STRING_LIST'?<StringRows id={`${id}-defaultValue`} label={`变量 ${index+1} 默认列表项`} values={value.defaultList} max={256} onChange={v=>change('defaultList',v)}/>:
    value.type==='BOOLEAN'?<label htmlFor={`${id}-defaultValue`}>默认值<select id={`${id}-defaultValue`} value={String(value.defaultBoolean)} onChange={e=>change('defaultBoolean',e.target.value==='true')}><option value="false">否（false）</option><option value="true">是（true）</option></select></label>:
      <label htmlFor={`${id}-defaultValue`}>默认值<input id={`${id}-defaultValue`} value={value.defaultText} inputMode={value.type==='INTEGER'?'numeric':value.type==='DECIMAL'?'decimal':'text'} onChange={e=>change('defaultText',e.target.value)}/>{value.type==='DECIMAL'&&<span className="consultation-muted">保留完整精度，请包含小数点或指数，例如 1.00。</span>}</label>)}
    <button type="button" onClick={onRemove}>移除变量 {index+1}</button></fieldset>;
}

export default function PromptDraftFields({draft,onChange,prefix='prompt-create-'}:{draft:PromptCreateInput;onChange:<K extends keyof PromptCreateInput>(field:K,value:PromptCreateInput[K])=>void;prefix?:string}){
  const change=onChange;
  return <>
        <div className="prompt-create-grid">
          <label htmlFor={prefix+'kind'}>提示词类型<select id={prefix+'kind'} value={draft.kind} onChange={e=>change('kind',e.target.value as PromptCreateInput['kind'])}><option value="GUIDE">引导提示词</option><option value="SYSTEM">系统提示词</option></select></label>
          <label htmlFor={prefix+'name'}>名称<input id={prefix+'name'} value={draft.name} onChange={e=>change('name',e.target.value)} maxLength={128}/></label>
          <label htmlFor={prefix+'source'}>已获许可来源编号<input id={prefix+'source'} value={draft.sourceId} onChange={e=>change('sourceId',e.target.value)} autoCapitalize="none" spellCheck={false}/><span className="consultation-muted">填写责任方提供、覆盖本次完整内容的准确来源编号。</span></label>
          <label htmlFor={prefix+'audience'}>受众引用<input id={prefix+'audience'} value={draft.audienceRef} onChange={e=>change('audienceRef',e.target.value)} maxLength={500}/></label>
          <label htmlFor={prefix+'language'}>语言<input id={prefix+'language'} value={draft.language} onChange={e=>change('language',e.target.value)} maxLength={63}/></label>
          <label htmlFor={prefix+'order'}>排序<input id={prefix+'order'} value={draft.displayOrder} inputMode="numeric" onChange={e=>change('displayOrder',e.target.value)}/></label>
        </div>
        <label htmlFor={prefix+'body'}>提示词正文<textarea id={prefix+'body'} rows={10} value={draft.body} onChange={e=>change('body',e.target.value)}/></label>
        <div className="prompt-create-grid"><StringRows id="scenarioIds" label="场景编号" values={draft.scenarioIds} onChange={v=>change('scenarioIds',v)}/><StringRows id="intentIds" label="意图编号" values={draft.intentIds} onChange={v=>change('intentIds',v)}/></div>
        <h3 id="variables" tabIndex={-1}>变量定义</h3>{draft.variables.map((value,index)=><VariableEditor key={index} value={value} index={index} onChange={v=>change('variables',draft.variables.map((old,i)=>i===index?v:old))} onRemove={()=>change('variables',draft.variables.filter((_,i)=>i!==index))}/>)}
        <button type="button" disabled={draft.variables.length>=64} onClick={()=>change('variables',[...draft.variables,emptyPromptVariable()])}>添加变量</button>
  </>;
}
