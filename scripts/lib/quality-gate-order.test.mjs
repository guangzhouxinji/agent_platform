import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { runQualityStages, verificationExitCode } from './verification-stages.mjs';
import { qualityGateCommands, sourceFingerprint, javaReports, freshJavaReports } from '../run-quality-gates.mjs';
const spec = qualityGateCommands('linux','node');
function execute(statuses = {}, options = {}) {
  const visited = [], observed = [];
  let sourceCalls = 0;
  const result = runQualityStages({ ...spec,
    captureSource: () => options.source ? options.source(++sourceCalls) : 'stable-sha',
    checkEvidence: () => options.evidence === undefined ? true : options.evidence,
    report: record => observed.push(record),
    run: stage => {
      visited.push(stage.label);
      const response = statuses[stage.label];
      if (response instanceof Error) throw response;
      return response === undefined ? {status:0} : typeof response === 'number' ? {status:response} : response;
    },
  });
  return {...result, visited, observed};
}
const labels = [spec.preflight.label, ...spec.stages.map(s=>s.label), spec.postflight.label];
test('green executes all original stages, postflight, evidence and source binding',()=>{
  const r=execute(); assert.equal(r.exitCode,0);assert.deepEqual(r.visited,labels);
  assert.deepEqual(r.results.slice(-2).map(x=>x.stage),['fresh-test-evidence','source-after']);
});
test('old test-health red allows new frontend/build/browser/Java but stays red',()=>{
  const r=execute({[spec.preflight.label]:2});assert.deepEqual(r.visited,labels);assert.equal(r.exitCode,1);
  assert.deepEqual(r.failures.map(x=>x.stage),[spec.preflight.label]);
});
test('postflight still reporting stale/current failed evidence is nonzero',()=>{
  const r=execute({[spec.preflight.label]:2,[spec.postflight.label]:2});assert.equal(r.exitCode,1);assert.equal(r.failures.length,2);
});
for(const code of [1,3,27]) test(`hard preflight exit ${code} stops before any independent command`,()=>{
  const r=execute({[spec.preflight.label]:code});assert.deepEqual(r.visited,[spec.preflight.label]);assert.equal(r.exitCode,1);assert.equal(r.fatal,spec.preflight.label);
});
for(const child of [{status:null},{status:undefined},{status:0,signal:'SIGTERM'},{status:2,error:{code:'ENOENT'}},new Error('spawn')]) test(`preflight process failure ${JSON.stringify(child)} is hard`,()=>{
  const r=execute({[spec.preflight.label]:child});assert.equal(r.exitCode,1);assert.equal(r.visited.length,1);
});
test('independent test/build failures aggregate without hiding later stages',()=>{
  const r=execute({[spec.stages[0].label]:4,[spec.stages[1].label]:5});assert.deepEqual(r.visited,labels);assert.equal(r.exitCode,1);assert.deepEqual(r.failures.map(x=>x.status),[4,5]);
});
test('independent signals/missing executable and exceptions never become green',()=>{
  for(const child of [{status:null},{status:0,signal:'SIGTERM'},{status:0,error:{code:'ENOENT'}},new Error('execution')]) {
    const r=execute({[spec.stages[2].label]:child});assert.deepEqual(r.visited,labels);assert.equal(r.exitCode,1);
  }
});
test('postflight hard failure and missing process status remain failures',()=>{
  for(const child of [1,{status:null},new Error('postflight')]) assert.equal(execute({[spec.postflight.label]:child}).exitCode,1);
});
test('missing fresh test evidence fails despite all child exits zero',()=>assert.equal(execute({}, {evidence:false}).exitCode,1));
test('source byte or file-set drift fails despite all child exits zero',()=>assert.equal(execute({}, {source:n=>n===1?'before':'after'}).exitCode,1));
test('source read failure before execution is hard and no tests run',()=>{
  const r=execute({}, {source:()=>{throw new Error()}});assert.equal(r.exitCode,1);assert.equal(r.visited.length,0);
});
test('source read failure after commands keeps their results and fails closed',()=>{
  const r=execute({}, {source:n=>{if(n===2)throw new Error();return 'sha'}});assert.equal(r.exitCode,1);assert.deepEqual(r.visited,labels);
});
test('default verify-all retains exit1; explicit pure deferred protocol yields2 only',()=>{
  const red={exitCode:1,fatal:null,deferred:['plan']};assert.equal(verificationExitCode(red),1);assert.equal(verificationExitCode(red,true),2);
  assert.equal(verificationExitCode({...red,fatal:'structure'},true),1);assert.equal(verificationExitCode({...red,deferred:[]},true),1);
  assert.equal(verificationExitCode({exitCode:0,fatal:null,deferred:[]},true),0);
});
test('production command factory preserves commands and classifies only governance',()=>{
  for(const platform of ['linux','win32']) {
    const p=qualityGateCommands(platform,'approved-node');assert.deepEqual(p.preflight.args,['scripts/verify-all.mjs','--test-health-exit-code']);
    assert.deepEqual(p.postflight.args,p.preflight.args);assert.deepEqual(p.stages.map(s=>s.args),[['run','frontend:test'],['run','frontend:build'],['run','test:frontend-browser'],['-q','test']]);
    assert.equal(p.stages.at(-1).command,platform==='win32'?'mvn.cmd':'mvn');
  }
});
test('unsupported verify-all option fails before any test execution',()=>{
  const r=spawnSync(process.execPath,['scripts/verify-all.mjs','--ignore-baseline'],{encoding:'utf8'});
  assert.equal(r.status,1);assert.match(r.stderr,/Unsupported verification option/);assert.equal(r.stdout,'');
});
function fixture(t) {
  const root=mkdtempSync(path.join(tmpdir(),'quality-order-'));t.after(()=>{ const relative=path.relative(path.resolve(tmpdir()),path.resolve(root)); if(!/^quality-order-[^\\\\/]+$/.test(relative)) throw new Error('UNSAFE_TEMP_CLEANUP'); rmSync(root,{recursive:true,force:true}); });
  for(const dir of ['requirements','scripts','backend','frontend','governance','docs'])mkdirSync(path.join(root,dir));
  writeFileSync(path.join(root,'docs','08-总开发实现计划.md'),'fixed plan');return root;
}
test('real source binding detects changed bytes, additions and removals; ignores generated target',t=>{
  const root=fixture(t);writeFileSync(path.join(root,'backend','a.java'),'a');const before=sourceFingerprint(root);
  mkdirSync(path.join(root,'backend','target'));writeFileSync(path.join(root,'backend','target','output.java'),'generated');assert.equal(sourceFingerprint(root),before);
  for(const dir of ['dist','dist-consultation','dist-adjudication','dist-enforcement']) {mkdirSync(path.join(root,'frontend',dir));writeFileSync(path.join(root,'frontend',dir,'bundle.js'),'generated');}assert.equal(sourceFingerprint(root),before);
  writeFileSync(path.join(root,'backend','a.java'),'b');assert.notEqual(sourceFingerprint(root),before);
  writeFileSync(path.join(root,'backend','a.java'),'a');assert.equal(sourceFingerprint(root),before);
  writeFileSync(path.join(root,'frontend','new.ts'),'new');assert.notEqual(sourceFingerprint(root),before);
  rmSync(path.join(root,'frontend','new.ts'));assert.equal(sourceFingerprint(root),before);
  rmSync(path.join(root,'backend','a.java'));assert.notEqual(sourceFingerprint(root),before);
});
test('requirement drift is included in quality source binding',t=>{
  const root=fixture(t),before=sourceFingerprint(root);writeFileSync(path.join(root,'requirements','business.md'),'changed');assert.notEqual(sourceFingerprint(root),before);
});
test('fresh reports require nonempty current reports and preserve error/skipped counts',()=>{
  const row={path:'suite.xml',mtimeMs:20,tests:10,failures:0,errors:0,skipped:3};
  assert.equal(freshJavaReports([],[],10),false);assert.equal(freshJavaReports([], [{...row,tests:0,skipped:0}],10),false);
  assert.equal(freshJavaReports([{...row,mtimeMs:15}],[row],10),true);assert.equal(freshJavaReports([row],[row],10),false);
  assert.equal(freshJavaReports([],[row],21),false);assert.equal(freshJavaReports([],[{...row,errors:1}],10),false);
  assert.equal(freshJavaReports([],[{...row,failures:1}],10),false);assert.equal(row.skipped,3);
});
test('real XML reader rejects missing counters and impossible totals; prior corrupt reports only supply timestamp metadata',t=>{
  const root=fixture(t),dir=path.join(root,'backend','module','target','surefire-reports');mkdirSync(dir,{recursive:true});
  const f=path.join(dir,'TEST-Suite.xml');writeFileSync(f,'<testsuite tests="3" failures="0" errors="0" skipped="1" name="Suite"></testsuite>');
  const [r]=javaReports(root);assert.equal(r.tests,3);assert.equal(r.skipped,1);
  writeFileSync(f,'bad old report');assert.throws(()=>javaReports(root),/TEST_SUITE_REQUIRED/);assert.equal(javaReports(root,false).length,1);
  writeFileSync(f,'<testsuite tests="3" failures="0" errors="0">');assert.throws(()=>javaReports(root),/TEST_COUNT_REQUIRED/);
  writeFileSync(f,'<testsuite tests="1" failures="1" errors="1" skipped="0">');assert.throws(()=>javaReports(root),/TEST_COUNTS_INVALID/);
});
test('source symbolic links or Windows junctions cannot redirect the evidence set',t=>{
  const root=fixture(t), original=path.join(root,'scripts','original');mkdirSync(original);
  writeFileSync(path.join(original,'source.mjs'),'source');
  symlinkSync(original,path.join(root,'scripts','linked'),process.platform==='win32'?'junction':'dir');
  assert.throws(()=>sourceFingerprint(root),/SOURCE_SYMLINK_REFUSED/);
});