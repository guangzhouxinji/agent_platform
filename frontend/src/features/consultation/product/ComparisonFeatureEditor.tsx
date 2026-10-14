import {useRef,useState} from 'react';
import type {FeatureInput} from './comparisonInputContracts';
export default function ComparisonFeatureEditor({text,features,onChange,disabled}:{text:string;features:FeatureInput[];onChange:(value:FeatureInput[])=>void;disabled:boolean}){
 const source=useRef<HTMLTextAreaElement>(null),[error,setError]=useState('');
 function add(){const node=source.current;if(!node)return;const start=node.selectionStart,end=node.selectionEnd;if(start===end){setError('请先在目标原文中选择需要确认的实际文字。');return;}setError('');onChange([...features,{featureId:'feature-'+crypto.randomUUID(),characterStart:start,characterEnd:end,userText:text.slice(start,end),parentFeatureId:null}]);}
 return <section><h3>逐项核对目标特征</h3><p>使用鼠标或键盘选择原文，再加入一个特征。原文不会改写；特征说明可由您修订，提交必须明确确认。</p>
  <label htmlFor="comparison-target-text">目标确切原文</label><textarea id="comparison-target-text" ref={source} readOnly value={text} rows={7} disabled={disabled}/>
  <button type="button" disabled={disabled} onClick={add}>将选中原文加入特征</button>{error&&<p role="alert">{error}</p>}
  {features.map((f,i)=><article key={f.featureId}><p>特征 {i+1} 原文：{text.slice(f.characterStart,f.characterEnd)}</p><label htmlFor={'feature-'+i}>特征 {i+1} 的确认说明</label><textarea id={'feature-'+i} value={f.userText} disabled={disabled} onChange={e=>onChange(features.map(x=>x.featureId===f.featureId?{...x,userText:e.target.value}:x))}/><button type="button" disabled={disabled} onClick={()=>onChange(features.filter(x=>x.featureId!==f.featureId))}>移除特征 {i+1}</button></article>)}
 </section>;
}
