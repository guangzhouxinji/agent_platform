import {humanRequestHash,humanRequestIdentity,humanRequestKey} from "./humanRequest";

export type HumanRecoveryRecord={key:string;requestSha:string;createdAt:number;expiresAt:number};
export function humanRequestRecovery(token:string,taskId:string,taskExpiresAt:string,storage?:Storage,now:()=>number=Date.now){
  const actor=humanRequestIdentity(token),taskUntil=Date.parse(taskExpiresAt);
  if(!taskId.trim()||!Number.isFinite(taskUntil))throw new Error("人工请求恢复上下文不可核验。");
  const binding={issuer:actor.issuer,subject:actor.subject,taskId};
  const storageKey="consultation-human-request/v1:"+JSON.stringify(Object.values(binding));
  let unavailable:unknown;
  if(!storage){try{storage=window.sessionStorage;}catch(error){unavailable=error;}}
  const fail=()=>new Error("人工请求恢复记录不可核验；请保留原键，不能创建新的请求。");
  function store():Storage{if(unavailable||!storage)throw new Error("浏览器恢复存储不可用，未发送人工请求。");return storage;}
  function valid(value:unknown):value is HumanRecoveryRecord{
    if(!value||typeof value!=="object"||Object.keys(value).sort().join(",")!=="createdAt,expiresAt,key,requestSha")return false;
    const r=value as HumanRecoveryRecord;
    return humanRequestKey(r.key)&&humanRequestHash(r.requestSha)&&Number.isSafeInteger(r.createdAt)&&Number.isSafeInteger(r.expiresAt)
      &&r.createdAt>=0&&r.createdAt<=now()&&r.expiresAt>r.createdAt&&r.expiresAt<=Math.min(taskUntil,r.createdAt+180*86400000);
  }
  function load():HumanRecoveryRecord|null{
    const raw=store().getItem(storageKey);if(raw===null)return null;
    let record;try{record=JSON.parse(raw);}catch{throw fail();}
    if(!record||Object.keys(record).sort().join(",")!=="binding,record"
      ||!record.binding||JSON.stringify(record.binding)!==JSON.stringify(binding)||!valid(record.record))throw fail();
    if(record.record.expiresAt<=now()){store().removeItem(storageKey);return null;}
    return {...record.record};
  }
  return {load,save(record:HumanRecoveryRecord){
    if(!valid(record)||record.expiresAt<=now())throw fail();
    const previous=load();
    if(previous&&(previous.key!==record.key||previous.requestSha!==record.requestSha
      ||previous.createdAt!==record.createdAt||previous.expiresAt!==record.expiresAt))throw fail();
    const raw=JSON.stringify({binding,record});store().setItem(storageKey,raw);
    if(store().getItem(storageKey)!==raw)throw new Error("人工请求恢复记录未可靠保存，未发送请求。");
  },clear(){store().removeItem(storageKey);}};
}
