import {expect,it} from 'vitest';
import {emptyPromptInput,emptyPromptVariable,encodePromptCreate} from './promptCreateCommand';
import {encodePromptSave} from './promptSaveCommand';
const revision='11111111-1111-4111-8111-111111111111';
const draft=()=>({...emptyPromptInput(),name:'新修订',body:' 完整正文\n',sourceId:revision,audienceRef:'internal',language:'zh-CN'});
it('keeps long CAS and decimal defaults exact while preserving the complete content contract',()=>{
  const value=draft();value.variables=[{...emptyPromptVariable(),name:'amount',type:'DECIMAL',sourceRef:'approved',allowDefault:true,defaultText:'90071992547409931234567890.00100'}];
  const encoded=encodePromptSave(value,{expectedGeneration:'9223372036854775806',expectedRevisionId:revision});
  expect(encoded).toContain('"expectedGeneration":9223372036854775806,');expect(encoded).toContain('"defaultValue":90071992547409931234567890.00100,');
  expect(encoded).toContain('"expectedRevisionId":"'+revision+'"');expect(encoded).toContain('"body":" 完整正文\\n"');
  const create=encodePromptCreate(value);expect(create.startsWith('{"expectedGeneration":0,"kind":')).toBe(true);
  expect(encoded.slice(encoded.indexOf('"kind":'))).toBe(create.slice(create.indexOf('"kind":')));
});
it.each(['0','-1','01','+1','1.0','1e2',' 1','9223372036854775807','99999999999999999999','1,"kind":"SYSTEM"'])('rejects invalid or overflowing CAS %s',expectedGeneration=>{
  expect(()=>encodePromptSave(draft(),{expectedGeneration,expectedRevisionId:revision})).toThrow('已知版本');
});
it('requires exact revision UUID and applies the whole-command byte bound',()=>{
  expect(()=>encodePromptSave(draft(),{expectedGeneration:'1',expectedRevisionId:'old'})).toThrow('修订编号');
  const value=draft();value.variables=[{...emptyPromptVariable(),name:'items',type:'STRING_LIST',sourceRef:'approved',allowDefault:true,defaultList:Array(17).fill('x'.repeat(64*1024))}];
  expect(()=>encodePromptSave(value,{expectedGeneration:'1',expectedRevisionId:revision})).toThrow('1 MiB');
});
