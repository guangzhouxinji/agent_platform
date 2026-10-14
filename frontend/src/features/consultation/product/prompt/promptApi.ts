import {z} from 'zod';

const uuid=z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const timestamp=z.string().refine(value=>Number.isFinite(Date.parse(value))&&value.endsWith('Z'));
const positive=z.number().int().positive().max(Number.MAX_SAFE_INTEGER);
const variable=z.object({name:z.string(),type:z.enum(['STRING','BOOLEAN','INTEGER','DECIMAL','STRING_LIST']),required:z.boolean(),
  sourceRef:z.string(),allowDefault:z.boolean(),defaultValue:z.union([z.string(),z.boolean(),z.array(z.string()),z.null()]),allowEmpty:z.boolean()}).strict();
const revisionSchema=z.object({workspaceId:uuid,contentSha:z.string().regex(/^[0-9a-f]{64}$/),promptId:uuid,revisionId:uuid,version:positive,kind:z.enum(['GUIDE','SYSTEM']),name:z.string(),body:z.string(),
  scenarioIds:z.array(z.string()),intentIds:z.array(z.string()),audienceRef:z.string(),language:z.string(),displayOrder:z.number().int(),
  variables:z.array(variable),submittedAt:timestamp,bodyUntil:timestamp}).strict();
const historySchema=z.object({promptId:uuid,page:positive,size:z.union([z.literal(10),z.literal(20),z.literal(50)]),
  total:z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),revisions:z.array(z.object({revisionId:uuid,version:positive,submittedAt:timestamp,bodyUntil:timestamp}).strict())}).strict();
export type PromptRevisionView=z.infer<typeof revisionSchema>;
export type PromptHistoryView=z.infer<typeof historySchema>;
export class PromptReadError extends Error{
  constructor(public readonly status:number){super(status===401?'会话已失效，请重新登录。':status===403?'所选提示词或当前读取权限已不可用。':
    status===400?'提示词链接或分页参数不正确。':status===0?'连接中断，请重新读取所选版本。':'当前提示词资料无法核验，请稍后重新读取。');}
}
function id(value:string){if(!uuid.safeParse(value).success)throw new PromptReadError(400);return value;}
async function read(token:string,url:string,signal?:AbortSignal):Promise<unknown>{
  if(!token)throw new PromptReadError(401);
  let response:Response;
  try{response=await fetch(url,{method:'GET',headers:{Authorization:'Bearer '+token,Accept:'application/json'},cache:'no-store',redirect:'error',signal});}
  catch(failure){if(signal?.aborted)throw failure;throw new PromptReadError(0);}
  if(!response.ok)throw new PromptReadError(response.status);
  try{return await response.json();}catch{throw new PromptReadError(503);}
}
export async function readPromptRevision(token:string,promptId:string,revisionId:string,signal?:AbortSignal):Promise<PromptRevisionView>{
  const raw=await read(token,`/v2/consultation/prompts/${id(promptId)}/revisions/${id(revisionId)}`,signal);
  const parsed=revisionSchema.safeParse(raw);
  if(!parsed.success||parsed.data.promptId!==promptId||parsed.data.revisionId!==revisionId
    ||Date.parse(parsed.data.bodyUntil)<=Date.now()||Date.parse(parsed.data.submittedAt)>=Date.parse(parsed.data.bodyUntil))throw new PromptReadError(503);
  return parsed.data;
}
export async function readPromptHistory(token:string,promptId:string,page=1,size:10|20|50=20,signal?:AbortSignal):Promise<PromptHistoryView>{
  if(!Number.isSafeInteger(page)||page<1||![10,20,50].includes(size))throw new PromptReadError(400);
  const raw=await read(token,`/v2/consultation/prompts/${id(promptId)}/revisions?page=${page}&size=${size}`,signal);
  const parsed=historySchema.safeParse(raw);
  if(!parsed.success||parsed.data.promptId!==promptId||parsed.data.page!==page||parsed.data.size!==size||parsed.data.revisions.length>size
    ||parsed.data.total<parsed.data.revisions.length||new Set(parsed.data.revisions.map(row=>row.revisionId)).size!==parsed.data.revisions.length
    ||parsed.data.revisions.some(row=>Date.parse(row.bodyUntil)<=Date.now()||Date.parse(row.submittedAt)>=Date.parse(row.bodyUntil)))throw new PromptReadError(503);
  return parsed.data;
}
