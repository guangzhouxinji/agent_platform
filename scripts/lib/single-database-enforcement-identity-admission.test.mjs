import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';import {createHash} from 'node:crypto';import {spawnSync} from 'node:child_process';
const root=process.cwd(),base=path.join(root,'docs/evidence/single-database-installation-20261009/approved-execution/enforcement-first-start44');
const dir=path.join(base,'receipt-test-runs',`${Date.now()}-${process.pid}`);fs.mkdirSync(dir,{recursive:true});
const library=path.resolve(process.env.SINGLE_DB_ENF_RECEIPT_TEST_LIBRARY??'scripts/lib/single-database-final-deployment.ps1');
const read=f=>JSON.parse(fs.readFileSync(path.join(base,f),'utf8').replace(/^\uFEFF/,''));
const execution=read('bootstrap44-actual-result.json'),reconciliation=read('actual-bootstrap44-reconciliation.json');
const target=execution.targetId,jar=execution.jar.sha256;let counter=0;
const q=s=>"'"+s.replaceAll("'","''")+"'";
function run(mutate=()=>{}){
 const c={domain:'enforcement',target,jar,exec:structuredClone(execution),post:structuredClone(reconciliation),init:{identityInitialization:{initializedDomains:['enforcement'],globalIdentityInitialized:false,evidence:{enforcement:{}}}}};mutate(c);
 const put=(kind,value)=>{const f=path.join(dir,`${counter++}-${kind}.json`),s=JSON.stringify(value);fs.writeFileSync(f,s);return{path:f,sha256:createHash('sha256').update(s).digest('hex')}};
 const refs=c.init.identityInitialization?.evidence?.enforcement;
 if(refs){refs.execution=put('execution',c.exec);refs.reconciliation=put('reconciliation',c.post);if(c.corruptExecutionHash)refs.execution.sha256='f'.repeat(64);if(c.missingReconciliation)delete refs.reconciliation;}
 const f=put('initialization',c.init);
 const code=`$ErrorActionPreference='Stop';. ${q(library)};function docker{throw 'REAL_DOCKER_FORBIDDEN'};try{Assert-FinalSelectedIdentityBootstrap (Read-FinalDeploymentJson ${q(f.path)}) ${q(c.domain)} ${q(c.target)} ${q(c.jar)};'ALLOWED'}catch{'REFUSED '+$_.Exception.Message}`;
 const r=spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(code,'utf16le').toString('base64')],{encoding:'utf8',timeout:30000});assert.ifError(r.error);assert.equal(r.status,0,r.stderr);return r.stdout.trim();
}
test('enforcement exact actual first-bootstrap receipt admitted without DB or Docker',()=>assert.equal(run(),'ALLOWED'));
for(const [name,mutate]of[
 ['missing identity',c=>delete c.init.identityInitialization],['global initialization claim',c=>c.init.identityInitialization.globalIdentityInitialized=true],['multiple initialized domains',c=>c.init.identityInitialization.initializedDomains.push('consultation')],['foreign selected domain',c=>c.domain='consultation'],['foreign adjudication',c=>c.domain='adjudication'],['foreign receipt domain',c=>c.post.domain='consultation'],['consultation execution format',c=>c.exec.format='ACTUAL_CONSULTATION_SHARED_BOOTSTRAP_ATTEMPT/v1'],['consultation reconciliation format',c=>c.post.format='ACTUAL_CONSULTATION_SHARED_BOOTSTRAP_RECONCILIATION/v1'],['bootstrap failure',c=>c.exec.exitCode=1],['bootstrap exit missing',c=>delete c.exec.exitCode],['bootstrap never invoked',c=>c.exec.bootstrapInvoked=false],['retry asserted',c=>c.exec.retryPerformed=true],['wrong execution target',c=>c.exec.targetId='a'.repeat(64)],['wrong reconciliation target',c=>c.post.targetId='a'.repeat(64)],['old executed jar',c=>c.exec.jar.sha256='a'.repeat(64)],['old reconciled jar',c=>c.post.jar.sha256='a'.repeat(64)],['wrong requested jar',c=>c.jar='a'.repeat(64)],['not actual JDBC',c=>c.post.actualJdbc=false],['missing domain completion',c=>delete c.post.enforcementIdentityInitialized],['global reconciliation claim',c=>c.post.globalIdentityInitialized=true],['business Grant or member',c=>c.post.memberOrBusinessGrantCreated=true],['missing full grant verification',c=>c.post.requiredObjectGrants=673],['postconditions failed',c=>c.post.postconditions.postconditionsPassed=false],['wrong postconditions domain format',c=>c.post.postconditions.format='ACTUAL_CONSULTATION_BOOTSTRAP_READ_ONLY_POSTCONDITIONS/v1'],['postconditions not actual JDBC',c=>c.post.postconditions.actualJdbc=false],['postcondition writes',c=>c.post.postconditions.executedWrites=1],['postcondition DCL',c=>c.post.postconditions.executedDcl=1],['primary member count',c=>c.post.postconditions.primaryIdentityRowCounts.ID2_SUBJECT=1],['primary count missing',c=>delete c.post.postconditions.primaryIdentityRowCounts.ID2_GRANT_EVENT],['reconciliation missing',c=>c.missingReconciliation=true],['execution SHA corrupt',c=>c.corruptExecutionHash=true]
])test('enforcement receipt fails closed: '+name,()=>assert.match(run(mutate),/^REFUSED /));

