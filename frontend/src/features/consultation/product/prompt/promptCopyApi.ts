import {z} from 'zod';
import {validPromptRequestKey} from './promptCreateApi';
import {promptCopyUuid,type PromptCopySource} from './promptCopyCommand';

const uuid=z.string().regex(promptCopyUuid),sha=z.string().regex(/^[0-9a-f]{64}$/);
const timestamp=z.string().refine(v=>/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v)&&Number.isFinite(Date.parse(v)));
const receipt=z.object({receiptId:uuid,workspaceId:uuid,promptId:uuid,revisionId:uuid,generation:z.literal('1'),
  contentSha:sha,resultSha:sha,submittedAt:timestamp,auditUntil:timestamp}).strict();
const command=z.object({expectedGeneration:z.literal(0),targetWorkspaceId:uuid,sourceRevisionId:uuid,sourceContentSha:sha,sourceId:uuid}).strict();
const rejection=z.object({code:z.literal('PROMPT_COPY_KEY_CONFLICT')}).strict();
export type PromptCopyReceipt=z.infer<typeof receipt>;
export class PromptCopyError extends Error{
  constructor(public readonly status:number,public readonly code?:string){
    super(status===401?'会话已失效，请重新登录。':status===403?'当前来源、目标或复制权限不可用，已关闭操作。':
      status===400||status===413||status===415?'输入未被受理，请检查工作区与来源编号。':
      status===409?'该提交编号已有原提交，请查询原复制结果。':
      code==='PROMPT_COPY_TARGET_MISMATCH'?'原回执与本次查询目标不一致，无法核验。':
      '复制结果尚未确认，请使用原提交编号查询；系统不会自动重复复制。');
  }
}
async function dispatch(token:string,sourceWorkspaceId:string,targetWorkspaceId:string,key:string,
  post?:{source:PromptCopySource;serialized:string},expectedSha?:string,signal?:AbortSignal):Promise<PromptCopyReceipt>{
  if(!token)throw new PromptCopyError(401);
  if(!uuid.safeParse(sourceWorkspaceId).success||!uuid.safeParse(targetWorkspaceId).success||!validPromptRequestKey(key))throw new PromptCopyError(400);
  if(post){
    let parsed:z.infer<typeof command>;
    try{parsed=command.parse(JSON.parse(post.serialized));}catch{throw new PromptCopyError(400);}
    if(!uuid.safeParse(post.source.sourcePromptId).success||parsed.targetWorkspaceId!==targetWorkspaceId
      ||parsed.sourceRevisionId!==post.source.sourceRevisionId||parsed.sourceContentSha!==post.source.sourceContentSha)throw new PromptCopyError(400);
  }
  let response:Response;
  try{response=await fetch(post?`/v2/consultation/prompt-workspaces/${sourceWorkspaceId}/prompts/${post.source.sourcePromptId}/copies`:
    `/v2/consultation/prompt-workspaces/${targetWorkspaceId}/requests/COPY?sourceWorkspaceId=${sourceWorkspaceId}`,{
      method:post?'POST':'GET',headers:{Authorization:'Bearer '+token,Accept:'application/json','Idempotency-Key':key,...(post?{'Content-Type':'application/json'}:{})},
      ...(post?{body:post.serialized}:{}),cache:'no-store',redirect:'error',signal});}
  catch(error){if(signal?.aborted)throw error;throw new PromptCopyError(0);}
  if(!response.ok){
    let code:string|undefined;
    if(response.status===409){try{const parsed=rejection.safeParse(await response.json());if(parsed.success)code=parsed.data.code;}catch{/* The original key remains unresolved. */}}
    throw new PromptCopyError(response.status,code);
  }
  if(response.status!==200)throw new PromptCopyError(503);
  let parsed:PromptCopyReceipt;
  try{parsed=receipt.parse(await response.json());}catch{throw new PromptCopyError(503);}
  if(parsed.workspaceId!==targetWorkspaceId)throw new PromptCopyError(503,'PROMPT_COPY_TARGET_MISMATCH');
  if(expectedSha!==undefined&&parsed.contentSha!==expectedSha||Date.parse(parsed.auditUntil)<=Date.now()
    ||Date.parse(parsed.submittedAt)>=Date.parse(parsed.auditUntil))throw new PromptCopyError(503);
  return parsed;
}
export const copyPrompt=(token:string,source:PromptCopySource,targetWorkspaceId:string,key:string,serialized:string,signal?:AbortSignal)=>
  dispatch(token,source.sourceWorkspaceId,targetWorkspaceId,key,{source,serialized},source.sourceContentSha,signal);
export const recoverPromptCopy=(token:string,sourceWorkspaceId:string,targetWorkspaceId:string,key:string,expectedSha?:string,signal?:AbortSignal)=>
  dispatch(token,sourceWorkspaceId,targetWorkspaceId,key,undefined,expectedSha,signal);
