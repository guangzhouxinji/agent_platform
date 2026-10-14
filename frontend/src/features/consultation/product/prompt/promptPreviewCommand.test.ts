import {expect,it} from 'vitest';
import {emptyPromptPreviewInputs,encodePromptPreview,validPreviewNumber} from './promptPreviewCommand';
import {previewRevision,sourceId} from './promptPreviewTestFixtures';
it('preserves typed exact numeric strings, booleans, arrays, missing values and explicit empty text',()=>{
  const revision=previewRevision(),inputs=emptyPromptPreviewInputs(revision);
  inputs.amount={provided:true,text:'90071992547409931234567890',booleanValue:false};inputs.total={provided:true,text:'0.0001e+4096',booleanValue:false};
  inputs.flag={provided:true,text:'',booleanValue:false};inputs.list={provided:true,text:'["a","{{amount}}",""]',booleanValue:false};inputs.text={provided:true,text:'',booleanValue:false};
  expect(JSON.parse(encodePromptPreview(revision,sourceId,inputs))).toEqual({contentSha:revision.contentSha,sourceId,values:[
    {name:'amount',type:'INTEGER',value:'90071992547409931234567890'},{name:'total',type:'DECIMAL',value:'0.0001e+4096'},
    {name:'flag',type:'BOOLEAN',value:false},{name:'list',type:'STRING_LIST',value:['a','{{amount}}','']},{name:'text',type:'STRING',value:''},
  ]});inputs.amount.provided=false;expect(JSON.parse(encodePromptPreview(revision,sourceId,inputs)).values).not.toContainEqual(expect.objectContaining({name:'amount'}));
});
it.each(['1','0.0001','-0.00','1e4096','1e-4096','0.001e4099','90071992547409931234567890.000100'])('accepts bounded exact decimal %s',value=>expect(validPreviewNumber(value,'DECIMAL')).toBe(true));
it.each(['1e4097','1e-4097','0.001e4100','01','+1','Infinity','1.','1e9999999999999999999999999999999999999','1'.repeat(1025)])('rejects invalid or excessive decimal %s',value=>expect(validPreviewNumber(value,'DECIMAL')).toBe(false));
it.each(['01','1.0','1e1','+1','1'.repeat(1025)])('rejects invalid integer %s',value=>expect(validPreviewNumber(value,'INTEGER')).toBe(false));
it.each(['"text"','[1]','[true]','null','{}','["\ud800"]'])('rejects malformed list sample %s',text=>{
  const revision=previewRevision(),inputs=emptyPromptPreviewInputs(revision);inputs.list={provided:true,text,booleanValue:false};
  expect(()=>encodePromptPreview(revision,sourceId,inputs)).toThrow();
});
it('locates numeric variable errors separately from source identifiers',()=>{
  const revision=previewRevision(),inputs=emptyPromptPreviewInputs(revision);inputs.amount={provided:true,text:'1.0',booleanValue:false};
  try{encodePromptPreview(revision,sourceId,inputs);}catch(error){expect(error).toMatchObject({field:'variable:amount'});}
  expect(()=>encodePromptPreview(revision,'bad',inputs)).toThrow(/来源编号/);
});
it('enforces unicode, per-string, list and whole JSON budgets without truncation',()=>{
  const revision=previewRevision(),inputs=emptyPromptPreviewInputs(revision);inputs.text={provided:true,text:'\ud800',booleanValue:false};expect(()=>encodePromptPreview(revision,sourceId,inputs)).toThrow();
  inputs.text.text='x'.repeat(65537);expect(()=>encodePromptPreview(revision,sourceId,inputs)).toThrow();inputs.text.provided=false;
  inputs.list={provided:true,text:JSON.stringify(Array(257).fill('x')),booleanValue:false};expect(()=>encodePromptPreview(revision,sourceId,inputs)).toThrow();
  inputs.list.text=JSON.stringify(Array(17).fill('x'.repeat(65536)));expect(()=>encodePromptPreview(revision,sourceId,inputs)).toThrow(/1 MiB/);
});
