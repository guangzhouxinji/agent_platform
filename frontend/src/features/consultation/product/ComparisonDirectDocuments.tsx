import type {ComparisonDocumentPage} from './comparisonInputContracts';
export default function ComparisonDirectDocuments({page,target,chosen,claims,onTarget,onChosen,onClaims,onMore}:{page:ComparisonDocumentPage;target:string;chosen:string[];claims:Record<string,number[]>;onTarget:(key:string)=>void;onChosen:(keys:string[])=>void;onClaims:(key:string,values:number[])=>void;onMore:()=>void}){
 return <section aria-label="获准确切文献"><p>仅显示当前任务获准展示及对照的文献原文；不需要检索选择。授权、版本和原件将在准备时重新核验。</p>
 <label>直接目标专利<select aria-label="直接目标专利" value={target} onChange={e=>onTarget(e.target.value)}><option value="">请选择确切文献版</option>{page.documents.map(d=><option key={d.key} value={d.key}>{d.sourceId} · {d.documentId} · {d.documentVersion}</option>)}</select></label>
 {!page.documents.length&&<p role="status">当前没有获准的确切文献，不能准备候选。</p>}
 {page.documents.map(d=><article key={d.key}><label><input type="checkbox" checked={chosen.includes(d.key)} onChange={e=>onChosen(e.target.checked?[...chosen,d.key]:chosen.filter(k=>k!==d.key))}/>候选 {d.documentId} · {d.documentVersion} · {d.sourceId}</label>
 <fieldset><legend>文献 {d.documentId} · {d.documentVersion} 的原文权项</legend>{d.claimNumbers.map(n=><label key={n}><input type="checkbox" checked={(claims[d.key]??[]).includes(n)} onChange={e=>onClaims(d.key,e.target.checked?[...(claims[d.key]??[]),n].sort((a,b)=>a-b):(claims[d.key]??[]).filter(c=>c!==n))}/>权项 {n}</label>)}{!d.claimNumbers.length&&<p>未提供可选择的显式权项号，不能推定权项。</p>}</fieldset>
 <details><summary>查看 {d.documentId} 确切原文及缺口</summary><pre>{d.claimsText}</pre>{d.gap&&<p role="status">原文缺口：{d.gap}</p>}</details></article>)}
 {page.nextOffset!==null&&<button onClick={onMore}>加载更多获准文献</button>}
 </section>;
}
