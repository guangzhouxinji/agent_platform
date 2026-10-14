export type PageKey='job-pack'|'document-templates'|'document-rules'|'legal-references';
export type Grant={organization:string;domain:string;resourceType:string;resourceId:string;resourceVersion:number;action:string;purpose:string;expiresAt:string};
export type Identity={issuer:string;subject:string;sessionId:string;grants:Grant[]};
export type CollectionTask={taskId:string;packageId:string;title:string;assignmentState:'UNCLAIMED'|'CLAIMED';workflowState:string;organization?:string};
export const pages:readonly PageKey[]=['job-pack','document-templates','document-rules','legal-references'];
export const labels:Record<PageKey,string>={'job-pack':'任务包采集','document-templates':'文书模板管理','document-rules':'文书规则管理','legal-references':'法律依据引用'};
function live(g:Grant,now:number){return g.domain==='ENFORCEMENT'&&Number.isFinite(Date.parse(g.expiresAt))&&Date.parse(g.expiresAt)>now;}
export function visibleTasks(tasks:CollectionTask[],identity:Identity,now=Date.now()){
 return tasks.filter(task=>{
  const grants=identity.grants.filter(g=>live(g,now)&&g.resourceType==='ENFORCEMENT_JOB_PACK'&&g.resourceId===task.packageId&&g.resourceVersion===1&&g.purpose==='JOB_PACK');
  if(task.assignmentState==='CLAIMED')return grants.some(g=>g.action==='READ'&&g.organization===task.organization);
  if(task.assignmentState!=='UNCLAIMED')return false;
  return grants.some(g=>['CREATE','COLLECT','READ'].every(action=>grants.some(x=>x.organization===g.organization&&x.action===action)));
 });
}
export function visiblePages(enabled:readonly PageKey[],identity:Identity,now=Date.now()){
 return enabled.filter(page=>identity.grants.some(g=>{
  if(!live(g,now))return false;
  if(page==='job-pack')return g.resourceType==='ENFORCEMENT_JOB_PACK'&&g.purpose==='JOB_PACK'&&['READ','CREATE','COLLECT','VERIFY','APPROVE','TRIGGER','COMPLETE'].includes(g.action);
  if(page==='document-templates')return g.resourceType==='DOCUMENT_TEMPLATE'&&g.purpose==='DOCUMENT_TEMPLATE'&&['READ','CREATE','EDIT','PREVIEW','DOWNLOAD','PUBLISH','DISABLE'].includes(g.action);
  if(page==='document-rules')return g.resourceType==='DOCUMENT_RULE'&&g.purpose==='DOCUMENT_MAPPING'&&['READ','EDIT','VALIDATE','TRIAL','PUBLISH','DISABLE'].includes(g.action);
  return ['SEARCH_LEGAL_BASIS','READ_LEGAL_BASIS'].includes(g.action)||g.resourceType==='LEGAL_REFERENCE'&&['READ','READ_HISTORY'].includes(g.action)&&g.purpose==='LEGAL_BASIS';
 }));
}
