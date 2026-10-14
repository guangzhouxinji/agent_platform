import {z} from 'zod';
import {validPromptRequestKey} from './promptCreateApi';
const uuid=z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const sha=z.string().regex(/^[0-9a-f]{64}$/);
const timestamp=z.string().refine(v=>/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v)&&Number.isFinite(Date.parse(v)));
const generation=z.string().refine(v=>/^[1-9][0-9]{0,18}$/.test(v)&&BigInt(v)>=2n&&BigInt(v)<=9223372036854775807n);
const receipt=z.object({receiptId:uuid,workspaceId:uuid,promptId:uuid,revisionId:uuid,generation,contentSha:sha,resultSha:sha,submittedAt:timestamp,auditUntil:timestamp}).strict();
const rejection=z.object({code:z.enum(['PROMPT_SAVE_BASE_CONFLICT','PROMPT_SAVE_KEY_CONFLICT'])}).strict();
export type PromptSaveReceipt=z.infer<typeof receipt>;
export class PromptSaveError extends Error{
  constructor(public readonly status:number,public readonly code?:string){
    super(status===401?'会话已失效，请重新登录。':status===403?'当前工作区、提示词或保存权限不可用，已清除正文并关闭写入。':
      code==='PROMPT_SAVE_BASE_CONFLICT'?'已知版本、修订编号或提示词类型已不匹配，请核对后重新保存。':
      status===409?'该提交编号的结果需要核对，请查询原提交结果。':
      status===400||status===413||status===415?'输入未被受理，请检查字段和内容大小。':
      code==='PROMPT_SAVE_TARGET_MISMATCH'?'该编号结果与当前所选提示词不一致，当前页面无法核验。':
      '保存结果尚未确认，请使用原提交编号查询；系统不会自动重复保存。');
  }
}
async function dispatch(token:string,workspaceId:string,promptId:string,key:string,serialized:string|undefined,targetGeneration:string|undefined,signal?:AbortSignal):Promise<PromptSaveReceipt>{
  if(!token)throw new PromptSaveError(401);
  if(!uuid.safeParse(workspaceId).success||!uuid.safeParse(promptId).success||!validPromptRequestKey(key))throw new PromptSaveError(400);
  let response:Response;
  try{response=await fetch(`/v2/consultation/prompt-workspaces/${workspaceId}/${serialized===undefined?'requests/SAVE':`prompts/${promptId}/revisions`}`,{
    method:serialized===undefined?'GET':'POST',headers:{Authorization:'Bearer '+token,Accept:'application/json','Idempotency-Key':key,...(serialized===undefined?{}:{'Content-Type':'application/json'})},
    ...(serialized===undefined?{}:{body:serialized}),cache:'no-store',redirect:'error',signal});}
  catch(error){if(signal?.aborted)throw error;throw new PromptSaveError(0);}
  if(!response.ok){
    let code:string|undefined;
    if(response.status===409){try{const parsed=rejection.safeParse(await response.json());if(parsed.success)code=parsed.data.code;}catch{/* A malformed conflict cannot prove this write was rejected before reserve. */}}
    throw new PromptSaveError(response.status,code);
  }
  if(response.status!==200)throw new PromptSaveError(503);
  let parsed:PromptSaveReceipt;
  try{parsed=receipt.parse(await response.json());}catch{throw new PromptSaveError(503);}
  if(parsed.workspaceId!==workspaceId||parsed.promptId!==promptId)throw new PromptSaveError(503,'PROMPT_SAVE_TARGET_MISMATCH');
  if(targetGeneration!==undefined&&parsed.generation!==targetGeneration||Date.parse(parsed.auditUntil)<=Date.now()||Date.parse(parsed.submittedAt)>=Date.parse(parsed.auditUntil))throw new PromptSaveError(503);
  return parsed;
}
export const savePrompt=(token:string,workspaceId:string,promptId:string,key:string,serialized:string,targetGeneration:string,signal?:AbortSignal)=>dispatch(token,workspaceId,promptId,key,serialized,targetGeneration,signal);
export const recoverPromptSave=(token:string,workspaceId:string,promptId:string,key:string,targetGeneration?:string,signal?:AbortSignal)=>dispatch(token,workspaceId,promptId,key,undefined,targetGeneration,signal);
