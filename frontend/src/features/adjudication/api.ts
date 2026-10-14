const callback=()=>window.location.origin+"/app/adjudication";
const client=()=>import.meta.env.VITE_ADJUDICATION_CLIENT_ID as string|undefined;
const b64=(value:Uint8Array)=>btoa(String.fromCharCode(...value)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"");
export async function beginLogin(){
  if(!client()||!window.isSecureContext)throw new Error("裁决身份客户端未配置或当前连接不安全，请联系部署管理员。");
  const verifier=b64(crypto.getRandomValues(new Uint8Array(32))),state=b64(crypto.getRandomValues(new Uint8Array(24)));
  const challenge=b64(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(verifier))));
  sessionStorage.setItem("adj-v2-pkce",JSON.stringify({verifier,state,caseId:new URLSearchParams(location.search).get("case")??""}));
  location.assign("/oauth2/authorize?"+new URLSearchParams({response_type:"code",client_id:client()!,redirect_uri:callback(),scope:"openid",state,code_challenge:challenge,code_challenge_method:"S256"}));
}
let completion:Promise<string|null>|undefined;
export function completeLogin(){
  if(completion)return completion;
  const query=new URLSearchParams(location.search);if(!query.has("code")&&!query.has("error"))return Promise.resolve(null);
  completion=(async()=>{
    const saved=sessionStorage.getItem("adj-v2-pkce");sessionStorage.removeItem("adj-v2-pkce");
    const record=saved?JSON.parse(saved) as {verifier:string;state:string;caseId:string}:null;
    history.replaceState(null,"",callback()+(record?.caseId?"?case="+encodeURIComponent(record.caseId):""));
    if(query.has("error")||!record||!client()||record.state!==query.get("state"))throw new Error("登录状态不匹配或授权已拒绝，请重新登录。");
    const result=await fetch("/oauth2/token",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams({grant_type:"authorization_code",code:query.get("code")!,redirect_uri:callback(),client_id:client()!,code_verifier:record.verifier})});
    if(!result.ok)throw new Error("未取得可信裁决会话，请重新登录。");
    const data=await result.json() as {access_token?:string};if(!data.access_token)throw new Error("身份服务未返回会话。");return data.access_token;
  })();return completion;
}
export function forgetLogin(){completion=undefined;sessionStorage.removeItem("adj-v2-pkce");}
const errors:Record<string,string>={REPORT_POLICY_CONFLICT:"报告规则版本已更新，请核对原稿并生成当前规则新报告。",WORK_VERSION_CONFLICT:"案件版本已变化。请重新读取并核对未提交内容，不会自动重试。",CLAIM_REVISION_CONFLICT:"权项版本不连续，请查看已有版本。",REPORT_INPUT_OR_HEAD_STALE:"所选报告已失效或不是最新稿，不能复用。",REPORT_HASH_CONFLICT:"确切报告摘要不一致，请重新核对版本。",REPORT_INCOMPLETE:"报告存在阻断性缺项，不能提交。",REPORT_NOT_DRAFT:"该版本不是可提交草稿，重复提交或退回旧稿不能再提交。",REPORT_NOT_SUBMITTED:"该稿不在待复核状态，不能复核。",INDEPENDENT_REVIEW_REQUIRED:"复核必须由未参与此稿的独立授权人员执行。",CASE_ACCESS_DENIED:"当前逐案权限或来源用途不可用，可能已过期或撤回。正文已清空。",CASE_NOT_FOUND:"未找到案件，请核对案件ID或先创建。",CONTEXT_IN_USE:"已有采用引用，不能直接替换上下文。",IDEMPOTENCY_KEY_CONFLICT:"同一请求键对应的命令不一致。",CASE_ORIGINAL_REQUIRED_OR_DRIFTED:"案件原文缺失或摘要不一致。",INVALID_COMMAND_BODY:"输入格式或必填字段不合法。"};
export class AdjudicationError extends Error{
  constructor(public status:number,public code:string,public reason?:string){super(reason==="SOURCE_REVOKED"?"来源已撤回：案件及历史正文已清空，请联系来源维护人。":reason==="SOURCE_USE_UNAVAILABLE"?"来源用途缺失、过期或不匹配，正文已清空。":errors[code]??(status===401?"会话已失效，请重新登录。":status===503?"当前授权或持久完整性不可核，未展示缓存正文。":status===0?"网络连接中断，命令结果尚未确认。":"服务器拒绝了本次操作，请核对输入或联系管理员。"));}
  get clearContent(){return this.status===0||this.status===401||this.status===403||this.status>=500;}
}
export async function request<T>(token:string,path:string,body?:unknown,key?:string):Promise<T>{
  if(!path.startsWith("/v2/adjudication/case-works")||path.includes(".."))throw new Error("非法裁决请求路径");
  let response:Response;try{response=await fetch(path,{method:body===undefined?"GET":"POST",headers:{Authorization:"Bearer "+token,Accept:"application/json",...(body===undefined?{}:{"Content-Type":"application/json","Idempotency-Key":key!})},body:body===undefined?undefined:JSON.stringify(body),cache:"no-store"});}catch{throw new AdjudicationError(0,"NETWORK_UNCONFIRMED");}
  if(!response.ok){let data:{code?:string;reason?:string}={};try{data=await response.json();}catch{/* No server error body is required. */}throw new AdjudicationError(response.status,data.code&&/^[A-Z0-9_]{1,100}$/.test(data.code)?data.code:"HTTP_"+response.status,data.reason);}
  try{return await response.json() as T;}catch{throw new AdjudicationError(0,"RESPONSE_UNCONFIRMED");}
}
export const casePath=(id:string)=>"/v2/adjudication/case-works/"+encodeURIComponent(id);
export async function digest(text:string){return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",new TextEncoder().encode(text))),b=>b.toString(16).padStart(2,"0")).join("");}
