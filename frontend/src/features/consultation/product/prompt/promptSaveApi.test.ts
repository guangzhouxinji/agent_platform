import {afterEach,expect,it,vi} from 'vitest';
import {savePrompt,recoverPromptSave} from './promptSaveApi';
const workspaceId='11111111-1111-4111-8111-111111111111',promptId='33333333-3333-4333-8333-333333333333';
const receipt=()=>({receiptId:'22222222-2222-4222-8222-222222222222',workspaceId,promptId,revisionId:'44444444-4444-4444-8444-444444444444',generation:'2',contentSha:'a'.repeat(64),resultSha:'b'.repeat(64),submittedAt:new Date(Date.now()-1000).toISOString(),auditUntil:new Date(Date.now()+3600000).toISOString()});
const ok=(data:unknown,status=200)=>({ok:true,status,json:async()=>data} as Response);
afterEach(()=>vi.unstubAllGlobals());
it('uses nested exact workspace POST and workspace-only GET with original header and no query/body',async()=>{
  const fetch=vi.fn(async()=>ok(receipt()));vi.stubGlobal('fetch',fetch);const raw='{"expectedGeneration":9007199254740993}';
  await savePrompt('session',workspaceId,promptId,'original',raw,'2');await recoverPromptSave('session',workspaceId,promptId,'original','2');
  expect(fetch.mock.calls[0]).toEqual([`/v2/consultation/prompt-workspaces/${workspaceId}/prompts/${promptId}/revisions`,expect.objectContaining({method:'POST',body:raw,cache:'no-store',redirect:'error',headers:expect.objectContaining({'Idempotency-Key':'original'})})]);
  expect(fetch.mock.calls[1]).toEqual([`/v2/consultation/prompt-workspaces/${workspaceId}/requests/SAVE`,expect.objectContaining({method:'GET',headers:expect.objectContaining({'Idempotency-Key':'original'})})]);
  expect((fetch.mock.calls as unknown[][])[1][1]).not.toHaveProperty('body');
});
it('accepts exact long generation strings and refuses rounded JSON numbers',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok({...receipt(),generation:'9223372036854775807'})));
  expect((await savePrompt('session',workspaceId,promptId,'original','{}','9223372036854775807')).generation).toBe('9223372036854775807');
  vi.stubGlobal('fetch',vi.fn(async()=>ok({...receipt(),generation:9223372036854775807})));
  await expect(recoverPromptSave('session',workspaceId,promptId,'original')).rejects.toMatchObject({status:503});
});
it.each(['1','02','2.0','9223372036854775808','-2','not-a-number'])('rejects invalid receipt generation %s',generation=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok({...receipt(),generation})));
  return expect(recoverPromptSave('session',workspaceId,promptId,'original')).rejects.toMatchObject({status:503});
});
it.each(['workspace','prompt','expired','extra','target','status'])('treats an unverified success as unknown: %s',async fault=>{
  const value:Record<string,unknown>={...receipt()};if(fault==='workspace')value.workspaceId=promptId;if(fault==='prompt')value.promptId=workspaceId;if(fault==='expired')value.auditUntil=new Date(Date.now()-1).toISOString();if(fault==='extra')value.body='unwanted';
  vi.stubGlobal('fetch',vi.fn(async()=>ok(value,fault==='status'?201:200)));
  await expect(savePrompt('session',workspaceId,promptId,'original','{}',fault==='target'?'3':'2')).rejects.toMatchObject({status:503});
});
it('fresh original-key lookup accepts an older legitimate receipt without guessing latest generation',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok({...receipt(),generation:'7'})));
  expect((await recoverPromptSave('session',workspaceId,promptId,'older')).generation).toBe('7');
});
it.each(['PROMPT_SAVE_BASE_CONFLICT','PROMPT_SAVE_KEY_CONFLICT'])('retains the authenticated conflict code %s',async code=>{
  vi.stubGlobal('fetch',vi.fn(async()=>({ok:false,status:409,json:async()=>({code})} as Response)));
  await expect(savePrompt('session',workspaceId,promptId,'key','{}','2')).rejects.toMatchObject({status:409,code});
});
it('malformed conflict body cannot be classified as definitely unwritten',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>({ok:false,status:409,json:async()=>({code:'PROMPT_SAVE_BASE_CONFLICT',body:'extra'})} as Response)));
  await expect(savePrompt('session',workspaceId,promptId,'key','{}','2')).rejects.toMatchObject({status:409,code:undefined});
});
