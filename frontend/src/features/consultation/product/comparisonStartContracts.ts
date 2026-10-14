import type {InputReceipt} from './comparisonInputContracts';
export type StartReceipt={commandId:string;taskId:string;comparisonId:string;candidateId:string;inputSha:string;requestId:string;attemptId:string|null;status:string;errorCode:string|null;expiresAt:string};
export type RecomputeSelection={configurationSha:string;candidateIds:string[];expiresAt:string};
export type StartPacket={path:string;submitPath?:string;key:string;body:{expectedTaskVersion:number;inputSha:string;configurationSha?:string};candidateId:string};
const text=(value:unknown):value is string=>typeof value==='string'&&!!value.trim();
export function startPath(input:InputReceipt,candidate:string) {
 return '/v2/consultation/tasks/'+encodeURIComponent(input.taskId)+'/patent-comparisons/'+encodeURIComponent(input.comparisonId)+'/candidates/'+encodeURIComponent(candidate)+'/start-commands';
}
export function startPacket(input:InputReceipt,candidate:string,recompute?:RecomputeSelection):StartPacket {
 if(!input.candidates.some(c=>c.candidateId===candidate&&['QUEUED','FAILED'].includes(c.status)))throw new Error('该候选不能再次发起。');
 if(recompute&&(!/^[a-f0-9]{64}$/.test(recompute.configurationSha)||!recompute.candidateIds.includes(candidate)||!Number.isFinite(Date.parse(recompute.expiresAt))||Date.parse(recompute.expiresAt)<=Date.now()))throw new Error('受影响候选依据已变化或到期，请重新读取。');
 const path=startPath(input,candidate);
 return {path,...(recompute?{submitPath:path+'/recompute'}:{}),key:crypto.randomUUID(),body:{expectedTaskVersion:input.taskVersion,inputSha:input.inputSha,...(recompute?{configurationSha:recompute.configurationSha}:{})},candidateId:candidate};
}
export function bindStart(input:InputReceipt,packet:StartPacket,value:StartReceipt):StartReceipt {
 if(!packet||packet.path!==startPath(input,packet.candidateId)||packet.body.expectedTaskVersion!==input.taskVersion||packet.body.inputSha!==input.inputSha
  ||(packet.submitPath!==undefined&&(packet.submitPath!==packet.path+'/recompute'||!packet.body.configurationSha||!/^[a-f0-9]{64}$/.test(packet.body.configurationSha)))
  ||(packet.submitPath===undefined&&packet.body.configurationSha!==undefined)
  ||!text(packet.key)||!input.candidates.some(c=>c.candidateId===packet.candidateId)
  ||!value||value.taskId!==input.taskId||value.comparisonId!==input.comparisonId||value.candidateId!==packet.candidateId||value.inputSha!==input.inputSha||value.requestId!==packet.key
  ||!text(value.commandId)||!text(value.requestId)||!(value.attemptId===null||text(value.attemptId))||!(value.errorCode===null||text(value.errorCode))
  ||!['ACCEPTED','DISPATCHING','RECONCILE_REQUIRED','COMMITTED','FAILED','CANCELLED','ADMISSION_REQUIRED'].includes(value.status)
  ||typeof value.expiresAt!=='string'||!Number.isFinite(Date.parse(value.expiresAt))
  ||value.status==='ADMISSION_REQUIRED'&&(value.attemptId!==null||value.errorCode!=='COMPARISON_MODEL_ADMISSION_REQUIRED')
  ||value.status==='COMMITTED'&&!value.attemptId)throw new Error('启动回执绑定不一致；保留原请求，仅查询对账。');
 // An execution deadline is not a client-side substitute for the server's current READ contract.
 return value;
}
