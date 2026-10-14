import {afterEach,expect,it,vi} from 'vitest';
import {readPromptHistory,readPromptRevision} from './promptApi';
import {history,newerId,ok,otherPrompt,promptId,revision,revisionId} from './promptTestFixtures';
afterEach(()=>vi.unstubAllGlobals());
it('reads exact native resources with no-store GET and the bearer',async()=>{
  const fetch=vi.fn(async()=>ok(revision()));vi.stubGlobal('fetch',fetch);
  expect((await readPromptRevision('session',promptId,revisionId)).body).toBe('原版本正文');
  expect(fetch).toHaveBeenCalledWith(`/v2/consultation/prompts/${promptId}/revisions/${revisionId}`,expect.objectContaining({method:'GET',cache:'no-store',headers:{Authorization:'Bearer session',Accept:'application/json'}}));
});
it('keeps decimal defaults as exact strings',async()=>{
  const value=revision();value.variables=[{name:'amount',type:'DECIMAL',required:false,sourceRef:'owned',allowDefault:true,defaultValue:'12345678901234567890.01234567890123456789',allowEmpty:false}];
  vi.stubGlobal('fetch',vi.fn(async()=>ok(value)));
  expect((await readPromptRevision('session',promptId,revisionId)).variables[0].defaultValue).toBe(value.variables[0].defaultValue);
});
it.each(['prompt','revision','expired','unknown','workspace','sha','missing-workspace','missing-sha'])('rejects wrong or unavailable revision response: %s',async fault=>{
  const value:Record<string,unknown>={...revision()};
  if(fault==='prompt')value.promptId=otherPrompt;if(fault==='revision')value.revisionId=newerId;
  if(fault==='expired')value.bodyUntil=new Date(Date.now()-1000).toISOString();if(fault==='unknown')value.authority=true;
  if(fault==='workspace')value.workspaceId='invalid';if(fault==='sha')value.contentSha='A'.repeat(64);
  if(fault==='missing-workspace')delete value.workspaceId;if(fault==='missing-sha')delete value.contentSha;
  vi.stubGlobal('fetch',vi.fn(async()=>ok(value)));
  await expect(readPromptRevision('session',promptId,revisionId)).rejects.toMatchObject({status:503});
});
it.each(['duplicate','wrong-page','expired','too-many'])('rejects inconsistent history response: %s',async fault=>{
  const value=history();if(fault==='duplicate')value.revisions.push(value.revisions[0]);if(fault==='wrong-page')value.page=2;
  if(fault==='expired')value.revisions[0].bodyUntil=new Date(Date.now()-1).toISOString();
  if(fault==='too-many')value.revisions=Array.from({length:21},()=>value.revisions[0]);
  vi.stubGlobal('fetch',vi.fn(async()=>ok(value)));await expect(readPromptHistory('session',promptId)).rejects.toMatchObject({status:503});
});
it('invalid IDs, page size and absent session never dispatch',async()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
  await expect(readPromptRevision('session','../other',revisionId)).rejects.toMatchObject({status:400});
  await expect(readPromptHistory('session',promptId,0)).rejects.toMatchObject({status:400});
  await expect(readPromptRevision('',promptId,revisionId)).rejects.toMatchObject({status:401});expect(fetch).not.toHaveBeenCalled();
});
