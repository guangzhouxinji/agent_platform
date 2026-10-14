import {afterEach,expect,it,vi} from 'vitest';
import {createPrompt,recoverPromptCreate} from './promptCreateApi';
const workspaceId='11111111-1111-4111-8111-111111111111';
const receipt=()=>({receiptId:'22222222-2222-4222-8222-222222222222',workspaceId,promptId:'33333333-3333-4333-8333-333333333333',revisionId:'44444444-4444-4444-8444-444444444444',generation:1,contentSha:'a'.repeat(64),resultSha:'b'.repeat(64),submittedAt:new Date(Date.now()-1000).toISOString(),auditUntil:new Date(Date.now()+3600000).toISOString()});
const ok=(data:unknown,status=200)=>({ok:true,status,json:async()=>data} as Response);
afterEach(()=>vi.unstubAllGlobals());
it('sends exact prepared bytes and original key; GET recovery never sends body or query',async()=>{
  const fetch=vi.fn(async()=>ok(receipt()));vi.stubGlobal('fetch',fetch);const body='{"defaultValue":9007199254740993.00}';
  await createPrompt('session',workspaceId,'original',body);await recoverPromptCreate('session',workspaceId,'original');
  expect(fetch.mock.calls[0]).toEqual([`/v2/consultation/prompt-workspaces/${workspaceId}/prompts`,expect.objectContaining({method:'POST',body,redirect:'error',cache:'no-store',headers:expect.objectContaining({'Idempotency-Key':'original'})})]);
  expect(fetch.mock.calls[1]).toEqual([`/v2/consultation/prompt-workspaces/${workspaceId}/requests/CREATE`,expect.objectContaining({method:'GET',headers:expect.objectContaining({'Idempotency-Key':'original'})})]);
  expect((fetch.mock.calls as unknown[][])[1][1]).not.toHaveProperty('body');
});
it.each(['workspace','expired','unknown','generation','hash','timestamps','status'])('treats invalid success receipt as unknown: %s',async fault=>{
  const value:Record<string,unknown>={...receipt()};if(fault==='workspace')value.workspaceId=value.promptId;if(fault==='expired')value.auditUntil=new Date(Date.now()-1).toISOString();if(fault==='unknown')value.body='unexpected';if(fault==='generation')value.generation=2;if(fault==='hash')value.contentSha='bad';if(fault==='timestamps')value.submittedAt=value.auditUntil;
  vi.stubGlobal('fetch',vi.fn(async()=>ok(value,fault==='status'?201:200)));await expect(recoverPromptCreate('session',workspaceId,'original')).rejects.toMatchObject({status:503});
});
it('does not dispatch invalid coordinates or whitespace keys',async()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);await expect(recoverPromptCreate('session','../bad','original')).rejects.toMatchObject({status:400});await expect(recoverPromptCreate('session',workspaceId,' key')).rejects.toMatchObject({status:400});expect(fetch).not.toHaveBeenCalled();
});
