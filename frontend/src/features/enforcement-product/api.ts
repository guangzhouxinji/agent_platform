import type {CollectionTask,Identity} from './contract';
const callback=()=>window.location.origin+'/app/enforcement';
const client=()=>import.meta.env.VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID as string|undefined;
const b64=(b:Uint8Array)=>btoa(String.fromCharCode(...b)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
export async function beginWorkspaceLogin(){
 if(!client()||!window.isSecureContext)throw Error('产品身份入口不可用，请联系部署管理员。');
 const verifier=b64(crypto.getRandomValues(new Uint8Array(32))),state=b64(crypto.getRandomValues(new Uint8Array(24)));
 const challenge=b64(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(verifier))));
 sessionStorage.setItem('enf-workspace-pkce',JSON.stringify({verifier,state}));
 window.location.assign('/oauth2/authorize?'+new URLSearchParams({response_type:'code',client_id:client()!,redirect_uri:callback(),scope:'openid',state,code_challenge:challenge,code_challenge_method:'S256'}));
}
let completion:Promise<string|null>|undefined;
export function completeWorkspaceLogin(){
 const q=new URLSearchParams(window.location.search);if(!q.has('code')&&!q.has('error'))return Promise.resolve(null);
 return completion??=(async()=>{
  const saved=sessionStorage.getItem('enf-workspace-pkce');sessionStorage.removeItem('enf-workspace-pkce');history.replaceState(null,'',callback());
  const record=saved?JSON.parse(saved) as {verifier:string;state:string}:null;
  if(q.has('error')||!record||!client()||record.state!==q.get('state'))throw Error('登录状态不匹配或授权已拒绝，请重新登录。');
  const response=await fetch('/oauth2/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'authorization_code',code:q.get('code')!,client_id:client()!,redirect_uri:callback(),code_verifier:record.verifier})});
  if(!response.ok)throw Error('未取得产品会话。');const data=await response.json() as {access_token?:string};if(!data.access_token)throw Error('身份服务未返回会话。');return data.access_token;
 })();
}
export async function logoutWorkspace(token:string){completion=undefined;sessionStorage.removeItem('enf-workspace-pkce');const response=await fetch('/v2/identity/logout',{method:'POST',headers:{Authorization:'Bearer '+token}});if(!response.ok&&response.status!==401)throw Error('退出服务未确认，本地内容已清空。');}
async function read<T>(token:string,path:string):Promise<T>{
 let response:Response;try{response=await fetch(path,{headers:{Authorization:'Bearer '+token,Accept:'application/json'},cache:'no-store'});}catch{throw Error('网络连接不可核，工作内容已清空。');}
 if(!response.ok)throw Error(response.status===401||response.status===403?'当前授权不可用，工作内容已清空。':'当前服务或授权不可核，工作内容已清空。');
 try{return await response.json() as T;}catch{throw Error('服务返回无法核对，工作内容已清空。');}
}
export async function readWorkspaceIdentity(token:string){
 const value=await read<Identity>(token,'/v2/identity/me');
 if(!value||typeof value.issuer!=='string'||!value.issuer.startsWith('https://')||!value.subject||!value.sessionId||!Array.isArray(value.grants)||value.grants.some(g=>!g||typeof g.organization!=='string'||typeof g.domain!=='string'||typeof g.resourceType!=='string'||typeof g.resourceId!=='string'||!Number.isSafeInteger(g.resourceVersion)||typeof g.action!=='string'||typeof g.purpose!=='string'||!Number.isFinite(Date.parse(g.expiresAt))))throw Error('身份返回无法核对，工作内容已清空。');return value;
}
export async function readWorkspaceTasks(token:string){
 const value=await read<CollectionTask[]>(token,'/v2/enforcement/job-packs/collection-tasks');
 if(!Array.isArray(value)||value.some(t=>!t||typeof t.taskId!=='string'||typeof t.packageId!=='string'||typeof t.title!=='string'||!['UNCLAIMED','CLAIMED'].includes(t.assignmentState)||typeof t.workflowState!=='string'||t.assignmentState==='CLAIMED'&&typeof t.organization!=='string'))throw Error('任务返回无法核对，工作内容已清空。');return value;
}
export function navigateWorkspace(path:string){if(!/^\/app\/enforcement\/(job-pack|document-templates|document-rules|legal-references)(\?package=[^#]*)?$/.test(path))throw Error('目标入口不可用');window.location.assign(path);}
