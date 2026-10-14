import {PromptCommandError} from './promptCreateCommand';

export interface PromptCopySource {sourceWorkspaceId:string;sourcePromptId:string;sourceRevisionId:string;sourceContentSha:string}
export interface PromptCopyInput {targetWorkspaceId:string;sourceId:string}
export const promptCopyUuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export function validPromptCopySource(source:PromptCopySource):boolean{
  return promptCopyUuid.test(source.sourceWorkspaceId)&&promptCopyUuid.test(source.sourcePromptId)
    &&promptCopyUuid.test(source.sourceRevisionId)&&/^[0-9a-f]{64}$/.test(source.sourceContentSha);
}
export function encodePromptCopy(source:PromptCopySource,input:PromptCopyInput):string{
  if(!validPromptCopySource(source))throw new PromptCommandError('','复制来源链接不正确，请重新打开确切版本。');
  if(!promptCopyUuid.test(input.targetWorkspaceId))throw new PromptCommandError('targetWorkspaceId','请输入准确的目标工作区编号（小写 UUID）。');
  if(!promptCopyUuid.test(input.sourceId))throw new PromptCommandError('sourceId','请输入准确的已获许可来源编号（小写 UUID）。');
  return JSON.stringify({expectedGeneration:0,targetWorkspaceId:input.targetWorkspaceId,sourceRevisionId:source.sourceRevisionId,
    sourceContentSha:source.sourceContentSha,sourceId:input.sourceId});
}
