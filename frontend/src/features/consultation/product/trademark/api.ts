import {z} from 'zod';
import {detailSchema,envelope,pageSchema,type Query,type RecordRef} from './types';

export class TrademarkError extends Error {
  constructor(public status:number){super(status===401?'会话已失效，请重新登录。':status===403?'当前账号没有这项任务或来源的读取权限。':status===404?'当前获准范围中没有这个确切版本的商标。':status===400?'查询条件不合法，请核对日期、页码和筛选内容。':status===504?'本次核验超时，请重试。':status===502?'来源数据未通过校验，请稍后重试。':status===0?'网络连接中断，请重试。':'当前来源、授权或存储无法核验，请稍后重试。');}
}
const base=(task:string)=>'/v2/consultation/tasks/'+encodeURIComponent(task)+'/trademarks';
async function read<T extends z.ZodType>(token:string,task:string,url:string,schema:T,signal:AbortSignal){
  let response:Response;
  try{response=await fetch(url,{headers:{Authorization:'Bearer '+token,Accept:'application/json','X-Request-Id':crypto.randomUUID()},cache:'no-store',signal});}
  catch(error){if(signal.aborted)throw error;throw new TrademarkError(0);}
  if(!response.ok)throw new TrademarkError(response.status);
  let result:z.infer<ReturnType<typeof envelope<T>>>;
  try{result=envelope(schema).parse(await response.json());}catch{throw new TrademarkError(502);}
  if(result.taskId!==task||Date.parse(result.expiresAt)<=Date.now())throw new TrademarkError(503);
  return result;
}
export async function queryTrademarks(token:string,task:string,query:Query,signal:AbortSignal,refresh=false){
  const params=new URLSearchParams({page:String(query.page),size:String(query.size),refresh:String(refresh)});
  for(const key of ['registrationNumber','name','applicant','registeredFrom','registeredTo','validFrom','validTo'] as const)
    if(query[key].trim())params.set(key,query[key].trim());
  for(const key of ['categories','statuses'] as const)for(const value of query[key].split(/[,，]/).map(s=>s.trim()).filter(Boolean))params.append(key,value);
  const result=await read(token,task,base(task)+'?'+params,pageSchema,signal);
  const p=result.data.pagination,remaining=Math.max(0,p.reliableTotal-(query.page-1)*query.size);
  if(p.request.page!==query.page||p.request.size!==query.size||result.data.items.length!==Math.min(query.size,remaining)||p.hasNext!==(remaining>query.size))throw new TrademarkError(502);
  return result;
}
export async function trademarkDetail(token:string,task:string,ref:RecordRef,signal:AbortSignal,refresh=false){
  const params=new URLSearchParams({sourceId:ref.sourceId,recordId:ref.recordId,version:ref.version,refresh:String(refresh)});
  const result=await read(token,task,base(task)+'/records/detail?'+params,detailSchema,signal);
  const actual=result.data.item.ref;
  if(actual.sourceId!==ref.sourceId||actual.recordId!==ref.recordId||actual.version!==ref.version)throw new TrademarkError(502);
  return result;
}
