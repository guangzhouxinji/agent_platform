import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const dir=path.dirname(fileURLToPath(import.meta.url));
const read=n=>JSON.parse(fs.readFileSync(path.join(dir,n),'utf8'));
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const before=read('audit-before-recovery-retry.json'),after=read('audit-after-recovery-retry.json');
const keys=['task','runs','revisions','attempts','modelInvocationFences','modelTurns','modelPayloadSummaries'];
for(const key of keys)assert.deepEqual(before[key],after[key],`Replay changed ${key}`);
for(const [key,n] of Object.entries({runs:1,revisions:10,attempts:1,modelInvocationFences:1,modelTurns:1}))assert.equal(after[key].length,n,key);
const w=read('after-run-workspace.json'),a=read('native-answer-read.json'),run=read('native-run-read.json').checkpoint,r=read('same-task-run-result.json'),loss=read('loss-and-recovery-result.json'),disclosure=read('audit-disclosure.json');
assert.equal(w.task.id,r.taskId);assert.equal(w.goal.inputId,r.inputId);assert.equal(w.goal.goalId,r.goalId);
assert.equal(run.task.id,r.taskId);assert.equal(run.runId,r.runId);assert.equal(run.task.goal.description,'confirmed-input:'+r.inputId);
assert.equal(a.taskId,r.taskId);assert.equal(a.resultSetId,w.result.resultSetId);assert.equal(a.resultSha,w.result.resultSha);assert.equal(a.turnId,r.turnId);assert.equal(run.outcome.reference,'answer-turn:'+a.turnId);
assert.equal(w.result.members.length,20);assert.equal(w.result.citations.length,20);assert.equal(w.result.candidateCount,40);
const checked=[];
for(const m of w.result.members){
 const c=w.result.citations.find(c=>c.memberId===m.memberId);assert.ok(c);assert.equal(c.documentId,m.documentId);assert.equal(c.rawSha,m.rawSha);assert.equal(c.documentVersion,'RAW_SHA256:'+c.rawSha);assert.equal(c.sourceVersion,m.sourceVersion);
 const [file,fragment]=c.locatorRef.split('#');const absolute=path.resolve(file);assert.ok(absolute.startsWith(path.resolve('data/patents')+path.sep));const bytes=fs.readFileSync(absolute);assert.equal(sha(bytes),c.rawSha);const doc=JSON.parse(bytes.toString('utf8'));
 const match=fragment.match(/^json-pointer=\/title&utf8=(\d+):(\d+)$/);assert.ok(match);assert.equal(JSON.parse(bytes.subarray(+match[1],+match[2]).toString('utf8')),doc.title);assert.ok(a.output.citations.includes(m.memberId));
 checked.push({memberId:m.memberId,documentId:m.documentId,title:doc.title,rawSha:c.rawSha,titleBytePointerVerified:true,matchedTerms:m.matchedTerms});
}
assert.equal(new Set(a.output.citations).size,20);assert.ok(a.output.answer.includes('3986239'));assert.ok(a.output.answer.includes('本页'));
const strong=checked.filter(m=>['soldering','blades','rotor'].every(t=>m.matchedTerms.includes(t)));assert.equal(strong.length,1);assert.equal(strong[0].documentId,'US_GRANT:3986239');assert.match(strong[0].title,/soldering.*blades.*rotor/);
assert.equal(disclosure.rawTitleClaimsFulltextTransmitted,false);assert.equal(disclosure.approvedMemberFieldsVerified.length,20);
const older=path.resolve(dir,'../consultation-agent-native-20261006');
for(const [name,oldfile] of [['robot','approved-run/audit-after-recovery.json'],['rotor','positive-run/audit-after-recovery.json']]){
 const old=JSON.parse(fs.readFileSync(path.join(older,oldfile),'utf8')),now=read('original-'+name+'-after-recovery.json');
 for(const key of keys)assert.deepEqual(old[key],now[key],`Old ${name} ledger changed ${key}`);
}
assert.equal(loss.checkpointVersion,9);assert.equal(loss.persistedTerminalRecovery,true);assert.equal(loss.midActionCrashRecoveryTested,false);
const expiry={goal:w.goal.expiresAt,result:w.result.expiresAt??null,answer:a.expiresAt,task:after.task[0].EXPIRES_AT,worker:after.worker[0].EXPIRES_AT};
const report={observedAt:new Date().toISOString(),taskId:r.taskId,runId:r.runId,resultSetId:r.resultSetId,resultSha:r.resultSha,turnId:r.turnId,normalBoundedAgentApiTaskVerified:true,candidateCorpusSize:40,returnedMembers:20,strongLexicalMatches:1,checkedCitations:checked,modelRequestApprovedMetadataShapeVerified:true,originalRobotAndRotorLedgersUnchanged:true,replayAudit:{runs:1,revisions:10,searchAttempts:1,modelInvocationFences:1,modelTurns:1,unchangedAfterDeniedRequestsTerminalReplayAndServiceRestart:true},permissionLoss:loss,expiry,browserE2e:false,professionalAcceptance:false,productionAcceptance:false,midActionCrashRecovery:false,autonomousBackgroundExecution:false,dynamicReplanning:false,semanticReview:'Exactly one title matches all three query terms; remaining 19 are weak/semantic candidates, not strong matches. Model statements about ranking causation are auxiliary, not independently professionally accepted.',limitations:['Only original 40-record sample; not exhaustive corpus','TITLE match does not establish claim similarity, novelty, infringement, legal status or validity','Goal expires earlier than Task; latest human approval does not expand resource retention','No browser E2E or professional acceptance','Terminal restart only, not crash during model call']};
for(const [name,obj] of [['task-result-review.json',report],['persistent-replay-audit.json',{observedAt:report.observedAt,taskId:r.taskId,runId:r.runId,...report.replayAudit,originalRobotAndRotorLedgersUnchanged:true,browserE2e:false,midActionCrashRecovery:false}]]){const dest=path.join(dir,name);assert.ok(!fs.existsSync(dest),'Preserve report');fs.writeFileSync(dest,JSON.stringify(obj,null,2)+'\n');}
console.log('NEW_BOUNDED_TASK_SOURCE_MODEL_AND_PERSISTENT_REPLAY_REVIEW_PASSED');
