import {expect,it} from 'vitest';
import {emptyPromptInput,emptyPromptVariable,encodePromptCreate} from './promptCreateCommand';
const draft=()=>({...emptyPromptInput(),name:'草稿',body:' 保留正文\n',sourceId:'11111111-1111-4111-8111-111111111111',audienceRef:'internal',language:'zh-CN'});
it('keeps all command fields, string bytes, sorted sets and ordered variables',()=>{
  const value={...draft(),scenarioIds:['z','a','a'],intentIds:['b','a']};const parsed=JSON.parse(encodePromptCreate(value));
  expect(parsed.body).toBe(' 保留正文\n');expect(parsed.scenarioIds).toEqual(['a','z']);expect(parsed.intentIds).toEqual(['a','b']);expect(parsed.expectedGeneration).toBe(0);
  expect(Object.keys(parsed).sort()).toEqual(['expectedGeneration','kind','name','body','scenarioIds','intentIds','audienceRef','language','displayOrder','sourceId','variables'].sort());
});
it.each([['INTEGER','90071992547409931234567890'],['DECIMAL','90071992547409931234567890.00100'],['DECIMAL','1.00'],['DECIMAL','1e-900']])('preserves %s numeric tokens without floating conversion', (type,defaultText)=>{
  const value=draft();value.variables=[{...emptyPromptVariable(),name:'amount',sourceRef:'approved',type:type as 'INTEGER'|'DECIMAL',allowDefault:true,defaultText}];
  expect(encodePromptCreate(value)).toContain('"defaultValue":'+defaultText+',');
});
it.each(['01','+1','1.0','1e2','1,"other":true','NaN','Infinity',' 1'])('rejects invalid integer numeric token %s',defaultText=>{
  const value=draft();value.variables=[{...emptyPromptVariable(),name:'amount',sourceRef:'approved',type:'INTEGER',allowDefault:true,defaultText}];expect(()=>encodePromptCreate(value)).toThrow();
});
it.each(['1','.1','1.','01.1','NaN','1e','1.0,"x":2'])('rejects invalid decimal numeric token %s',defaultText=>{
  const value=draft();value.variables=[{...emptyPromptVariable(),name:'amount',sourceRef:'approved',type:'DECIMAL',allowDefault:true,defaultText}];expect(()=>encodePromptCreate(value)).toThrow();
});
it('keeps list order and permitted empty items, rejects empty when not allowed',()=>{
  const value=draft();value.variables=[{...emptyPromptVariable(),name:'items',sourceRef:'approved',type:'STRING_LIST',allowDefault:true,allowEmpty:true,defaultList:[' b ','','a']}];
  expect(JSON.parse(encodePromptCreate(value)).variables[0].defaultValue).toEqual([' b ','','a']);
  value.variables[0].allowEmpty=false;expect(()=>encodePromptCreate(value)).toThrow('空列表');
});
it('rejects unicode damage, body byte overflow, duplicate variables and int32 overflow',()=>{
  expect(()=>encodePromptCreate({...draft(),body:'\ud800'})).toThrow();expect(()=>encodePromptCreate({...draft(),body:'中'.repeat(90000)})).toThrow();
  expect(()=>encodePromptCreate({...draft(),displayOrder:'2147483648'})).toThrow();
  const variable={...emptyPromptVariable(),name:'same',sourceRef:'approved'};expect(()=>encodePromptCreate({...draft(),variables:[variable,variable]})).toThrow('重复');
});
