import test from 'node:test';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {
  validateNativeExportKey, selectNativeExportFile, nativeExportInvocation,
  acquireFence, exportDatabase
} from './single-database-lifecycle-native.mjs';
import {hash, canonical} from './single-database-lifecycle-runtime.mjs';

// Public unit-test constants only: no environment reads, processes or live adapters.
const base='single-native-11111111-1111-1111-1111-111111111111.dmp';
const stem=base.slice(0,-4), maxBytes=134217728;
const keyOf=length=>'Aa0'+'x'.repeat(length-3);
const filename=(suffix='1')=>`${stem}-${suffix}.dmp`;
const listing=(name=filename(),type='f',bytes=1)=>Buffer.from(`${name} ${type} ${bytes}\n`);
const rejectListing=(buffer,requestedBase=base)=>assert.throws(
  ()=>selectNativeExportFile(requestedBase,buffer),/NATIVE/
);
function rejectKey(key){
  assert.throws(()=>validateNativeExportKey(key),error=>{
    assert.ok(error instanceof Error);
    assert.equal(error.message,'MANAGED_NATIVE_EXPORT_KEY_FORMAT_REQUIRED');
    return true;
  });
}

// Minimal legacy configuration contract, not a production identity or approval.
function config(){
  const target='1'.repeat(64),writer='2'.repeat(64);
  const lock=resolve('var',`single-database-maintenance-${target}.lock`);
  const c={
    targetId:target,schemas:['CONPROD','CONAUTH','ENFPROD','ENFAUTH','ADJPROD','ADJAUTH'],
    inventoryJdbc:{url:'jdbc:dm://127.0.0.1:1'},
    deployment:{database:{containerId:target},domains:[{writerContainerIds:[writer]}]},
    approval:{exportMethod:'DM8_NATIVE_EXPORT',nativeExportKeyReference:'unit-key-reference',
      javaFenceSha256:'5'.repeat(64),writerRegistryComplete:true,
      writerRegistryEvidence:'unit-registry',maintenanceExclusivityEvidence:'unit-maintenance',
      writers:[{kind:'CONTAINER',id:writer,owner:'unit',responsibilitySource:'unit-registry'},
        {kind:'MAINTENANCE',id:lock,owner:'unit',responsibilitySource:'unit-maintenance'}]},
    native:{user:'UNIT_BKP',passwordEnv:'UNIT_NATIVE_PASSWORD',exportKeyEnv:'UNIT_NATIVE_KEY',
      exportKeyReference:'unit-key-reference',timeoutSeconds:120,maintenanceLock:lock,
      fence:{java:'java',classpath:'unit-driver',jdbcUrl:'jdbc:dm://127.0.0.1:1',
        user:'UNIT_FENCE',passwordEnv:'UNIT_FENCE_PASSWORD'}}
  };
  c.approval.nativeConfigurationSha256=hash(canonical(c.native));
  return c;
}

test('key accepts every mixed ASCII length from 44 through 48',()=>{
  for(let length=44;length<=48;length++)assert.equal(validateNativeExportKey(keyOf(length)),true);
});
test('key rejects empty, below-minimum and above-maximum lengths',()=>{
  for(const key of ['',keyOf(43),keyOf(49),keyOf(64)])rejectKey(key);
});
test('key rejects each missing character class, including single-class values',()=>{
  for(const key of ['a'.repeat(44),'A'.repeat(44),'1'.repeat(44),
    'Aa'.repeat(22),'A1'.repeat(22),'a1'.repeat(22)])rejectKey(key);
});
test('key rejects Unicode, punctuation and whitespace at otherwise valid length',()=>{
  for(const char of ['é','Ａ','１','_','-',' ','\t'])rejectKey(keyOf(43)+char);
});
test('key rejects newline, CR and NUL, including terminal newline anchor bypass',()=>{
  for(const char of ['\n','\r','\0']){
    rejectKey(keyOf(44)+char);
    rejectKey('Aa0'+char+'x'.repeat(40));
  }
});
test('key rejects non-string input without implicit coercion',()=>{
  for(const key of [undefined,null,44,{},[],Buffer.from(keyOf(44))])rejectKey(key);
});
test('key failure has a fixed message and does not expose rejected input',()=>{
  const rejected='PUBLIC_UNIT_INVALID_KEY_'+keyOf(44);
  assert.throws(()=>validateNativeExportKey(rejected),error=>{
    assert.equal(error.message,'MANAGED_NATIVE_EXPORT_KEY_FORMAT_REQUIRED');
    for(const exposed of [String(error),error.stack??'',JSON.stringify(error)]){
      assert.equal(exposed.includes(rejected),false);
    }
    return true;
  });
});

