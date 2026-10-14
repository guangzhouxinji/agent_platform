export type HumanMaterial={sourceId:string;documentId:string;documentVersion:string;fields:string[]};
export type HumanRequestCommand={expectedTaskVersion:number;comparison:{comparisonId:string;reportIds:string[]}|null;
  materials:HumanMaterial[];reason:string;purpose:"ORDINARY_ASSISTANCE"};
export type HumanReference={type:string;id:string;revision:number;sha:string};
export type HumanRequestView={kind:"REQUEST_RECORD"|"RECEIPT_ONLY";receipt:{taskId:string;packageId:string;
  packageRevision:number;manifestSha:string;eventSequence:number;requestSha:string;createdAt:string;auditUntil:string};
  body:{taskVersion:number;reason:string;purpose:"ORDINARY_ASSISTANCE";goal:HumanReference;message:HumanReference;input:HumanReference;
    materials:HumanMaterial[];comparisonId:string|null;reportIds:string[];gaps:string[];bodyAccessUntil:string}|null};
const fail=():never=>{throw new Error("人工请求响应或原键绑定不可核验，请保留原键重新查回。");};
const text=(value:unknown):value is string=>typeof value==="string"&&value.trim().length>0;
const positive=(value:unknown):value is number=>Number.isSafeInteger(value)&&Number(value)>0;
export const humanRequestKey=(value:unknown):value is string=>typeof value==="string"&&/^[A-Za-z0-9][A-Za-z0-9._:-]{0,179}$/.test(value);
export const humanRequestHash=(value:unknown):value is string=>typeof value==="string"&&/^[a-f0-9]{64}$/.test(value);
function object(value:unknown,keys:string[]):Record<string,unknown>{
  if(!value||typeof value!=="object"||Array.isArray(value)||Object.keys(value).sort().join(",")!==keys.sort().join(","))return fail();
  return value as Record<string,unknown>;
}
function strings(value:unknown):string[]{
  if(!Array.isArray(value)||!value.every(text)||new Set(value).size!==value.length)return fail();return value;
}
function time(value:unknown):number{
  if(typeof value!=="string"||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)||!Number.isFinite(Date.parse(value)))return fail();
  if(new Date(Date.parse(value)).toISOString().slice(0,19)!==value.slice(0,19))return fail();
  return Date.parse(value);
}
// Token parsing partitions local hints only; it does not validate a signature or grant permission.
export function humanRequestIdentity(token:string):{issuer:string;subject:string;kind:"HUMAN"}{
  try{
    const pieces=token.split(".");if(pieces.length!==3||!pieces.every(Boolean))return fail();
    const bytes=Uint8Array.from(atob(pieces[1].replace(/-/g,"+").replace(/_/g,"/")),c=>c.charCodeAt(0));
    const value=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(bytes));
    if(!text(value?.iss)||!text(value?.sub)||value.iss.length>512||value.sub.length>200)return fail();
    return {issuer:value.iss,subject:value.sub,kind:"HUMAN"};
  }catch{return fail();}
}
function material(value:unknown):HumanMaterial{
  const m=object(value,["sourceId","documentId","documentVersion","fields"]);
  if(!text(m.sourceId)||!text(m.documentId)||!text(m.documentVersion))return fail();
  const fields=strings(m.fields);if(!fields.length||fields.some(f=>!["TITLE","ABSTRACT","CLAIMS","DESCRIPTION"].includes(f)))return fail();
  return {sourceId:m.sourceId,documentId:m.documentId,documentVersion:m.documentVersion,fields:[...fields].sort()};
}
function command(value:unknown):HumanRequestCommand{
  const c=object(value,["expectedTaskVersion","comparison","materials","reason","purpose"]);
  if(!positive(c.expectedTaskVersion)||!text(c.reason)||c.reason.length>4000||c.purpose!=="ORDINARY_ASSISTANCE"||!Array.isArray(c.materials))return fail();
  const materials=c.materials.map(material);
  if(new Set(materials.map(m=>JSON.stringify([m.sourceId,m.documentId,m.documentVersion]))).size!==materials.length)return fail();
  // JS relational string comparison, like Java String.compareTo, compares UTF-16 code units.
  const compare=(a:string,b:string)=>a<b?-1:a>b?1:0;
  materials.sort((a,b)=>compare(a.sourceId,b.sourceId)||compare(a.documentId,b.documentId)||compare(a.documentVersion,b.documentVersion));
  let comparison:HumanRequestCommand["comparison"]=null;
  if(c.comparison!==null){const p=object(c.comparison,["comparisonId","reportIds"]);if(!text(p.comparisonId))return fail();
    comparison={comparisonId:p.comparisonId,reportIds:[...strings(p.reportIds)].sort()};}
  return {expectedTaskVersion:c.expectedTaskVersion,comparison,materials,reason:c.reason,purpose:c.purpose};
}
// ProductComparisonJson uses Jackson's default uppercase control escapes, then UTF-8.
// Reject malformed UTF-16 rather than copying Java's lossy '?' replacement for lone surrogates.
function javaString(value:string):string {
  let result='"';
  const slash=String.fromCharCode(92),shortEscapes:Record<number,string>={8:'b',9:'t',10:'n',12:'f',13:'r'};
  for(let i=0;i<value.length;i++){
    const char=value[i],code=value.charCodeAt(i);
    if(code===34||code===92){result+=slash+char;continue;}
    if(Object.hasOwn(shortEscapes,code)){result+=slash+shortEscapes[code];continue;}
    if(code<0x20){result+=slash+'u'+code.toString(16).toUpperCase().padStart(4,'0');continue;}
    if(code>=0xd800&&code<=0xdbff){
      const next=value.charCodeAt(i+1);
      if(!(next>=0xdc00&&next<=0xdfff))return fail();
      result+=char+value[++i];continue;
    }
    if(code>=0xdc00&&code<=0xdfff)return fail();
    result+=char;
  }
  return result+'"';
}
function javaCanonical(value:unknown):string {
  if(value===null)return 'null';
  if(typeof value==='string')return javaString(value);
  if(typeof value==='number'&&Number.isSafeInteger(value))return String(value);
  if(typeof value==='boolean')return String(value);
  if(Array.isArray(value))return '['+value.map(javaCanonical).join(',')+']';
  if(value&&typeof value==='object')return '{'+Object.keys(value).sort().map(key=>
    javaString(key)+':'+javaCanonical((value as Record<string,unknown>)[key])).join(',')+'}';
  return fail();
}
export async function humanRequestSha(token:string,taskId:string,key:string,value:HumanRequestCommand):Promise<string>{
  if(!text(taskId)||!humanRequestKey(key))return fail();
  const canonical=javaCanonical({taskId,actor:humanRequestIdentity(token),requestId:key,command:command(value)});
  const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(canonical));
  return Array.from(new Uint8Array(bytes)).map(byte=>byte.toString(16).padStart(2,'0')).join('');
}
function reference(value:unknown,type:string):void{
  const r=object(value,["type","id","revision","sha"]);
  if(r.type!==type||!text(r.id)||!positive(r.revision)||!humanRequestHash(r.sha))fail();
}
export async function bindHumanRequest(value:unknown,taskId:string,expectedSha:string,token:string,key:string,now=Date.now()):Promise<HumanRequestView>{
  humanRequestIdentity(token);if(!text(taskId)||!humanRequestHash(expectedSha)||!humanRequestKey(key)||!Number.isFinite(now))return fail();
  const view=object(value,["kind","receipt","body"]);
  const r=object(view.receipt,["taskId","packageId","packageRevision","manifestSha","eventSequence","requestSha","createdAt","auditUntil"]);
  if(r.taskId!==taskId||!text(r.packageId)||!positive(r.packageRevision)||r.eventSequence!==1
    ||!humanRequestHash(r.manifestSha)||r.requestSha!==expectedSha)return fail();
  const created=time(r.createdAt),audit=time(r.auditUntil);
  if(created>now||audit<=now||audit<=created||audit>created+365*86400000)return fail();
  if(view.kind==="RECEIPT_ONLY"){if(view.body!==null)return fail();return value as HumanRequestView;}
  if(view.kind!=="REQUEST_RECORD")return fail();
  const b=object(view.body,["taskVersion","reason","purpose","goal","message","input","materials","comparisonId","reportIds","gaps","bodyAccessUntil"]);
  reference(b.goal,"GOAL");reference(b.message,"USER_MESSAGE");reference(b.input,"SEARCH_INPUT");
  const reports=strings(b.reportIds),gaps=strings(b.gaps);
  if(b.comparisonId!==null&&!text(b.comparisonId)||b.comparisonId===null&&reports.length)return fail();
  const expectedGaps=b.comparisonId===null?["NO_COMPARISON_SELECTED","NO_REPORT_SELECTED"]:reports.length?[]:["NO_REPORT_SELECTED"];
  if(JSON.stringify(gaps)!==JSON.stringify(expectedGaps))return fail();
  const bodyUntil=time(b.bodyAccessUntil);
  if(bodyUntil<=now||bodyUntil<=created||bodyUntil>audit||bodyUntil>created+180*86400000)return fail();
  const restored=command({expectedTaskVersion:b.taskVersion,comparison:b.comparisonId===null?null:{comparisonId:b.comparisonId,reportIds:reports},
    materials:b.materials,reason:b.reason,purpose:b.purpose});
  if(await humanRequestSha(token,taskId,key,restored)!==expectedSha)return fail();
  return value as HumanRequestView;
}
