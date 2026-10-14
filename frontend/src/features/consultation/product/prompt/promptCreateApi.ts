import {z} from 'zod';
const uuid=z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const sha=z.string().regex(/^[0-9a-f]{64}$/);
const timestamp=z.string().refine(v=>/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v)&&Number.isFinite(Date.parse(v)));
const receipt=z.object({receiptId:uuid,workspaceId:uuid,promptId:uuid,revisionId:uuid,generation:z.literal(1),contentSha:sha,resultSha:sha,submittedAt:timestamp,auditUntil:timestamp}).strict();
export type PromptCreateReceipt=z.infer<typeof receipt>;
export class PromptCreateError extends Error {
  constructor(public readonly status:number){super(status===401?'会话已失效，请重新登录。':status===403?'当前工作区或创建权限不可用，已清除正文并关闭写入。':status===400||status===413||status===415?'输入未被受理，请检查字段和内容大小。':status===409?'该提交编号已关联其他内容，请查询原提交结果。':'提交结果尚未确认，请使用原提交编号查询；系统不会自动重复提交。');}
}
export function validPromptRequestKey(value:string){return /^[\x21-\x7e]{1,64}$/.test(value);}
async function dispatch(token:string,workspaceId:string,key:string,serialized:string|undefined,signal?:AbortSignal):Promise<PromptCreateReceipt>{
  if(!token)throw new PromptCreateError(401);
  if(!uuid.safeParse(workspaceId).success||!validPromptRequestKey(key))throw new PromptCreateError(400);
  let response:Response;
  try{response=await fetch(`/v2/consultation/prompt-workspaces/${workspaceId}/${serialized===undefined?'requests/CREATE':'prompts'}`,{
    method:serialized===undefined?'GET':'POST',headers:{Authorization:'Bearer '+token,Accept:'application/json','Idempotency-Key':key,...(serialized===undefined?{}:{'Content-Type':'application/json'})},
    ...(serialized===undefined?{}:{body:serialized}),cache:'no-store',redirect:'error',signal});}
  catch(error){if(signal?.aborted)throw error;throw new PromptCreateError(0);}
  if(!response.ok)throw new PromptCreateError(response.status);
  if(response.status!==200)throw new PromptCreateError(503);
  try{const parsed=receipt.parse(await response.json());if(parsed.workspaceId!==workspaceId||Date.parse(parsed.auditUntil)<=Date.now()||Date.parse(parsed.submittedAt)>=Date.parse(parsed.auditUntil))throw new Error('receipt');return parsed;}
  catch{throw new PromptCreateError(503);}
}
export const createPrompt=(token:string,workspaceId:string,key:string,serialized:string,signal?:AbortSignal)=>dispatch(token,workspaceId,key,serialized,signal);
export const recoverPromptCreate=(token:string,workspaceId:string,key:string,signal?:AbortSignal)=>dispatch(token,workspaceId,key,undefined,signal);
