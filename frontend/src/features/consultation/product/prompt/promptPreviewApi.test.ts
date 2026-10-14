import {afterEach,expect,it,vi} from 'vitest';
import {previewPrompt} from './promptPreviewApi';
import {previewDiagnostic,previewResponse,previewResult,previewRevision,sourceId} from './promptPreviewTestFixtures';
const serialized=JSON.stringify({contentSha:'a'.repeat(64),sourceId,values:[]});
afterEach(()=>vi.unstubAllGlobals());
it('POSTs exact revision once with no-store and no idempotency or implicit READ',async()=>{
  const revision=previewRevision(),fetch=vi.fn().mockResolvedValue(previewResponse(previewResult()));vi.stubGlobal('fetch',fetch);
  const result=await previewPrompt('session',revision,serialized);expect(result.segments[1]).toMatchObject({value:'90071992547409931234567890'});
  expect(fetch).toHaveBeenCalledWith(`/v2/consultation/prompt-workspaces/${revision.workspaceId}/prompts/${revision.promptId}/revisions/${revision.revisionId}/preview`,expect.objectContaining({method:'POST',cache:'no-store',redirect:'error',body:serialized,headers:{Authorization:'Bearer session',Accept:'application/json','Content-Type':'application/json'}}));expect(fetch).toHaveBeenCalledTimes(1);
});
it.each(['workspace','prompt','revision','number','sha','mode','extra','expired','late-expiry','numeric','undefined','source-path','too-many'])('strict successful result rejects %s',async fault=>{
  const value:Record<string,unknown>={...previewResult()},segments=previewResult().segments;
  if(fault==='workspace')value.workspaceId=sourceId;if(fault==='prompt')value.promptId=sourceId;if(fault==='revision')value.revisionId=sourceId;if(fault==='number')value.revisionNumber=1;if(fault==='sha')value.contentSha='d'.repeat(64);
  if(fault==='mode')value.mode='RUNTIME';if(fault==='extra')value.body='leak';if(fault==='expired')value.expiresAt=new Date(Date.now()-1).toISOString();if(fault==='late-expiry')value.expiresAt=new Date(Date.now()+7200000).toISOString();
  if(fault==='numeric')segments[1]={...segments[1],value:9007199254740992} as unknown as typeof segments[number];
  if(fault==='undefined')segments[1]={...segments[1],name:'other'} as typeof segments[number];if(fault==='source-path')segments[1]={...segments[1],sourceEvidenceId:'C:/secrets'} as typeof segments[number];
  if(['numeric','undefined','source-path'].includes(fault))value.segments=segments;if(fault==='too-many')value.segments=Array(1025).fill({kind:'LITERAL',text:'a'});
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(previewResponse(value)));await expect(previewPrompt('session',previewRevision(),serialized)).rejects.toMatchObject({status:503});
});
it('400 diagnostics retain only issue types/names and valid current expiry',async()=>{
  const diagnostic=previewDiagnostic();vi.stubGlobal('fetch',vi.fn().mockResolvedValue(previewResponse(diagnostic,400)));
  await expect(previewPrompt('session',previewRevision(),serialized)).rejects.toMatchObject({status:400,diagnostic});
});
it.each(['expired','extra','bad-issue','value','late-expiry'])('malformed 400 diagnostic %s cannot expose data',async fault=>{
  const value:Record<string,unknown>={...previewDiagnostic()};if(fault==='expired')value.expiresAt=new Date(Date.now()-1).toISOString();if(fault==='extra')value.segments=[];
  if(fault==='bad-issue')value.issues=[{code:'VARIABLE_POLICY_UNAVAILABLE',variable:'amount'}];if(fault==='value')value.issues=[{code:'MISSING_VARIABLE',variable:'amount',value:'secret'}];if(fault==='late-expiry')value.expiresAt=new Date(Date.now()+7200000).toISOString();
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(previewResponse(value,400)));await expect(previewPrompt('session',previewRevision(),serialized)).rejects.toMatchObject({status:503});
});
it.each([400,413,415,503])('generic %s error never carries provider details',async status=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(previewResponse({code:'PROMPT_PREVIEW_REJECTED'},status)));await expect(previewPrompt('session',previewRevision(),serialized)).rejects.toMatchObject({status});
});
it.each([401,403])('auth failure %s closes without consuming possible leaked body',async status=>{
  const response=previewResponse({secret:'never consume'},status),read=vi.spyOn(response.body!,'getReader');vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response));
  await expect(previewPrompt('session',previewRevision(),serialized)).rejects.toMatchObject({status});expect(read).not.toHaveBeenCalled();
});
it('rejects an oversized response before displaying or parsing partial result',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(previewResponse({...previewResult(),segments:[{kind:'LITERAL',text:'x'.repeat(1024*1024)}]})));
  await expect(previewPrompt('session',previewRevision(),serialized)).rejects.toMatchObject({status:503});
});
it('network failure has no automatic retry and no session prevents dispatch',async()=>{
  const fetch=vi.fn().mockRejectedValue(new Error('offline'));vi.stubGlobal('fetch',fetch);await expect(previewPrompt('session',previewRevision(),serialized)).rejects.toMatchObject({status:0});
  await expect(previewPrompt('',previewRevision(),serialized)).rejects.toMatchObject({status:401});expect(fetch).toHaveBeenCalledTimes(1);
});
it.each(['body','sha','duplicate','wrong-type','numeric-number','source','oversize'])('invalid local request %s cannot dispatch',async fault=>{
  const input:Record<string,unknown>={contentSha:'a'.repeat(64),sourceId,values:[]};
  if(fault==='body')input.body='not allowed';if(fault==='sha')input.contentSha='d'.repeat(64);if(fault==='duplicate')input.values=Array(2).fill({name:'amount',type:'INTEGER',value:'1'});
  if(fault==='wrong-type')input.values=[{name:'amount',type:'STRING',value:'1'}];if(fault==='numeric-number')input.values=[{name:'amount',type:'INTEGER',value:1}];if(fault==='source')input.sourceId='bad';if(fault==='oversize')input.padding='x'.repeat(1024*1024);
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);await expect(previewPrompt('session',previewRevision(),JSON.stringify(input))).rejects.toMatchObject({status:400});expect(fetch).not.toHaveBeenCalled();
});
