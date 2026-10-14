import {describe,it,expect} from 'vitest';
import {bindStart,startPacket,type StartReceipt} from './comparisonStartContracts';
import type {InputReceipt} from './comparisonInputContracts';
const input:InputReceipt={taskId:'owned-task',comparisonId:'owned-input',inputSha:'a'.repeat(64),taskVersion:5,featureRevisionId:'features',featureRevisionNumber:1,packageSha:'b'.repeat(64),purpose:'TECHNICAL_SIMILARITY',methodVersion:'PATENT_FEATURE_MATRIX_V1',expiresAt:'2020-01-01T00:00:00Z',candidates:[{candidateId:'c'.repeat(64),status:'QUEUED'}]};
function fixture(){const packet=startPacket(input,input.candidates[0].candidateId);const receipt:StartReceipt={commandId:'command',taskId:input.taskId,comparisonId:input.comparisonId,candidateId:packet.candidateId,inputSha:input.inputSha,requestId:packet.key,attemptId:'attempt',status:'COMMITTED',errorCode:null,expiresAt:input.expiresAt};return {packet,receipt};}
describe('exact consumer receipt contract; unit coordinates, not real computation',()=>{
 it('retains server-authorized expired-deadline READ and cancellation coordinates',()=>{const {packet,receipt}=fixture();expect(bindStart(input,packet,receipt)).toEqual(receipt);expect(bindStart(input,packet,{...receipt,status:'CANCELLED',attemptId:null})).toMatchObject({status:'CANCELLED'});});
 it.each(['expectedTaskVersion','inputSha'] as const)('rejects original request %s drift',field=>{const {packet,receipt}=fixture();const body=field==='expectedTaskVersion'?{...packet.body,expectedTaskVersion:6}:{...packet.body,inputSha:'d'.repeat(64)};expect(()=>bindStart(input,{...packet,body},receipt)).toThrow();});
 it('rejects original route drift rather than trusting only returned IDs',()=>{const {packet,receipt}=fixture();expect(()=>bindStart(input,{...packet,path:packet.path.replace('owned-task','foreign-task')},receipt)).toThrow();});
 it('rejects candidate absent from exact immutable Input',()=>{const {packet,receipt}=fixture();expect(()=>bindStart({...input,candidates:[]},packet,receipt)).toThrow();});
 it.each(['commandId','attemptId','errorCode','expiresAt'] as const)('rejects non-contract primitive %s',field=>{const {packet,receipt}=fixture();expect(()=>bindStart(input,packet,{...receipt,[field]:1} as unknown as StartReceipt)).toThrow();});
});
