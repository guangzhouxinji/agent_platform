import {useEffect,useRef,useState} from 'react';
import {beginWorkspaceLogin,completeWorkspaceLogin,logoutWorkspace,readWorkspaceIdentity,readWorkspaceTasks,navigateWorkspace} from './api';
import {labels,visiblePages,visibleTasks,type Identity,type CollectionTask,type PageKey} from './contract';
import './workspace.css';
export default function EnforcementWorkspace({enabledPages}:{enabledPages:readonly PageKey[]}){
 const [token,setToken]=useState<string|null>(null),[identity,setIdentity]=useState<Identity|null>(null),[tasks,setTasks]=useState<CollectionTask[]>([]),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 const epoch=useRef(0);
 const clear=()=>{setIdentity(null);setTasks([])};
 useEffect(()=>{let active=true;void completeWorkspaceLogin().then(value=>{if(active)setToken(value)}).catch(e=>{if(active)setError(e instanceof Error?e.message:'登录失败')});return()=>{active=false;epoch.current++}},[]);
 async function refresh(){
  if(!token)return null;const seq=++epoch.current;clear();setBusy(true);setError('');
  try{const current=await readWorkspaceIdentity(token);const data=await readWorkspaceTasks(token);if(seq!==epoch.current)return null;
   const selected=visibleTasks(data,current);setIdentity(current);setTasks(selected);return {identity:current,tasks:selected};
  }catch(e){if(seq===epoch.current){clear();setError(e instanceof Error?e.message:'当前工作不可核，内容已清空。')}return null;}
  finally{if(seq===epoch.current)setBusy(false)}
 }
 useEffect(()=>{if(token)void refresh();const blur=()=>{epoch.current++;clear();setBusy(false)};const focus=()=>{if(token)void refresh()};window.addEventListener('blur',blur);window.addEventListener('focus',focus);return()=>{epoch.current++;window.removeEventListener('blur',blur);window.removeEventListener('focus',focus)}},[token]);
 async function leave(){const current=token;epoch.current++;clear();setToken(null);setBusy(false);if(current)try{await logoutWorkspace(current)}catch(e){setError(e instanceof Error?e.message:'退出服务未确认，本地内容已清空。')}}
 async function open(page:PageKey,task?:CollectionTask){
  const current=await refresh();if(!current)return;
  if(!visiblePages(enabledPages,current.identity).includes(page)||task&&!current.tasks.some(t=>t.taskId===task.taskId&&t.packageId===task.packageId)){setError('目标工作当前不可访问。');return;}
  navigateWorkspace('/app/enforcement/'+page+(task?'?package='+encodeURIComponent(task.packageId):''));
 }
 const menu=identity?visiblePages(enabledPages,identity):[];
 const business=menu.filter(p=>p==='job-pack'||p==='legal-references'),management=menu.filter(p=>p==='document-templates'||p==='document-rules');
 return <main className="enforcement-product" aria-labelledby="enforcement-workspace-title">
  <header><div><h1 id="enforcement-workspace-title">执法工作台</h1><p>按当前授权进入任务和待处理工作。辅助分析与人工正式决定分别留痕。</p></div><div className="entry-actions">
   {token?<><button disabled={busy} onClick={()=>void refresh()}>刷新当前工作</button><button onClick={()=>void leave()}>退出</button></>:<button onClick={()=>void beginWorkspaceLogin().catch(e=>setError(e instanceof Error?e.message:'登录失败'))}>登录</button>}
  </div></header>
  {error&&<p role="alert" className="entry-error">{error}</p>}
  {!token?<section aria-label="登录入口"><h2>使用当前授权进入工作</h2><p>请通过已部署的身份服务登录。任务、来源和操作权限均以服务端当前校验为准。</p></section>:<>
   {busy&&<p role="status">正在核对当前授权和任务。</p>}
   {identity&&<><p className="entry-identity">当前身份：{identity.subject}</p>
    <section aria-labelledby="entry-tasks"><h2 id="entry-tasks">当前任务包采集工作</h2><p>这里显示当前授权范围内的采集任务，状态来自任务包服务。</p>
     {tasks.length===0?<p>当前授权范围内没有采集待办。</p>:<ul className="entry-tasks">{tasks.map(t=><li key={t.taskId}><div><h3>{t.title}</h3><dl><dt>任务</dt><dd>{t.taskId}</dd><dt>任务包</dt><dd>{t.packageId}</dd><dt>领取状态</dt><dd>{t.assignmentState}</dd><dt>工作状态</dt><dd>{t.workflowState}</dd></dl></div><button disabled={busy} onClick={()=>void open('job-pack',t)}>进入任务包</button></li>)}</ul>}
    </section>
    {business.length>0&&<nav aria-label="业务工作"><h2>业务工作</h2>{business.map(p=><button key={p} disabled={busy} onClick={()=>void open(p)}>{labels[p]}</button>)}</nav>}
    {management.length>0&&<nav aria-label="管理配置"><h2>管理配置</h2><p>配置权限与业务操作权限分别校验。</p>{management.map(p=><button key={p} disabled={busy} onClick={()=>void open(p)}>{labels[p]}</button>)}</nav>}
   </>}
  </>}
 </main>;
}