for(const [name,mutate] of [
 ['adjudication execution format',c=>c.exec.format='ACTUAL_ADJ_NORMAL_FIRST_BOOTSTRAP_ATTEMPT/v1'],
 ['adjudication reconciliation format',c=>c.post.format='ACTUAL_ADJUDICATION_SHARED_BOOTSTRAP_RECONCILIATION/v1'],
 ['unrelated data drift',c=>c.post.structureAccountsPrivilegesUnchanged=false],
 ['unexpected authority rows',c=>c.post.onlyExpectedThreeAuthorityRowsChanged=false],
 ['maintainer missing',c=>c.post.postconditions.maintainers=[]],
 ['maintainer multiplicity',c=>c.post.postconditions.maintainers.push(c.post.postconditions.maintainers[0])],
 ['foreign subject',c=>c.post.postconditions.maintainers[0].SUBJECT_REF='other'],
 ['foreign organization',c=>c.post.postconditions.maintainers[0].ORGANIZATION='other'],
 ['foreign certificate',c=>c.post.postconditions.maintainers[0].CERTIFICATE_SHA='a'.repeat(64)],
 ['missing roles',c=>c.post.postconditions.maintainers[0].ALLOWED_ROLES='SUBJECTS'],
 ['inactive maintainer',c=>c.post.postconditions.maintainers[0].STATUS='INACTIVE'],
 ['wrong version',c=>c.post.postconditions.maintainers[0].VERSION=2],
 ['wrong singleton',c=>c.post.postconditions.maintainers[0].SINGLETON_KEY=2],
 ['watermark missing',c=>c.post.postconditions.watermarks=[]],
 ['foreign issuer',c=>c.post.postconditions.watermarks[0].ISSUER='https://other.invalid'],
 ['nonzero generation',c=>c.post.postconditions.watermarks[0].GENERATION=1],
 ['nonready authority',c=>c.post.postconditions.watermarks[0].STATE='LOCKED'],
 ['active intent',c=>c.post.postconditions.watermarks[0].INTENT_ID='other'],
 ['nonempty history',c=>c.post.postconditions.watermarks[0].HISTORY_SHA='a'.repeat(64)],
 ['audit missing',c=>c.post.postconditions.bootstrapAudit=[]],
 ['audit multiplicity',c=>c.post.postconditions.bootstrapAudit.push(c.post.postconditions.bootstrapAudit[0])],
 ['audit foreign subject',c=>c.post.postconditions.bootstrapAudit[0].SUBJECT_REF='other'],
 ['audit foreign certificate',c=>c.post.postconditions.bootstrapAudit[0].CERTIFICATE_SHA='a'.repeat(64)],
 ['audit not bootstrap role',c=>c.post.postconditions.bootstrapAudit[0].ROLE_CODE='SUBJECTS'],
 ['audit not bootstrap decision',c=>c.post.postconditions.bootstrapAudit[0].DECISION='ALLOW']
])test('enforcement exact maintenance boundary denies '+name,()=>assert.match(run(mutate),/^REFUSED /));
