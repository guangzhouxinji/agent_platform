import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const BASE='docs/evidence/single-database-installation-20261009/approved-execution';
export const CONTINUATION55=Object.freeze({extensionSha256:'de9b2d73e1406ca59388a115c371e6813651e3866be074ddcd04e2e3375f71b6',consentSha256:'fa80272a688ef0a5890d2a80720480033b57520e03bb7f568da77bd0c74236e1',originalSha256:'649f93fd58081b7ca00f0887b547f1608f0a62f55f546227dc597d799000fff7',requestSha256:'8c6c5b46f977732b4dcb1825b54620ae4edac1b700004a3c2f967a70738d3a17',proposalSha256:'128f681d2a76d35e0c29dd6bc9688b01e4d48a053b48040a30358c72470b8c20',humanSha256:'b6f4b57ade409674cb0a60c9d9a67eb714f0c6bc2bfc9878f15547e39b444533',sourceId:'f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f',targetId:'78963630b94072dd64c1a7701200c4c48bf837350a9f85b5c9126cfd934ff481',from:'2026-10-10T18:28:00.9158632+08:00',until:'2026-10-13T18:00:00+08:00'});
const allowed=new Set(['fresh-fenced-source-protection','existing-initialized-empty-independent-target-recovery']);
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
function require(ok,code){if(!ok)throw new Error(code);}
export function validateContinuation55(input,action,now){
 require(input&&Object.keys(input).sort().join(',')==='consent,extension,human,original,proposal,request','EXACT_CONTINUATION_INPUT_REQUIRED');
 require(allowed.has(action),'CONTINUATION_ACTION_NOT_ADMITTED');
 require(now instanceof Date&&Number.isFinite(now.getTime()),'CURRENT_SERVER_TIME_REQUIRED');
 for(const [key,pin] of [['extension','extensionSha256'],['consent','consentSha256'],['original','originalSha256'],['request','requestSha256'],['proposal','proposalSha256'],['human','humanSha256']])require(Buffer.isBuffer(input[key])&&input[key].length<=65536&&hash(input[key])===CONTINUATION55[pin],'CONTINUATION_ORIGINAL_BYTES_REQUIRED');
 const x=JSON.parse(input.extension),c=JSON.parse(input.consent),o=JSON.parse(input.original);
 require(x.approvalGranted===true&&x.taskId==='IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01'&&x.approvalId==='APPROVAL-SINGLE-FINAL-WINDOW-20261010-55'&&c.sourceThreadId==='01a12159-ccfb-7402-b371-57986aeb1e88'&&c.humanApproval.messageId==='01a12557-6b73-7eb3-8cbf-4472c2079e79'&&input.human.toString('utf8')==='批准\n','VERIFIED_HUMAN_CONTINUATION_REQUIRED');
 require(x.originalApprovalSha256===CONTINUATION55.originalSha256&&x.sourceConsentCaptureSha256===CONTINUATION55.consentSha256&&o.approvalGranted===true&&o.requestOriginalSha256===CONTINUATION55.requestSha256,'ORIGINAL_SCOPE_REQUIRED');
 require(x.operationWindowBeijing.from===CONTINUATION55.from&&x.operationWindowBeijing.until===CONTINUATION55.until&&now.getTime()>=Date.parse(CONTINUATION55.from)&&now.getTime()<Date.parse(CONTINUATION55.until),'CONTINUATION_WINDOW_CLOSED');
 require(o.approvedLimits.totalMemoryBytes===7516192768&&o.approvedLimits.totalNanoCpuLimit===4000000000&&o.approvedLimits.concurrentDm8Instances===1&&x.approvedLimits.totalMemoryBytes===7516192768&&x.approvedLimits.totalNanoCpuLimit===4000000000&&x.approvedLimits.concurrentDm8Instances===1,'ORIGINAL_RESOURCE_LIMITS_REQUIRED');
 return Object.freeze({format:'VERIFIED_CONTINUATION_WINDOW55/v1',action,extensionSha256:CONTINUATION55.extensionSha256,from:CONTINUATION55.from,until:CONTINUATION55.until,sourceId:CONTINUATION55.sourceId,targetId:CONTINUATION55.targetId,runtimeAdmitted:false});
}
export function readContinuation55(root,action,now=new Date()){
 const p=path.resolve(root,BASE),files={extension:'continuous-window55/resource-window-extension55.json',consent:'continuous-window55/source-consent55.json',original:'resource-approval01.json',request:'resource-request-original01.json',proposal:'continuous-window55/proposal-retrieved55.txt',human:'continuous-window55/human-approval-retrieved55.txt'};const bytes={};
 for(const [k,f]of Object.entries(files)){const absolute=path.join(p,f),s=fs.lstatSync(absolute);require(s.isFile()&&!s.isSymbolicLink()&&s.size<=65536&&fs.realpathSync(absolute).toLowerCase()===absolute.toLowerCase(),'CONTINUATION_REGULAR_OWNED_EVIDENCE_REQUIRED');bytes[k]=fs.readFileSync(absolute);}
 return validateContinuation55(bytes,action,now);
}
export function assertContinuationResources55(admission,current,now=new Date()){
 require(admission?.format==='VERIFIED_CONTINUATION_WINDOW55/v1'&&admission.extensionSha256===CONTINUATION55.extensionSha256&&allowed.has(admission.action),'VERIFIED_WINDOW_REQUIRED');
 require(now instanceof Date&&Number.isFinite(now.getTime())&&now.getTime()>=Date.parse(CONTINUATION55.from)&&now.getTime()<Date.parse(CONTINUATION55.until),'CONTINUATION_WINDOW_CLOSED');
 require(current&&current.sourceId===CONTINUATION55.sourceId&&current.targetId===CONTINUATION55.targetId&&current.targetInitialized===true&&current.targetImportStarted===false,'EXACT_INITIALIZED_EMPTY_TARGET_REQUIRED');
 require(Number.isSafeInteger(current.memoryBytes)&&current.memoryBytes>0&&current.memoryBytes<=7516192768&&Number.isSafeInteger(current.nanoCpus)&&current.nanoCpus>0&&current.nanoCpus<=4000000000&&Number.isSafeInteger(current.onlineDm8)&&current.onlineDm8>=0&&current.onlineDm8<=1&&Number.isSafeInteger(current.sourcePid)&&current.sourcePid>=0&&Number.isSafeInteger(current.targetPid)&&current.targetPid>=0&&current.onlineDm8===(Number(current.sourcePid>0)+Number(current.targetPid>0)),'SINGLE_DB_CURRENT_BUDGET_REQUIRED');
 require(current.writerRegistryComplete===true&&current.maintenanceExclusive===true&&current.safeReturnReady===true,'CURRENT_WRITERS_LOCK_AND_RETURN_REQUIRED');
 if(admission.action==='existing-initialized-empty-independent-target-recovery')require(current.sourcePid===0&&Number.isSafeInteger(current.targetPid)&&current.targetPid>=0,'SOURCE_STOPPED_REQUIRED');
 else require(Number.isSafeInteger(current.sourcePid)&&current.sourcePid>0&&current.targetPid===0,'SOURCE_ONLY_RUNNING_REQUIRED');
 return true;
}
