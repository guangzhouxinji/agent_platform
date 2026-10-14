import type {PromptHistoryView,PromptRevisionView} from './promptApi';
// Owned component inputs only, never shipped as product data.
export const promptId='00000000-0000-4000-8000-000000000010';
export const revisionId='00000000-0000-4000-8000-000000000011';
export const newerId='00000000-0000-4000-8000-000000000012';
export const otherPrompt='00000000-0000-4000-8000-000000000020';
export const workspaceId='00000000-0000-4000-8000-000000000030';
export function revision(id=revisionId,version=1):PromptRevisionView{return {workspaceId,contentSha:(version===1?'a':'b').repeat(64),promptId,revisionId:id,version,kind:'SYSTEM',name:'咨询草稿',body:version===1?'原版本正文':'新版本正文',
  scenarioIds:[],intentIds:[],audienceRef:'internal',language:'zh-CN',displayOrder:0,variables:[],
  submittedAt:new Date(Date.now()-60000).toISOString(),bodyUntil:new Date(Date.now()+3600000).toISOString()};}
export function history(values=[revision(newerId,2),revision()]):PromptHistoryView{return {promptId,page:1,size:20,total:values.length,
  revisions:values.map(({revisionId,version,submittedAt,bodyUntil})=>({revisionId,version,submittedAt,bodyUntil}))};}
export function ok(value:unknown){return new Response(JSON.stringify(value),{status:200,headers:{'Content-Type':'application/json'}});}
