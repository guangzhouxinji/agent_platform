import {boundedPromptCommand,encodePromptContent,PromptCommandError,type PromptCreateInput} from './promptCreateCommand';

export interface PromptSaveBase {expectedGeneration:string;expectedRevisionId:string}
export const emptyPromptSaveBase=():PromptSaveBase=>({expectedGeneration:'',expectedRevisionId:''});
export function encodePromptSave(value:PromptCreateInput,base:PromptSaveBase):string{
  if(!/^[1-9][0-9]{0,18}$/.test(base.expectedGeneration)||BigInt(base.expectedGeneration)>9223372036854775806n)
    throw new PromptCommandError('expectedGeneration','已知版本须为 1 至 9223372036854775806 的完整整数。');
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(base.expectedRevisionId))
    throw new PromptCommandError('expectedRevisionId','请输入准确的已知修订编号（小写 UUID）。');
  return boundedPromptCommand('{"expectedGeneration":'+base.expectedGeneration+',"expectedRevisionId":'+JSON.stringify(base.expectedRevisionId)+','+encodePromptContent(value).slice(1));
}
