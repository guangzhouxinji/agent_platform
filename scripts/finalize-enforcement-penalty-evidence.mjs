import { readFile, readdir, writeFile, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
const root=process.cwd(),base=resolve(root,'docs/evidence/enforcement-followup-final-20260930');
const finalRun='ENF-PEN-FINAL-20261001-166',out=resolve(base,finalRun);
const load=async p=>JSON.parse((await readFile(p,'utf8')).replace(/^﻿/,''));
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const selections=[
 ['ENF-PEN-FINAL-I-20261001-165',/Penalty(FormulaTest|LifecycleTest|PublishChecksTest|AmountSourceTest|StoreDm8IT)-/],
 ['ENF-PEN-BROWSER-20261001-153',/PenaltyCompleteBrowserReconciliationIT-/],
 ['ENF15-TRANSITION-20261001-143',/PenaltyTransitionNegativesDm8IT-/],
 ['ENF-PEN-CHANGE-20261001-160',/PenaltyChangeBrowserDm8IT-/],
 ['ENF-PEN-RECOVERY-20261001-163',/Penalty(RecoveryTargetDm8IT|RestoredGenerationDm8IT)-/],
 ['ENF-PEN-REVOKE-20261001-164',/PenaltyCurrentRevocationDm8IT-/]
];
for(const name of ['execution.json','technical-review.json'])await assert.rejects(stat(resolve(out,name)),{code:'ENOENT'},'Immutable final evidence already exists');
const reports=[],excluded=[];
for(const [run,pattern] of selections){
 const dir=resolve(base,run),gate=await load(resolve(dir,'execution.json'));
 assert.ok(gate.status==='PASSED'||gate.exitCode===0);assert.equal(gate.failures,0);assert.equal(gate.errors,0);assert.equal(gate.skipped,0);
 for(const name of (await readdir(dir)).filter(n=>n.startsWith('TEST-')&&n.endsWith('.xml'))){
  const path=resolve(dir,name),bytes=await readFile(path),text=bytes.toString('utf8'),start=text.indexOf('<testsuite '),header=text.slice(start,text.indexOf('>',start)+1);assert.ok(start>=0&&header);
  const get=k=>header.match(new RegExp(k+'="([^"]*)"'))?.[1];
  const report={run,path,sha256:sha(bytes),modifiedAt:(await stat(path)).mtime.toISOString(),suite:get('name'),tests:Number(get('tests')),failures:Number(get('failures')),errors:Number(get('errors')),skipped:Number(get('skipped'))};
  if(!pattern.test(name)){excluded.push({...report,classification:'PREPARATION_OR_READ_ONLY_AUDIT_NOT_DOMAIN_I'});continue;}
  assert.ok(report.tests>0);for(const field of ['failures','errors','skipped'])assert.equal(report[field],0,path);
  assert.equal(text.split('<testcase ').length-1,report.tests,path+' actual testcase count');
  report.classification=/Penalty(?:FormulaTest|LifecycleTest|PublishChecksTest|AmountSourceTest)-/.test(name)?'C':'REAL_DOMAIN_I';reports.push(report);
 }
}
assert.equal(reports.length,11);const count=type=>reports.filter(r=>r.classification===type).reduce((sum,r)=>sum+r.tests,0);assert.equal(count('C'),29);assert.equal(count('REAL_DOMAIN_I'),8);
const complete=await load(resolve(base,'ENF-PEN-BROWSER-20261001-153/browser/result.json'));
for(const name of ['AMOUNT_SEMANTICS_REQUIRED','STATUTORY_RANGE_REQUIRED','TYPED_FORMULA_REQUIRED','BOUNDARY_TRIAL_REQUIRED','STATUTORY_BOUNDARIES_NOT_VERIFIED','BOUNDARY_TRIAL_MISMATCH','PROCEDURE_NOT_APPLICABLE','OVERLAP_PRIORITY_REQUIRED','STATUTORY_BASIS_MISMATCH','SCRIPT_EXPRESSION_REJECTED','DEPENDENCY_SHA_REJECTED','UNCONFIRMED_FACTS_WAITING_INPUT','OUTSIDE_RANGE_REVIEW_REJECTED'])assert.ok(complete.checks.some(c=>c.name===name),name);
assert.equal(complete.mutations.length,26);const completeHttp=await load(resolve(base,'ENF-PEN-BROWSER-20261001-153/browser/http-records.json'));assert.ok(completeHttp.filter(r=>r.method==='GET').every(r=>r.status===200));
const changes=await load(resolve(base,'ENF-PEN-CHANGE-20261001-160/browser/result.json'));assert.equal(changes.mutations.length,4);assert.equal(changes.ruleRevision,7);assert.equal(changes.changedRule.state,'PENDING_REVIEW');assert.deepEqual(changes.changedRule.options.map(o=>o.calculation.amount),[24,24]);assert.equal(changes.changedSource.state,'WAITING_INPUT');assert.deepEqual(changes.changedSource.options,[]);assert.ok(changes.changedSource.gaps.includes('LEGAL_REFERENCE_REVIEW_REQUIRED'));
const recovery=await load(resolve(base,'ENF-PEN-RECOVERY-20261001-163/execution.json'));assert.equal(recovery.sourceDomainOriginals,134);assert.equal(recovery.physicalDbShaExact,true);assert.equal(recovery.currentAuthorityOutsideBackup,true);
const recovered=await load(resolve(base,'ENF-PEN-RECOVERY-20261001-163/target-after-browser.json'));assert.equal(recovered.restoredRecords,135);assert.equal(recovered.rejectedOperations,4);assert.equal(recovered.rejectedReceiptEventWrites,0);assert.equal(recovered.suggestion.state,'WAITING_INPUT');assert.deepEqual(recovered.suggestion.options,[]);
const revoke=await load(resolve(base,'ENF-PEN-REVOKE-20261001-164/execution.json'));assert.equal(revoke.sourceTrial,403);assert.equal(revoke.oldPhysicalRestoreTrial,503);assert.equal(revoke.domainMutations,0);
const inventory=await load(resolve(out,'source-final-inventory.json'));assert.equal(inventory.records.length,141);assert.equal(inventory.receipts.length,141);assert.deepEqual(inventory.receipts,inventory.events);for(const record of inventory.records)assert.equal(sha(await readFile(record.originalPath)),record.sha256);
const latest=await load(resolve(base,'ENF-PEN-FINAL-I-20261001-165-build-build-inputs.json')),runtime=await load(resolve(base,'ENF-PEN-RUNTIME-20261001-152-build-build-inputs.json')),frozen=await load(resolve(base,'ENF13-FINAL-20261001-100-build-inputs.json'));
const normalize=p=>p.replaceAll(String.fromCharCode(92),'/');
function sameProduction(prior,needle){const before=new Map(prior.files.map(f=>[normalize(f.path),f.sha256])),selected=latest.files.filter(f=>normalize(f.path).includes('/src/main/')&&normalize(f.path).includes(needle));assert.ok(selected.length>0);for(const file of selected)assert.equal(file.sha256,before.get(normalize(file.path)),file.path);return selected;}
const productCode=sameProduction(runtime,'/enforcement/penalty/'),frozenCode=sameProduction(frozen,'/enforcement/documentrule/');assert.equal(productCode.length,12);assert.equal(frozenCode.length,8);
const now=new Date().toISOString(),coverage=[
 {id:'PEN01',evidence:['165 Formula C','120 actual missing/zero/repeated HTTPS browser trial','153 real generation/parallel options','163 restored trial'],result:'repeatable trace; no official decision'},
 {id:'PEN02',evidence:['165 PublishChecks C','153 nine actual publication negatives + script rejection','122 actual unresolved-basis product rejection'],result:'no published successor, success receipt or orphan original on failure'},
 {id:'PEN03',evidence:['165 Formula/Lifecycle C','120 actual missing vs confirmed-zero product trial'],result:'missing is not default zero'},
 {id:'PEN04',evidence:['165 Lifecycle C upstream applicability/confirmation','153 actual unconfirmed facts WAITING_INPUT','160/163 source-review gap'],result:'gaps retain history and produce no amount/options'},
 {id:'PEN05',evidence:['153 overlap publication rejection','153 real 24/24 parallel options, adjust, review and rerun','160 rule-change recalculation'],result:'no hidden stronger selection; revision clears old review'},
 {id:'PEN06',evidence:['143 actual replay/CAS/illegal transition product negatives','153 out-of-range review rejection','165 real DM8 CAS/replay/rollback/original faults','163 joint cold DB+originals restore','164 current revocation/old physical backup and old-command replay'],result:'no forbidden domain/file/event/receipt side effects'}
];
const review={reviewer:'Codex',reviewerNature:'NAMED_TECHNICAL_REVIEW_NOT_REAL_BUSINESS_APPROVAL',reviewedAt:now,task:'IMP-ENF-PENALTY-04',decision:'PASSED',contract:'docs/evidence/enforcement-followup-v2-d-20260929.md / frozen PEN01-PEN06',scope:'FINAL_DEVELOPMENT_ACCEPTANCE_ONLY',components:29,realDomainI:8,failures:0,errors:0,skipped:0,coverage,productCodeMatchesRealBrowserRuntime:productCode,frozenEnf13Unchanged:frozenCode,reports,excludedReports:excluded,finalReadOnlyAudit:{path:resolve(out,'source-final-inventory.json'),records:141,domainICount:0},history:'All original failures/XML/logs/volumes retained; selected current passing evidence does not rewrite historical failures',businessAcceptance:'BUSINESS_ACCEPTANCE_PENDING',successorStarted:false};
await writeFile(resolve(out,'technical-review.json'),JSON.stringify(review,null,2));
await writeFile(resolve(out,'execution.json'),JSON.stringify({runId:finalRun,task:'IMP-ENF-PENALTY-04',status:'PASSED',scope:'FROZEN_ENF15_ENF16_FINAL_DEVELOPMENT_ACCEPTANCE',finalizedAt:now,componentTests:29,realDomainTests:8,tests:37,failures:0,errors:0,skipped:0,reportCount:11,reports,sourceFinalRecords:141,restoredCheckpointRecords:134,restoredProductRecords:135,preparationAndReadOnlyAuditExcluded:true,technicalReview:'PASSED',businessAcceptance:'BUSINESS_ACCEPTANCE_PENDING',successorStarted:false,globalVerification:'SEPARATE_VERIFY_ALL_LOG_NOT_FULL_JAVA_REGRESSION'},null,2));
console.log('Frozen ENF15/16 evidence reconciled: C29 + real-domain I8 =37/0/0/0; named Codex technical review PASSED; business acceptance pending; no successor started');
