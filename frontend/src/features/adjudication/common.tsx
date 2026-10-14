import { useEffect,useState } from "react";
import { digest } from "./api";
import type { Source } from "./contract";
export function Text({text,title,quote=""}:{text:string;title:string;quote?:string}){
 const [search,setSearch]=useState(quote);useEffect(()=>setSearch(quote),[quote]);
 const parts=search?text.split(search):[text];const found=search&&parts.length>1;
 return <div className="adj-original"><label>在{title}中定位<input type="search" value={search} onChange={e=>setSearch(e.target.value)} placeholder="输入确切片段"/></label>{search&&<small role="status">{found?`找到 ${parts.length-1} 处原文片段`:"当前原文未找到该片段"}</small>}<div className="adj-long-text" tabIndex={0} aria-label={title}>{parts.map((part,i)=><span key={i}>{i>0&&<mark>{search}</mark>}{part}</span>)}</div></div>;
}
export function SourceCard({source,text}:{source:Source;text?:string}){
 const [check,setCheck]=useState("未进行浏览器摘要核对");useEffect(()=>{let live=true;setCheck("正在核对原文摘要");if(text===undefined){setCheck("正文不可得，不能进行摘要核对");return;}void digest(text).then(hash=>{if(live)setCheck(hash===source.sha256?"原文摘要一致":"原文摘要不一致，请停止使用并核查");}).catch(()=>{if(live)setCheck("当前环境无法核对摘要");});return()=>{live=false}},[text,source.sha256]);
 return <aside className="adj-source"><strong>{source.kind==="PUBLIC"?"公开来源（用途仍须授权）":source.kind==="DEIDENTIFIED"?"脱敏来源":source.kind==="USER_PROVIDED"?"用户提供来源":"已登记资料"}</strong><dl><dt>来源版本</dt><dd>{source.id} / {source.version}</dd><dt>原文定位</dt><dd>{source.locator}</dd><dt>SHA-256</dt><dd className="adj-hash">{source.sha256}</dd></dl><span role="status">{check}；来源用途以服务器当前校验为准</span></aside>;
}
export function Panel({title,children}:{title:string;children:React.ReactNode}){return <section className="adj-panel"><h2>{title}</h2>{children}</section>}
export function Field({label,name,area=false,required=true,value,type="text"}:{label:string;name:string;area?:boolean;required?:boolean;value?:string;type?:string}){return <label>{label}{area?<textarea aria-label={label} name={name} required={required} defaultValue={value} rows={4}/>:<input aria-label={label} name={name} required={required} defaultValue={value} type={type}/>}</label>}
export const val=(data:FormData,name:string)=>String(data.get(name)??"");
export function SourceFields({textName}:{textName:string}){
 const [sha,setSha]=useState("");return <fieldset><legend>原文来源（不自动获得用途许可）</legend><Field name="sourceId" label="来源ID"/><Field name="sourceVersion" label="来源版本"/><Field name="locator" label="原文定位"/><label>来源种类<select aria-label="来源种类" name="kind" defaultValue="" required><option value="" disabled>请选择来源性质</option><option value="USER_PROVIDED">用户提供</option><option value="PUBLIC">公开来源</option><option value="DEIDENTIFIED">脱敏来源</option></select></label><label>原文SHA-256<input name="sha256" value={sha} onChange={e=>setSha(e.target.value)} pattern="[a-f0-9]{64}" required/></label><button type="button" onClick={async e=>{const f=e.currentTarget.closest("form");if(f)setSha(await digest(val(new FormData(f),textName)))}}>计算此原文摘要</button></fieldset>;
}
export function source(data:FormData):Source{return {id:val(data,"sourceId"),version:val(data,"sourceVersion"),locator:val(data,"locator"),sha256:val(data,"sha256"),kind:val(data,"kind") as Source["kind"]};}
