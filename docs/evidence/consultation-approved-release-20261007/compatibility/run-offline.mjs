// Independent offline runner. Its only writes are beside this file.
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
const dir=path.dirname(fileURLToPath(import.meta.url));
const java='D:/software/jdk-21/bin/java.exe';
const javac='D:/software/jdk-21/bin/javac.exe';
const h2='D:/software/m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar';
const steps=[];
function run(id,command,args){
 const startedAt=new Date().toISOString();const r=spawnSync(command,args,{cwd:dir,encoding:'utf8',timeout:120000});
 fs.writeFileSync(path.join(dir,id+'.log'),(r.stdout??'')+(r.stderr??''));
 steps.push({id,command,args,startedAt,finishedAt:new Date().toISOString(),exitCode:r.status,error:r.error?.message??null});
 fs.writeFileSync(path.join(dir,'offline-run.json'),JSON.stringify({scope:'OFFLINE_H2_AND_BYTECODE_NOT_DM8_DEPLOYMENT_OR_BUSINESS_ACCEPTANCE',steps},null,2));
 if(r.status!==0){console.error(id+' failed; retained log');process.exit(r.status??1);}console.log(id+' passed');
}
run('java-version',java,['-version']);
run('byte-binding','python',['verify_offline.py']);
run('verifier-counterexamples','python',['test_verify_offline.py']);
run('probe-compile',javac,['-encoding','UTF-8','-d',path.join(dir,'classes'),path.join(dir,'MigrationCompatibilityProbe.java')]);
run('probe-run',java,['-cp',path.join(dir,'classes')+';'+h2,'MigrationCompatibilityProbe',dir]);
run('old-auth-compile',javac,['-encoding','UTF-8','-cp',path.join(dir,'classes'),'-d',path.join(dir,'classes'),path.join(dir,'OldJarReadAuthorizationProbe.java')]);
run('old-auth-run',java,['-cp',path.join(dir,'classes')+';'+h2,'OldJarReadAuthorizationProbe',dir]);
// Persist evidence limitations, not another task/status ledger.
const report=JSON.parse(fs.readFileSync(path.join(dir,'migration-results.json'),'utf8'));
const shape=JSON.parse(fs.readFileSync(path.join(dir,'old-jar-query-results.json'),'utf8'));
const old=JSON.parse(fs.readFileSync(path.join(dir,'old-jar-read-compatibility.json'),'utf8'));
const queryRows=JSON.parse(fs.readFileSync(path.join(dir,'old-jar-selects.sql.json'),'utf8'));
const authorization=JSON.parse(fs.readFileSync(path.join(dir,'old-jar-authorization-results.json'),'utf8'));
const errors=JSON.parse(fs.readFileSync(path.join(dir,'backend20-impact.json'),'utf8'));
fs.writeFileSync(path.join(dir,'release-gaps.json'),JSON.stringify({
 task:'VER-CON-COMPARISON-RELEASE-COMPAT-01',scope:'Evidence limitations, not release approval or task state',
 offline:{assertions:report.assertions,failures:report.failures,oldSelectShapesPreserved:shape.preserved,oldSelectUnresolved:shape.unresolvedBefore,oldSelectRegressions:shape.regressions,originalErrorsClassified:errors.errors.length,oldJarAuthorizationAssertions:authorization.assertions,oldJarAuthorizationFailures:authorization.failures},
 unresolvedSelects:shape.queries.filter(x=>!x.shapePreserved).map(x=>({...x,class:queryRows[x.queryIndex].class,sqlSha256:queryRows[x.queryIndex].sqlSha256})),
 dynamicSqlNotProved:old.dynamicOrFragmentSql.map(x=>({class:x.class,sqlSha256:x.sqlSha256})),
 requiredIndependentReleaseEvidence:[
  'Exact current DM8 target identities/schema and original/replacement runtime binding, within approved window',
  'Existing non-destructive protection/consistent backup/restore validation; H2 and metadata cannot prove recoverability',
  'DM8 exact check/key/index semantics and existing migration provenance; no baseline replay, no unknown source activation',
  'Live V092 old constraint existence and allowed existing values; any interrupted expanded constraint requires exact reconciliation, not blind reapply',
  'Pinned new Jar with comparison disabled still requires INPUT V098/V099/V101 shapes: actual fixed guard fails without them; V100 is skipped by guard while disabled, not optional in approved five-migration scope',
  'Actual deployed calculation flag, startup/readiness, old result authorization-aware reads and absence of new model/Worker/unknown-call resend effects',
  'Old Jar dynamic SELECTs and full retained-result endpoint/data mapping not covered by empty SQL shape tests. 41 isolated old-code authorization/task reads now exercised; real JWT signature/login/authorization resources and real DM8 timing are NOT proved',
  'Old Jar writes with COMPARISON persisted historical values and real stop/switch/restart rollback not proved; no automatic write resumption',
  'Nonempty reverse guard refusal observed in H2 only; no live reverse execution or restoration authorization inferred',
  'Full backend remains 20 errors/128 skips and incomplete reactor; enforcement acceptance/recovery and shared live DM8 behavior cannot be claimed passed'
 ],
 currentProtection:'Main-session read-only receipts bound; historicalAuditUntil 2026-10-03 06:53:33+08:00 expired. No current backup created/validated here; native export or historical dump is NOT a current protection substitute.',
 reverseScope:'U092 is ancillary workspace SQL absent from fixed manifest reverse list; tested offline only, not approved live reverse. U098-U101 nonempty refusal and empty reversal are H2 only.'
},null,2));
