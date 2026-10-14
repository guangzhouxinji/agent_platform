import {useEffect,useLayoutEffect,useRef,useState,type FormEvent} from 'react';
import {queryTrademarks,trademarkDetail,TrademarkError} from './api';
import {initialQuery,type Query,type Page,type Detail,type View,type Observation,type RecordRef} from './types';
import './trademark.css';

type Props={token:string;taskId:string;onBack:()=>void;onSessionExpired?:()=>void};
type Display={owner:string;page:View<Page>|null;detail:View<Detail>|null};
export default function ProductTrademarkWorkspace({token,taskId,onBack,onSessionExpired}:Props){
  const owner=taskId+'\u0000'+token;
  const [query,setQuery]=useState<Query>(initialQuery),[applied,setApplied]=useState<Query>(initialQuery);
  const [display,setDisplay]=useState<Display>({owner,page:null,detail:null});
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  const request=useRef<{epoch:number;abort?:AbortController}>({epoch:0}),currentOwner=useRef(owner),errorNode=useRef<HTMLDivElement>(null);
  const retry=useRef<()=>void>(()=>{});
  const page=display.owner===owner?display.page:null,detail=display.owner===owner?display.detail:null;
  function clear(){setDisplay({owner,page:null,detail:null});}
  useLayoutEffect(()=>{
    currentOwner.current=owner;request.current.epoch++;request.current.abort?.abort();setBusy(false);setError('');
    setDisplay({owner,page:null,detail:null});setQuery(initialQuery());setApplied(initialQuery());
    return()=>{request.current.epoch++;request.current.abort?.abort();};
  },[owner]);
  useEffect(()=>{if(error)errorNode.current?.focus();},[error]);
  useEffect(()=>{
    const until=Math.min(...[page?.expiresAt,detail?.expiresAt].filter((v):v is string=>!!v).map(Date.parse));
    if(!Number.isFinite(until))return;
    const timer=setTimeout(()=>{request.current.epoch++;request.current.abort?.abort();setBusy(false);clear();setError('结果的可访问期限已到，请重新查询。');},Math.max(0,Math.min(2147483647,until-Date.now())));
    return()=>clearTimeout(timer);
  },[page?.expiresAt,detail?.expiresAt,owner]);
  async function run(work:(signal:AbortSignal)=>Promise<void>,again:()=>void){
    request.current.abort?.abort();const abort=new AbortController();const epoch=++request.current.epoch;request.current.abort=abort;
    retry.current=again;setBusy(true);setError('');
    try{await work(abort.signal);}catch(failure){
      if(abort.signal.aborted||epoch!==request.current.epoch||currentOwner.current!==owner)return;
      clear();setError(failure instanceof Error?failure.message:'本次读取未完成，请重试。');
      if(failure instanceof TrademarkError&&failure.status===401)onSessionExpired?.();
    }finally{if(epoch===request.current.epoch&&currentOwner.current===owner)setBusy(false);}
  }
  function search(next:Query,refresh=false){
    const expected=request.current.epoch+1;clear();setApplied(next);
    void run(async signal=>{const result=await queryTrademarks(token,taskId,next,signal,refresh);
      if(!signal.aborted&&expected===request.current.epoch&&currentOwner.current===owner)setDisplay({owner,page:result,detail:null});},()=>search(next,refresh));
  }
  function open(ref:RecordRef,refresh=false){
    const expected=request.current.epoch+1;
    setDisplay(previous=>({...previous,detail:null}));
    void run(async signal=>{const result=await trademarkDetail(token,taskId,ref,signal,refresh);
      if(!signal.aborted&&expected===request.current.epoch&&currentOwner.current===owner)setDisplay(previous=>({owner,page:previous.owner===owner?previous.page:null,detail:result}));},()=>open(ref,refresh));
  }
  function submit(event:FormEvent){event.preventDefault();search({...query,page:1});}
  function change(key:keyof Query,value:string){setQuery(previous=>({...previous,[key]:value}));}
  function backToTask(){request.current.epoch++;request.current.abort?.abort();clear();onBack();}
  const fields=([key,label,type='text']:[keyof Query,string,string?])=><label key={key}>{label}<input type={type} value={String(query[key])} maxLength={1000} onChange={event=>change(key,event.target.value)}/></label>;
  return <section className="consultation-panel trademark-workspace" aria-labelledby="trademark-title">
    <header className="trademark-heading"><div><p className="consultation-muted">当前咨询任务 · 商标资料</p><h2 id="trademark-title">查询商标</h2></div><button type="button" onClick={backToTask}>返回任务</button></header>
    <p className="consultation-muted">查看当前获准来源的商标资料。来源状态按原文呈现，不作为法律状态判断。</p>
    <form onSubmit={submit} aria-label="商标查询条件"><div className="trademark-filters">
      {fields(['registrationNumber','注册号'])}{fields(['name','商标名称'])}{fields(['applicant','注册人'])}{fields(['categories','类别（多个用逗号分隔）'])}
    </div><details><summary>日期和状态筛选</summary><div className="trademark-filters">
      {fields(['registeredFrom','注册日期起','date'])}{fields(['registeredTo','注册日期止','date'])}{fields(['validFrom','有效期至起','date'])}{fields(['validTo','有效期至止','date'])}
      <label>来源状态<select value={query.statuses} onChange={event=>change('statuses',event.target.value)}><option value="">全部</option><option value="UNMAPPED">来源原始状态（未映射）</option></select></label>
    </div></details><div className="trademark-actions"><button type="submit" disabled={busy}>查询</button><button type="button" disabled={busy} onClick={()=>{const next=initialQuery();setQuery(next);search(next);}}>清空条件并查询</button></div></form>
    <div role="status" aria-live="polite">{busy?'正在核验来源和读取商标…':''}</div>
    {error&&<div role="alert" tabIndex={-1} ref={errorNode} className="trademark-error"><p>{error}</p><button type="button" disabled={busy} onClick={()=>retry.current()}>重试</button></div>}
    {detail?<section aria-label="商标详情"><div className="trademark-actions"><button type="button" disabled={busy} onClick={()=>setDisplay(previous=>({...previous,detail:null}))}>返回查询列表</button><button type="button" disabled={busy} onClick={()=>open(detail.data.item.ref,true)}>重新核验详情</button></div>
      <h3>{detail.data.item.name??'名称未提供'}</h3><TrademarkFacts item={detail.data.item}/>
      <p>变更记录：{capability(detail.data.capabilities.CHANGES)} · 异议记录：{capability(detail.data.capabilities.OPPOSITIONS)} · 无效记录：{capability(detail.data.capabilities.INVALIDATIONS)}</p>
      <p>图样：当前尚未提供可核验内容。</p><Provenance view={detail.data.provenance}/>
    </section>:page&&<section aria-label="商标查询结果"><div className="trademark-heading"><h3>共 {page.data.pagination.reliableTotal} 条</h3><button type="button" disabled={busy} onClick={()=>search(applied,true)}>重新核验来源</button></div>
      {page.data.items.length===0?<p>{page.data.pagination.reliableTotal===0?'当前条件下没有匹配商标。':'这一页没有结果，请返回上一页。'}</p>:<ol className="trademark-records">{page.data.items.map(item=><li key={[item.ref.sourceId,item.ref.recordId,item.ref.version].join('\u0000')}>
        <button type="button" className="trademark-record-link" disabled={busy} onClick={()=>open(item.ref)}>{item.name??'名称未提供'}</button><p>注册号：{item.registrationNumber??'未提供'}</p><p>注册人：{item.applicants.length?item.applicants.join('、'):'未提供'}</p><p>类别：{item.classes.map(c=>c.code).join('、')||'未提供'}</p><p className="consultation-muted">来源：{item.ref.sourceId} · 注册日期：{item.registered?.raw??'未提供'}</p>
      </li>)}</ol>}
      <nav className="trademark-actions" aria-label="商标分页"><button type="button" disabled={busy||applied.page===1} onClick={()=>search({...applied,page:applied.page-1})}>上一页</button><span>第 {applied.page} 页</span><button type="button" disabled={busy||!page.data.pagination.hasNext} onClick={()=>search({...applied,page:applied.page+1})}>下一页</button>
        <label>每页条数<select value={applied.size} disabled={busy} onChange={event=>search({...applied,size:Number(event.target.value) as 10|20|50,page:1})}>{[10,20,50].map(size=><option key={size} value={size}>{size}</option>)}</select></label></nav>
      {page.data.provenance.map(source=><Provenance key={source.source.sourceId} view={source}/>)}</section>}
  </section>;
}
function capability(value:string){return value==='UNKNOWN'?'尚待核实':value==='NOT_PROVIDED'?'来源未提供':'可提供';}
function TrademarkFacts({item}:{item:Observation}){return <dl className="trademark-facts">
  <dt>注册号</dt><dd>{item.registrationNumber??'未提供'}</dd><dt>注册人</dt><dd>{item.applicants.length?item.applicants.join('、'):'未提供'}</dd><dt>地址</dt><dd>{item.address??'未提供'}</dd>
  <dt>注册日期</dt><dd>{item.registered?.raw??'未提供'}</dd><dt>有效期至</dt><dd>{item.validUntil?.raw??'未提供'}</dd><dt>来源原始状态</dt><dd>{item.sourceStatus??'未提供'}</dd><dt>来源更新时间</dt><dd>{item.sourceUpdatedAt??'未提供'}</dd>
  <dt>类别及商品／服务</dt><dd>{item.classes.length?item.classes.map((c,i)=><div key={i}><strong>{c.code} {c.name}</strong><p>{c.items.join('；')||'商品／服务未提供'}</p><p>类似群：{c.similarGroups.join('、')||'未提供'}</p></div>):'未提供'}</dd>
  <dt>记录版本</dt><dd>{item.ref.version}</dd><dt>来源定位</dt><dd>{item.sourceRef??'未提供'}</dd></dl>;}
function Provenance({view}:{view:Page['provenance'][number]}){return <details className="trademark-provenance"><summary>来源与核验时间：{view.source.sourceId}</summary><dl className="trademark-facts"><dt>来源版本</dt><dd>{view.source.version}</dd><dt>本次核验时间</dt><dd>{new Date(view.fetchedAt).toLocaleString()}</dd><dt>来源更新时间</dt><dd>{view.sourceUpdatedAt??'未提供'}</dd><dt>来源范围</dt><dd>当前获准的完整资料包</dd></dl><p className="consultation-muted">重新核验检查当前已收集资料，不表示已从上游重新采集。</p></details>;}
