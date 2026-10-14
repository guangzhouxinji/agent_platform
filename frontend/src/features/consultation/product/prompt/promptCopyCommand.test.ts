import {expect,it} from 'vitest';
import {encodePromptCopy,validPromptCopySource} from './promptCopyCommand';
const id='11111111-1111-4111-8111-111111111111';
const source={sourceWorkspaceId:id,sourcePromptId:id,sourceRevisionId:id,sourceContentSha:'a'.repeat(64)};
it('encodes only the five approved fields with numeric initial generation',()=>{
  expect(JSON.parse(encodePromptCopy(source,{targetWorkspaceId:id,sourceId:id}))).toEqual({expectedGeneration:0,targetWorkspaceId:id,sourceRevisionId:id,sourceContentSha:'a'.repeat(64),sourceId:id});
});
it.each(['sourceWorkspaceId','sourcePromptId','sourceRevisionId','sourceContentSha'])('rejects invalid source coordinate %s',field=>{
  expect(validPromptCopySource({...source,[field]:'invalid'})).toBe(false);
  expect(()=>encodePromptCopy({...source,[field]:'invalid'},{targetWorkspaceId:id,sourceId:id})).toThrow();
});
it.each(['targetWorkspaceId','sourceId'])('locates invalid input %s',field=>{
  try{encodePromptCopy(source,{targetWorkspaceId:id,sourceId:id,[field]:'INVALID'});throw new Error('accepted');}
  catch(failure){expect(failure).toMatchObject({field});}
});
