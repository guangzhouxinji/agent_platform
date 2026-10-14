import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import assert from 'node:assert/strict';
import {bindConsultationResult} from '../../../frontend/src/features/consultation/product/agentResultHandoff.ts';
const root='docs/evidence/consultation-agent-native-20261006/',checks=[];
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
for(const directory of ['positive-run','approved-run']){
 const rf=root+directory+'/native-run-read.json',wf=root+directory+'/after-run-workspace.json';const run=JSON.parse(fs.readFileSync(rf)),workspace=JSON.parse(fs.readFileSync(wf));
 const answer=bindConsultationResult(run,workspace);assert.ok(answer);assert.equal(answer.turnId,workspace.dialogue[0].turnId);
 if(workspace.result.members.length){const changed=structuredClone(workspace);changed.dialogue[0].citations[0].locatorRef+='-other';assert.equal(bindConsultationResult(run,changed),null);}
 checks.push({directory,taskId:run.checkpoint.task.id,runId:run.checkpoint.runId,turnId:answer.turnId,returnedMembers:workspace.result.members.length,existingResultStillBinds:true,runFileSha:hash(rf),workspaceFileSha:hash(wf)});
}
const report={observedAt:new Date().toISOString(),scope:'OFFLINE_RETAINED_EVIDENCE_COMPATIBILITY_NOT_CURRENT_AUTHORIZATION_OR_PRODUCT_ACCEPTANCE',checks,newProductRequests:0,newModelCalls:0,browserE2e:false};
const dest='docs/evidence/consultation-agent-context-20261006/retained-result-compatibility.json';assert.ok(!fs.existsSync(dest));fs.writeFileSync(dest,JSON.stringify(report,null,2)+'\n');console.log('RETAINED_POSITIVE_AND_ZERO_RESULT_COMPATIBILITY_PASSED');
