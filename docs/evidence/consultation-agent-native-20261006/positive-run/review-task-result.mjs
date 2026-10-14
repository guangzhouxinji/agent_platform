import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const dir=path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/,'$1'));
const read=name=>JSON.parse(fs.readFileSync(path.join(dir,name),'utf8'));
const sha=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const before=read('audit-before-recovery.json'),after=read('audit-after-recovery.json');
const w=read('after-run-workspace.json'),a=read('native-answer-read.json'),run=read('native-run-read.json').checkpoint,r=read('same-task-run-result.json');
const loss=read('loss-and-recovery-result.json'),disclosure=read('audit-disclosure.json');
for(const key of ['task','runs','revisions','attempts','modelInvocationFences','modelTurns','modelPayloadSummaries'])assert.deepEqual(before[key],after[key],`Unexpected replay side effect ${key}`);
assert.equal(after.runs.length,1);assert.equal(after.revisions.length,10);assert.equal(after.attempts.length,1);assert.equal(after.modelInvocationFences.length,1);assert.equal(after.modelTurns.length,1);
assert.equal(w.task.id,r.taskId);assert.equal(w.goal.inputId,r.inputId);assert.equal(w.goal.goalId,r.goalId);assert.equal(run.task.id,r.taskId);assert.equal(run.runId,r.runId);assert.equal(a.taskId,r.taskId);assert.equal(a.resultSetId,w.result.resultSetId);assert.equal(a.resultSha,w.result.resultSha);assert.equal(a.turnId,r.turnId);assert.equal(run.outcome.reference,'answer-turn:'+a.turnId);assert.equal(w.dialogue[0].turnId,a.turnId);
assert.equal(w.result.members.length,20);assert.equal(w.result.citations.length,20);assert.equal(w.result.candidateCount,40);
const checked=[];
for(const m of w.result.members){
 const c=w.result.citations.find(c=>c.memberId===m.memberId);assert.ok(c);assert.equal(c.documentId,m.documentId);assert.equal(c.rawSha,m.rawSha);assert.equal(c.documentVersion,'RAW_SHA256:'+c.rawSha);
 const [file,fragment]=c.locatorRef.split('#');const absolute=path.resolve(file);assert.ok(absolute.startsWith(path.resolve('data/patents')+path.sep));const bytes=fs.readFileSync(absolute);assert.equal(sha(bytes),c.rawSha);const doc=JSON.parse(bytes.toString('utf8'));const match=fragment.match(/^json-pointer=\/title&utf8=(\d+):(\d+)$/);assert.ok(match);const located=bytes.subarray(+match[1],+match[2]).toString('utf8');assert.equal(JSON.parse(located),doc.title);assert.equal(c.sourceVersion,m.sourceVersion);assert.ok(a.output.citations.includes(m.memberId));checked.push({memberId:m.memberId,documentId:m.documentId,title:doc.title,rawSha:c.rawSha,titleBytePointerVerified:true,matchedTerms:m.matchedTerms});
}
assert.equal(new Set(a.output.citations).size,20);assert.ok(a.output.answer.includes('3986239'));assert.ok(a.output.answer.includes('不涉及新颖性'));
const strong=checked.filter(m=>['soldering','blades','rotor'].every(t=>m.matchedTerms.includes(t)));assert.equal(strong.length,1);assert.equal(strong[0].documentId,'US_GRANT:3986239');assert.match(strong[0].title,/soldering.*blades.*rotor/);
assert.equal(disclosure.rawTitleClaimsFulltextTransmitted,false);assert.equal(disclosure.approvedMemberFieldsVerified.length,20);
const original=JSON.parse(fs.readFileSync(path.join(dir,'../approved-run/audit-after-recovery.json'),'utf8'));const originalNow=read('audit-original-preservation-retry.json');for(const key of ['task','runs','revisions','attempts','modelInvocationFences','modelTurns','modelPayloadSummaries'])assert.deepEqual(original[key],originalNow[key],`Original task changed ${key}`);
assert.equal(loss.checkpointVersion,9);assert.equal(loss.fixedExpiry,'2026-10-06T18:00:00.0000000+08:00');
const report={observedAt:new Date().toISOString(),taskId:r.taskId,runId:r.runId,resultSetId:r.resultSetId,resultSha:r.resultSha,turnId:r.turnId,normalBoundedAgentApiTaskVerified:true,semanticReview:'One title with all three approved query terms; 19 remaining members are weak/semantic candidates, not 20 strong matches.',candidateCorpusSize:40,returnedMembers:20,strongLexicalMatches:1,checkedCitations:checked,modelRequestApprovedMetadataShapeVerified:true,originalRobotTaskLedgerUnchanged:true,sourceCurrentProjectionRetiredSeparately:true,replayAudit:{runs:1,revisions:10,searchAttempts:1,modelInvocationFences:1,modelTurns:1,unchangedAfterDeniedRequestsTerminalReplayAndServiceRestart:true},permissionLoss:loss,browserE2e:false,professionalAcceptance:false,productionAcceptance:false,midActionCrashRecovery:false,autonomousBackgroundExecution:false,dynamicReplanning:false,limitations:['Bounded original 40-record capture, not exhaustive patent search','Title relevance does not establish claims similarity, novelty, infringement, status or validity','API/source review is not browser task E2E or human professional acceptance','Terminal restart only, not crash during external model call']};
const dest=path.join(dir,'task-result-review.json');assert.ok(!fs.existsSync(dest),'Preserve report');fs.writeFileSync(dest,JSON.stringify(report,null,2)+'\n');
const ledger=path.join(dir,'persistent-replay-audit.json');assert.ok(!fs.existsSync(ledger),'Preserve report');fs.writeFileSync(ledger,JSON.stringify({observedAt:report.observedAt,taskId:r.taskId,runId:r.runId,...report.replayAudit,originalRobotTaskLedgerUnchanged:true,browserE2e:false,midActionCrashRecovery:false},null,2)+'\n');console.log('BOUNDED_POSITIVE_API_TASK_SOURCE_AND_PERSISTENT_REPLAY_REVIEW_PASSED');

