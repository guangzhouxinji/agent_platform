const callback=()=>location.origin+"/app/consultation";
const client=()=>import.meta.env.VITE_CONSULTATION_CLIENT_ID as string|undefined;
const encode=(bytes:Uint8Array)=>btoa(String.fromCharCode(...bytes)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/g,"");
export type ConsultationDestination={view:"prompt";promptId:string;revisionId?:string}|{view:"prompt-create";workspaceId:string}|{view:"prompt-save";workspaceId:string;promptId:string}
  |{view:"prompt-copy"}|{view:"prompt-copy";sourceWorkspaceId:string;sourcePromptId:string;sourceRevisionId:string;sourceContentSha:string}|{view:"trademarks"};
export class ConsultationLoginError extends Error{
  constructor(public taskId:string,public destination?:ConsultationDestination){super("登录未完成，请重新登录。");}
}
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function checkedDestination(value:unknown):ConsultationDestination|undefined{
  if(value===undefined)return undefined;
  if(!value||typeof value!=="object"||Array.isArray(value))throw new Error("咨询页面地址不合法。");
  const v=value as Record<string,unknown>;
  if(v.view==="trademarks"&&Object.keys(v).length===1)return {view:"trademarks"};
  if(v.view==="prompt-copy"){
    if(Object.keys(v).length===1)return {view:"prompt-copy"};
    if(Object.keys(v).length===5&&typeof v.sourceWorkspaceId==="string"&&uuid.test(v.sourceWorkspaceId)
      &&typeof v.sourcePromptId==="string"&&uuid.test(v.sourcePromptId)&&typeof v.sourceRevisionId==="string"&&uuid.test(v.sourceRevisionId)
      &&typeof v.sourceContentSha==="string"&&/^[0-9a-f]{64}$/i.test(v.sourceContentSha))
      return {view:"prompt-copy",sourceWorkspaceId:v.sourceWorkspaceId.toLowerCase(),sourcePromptId:v.sourcePromptId.toLowerCase(),
        sourceRevisionId:v.sourceRevisionId.toLowerCase(),sourceContentSha:v.sourceContentSha.toLowerCase()};
    throw new Error("咨询页面地址不合法。");
  }
  if(v.view==="prompt-create"&&typeof v.workspaceId==="string"&&uuid.test(v.workspaceId)
    &&Object.keys(v).length===2)return {view:"prompt-create",workspaceId:v.workspaceId.toLowerCase()};
  if(v.view==="prompt-save"&&typeof v.workspaceId==="string"&&uuid.test(v.workspaceId)
    &&typeof v.promptId==="string"&&uuid.test(v.promptId)&&Object.keys(v).length===3)
    return {view:"prompt-save",workspaceId:v.workspaceId.toLowerCase(),promptId:v.promptId.toLowerCase()};
  if(v.view==="prompt"&&typeof v.promptId==="string"&&uuid.test(v.promptId)
    &&(v.revisionId===undefined||typeof v.revisionId==="string"&&uuid.test(v.revisionId))
    &&Object.keys(v).every(k=>["view","promptId","revisionId"].includes(k)))
    return {view:"prompt",promptId:v.promptId.toLowerCase(),...(v.revisionId?{revisionId:(v.revisionId as string).toLowerCase()}:{})};
  throw new Error("咨询页面地址不合法。");
}
export function readConsultationDestination(search:string):ConsultationDestination|undefined{
  const q=new URLSearchParams(search);
  const copyCoordinates=["sourceWorkspace","sourcePrompt","sourceRevision","sourceContentSha"];
  if(["view","prompt","revision","workspace",...copyCoordinates].some(k=>q.getAll(k).length>1))throw new Error("咨询页面地址不合法。");
  if(q.get("view")==="prompt-copy"){
    if(q.getAll("task").length>1||[...q.keys()].some(k=>!["view","task",...copyCoordinates].includes(k)))throw new Error("咨询页面地址不合法。");
    if(copyCoordinates.every(k=>!q.has(k)))return {view:"prompt-copy"};
    return checkedDestination({view:"prompt-copy",sourceWorkspaceId:q.get("sourceWorkspace"),sourcePromptId:q.get("sourcePrompt"),
      sourceRevisionId:q.get("sourceRevision"),sourceContentSha:q.get("sourceContentSha")});
  }
  if(copyCoordinates.some(k=>q.has(k)))throw new Error("咨询页面地址不合法。");
  if(q.get("view")==="prompt-save"){
    if(q.has("revision"))throw new Error("咨询页面地址不合法。");
    return checkedDestination({view:"prompt-save",workspaceId:q.get("workspace"),promptId:q.get("prompt")});
  }
  if(q.has("workspace")||q.get("view")==="prompt-create"){
    if(q.get("view")!=="prompt-create"||q.has("prompt")||q.has("revision"))throw new Error("咨询页面地址不合法。");
    return checkedDestination({view:"prompt-create",workspaceId:q.get("workspace")});
  }
  if(q.has("prompt")||q.has("revision")){
    if(q.has("view")&&q.get("view")!=="prompt")throw new Error("咨询页面地址不合法。");
    return checkedDestination({view:"prompt",promptId:q.get("prompt"),...(q.has("revision")?{revisionId:q.get("revision")}:{})});
  }
  if(q.has("view"))return checkedDestination({view:q.get("view")});
  return undefined;
}
export function consultationLocation(taskId:string,destination?:ConsultationDestination){
  const d=checkedDestination(destination),q=new URLSearchParams();
  if(taskId)q.set("task",taskId);
  if(d){q.set("view",d.view);if(d.view==="prompt"){q.set("prompt",d.promptId);if(d.revisionId)q.set("revision",d.revisionId);}else if(d.view==="prompt-create")q.set("workspace",d.workspaceId);
    else if(d.view==="prompt-save"){q.set("workspace",d.workspaceId);q.set("prompt",d.promptId);}
    else if(d.view==="prompt-copy"&&"sourceWorkspaceId" in d){q.set("sourceWorkspace",d.sourceWorkspaceId);q.set("sourcePrompt",d.sourcePromptId);
      q.set("sourceRevision",d.sourceRevisionId);q.set("sourceContentSha",d.sourceContentSha);}}
  return callback()+(q.size?"?"+q.toString():"");
}
export async function login(taskId:string,destination?:ConsultationDestination){
  if(!client()||!window.isSecureContext)throw new Error("咨询登录服务未配置或当前连接不安全，请联系部署管理员。");
  const route=checkedDestination(destination);
  const verifier=encode(crypto.getRandomValues(new Uint8Array(32))),state=encode(crypto.getRandomValues(new Uint8Array(24)));
  const challenge=encode(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(verifier))));
  sessionStorage.setItem("consultation-pkce",JSON.stringify({verifier,state,taskId,...(route?{destination:route}:{})}));
  location.assign("/oauth2/authorize?"+new URLSearchParams({response_type:"code",client_id:client()!,redirect_uri:callback(),scope:"openid",state,code_challenge:challenge,code_challenge_method:"S256"}));
}
let completion:Promise<{token:string;taskId:string;destination?:ConsultationDestination}|null>|undefined;
export function completeLogin(){
  if(completion)return completion;
  const query=new URLSearchParams(location.search);
  if(!query.has("code")&&!query.has("error"))return Promise.resolve(null);
  completion=(async()=>{
    const stored=sessionStorage.getItem("consultation-pkce");sessionStorage.removeItem("consultation-pkce");
    const record=stored?JSON.parse(stored) as {verifier:string;state:string;taskId:string;destination?:unknown}:null;
    history.replaceState(null,"",callback());
    if(!record||!client()||typeof record.state!=="string"||!record.state||record.state!==query.get("state")
      ||typeof record.verifier!=="string"||!record.verifier||typeof record.taskId!=="string")throw new Error("登录状态不匹配或授权被拒绝，请重新登录。");
    const destination=checkedDestination(record.destination);
    history.replaceState(null,"",consultationLocation(record.taskId,destination));
    try{
      if(query.has("error"))throw new Error("Authorization denied");
      const response=await fetch("/oauth2/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams({grant_type:"authorization_code",client_id:client()!,redirect_uri:callback(),code:query.get("code")!,code_verifier:record.verifier})});
      if(!response.ok)throw new Error("Token exchange failed");
      const data=await response.json() as {access_token?:string};if(typeof data.access_token!=="string"||!data.access_token)throw new Error("Session missing");
      return {token:data.access_token,taskId:record.taskId,...(destination?{destination}:{})};
    }catch{throw new ConsultationLoginError(record.taskId,destination);}
  })();return completion;
}
export function forgetLogin(){completion=undefined;sessionStorage.removeItem("consultation-pkce");}
export class ConsultationError extends Error{
  constructor(public status:number){super(status===401?"会话已失效，请重新登录。":status===404?"任务或当前权限已不可用，已清空结果。":status===409?"任务或结果版本已变化，请重新读取并核对条件。":status===400?"提交条件不合法，请核对查询、字段与来源。":status===502?"模型输出未通过引用或边界校验，未采用该回答。":status===503?"当前来源、授权、模型或存储不可核验，未展示缓存结果。":status===0?"网络中断，提交结果尚未确认；请重新读取，不要重复确认条件。":"服务器拒绝了本次操作，请核对权限和输入。");}
}
export const path=(id:string)=>"/v2/consultation/tasks/"+encodeURIComponent(id);
export async function request<T>(token:string,url:string,body?:unknown,key?:string):Promise<T>{
  if(!url.startsWith("/v2/consultation/tasks/")||url.includes(".."))throw new Error("非法咨询请求路径");
  let response:Response;
  try{response=await fetch(url,{method:body===undefined?"GET":"POST",headers:{Authorization:"Bearer "+token,Accept:"application/json",...(body===undefined?{}:{"Content-Type":"application/json","Idempotency-Key":key??crypto.randomUUID()})},body:body===undefined?undefined:JSON.stringify(body),cache:"no-store"});}catch{throw new ConsultationError(0);}
  if(!response.ok)throw new ConsultationError(response.status);
  try{return await response.json() as T;}catch{throw new ConsultationError(0);}
}
export function sourceLink(value:string){try{const uri=new URL(value);return uri.protocol==="https:"?uri.href:undefined;}catch{return undefined;}}
