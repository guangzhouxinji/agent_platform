import test from 'node:test';import assert from 'node:assert/strict';import {spawnSync} from 'node:child_process';
import {deliverImportedStart86} from './consultation-activation-recovery-continuous-runtime.mjs';
import {runNativeCommand} from './single-database-lifecycle-native.mjs';
import {CONTINUOUS75 as C} from './consultation-activation-recovery-continuous-fence.mjs';
test('existing imported entry preserves init proof and rejects false scope, empty identity and expiry',()=>{
 const script=`import importlib.util,datetime
s=importlib.util.spec_from_file_location('entry','scripts/lib/single-database-owned-restore-entry.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
r={'mode':'START_EXISTING_IMPORTED_ACTR86','taskId':m.TASK,'packageSha256':'c'*64,'sourceId':m.SOURCE,'sourcePidZero':True,'restartProofSha256':'4c9c608cb9728540c73bdcb3964c1dcc6c7d86821dd90a00a47486526052d718','continuationApprovalSha256':m.CONTINUATION_SHA55,'targetNeverImported':False,'runId':'72ed8ab4-d79f-4a60-bfca-a644af66c3d3','importJournalSha256':'97bc275b613ce8a801d5ea64715c194a3e0244ea940f1f2a9fb65e0a9b3324e4','scopeApprovalSha256':'f0c07ab3cc90059055d7190886f1909a63b7edf30db5c86f455551fff857b4d0'}
n=datetime.datetime.fromisoformat('2026-10-12T10:00:00+08:00')
assert m.existing_start_admission(r,n)==['/opt/dmdbms/bin/dmserver','dmserver',m.INI]
for k,v in [('sourceId','a'*64),('sourcePidZero',False),('taskId','other'),('restartProofSha256','a'*64),('continuationApprovalSha256','a'*64),('targetNeverImported',True),('targetNeverImported',0),('runId','invalid'),('importJournalSha256','a'*64),('scopeApprovalSha256','a'*64),('packageSha256',m.PACKAGE),('extra',True)]:
 try:m.existing_start_admission(dict(r,**{k:v}),n)
 except ValueError:pass
 else:raise AssertionError('unsafe '+k)
for k in r:
 c=dict(r);del c[k]
 try:m.existing_start_admission(c,n)
 except (ValueError,KeyError):pass
 else:raise AssertionError('missing '+k)
for t in ['2026-10-10T18:00:00+08:00','2026-10-13T18:00:00+08:00']:
 try:m.existing_start_admission(r,datetime.datetime.fromisoformat(t))
 except ValueError:pass
 else:raise AssertionError('unsafe window')
print('IMPORTED_EXISTING_ONLY_POSITIVE_AND_NEGATIVES')`;
 const r=spawnSync(process.platform==='win32'?'python':'python3',['-B','-c',script],{encoding:'utf8',timeout:10000});assert.equal(r.status,0,r.stderr);assert.match(r.stdout,/IMPORTED_EXISTING_ONLY/);
});
const input86={mode:'START_EXISTING_IMPORTED_ACTR86',targetNeverImported:false,sourceId:C.source,sourcePidZero:true};
test('restore input distinguishes empty, invalid and oversized lines without disclosing bytes',()=>{
 const script=`import importlib.util,json
s=importlib.util.spec_from_file_location('entry','scripts/lib/single-database-owned-restore-entry.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
assert m.parse_start_request(b'{"mode":"valid"}\\n')=={'mode':'valid'}
for raw,code in [(b'','RESTORE_START_INPUT_EMPTY'),(b'\\n','RESTORE_START_INPUT_INVALID_JSON'),(b'{bad-secret','RESTORE_START_INPUT_INVALID_JSON'),(bytes([255]),'RESTORE_START_INPUT_INVALID_JSON'),(b'x'*4097,'BOUNDED_INIT_REQUEST_REQUIRED')]:
 try:m.parse_start_request(raw)
 except ValueError as e:assert str(e)==code and 'secret' not in str(e)
 else:raise AssertionError('accepted invalid input')
print('BOUNDED_INPUT_DIAGNOSTICS_VERIFIED')`;
 const r=spawnSync(process.platform==='win32'?'python':'python3',['-B','-c',script],{encoding:'utf8',timeout:10000});assert.equal(r.status,0,r.stderr);
});
test('imported carrier uses one exact attach without signal proxy and terminates its real child',async()=>{
 let calls=0;
 const result=await deliverImportedStart86(async(args,options)=>{
  calls++;assert.deepEqual(args,['attach','--sig-proxy=false',C.target]);assert.equal(JSON.parse(options.input).sourceId,C.source);assert.equal(options.timeoutMs,15000);assert.ok(Object.keys(options.env).every(k=>!k.startsWith('SINGLE_DB_')));
  return runNativeCommand(process.execPath,['-e','process.stdin.resume();setInterval(()=>{},1000)'],{...options,timeoutMs:100});
 },input86);
 assert.equal(calls,1);assert.equal(result.carrierClosed,true);assert.equal(result.diagnostic.reason,'TIMEOUT');assert.equal(result.diagnostic.closeObserved,true);
});
test('imported carrier refuses untrusted timeout, nonzero child and unreadable termination without retry',async()=>{
 let calls=0;await assert.rejects(deliverImportedStart86(async()=>{calls++;throw Object.assign(Error('TIMEOUT'),{closeObserved:true});},input86),/CARRIER_UNCONFIRMED/);assert.equal(calls,1);
 await assert.rejects(deliverImportedStart86(async(_,o)=>runNativeCommand(process.execPath,['-e','process.exit(1)'],o),input86),/CARRIER_UNCONFIRMED/);
 await assert.rejects(deliverImportedStart86(async()=>{throw Error('unexpected');},{...input86,targetNeverImported:true}),/START_REQUEST_REQUIRED/);
});
test('fresh restore entry: real Python pure admission, nine negatives, no initialization',()=>{
 const script=`import importlib.util,datetime,copy
s=importlib.util.spec_from_file_location('entry','scripts/lib/single-database-owned-restore-entry.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
r={'taskId':m.TASK,'packageSha256':m.PACKAGE,'sourceId':m.SOURCE,'sourcePidZero':True,'administratorPassword':'A'*44,'auditorPassword':'B'*44}
n=datetime.datetime.fromisoformat('2026-10-10T16:00:00+08:00')
a=m.initialize_arguments(r,'restore',m.TARGET,m.MARKER,['.single-final-owner'],n)
assert 'PAGE_SIZE=32' in a and 'CHARSET=1' in a and 'LOG_SIZE=256' in a
cases=[(dict(r,sourceId='a'*64),'restore',m.TARGET,m.MARKER,['.single-final-owner'],n),(r,'primary',m.TARGET,m.MARKER,['.single-final-owner'],n),(r,'restore','other',m.MARKER,['.single-final-owner'],n),(r,'restore',m.TARGET,'wrong',['.single-final-owner'],n),(r,'restore',m.TARGET,m.MARKER,['.single-final-owner','SINGLE01'],n),(dict(r,sourcePidZero=False),'restore',m.TARGET,m.MARKER,['.single-final-owner'],n),(dict(r,administratorPassword='bad'),'restore',m.TARGET,m.MARKER,['.single-final-owner'],n),(dict(r,packageSha256='c'*64),'restore',m.TARGET,m.MARKER,['.single-final-owner'],n),(r,'restore',m.TARGET,m.MARKER,['.single-final-owner'],datetime.datetime.fromisoformat('2026-10-10T17:10:00+08:00'))]
for c in cases:
 try:m.initialize_arguments(*c)
 except ValueError:pass
 else:raise AssertionError('accepted unsafe restore')
print('ADMISSION_POSITIVE_AND_9_NEGATIVES')`;
 const r=spawnSync(process.platform==='win32'?'python':'python3',['-B','-c',script],{encoding:'utf8',timeout:10000});assert.equal(r.status,0,r.stderr);assert.match(r.stdout,/ADMISSION_POSITIVE_AND_9_NEGATIVES/);
});

