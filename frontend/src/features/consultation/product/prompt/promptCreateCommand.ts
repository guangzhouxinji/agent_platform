export type PromptVariableType='STRING'|'BOOLEAN'|'INTEGER'|'DECIMAL'|'STRING_LIST';
export interface PromptVariableInput {name:string;type:PromptVariableType;required:boolean;sourceRef:string;allowDefault:boolean;defaultText:string;defaultBoolean:boolean;defaultList:string[];allowEmpty:boolean}
export interface PromptCreateInput {kind:'GUIDE'|'SYSTEM';name:string;body:string;scenarioIds:string[];intentIds:string[];audienceRef:string;language:string;displayOrder:string;variables:PromptVariableInput[];sourceId:string}
export const emptyPromptInput=():PromptCreateInput=>({kind:'GUIDE',name:'',body:'',scenarioIds:[],intentIds:[],audienceRef:'',language:'',displayOrder:'0',variables:[],sourceId:''});
export const emptyPromptVariable=():PromptVariableInput=>({name:'',type:'STRING',required:false,sourceRef:'',allowDefault:false,defaultText:'',defaultBoolean:false,defaultList:[],allowEmpty:false});
export class PromptCommandError extends Error {constructor(public readonly field:string,message:string){super(message);}}
const integer=/^-?(?:0|[1-9][0-9]*)$/;
const decimal=/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function fail(field:string,message:string):never{throw new PromptCommandError(field,message);}
function validUnicode(value:string){for(let i=0;i<value.length;i++){const code=value.charCodeAt(i);if(code>=0xd800&&code<=0xdbff){const next=value.charCodeAt(++i);if(!(next>=0xdc00&&next<=0xdfff))return false;}else if(code>=0xdc00&&code<=0xdfff)return false;}return true;}
function text(value:string,field:string,label:string,max:number,required=true,byteLimit=false){
  if(!validUnicode(value)||required&&!value.trim()||(byteLimit?new TextEncoder().encode(value).length:value.length)>max)fail(field,`${label}不能为空或超出允许长度，请检查此字段。`);
  return value;
}
function strings(values:string[],field:string,label:string){if(values.length>64)fail(field,`${label}最多64项。`);return [...new Set(values.map((v,i)=>text(v,`${field}-${i}`,label,128)))].sort();}
function variable(value:PromptVariableInput,index:number){
  const field=`variables-${index}`,label=`变量 ${index+1}`;
  if(!/^[A-Za-z][A-Za-z0-9_]*$/.test(value.name))fail(`${field}-name`,`${label}名称须以英文字母开头，仅含字母、数字和下划线。`);
  text(value.sourceRef,`${field}-sourceRef`,`${label}来源引用`,500);
  let defaultJson='null';
  if(value.allowDefault){
    switch(value.type){
      case 'INTEGER':if(!integer.test(value.defaultText))fail(`${field}-defaultValue`,`${label}默认值须为完整整数，不允许前导零、小数或指数。`);defaultJson=value.defaultText;break;
      case 'DECIMAL':if(!decimal.test(value.defaultText)||!/[.eE]/.test(value.defaultText))fail(`${field}-defaultValue`,`${label}默认值须为含小数点或指数的十进制数。`);defaultJson=value.defaultText;break;
      case 'BOOLEAN':defaultJson=JSON.stringify(value.defaultBoolean);break;
      case 'STRING':text(value.defaultText,`${field}-defaultValue`,`${label}默认值`,64*1024,false,true);if(!value.allowEmpty&&value.defaultText==='')fail(`${field}-defaultValue`,`${label}未允许空默认值。`);defaultJson=JSON.stringify(value.defaultText);break;
      case 'STRING_LIST':
        if(value.defaultList.length>256)fail(`${field}-defaultValue`,`${label}默认列表最多256项。`);
        value.defaultList.forEach(v=>text(v,`${field}-defaultValue`,`${label}默认列表项`,64*1024,false,true));
        if(!value.allowEmpty&&(value.defaultList.length===0||value.defaultList.some(v=>v==='')))fail(`${field}-defaultValue`,`${label}未允许空列表或空列表项。`);
        defaultJson=JSON.stringify(value.defaultList);break;
      default:fail(`${field}-type`,`${label}类型不支持。`);
    }
  }
  return '{'+[`"name":${JSON.stringify(value.name)}`,`"type":${JSON.stringify(value.type)}`,`"required":${value.required}`,`"sourceRef":${JSON.stringify(value.sourceRef)}`,`"allowDefault":${value.allowDefault}`,`"defaultValue":${defaultJson}`,`"allowEmpty":${value.allowEmpty}`].join(',')+'}';
}
/** Only validated numeric default tokens bypass JSON.stringify; no caller can supply raw JSON. */
export function encodePromptContent(value:PromptCreateInput):string{
  if(!['GUIDE','SYSTEM'].includes(value.kind))fail('kind','请选择提示词类型。');
  text(value.name,'name','名称',128);text(value.body,'body','正文',256*1024,true,true);
  text(value.audienceRef,'audienceRef','受众引用',500);text(value.language,'language','语言',63);
  if(!uuid.test(value.sourceId))fail('sourceId','请输入准确的规范来源编号（小写 UUID）。');
  if(!integer.test(value.displayOrder)||BigInt(value.displayOrder)<-2147483648n||BigInt(value.displayOrder)>2147483647n)fail('displayOrder','排序须为32位有符号整数。');
  if(value.variables.length>64)fail('variables','变量最多64项。');
  if(new Set(value.variables.map(v=>v.name)).size!==value.variables.length)fail('variables','变量名称不能重复。');
  const scalar={kind:value.kind,name:value.name,body:value.body,scenarioIds:strings(value.scenarioIds,'scenarioIds','场景编号'),intentIds:strings(value.intentIds,'intentIds','意图编号'),audienceRef:value.audienceRef,language:value.language,displayOrder:Number(value.displayOrder),sourceId:value.sourceId};
  const result=JSON.stringify(scalar).slice(0,-1)+',"variables":['+value.variables.map(variable).join(',')+']}';
  return result;
}
export function boundedPromptCommand(result:string):string{
  if(new TextEncoder().encode(result).length>1024*1024)fail('body','整份提交超过1 MiB，请缩减内容。');
  return result;
}
export function encodePromptCreate(value:PromptCreateInput):string{
  return boundedPromptCommand('{"expectedGeneration":0,'+encodePromptContent(value).slice(1));
}

