import { Check, Pencil, X } from "lucide-react";
import { SourceCard } from "./common";
import type { CaseView } from "./contract";
import type { Action, Candidate, DecisionView, Location } from "./materialOrganizationBinding";
const kinds:Record<string,string>={PARTY_ASSERTION:"当事方主张",RECORDED_STATEMENT:"材料记载",DISPUTE_CLUE:"争议线索",MISSING_ITEM:"有锚点缺项"};
export const actionLabels:Record<Action,string>={ACCEPT:"已接受",EDIT:"人工修改",REJECT:"已拒绝"};
export default function MaterialOrganizationRead({candidate:c,location,view,decision,disabled,onAction}:{candidate:Candidate;location:Location;view:CaseView;decision?:DecisionView;disabled:boolean;onAction:(action:Action)=>void}) {
 return <article aria-label={"候选 "+c.id}><h3>{c.id} · {kinds[c.kind]} · {c.providerSide==="APPLICANT"?"申请方提供":"答辩方提供"}</h3>
 {location.error?<p role="status" className="adj-danger">{location.error}</p>:<><p><strong>机器候选</strong></p><p>{c.statement}</p><p>原文引用 · UTF16 [{c.start}, {c.end})</p><blockquote>{c.exactQuote}</blockquote>
 <div className="adj-long-text" tabIndex={0} aria-label={"候选 "+c.id+" 原文"}>{location.text!.slice(0,c.start)}<mark>{location.text!.slice(c.start,c.end)}</mark>{location.text!.slice(c.end)}</div>
 <SourceCard source={location.source!} text={location.text}/>
 {location.materials.length>1&&<p className="adj-state warning">同一来源对应多个材料，本回合未返回唯一选中材料。</p>}
 </>}
 <ul>{location.materials.map(m=><li key={m.id}>{m.id} · {m.side==="APPLICANT"?"申请方":"答辩方"} · {m.submitter} · {m.assertedPurpose}{!m.readable?" · 原文不可读":""}{view.materials.some(n=>n.supersedesMaterialId===m.id)?" · 历史材料":""}</li>)}</ul>
 {decision?<p role="status">{actionLabels[decision.version.action]} · 整理版本 {decision.version.organizationVersion}{decision.stale?" · 已失效":""}</p>:!location.error&&<div className="adj-version-buttons">
 <button title="接受候选" aria-label={"接受 "+c.id} disabled={disabled} onClick={()=>onAction("ACCEPT")}><Check size={16} aria-hidden/> 接受</button>
 <button title="修改候选" aria-label={"修改 "+c.id} disabled={disabled} onClick={()=>onAction("EDIT")}><Pencil size={16} aria-hidden/> 修改</button>
 <button title="拒绝候选" aria-label={"拒绝 "+c.id} disabled={disabled} onClick={()=>onAction("REJECT")}><X size={16} aria-hidden/> 拒绝</button></div>}
 </article>;
}
export function DecisionHistory({versions,view,disabled,onRevise}:{versions:DecisionView[];view:CaseView;disabled:boolean;onRevise:(version:DecisionView)=>void}) {
 return <section aria-label="人工整理历史"><h3>人工整理历史</h3>{!versions.length?<p>尚无人工整理版本。</p>:versions.map(v=>{const d=v.version,m=view.materials.find(m=>m.source.id===d.source.id&&m.source.version===d.source.version&&m.source.sha256===d.source.sha256&&m.source.locator===d.source.locator);return <details key={d.versionId}><summary>整理版本 {d.organizationVersion} · {actionLabels[d.action]}{v.stale?" · 已失效":""}{!v.currentCandidateDecision?" · 历史":""}</summary>
 <p>机器候选：{d.machineStatement}</p>{d.humanText!==null&&<p>人工陈述：{d.humanText}</p>}{d.reason!==null&&<p>原因：{d.reason}</p>}<blockquote>{d.exactQuote}</blockquote><SourceCard source={d.source} text={m?.content??undefined}/>
 <p>{d.actor} · {d.createdAt} · 截止 {d.expiresAt}</p>{d.baseVersionId&&<p>更正自：{d.baseVersionId}</p>}
 {v.currentCandidateDecision&&!v.stale&&d.action!=="REJECT"&&<button title="更正人工版本" disabled={disabled||Date.parse(d.expiresAt)<=Date.now()} onClick={()=>onRevise(v)}><Pencil size={16} aria-hidden/> 更正版本 {d.organizationVersion}</button>}
 </details>;})}</section>;
}