test('existing continuation entry has real Python pure admission and never initializes',()=>{
 const script=`import importlib.util,datetime,copy
s=importlib.util.spec_from_file_location('entry','scripts/lib/single-database-owned-restore-entry.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
r={'mode':'START_EXISTING_CONTINUATION_RESTORE55','taskId':m.TASK,'packageSha256':'c'*64,'sourceId':m.SOURCE,'sourcePidZero':True,'restartProofSha256':'4c9c608cb9728540c73bdcb3964c1dcc6c7d86821dd90a00a47486526052d718','continuationApprovalSha256':m.CONTINUATION_SHA55,'targetNeverImported':True}
n=datetime.datetime.fromisoformat('2026-10-10T19:00:00+08:00')
a=m.existing_start_admission(r,n);assert a==['/opt/dmdbms/bin/dmserver','dmserver',m.INI] and not any('dminit' in v for v in a)
for k,v in [('sourcePidZero',False),('sourceId','b'*64),('taskId','wrong'),('restartProofSha256','a'*64),('continuationApprovalSha256','a'*64),('targetNeverImported',False),('packageSha256',m.PACKAGE),('packageSha256','9480f1d388930195d32a9157690be441c5e1c1401fe6834345af007f42254c18'),('packageSha256','invalid'),('extra',True)]:
 try:m.existing_start_admission(dict(r,**{k:v}),n)
 except ValueError:pass
 else:raise AssertionError('unsafe '+k)
for t in ['2026-10-10T18:00:00+08:00','2026-10-13T18:00:00+08:00']:
 try:m.existing_start_admission(r,datetime.datetime.fromisoformat(t))
 except ValueError:pass
 else:raise AssertionError('unsafe window')
old={k:v for k,v in r.items() if k not in ['continuationApprovalSha256','targetNeverImported']};old['mode']='START_EXISTING_APPROVED_RESTORE';old['packageSha256']=m.PACKAGE
assert m.existing_start_admission(old,datetime.datetime.fromisoformat('2026-10-10T17:00:00+08:00'))==a
try:m.existing_start_admission(old,n)
except ValueError:pass
else:raise AssertionError('old window revived')
print('CONTINUATION_NO_DMINIT_POSITIVE_13_NEGATIVES')`;
 const r=spawnSync(process.platform==='win32'?'python':'python3',['-B','-c',script],{encoding:'utf8',timeout:10000});assert.equal(r.status,0,r.stderr);assert.match(r.stdout,/CONTINUATION_NO_DMINIT/);
});
