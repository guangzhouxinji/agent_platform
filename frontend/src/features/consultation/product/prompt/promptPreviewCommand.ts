import type {PromptRevisionView} from './promptApi';
import {PromptCommandError} from './promptCreateCommand';
import {promptCopyUuid} from './promptCopyCommand';

export type PromptPreviewType=PromptRevisionView['variables'][number]['type'];
export interface PromptPreviewInput {provided:boolean;text:string;booleanValue:boolean}
export type PromptPreviewInputs=Record<string,PromptPreviewInput>;
export const emptyPromptPreviewInputs=(revision:PromptRevisionView):PromptPreviewInputs=>Object.fromEntries(revision.variables.map(variable=>
  [variable.name,{provided:false,text:variable.type==='STRING_LIST'?'[]':'',booleanValue:false}]));
export const previewVariableName=/^[A-Za-z][A-Za-z0-9_]{0,127}$/;
export function validPreviewUnicode(value:string):boolean{
  for(let i=0;i<value.length;i++){const code=value.charCodeAt(i);if(code>=0xd800&&code<=0xdbff){const next=value.charCodeAt(++i);if(!(next>=0xdc00&&next<=0xdfff))return false;}else if(code>=0xdc00&&code<=0xdfff)return false;}return true;
}
export function validPreviewNumber(value:string,type:'INTEGER'|'DECIMAL'):boolean{
  if(value.length>1024)return false;
  if(type==='INTEGER')return /^-?(?:0|[1-9][0-9]*)$/.test(value);
  const parsed=/^-?(0|[1-9][0-9]*)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/.exec(value);
  if(!parsed)return false;
  const scale=BigInt(parsed[2]?.length??0)-BigInt(parsed[3]??'0');
  const significant=(parsed[1]+(parsed[2]??'')).replace(/^0+/,'');
  return Math.max(significant.length,1)<=1024&&scale>=-4096n&&scale<=4096n;
}
export function validPreviewValue(value:unknown,type:PromptPreviewType):boolean{
  const text=(item:unknown)=>typeof item==='string'&&validPreviewUnicode(item)&&new TextEncoder().encode(item).length<=64*1024;
  if(type==='BOOLEAN')return typeof value==='boolean';
  if(type==='INTEGER'||type==='DECIMAL')return typeof value==='string'&&validPreviewNumber(value,type);
  if(type==='STRING_LIST')return Array.isArray(value)&&value.length<=256&&value.every(text);
  return text(value);
}
export function encodePromptPreview(revision:PromptRevisionView,sourceId:string,inputs:PromptPreviewInputs):string{
  if(!promptCopyUuid.test(sourceId))throw new PromptCommandError('sourceId','请输入准确的样例来源编号（小写 UUID）。');
  if(revision.variables.length>64||new Set(revision.variables.map(variable=>variable.name)).size!==revision.variables.length
    ||revision.variables.some(variable=>!previewVariableName.test(variable.name)))throw new PromptCommandError('','当前变量定义超出预览范围，请重新读取。');
  const values=revision.variables.flatMap(variable=>{
    const input=inputs[variable.name];if(!input?.provided)return [];
    let value:unknown=input.text;
    if(variable.type==='BOOLEAN')value=input.booleanValue;
    if(variable.type==='STRING_LIST'){try{value=JSON.parse(input.text);}catch{throw new PromptCommandError('variable:'+variable.name,`${variable.name} 的样例值须为 JSON 文本数组。`);}}
    if(!validPreviewValue(value,variable.type))throw new PromptCommandError('variable:'+variable.name,`${variable.name} 的样例值类型或大小不正确。`);
    return [{name:variable.name,type:variable.type,value}];
  });
  const serialized=JSON.stringify({contentSha:revision.contentSha,sourceId,values});
  if(new TextEncoder().encode(serialized).length>1024*1024)throw new PromptCommandError('sourceId','整份预览输入超过 1 MiB，请缩减样例。');
  return serialized;
}