test('selector accepts numeric suffixes of different widths without assuming a start index',()=>{
  for(const suffix of ['0','7','001','000001','1234567890']){
    assert.deepEqual(selectNativeExportFile(base,listing(filename(suffix))),{name:filename(suffix),bytes:1});
  }
});
test('selector accepts the exact 128 MiB boundary and returns numeric bytes',()=>{
  assert.deepEqual(selectNativeExportFile(base,listing(filename(),'f',maxBytes)),{name:filename(),bytes:maxBytes});
});
test('selector rejects empty and whitespace-only listings',()=>{
  for(const value of ['', '\n',' \n'])rejectListing(Buffer.from(value));
});
test('selector rejects multiple files and duplicate file records',()=>{
  rejectListing(Buffer.concat([listing(),listing(filename('2'))]));
  rejectListing(Buffer.concat([listing(),listing()]));
});
test('selector rejects another UUID, unrelated prefix and the unexpanded base',()=>{
  for(const name of [filename().replace('11111111','22222222'),'other-1.dmp',base])rejectListing(listing(name));
});
test('selector rejects traversal, absolute paths and directory components',()=>{
  for(const name of [`../${filename()}`,`/tmp/${filename()}`,`dir/${filename()}`,`dir\\${filename()}`])rejectListing(listing(name));
});
test('selector rejects symlinks and directories rather than following or reading them',()=>{
  for(const type of ['l','d'])rejectListing(listing(filename(),type));
});
test('selector rejects zero, negative and oversized byte counts',()=>{
  for(const bytes of [0,-1,maxBytes+1,'9007199254740993'])rejectListing(listing(filename(),'f',bytes));
});
test('selector rejects non-integer byte syntax and trailing fields',()=>{
  for(const bytes of ['1.5','1e2','NaN','Infinity','1 extra'])rejectListing(listing(filename(),'f',bytes));
});
test('selector rejects missing, non-digit and overlong suffixes',()=>{
  for(const suffix of ['','a','-1','1.0','12345678901'])rejectListing(listing(filename(suffix)));
});
test('selector rejects invalid UTF-8 bytes rather than accepting replacement text',()=>{
  rejectListing(Buffer.concat([listing(),Buffer.from([0xc3,0x28])]));
  rejectListing(Buffer.concat([Buffer.from(`${stem}-`),Buffer.from([0xff]),Buffer.from('.dmp f 1\n')]));
});
test('selector validates the controlled base name independently of the listing',()=>{
  for(const requested of [`../${base}`,base.toUpperCase(),base+'\n','single-native-123.dmp']){
    rejectListing(listing(),requested);
  }
});

test('invocation expands only the controlled FILE template and preserves export protections',()=>{
  const c=config(),args=nativeExportInvocation(c,base),script=args[5];
  assert.deepEqual(args.slice(0,5),['exec','-i',c.targetId,'sh','-c']);
  assert.ok(script.includes('"FILE=${1%.dmp}-%U.dmp"'));
  for(const flag of ['FILESIZE=128M','FILENUM=1','ENCRYPT=Y','DROP=N','GRANTS=Y','WITH_UR=N']){
    assert.ok(script.split(/\s+/).includes(flag),`missing ${flag}`);
  }
  assert.match(script,/timeout\s+-k\s+5\s+"\$2"/);
  assert.deepEqual(args.slice(7),[base,'120',c.native.user,c.schemas.join(',')]);
  assert.doesNotMatch(script,/FULL=Y/);
});
test('invocation reads credentials through stdin and leaves only a variable in argv',()=>{
  const args=nativeExportInvocation(config(),base),script=args[5];
  assert.match(script,/IFS=\s*read\s+-r\s+p/);
  assert.match(script,/IFS=\s*read\s+-r\s+k/);
  assert.ok(script.includes('"ENCRYPT_PASSWORD=$k"'));
  assert.equal(args.filter(arg=>arg.includes('ENCRYPT_PASSWORD=')).length,1);
  assert.ok(args.every(arg=>!arg.includes(keyOf(44))));
});
test('invocation rejects unsafe base names and unbounded timeout configuration',()=>{
  for(const name of [`../${base}`,base+'\n',filename()]){
    assert.throws(()=>nativeExportInvocation(config(),name),/NATIVE/);
  }
  for(const timeout of [0,121]){
    const c=config();c.native.timeoutSeconds=timeout;
    c.approval.nativeConfigurationSha256=hash(canonical(c.native));
    assert.throws(()=>nativeExportInvocation(c,base),/NATIVE_TIMEOUT/);
  }
});

// Structural wiring checks only: never invoke fence acquisition or database export.
// They do not establish runtime safety, source permissions or successful export.
test('acquireFence wires key validation before the first target inspection',()=>{
  const source=acquireFence.toString();
  const validation=source.search(/\bvalidateNativeExportKey\s*\(/);
  const inspection=source.search(/\bassertLiveOwnedTarget\s*\(/);
  assert.ok(validation>=0,'missing acquireFence key validation');
  assert.ok(inspection>validation,'key validation must precede target inspection');
});
test('exportDatabase revalidates key before export and consumes the selection helper',()=>{
  const source=exportDatabase.toString();
  const validation=source.search(/\bvalidateNativeExportKey\s*\(/);
  const invocation=source.search(/\bnativeExportInvocation\s*\(/);
  assert.ok(validation>=0,'missing export key revalidation');
  assert.ok(invocation>validation,'key validation must precede export invocation');
  assert.match(source,/\bselectNativeExportFile\s*\(/);
});
