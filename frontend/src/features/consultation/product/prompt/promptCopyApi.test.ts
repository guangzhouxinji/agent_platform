import {afterEach,expect,it,vi} from 'vitest';
import {copyPrompt,recoverPromptCopy} from './promptCopyApi';
import {encodePromptCopy} from './promptCopyCommand';
const sourceWorkspaceId='11111111-1111-4111-8111-111111111111',targetWorkspaceId='22222222-2222-4222-8222-222222222222';
const source={sourceWorkspaceId,sourcePromptId:sourceWorkspaceId,sourceRevisionId:sourceWorkspaceId,sourceContentSha:'a'.repeat(64)};
const serialized=encodePromptCopy(source,{targetWorkspaceId,sourceId:sourceWorkspaceId});
const receipt=()=>({receiptId:sourceWorkspaceId,workspaceId:targetWorkspaceId,promptId:targetWorkspaceId,revisionId:sourceWorkspaceId,generation:'1',contentSha:source.sourceContentSha,resultSha:'b'.repeat(64),submittedAt:new Date(Date.now()-1000).toISOString(),auditUntil:new Date(Date.now()+3600000).toISOString()});
const ok=(value:unknown)=>new Response(JSON.stringify(value),{status:200});
afterEach(()=>vi.unstubAllGlobals());
it('sends approved POST bytes and exact source path with no-store bearer and key',async()=>{
  const fetch=vi.fn().mockResolvedValue(ok(receipt()));vi.stubGlobal('fetch',fetch);
  await copyPrompt('token',source,targetWorkspaceId,'copy-key',serialized);
  expect(fetch).toHaveBeenCalledWith(`/v2/consultation/prompt-workspaces/${sourceWorkspaceId}/prompts/${source.sourcePromptId}/copies`,expect.objectContaining({method:'POST',body:serialized,cache:'no-store',redirect:'error',headers:{Authorization:'Bearer token',Accept:'application/json','Idempotency-Key':'copy-key','Content-Type':'application/json'}}));
});
it('original-key recovery is bounded to source and target without source version inference',async()=>{
  const fetch=vi.fn().mockResolvedValue(ok({...receipt(),contentSha:'c'.repeat(64)}));vi.stubGlobal('fetch',fetch);
  expect((await recoverPromptCopy('token',sourceWorkspaceId,targetWorkspaceId,'original-key')).contentSha).toBe('c'.repeat(64));
  expect(fetch.mock.calls[0][0]).toBe(`/v2/consultation/prompt-workspaces/${targetWorkspaceId}/requests/COPY?sourceWorkspaceId=${sourceWorkspaceId}`);
  expect(fetch.mock.calls[0][1]).toMatchObject({method:'GET',cache:'no-store',headers:{'Idempotency-Key':'original-key'}});expect(fetch.mock.calls[0][1]).not.toHaveProperty('body');
});
it.each(['workspaceId','generation','contentSha','expired','extra','timestamp'])('rejects malformed or mismatched normal COPY receipt %s',async fault=>{
  const value:Record<string,unknown>={...receipt()};
  if(fault==='workspaceId')value.workspaceId=sourceWorkspaceId;if(fault==='generation')value.generation='2';if(fault==='contentSha')value.contentSha='c'.repeat(64);
  if(fault==='expired')value.auditUntil=new Date(Date.now()-1).toISOString();if(fault==='extra')value.operation='COPY';if(fault==='timestamp')value.submittedAt=value.auditUntil;
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(ok(value)));await expect(copyPrompt('token',source,targetWorkspaceId,'key',serialized)).rejects.toMatchObject({status:503});
});
it('UNKNOWN lookup enforces the frozen source digest while standalone lookup does not',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(ok({...receipt(),contentSha:'c'.repeat(64)})));
  await expect(recoverPromptCopy('token',sourceWorkspaceId,targetWorkspaceId,'key',source.sourceContentSha)).rejects.toMatchObject({status:503});
});
it('returns typed key conflict without interpreting it as current source success',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'PROMPT_COPY_KEY_CONFLICT'}),{status:409})));
  await expect(copyPrompt('token',source,targetWorkspaceId,'key',serialized)).rejects.toMatchObject({status:409,code:'PROMPT_COPY_KEY_CONFLICT'});
});
it.each(['session','source','target','key','body','extra-body'])('invalid local %s prevents dispatch',async fault=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
  const body=fault==='body'?'{}':fault==='extra-body'?serialized.slice(0,-1)+',"body":"forbidden"}':serialized;
  await expect(copyPrompt(fault==='session'?'':'token',fault==='source'?{...source,sourcePromptId:'bad'}:source,fault==='target'?'bad':targetWorkspaceId,fault==='key'?' key ':'key',body)).rejects.toMatchObject({status:fault==='session'?401:400});
  expect(fetch).not.toHaveBeenCalled();
});
