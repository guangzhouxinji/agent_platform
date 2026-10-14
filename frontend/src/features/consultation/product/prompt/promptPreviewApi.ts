import {z} from 'zod';
import type {PromptRevisionView} from './promptApi';
import {promptCopyUuid} from './promptCopyCommand';
import {previewVariableName,validPreviewUnicode,validPreviewValue} from './promptPreviewCommand';

const uuid=z.string().regex(promptCopyUuid),sha=z.string().regex(/^[0-9a-f]{64}$/);
const timestamp=z.string().refine(value=>/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value)&&Number.isFinite(Date.parse(value)));
const type=z.enum(['STRING','BOOLEAN','INTEGER','DECIMAL','STRING_LIST']);
const data=z.object({kind:z.literal('DATA'),name:z.string().regex(previewVariableName),type,sourceEvidenceId:z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/),
  value:z.union([z.string(),z.boolean(),z.array(z.string())])}).strict().refine(value=>validPreviewValue(value.value,value.type));
const segment=z.union([z.object({kind:z.literal('LITERAL'),text:z.string().refine(validPreviewUnicode)}).strict(),data]);
const result=z.object({status:z.literal('OK'),mode:z.literal('TEST'),workspaceId:uuid,promptId:uuid,revisionId:uuid,
  revisionNumber:z.string().refine(value=>/^[1-9][0-9]{0,18}$/.test(value)&&BigInt(value)<=9223372036854775807n),
  contentSha:sha,schemaSha:sha,policySha:sha,expiresAt:timestamp,segments:z.array(segment).max(1024)}).strict();
const issue=z.object({code:z.enum(['MISSING_VARIABLE','UNDEFINED_VARIABLE','VARIABLE_TYPE_MISMATCH','VARIABLE_EMPTY_NOT_ALLOWED']),variable:z.string().regex(previewVariableName)}).strict();
const diagnostic=z.object({code:z.literal('PROMPT_PREVIEW_INVALID_VALUES'),expiresAt:timestamp,issues:z.array(issue)}).strict();
const generic=z.object({code:z.string().regex(/^[A-Z][A-Z0-9_]{0,127}$/)}).strict();
const request=z.object({contentSha:sha,sourceId:uuid,values:z.array(z.object({name:z.string().regex(previewVariableName),type,
  value:z.union([z.string(),z.boolean(),z.array(z.string()),z.null()])}).strict().refine(value=>value.value===null||validPreviewValue(value.value,value.type))).max(64)}).strict();
export type PromptPreviewResult=z.infer<typeof result>;
export type PromptPreviewDiagnostic=z.infer<typeof diagnostic>;
export class PromptPreviewError extends Error{
  constructor(public readonly status:number,public readonly diagnostic?:PromptPreviewDiagnostic){
    super(status===401?'会话已失效，请重新登录。':status===403?'当前预览权限或所选版本已不可用，已关闭正文和样例。':
      diagnostic?'样例值未通过核验，请检查所列变量。':status===400?'预览输入未被受理，请检查样例与来源编号。':
      status===413?'预览输入或结果超出允许大小，请缩减样例。':status===415?'预览输入格式不可用，请重新提交。':
      '预览结果无法核验，请重新提交。');
  }
}
async function boundedJson(response:Response):Promise<unknown>{
  if(!response.body)throw new PromptPreviewError(503);
  const reader=response.body.getReader(),chunks:Uint8Array[]=[];let size=0;
  try{while(true){const chunk=await reader.read();if(chunk.done)break;size+=chunk.value.length;
    if(size>1024*1024){await reader.cancel();throw new PromptPreviewError(503);}chunks.push(chunk.value);}
    const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
    return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
  }catch{throw new PromptPreviewError(503);}finally{reader.releaseLock();}
}
const currentExpiry=(expiresAt:string,revision:PromptRevisionView)=>Date.parse(expiresAt)>Date.now()&&Date.parse(expiresAt)<=Date.parse(revision.bodyUntil);
export async function previewPrompt(token:string,revision:PromptRevisionView,serialized:string,signal?:AbortSignal):Promise<PromptPreviewResult>{
  if(!token)throw new PromptPreviewError(401);
  if(![revision.workspaceId,revision.promptId,revision.revisionId].every(id=>promptCopyUuid.test(id))
    ||!/^[0-9a-f]{64}$/.test(revision.contentSha)||Date.parse(revision.bodyUntil)<=Date.now())throw new PromptPreviewError(400);
  let input:z.infer<typeof request>;
  try{if(new TextEncoder().encode(serialized).length>1024*1024)throw new Error('budget');input=request.parse(JSON.parse(serialized));}
  catch{throw new PromptPreviewError(400);}
  if(input.contentSha!==revision.contentSha||new Set(input.values.map(value=>value.name)).size!==input.values.length
    ||input.values.some(value=>!revision.variables.some(variable=>variable.name===value.name&&variable.type===value.type)))throw new PromptPreviewError(400);
  let response:Response;
  try{response=await fetch(`/v2/consultation/prompt-workspaces/${revision.workspaceId}/prompts/${revision.promptId}/revisions/${revision.revisionId}/preview`,{
    method:'POST',headers:{Authorization:'Bearer '+token,Accept:'application/json','Content-Type':'application/json'},body:serialized,cache:'no-store',redirect:'error',signal});}
  catch(failure){if(signal?.aborted)throw failure;throw new PromptPreviewError(0);}
  if(response.status===401||response.status===403)throw new PromptPreviewError(response.status);
  const raw=await boundedJson(response);
  if(response.status===400){const parsed=diagnostic.safeParse(raw);
    if(parsed.success){if(!currentExpiry(parsed.data.expiresAt,revision))throw new PromptPreviewError(503);throw new PromptPreviewError(400,parsed.data);}
  }
  if(response.status!==200){if(!generic.safeParse(raw).success)throw new PromptPreviewError(503);throw new PromptPreviewError(response.status);}
  const parsed=result.safeParse(raw);
  if(!parsed.success||parsed.data.workspaceId!==revision.workspaceId||parsed.data.promptId!==revision.promptId||parsed.data.revisionId!==revision.revisionId
    ||parsed.data.revisionNumber!==String(revision.version)||parsed.data.contentSha!==revision.contentSha||!currentExpiry(parsed.data.expiresAt,revision)
    ||parsed.data.segments.some(item=>item.kind==='DATA'&&!revision.variables.some(variable=>variable.name===item.name&&variable.type===item.type)))throw new PromptPreviewError(503);
  return parsed.data;
}
