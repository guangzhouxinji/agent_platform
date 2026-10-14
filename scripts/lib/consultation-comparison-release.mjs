// Offline engineering evidence only. Never applies SQL, builds, deploys, or approves business use.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { inflateRawSync } from 'node:zlib';
import { pathToFileURL } from 'node:url';

// Fixed from workspace original bytes on 2026-10-07; not caller-controlled pins.
export const FORWARD = Object.freeze([
  {
    "path": "db/dm8/V032__product_identity.sql",
    "sha256": "7a025bd04bdf8b73a22e503148fc38e901b9ca1ed33bf412363992417c26aded",
    "dependencies": []
  },
  {
    "path": "db/dm8/V034__product_consultation_initial_task.sql",
    "sha256": "6ba27d5438f9f47f9b57db74d77f6296a1a93539cec9bbd7cd7cecfb8c5528c0",
    "dependencies": [
      "db/dm8/V032__product_identity.sql"
    ]
  },
  {
    "path": "db/dm8/V036__product_source_candidates.sql",
    "sha256": "200d08291e28a221af09adc738b1591e900000761858f423e491c375d75ad9bc",
    "dependencies": []
  },
  {
    "path": "db/dm8/V037__product_source_admission.sql",
    "sha256": "481a34f039ad6c12981036b7dde2f84f39f01d08053c6e363d509ce8d9e6fd1f",
    "dependencies": [
      "db/dm8/V036__product_source_candidates.sql"
    ]
  },
  {
    "path": "db/dm8/V042__product_consultation_goal.sql",
    "sha256": "898b60c54a539d9fd2691ed127f4f21cd5ecade8d7c0742e847f4294c0605ec6",
    "dependencies": [
      "db/dm8/V032__product_identity.sql",
      "db/dm8/V034__product_consultation_initial_task.sql"
    ]
  },
  {
    "path": "db/dm8/V047__product_task_worker_delegation.sql",
    "sha256": "e4b3b720390f854bb2129078b5cfe1207fe9a0d6ac70507f9068701567926251",
    "dependencies": [
      "db/dm8/V032__product_identity.sql",
      "db/dm8/V034__product_consultation_initial_task.sql"
    ]
  },
  {
    "path": "db/dm8/V092__product_source_comparison_use.sql",
    "sha256": "83d3643eaa7e4a729899c927b4b0495954f39aa17909782efd1fba0ea398dd77",
    "dependencies": [
      "db/dm8/V037__product_source_admission.sql"
    ]
  },
  {
    "path": "db/dm8/V094__product_comparison_feature_confirmation.sql",
    "sha256": "d076171d17a5201ef5ca61b6ba03255c50a12a43c306721ec385bf73459acee8",
    "dependencies": [
      "db/dm8/V032__product_identity.sql",
      "db/dm8/V034__product_consultation_initial_task.sql",
      "db/dm8/V037__product_source_admission.sql",
      "db/dm8/V042__product_consultation_goal.sql"
    ]
  },
  {
    "path": "db/dm8/V095__product_comparison_input.sql",
    "sha256": "7d84594ed353c17731ef2a94dfbdb49b1a5fff8268dbf079ebb8b10661de1c3d",
    "dependencies": [
      "db/dm8/V032__product_identity.sql",
      "db/dm8/V094__product_comparison_feature_confirmation.sql"
    ]
  },
  {
    "path": "db/dm8/V098__product_comparison_worker_delegation.sql",
    "sha256": "83f525db3215addff9345b046c71943d1ca35e5e5373e8c98b36e4b1bcbb0bca",
    "dependencies": [
      "db/dm8/V047__product_task_worker_delegation.sql",
      "db/dm8/V095__product_comparison_input.sql"
    ]
  },
  {
    "path": "db/dm8/V099__product_comparison_candidate_lease.sql",
    "sha256": "43b5df74b170173e1d29606ba0369a29c502c8872e9b54d68530a3459ae715d6",
    "dependencies": [
      "db/dm8/V095__product_comparison_input.sql",
      "db/dm8/V098__product_comparison_worker_delegation.sql"
    ]
  },
  {
    "path": "db/dm8/V100__product_comparison_matrix.sql",
    "sha256": "d0fcf5ecb09336b59285b04bc5979321b1a2d6c961a8c940778e05444b113d26",
    "dependencies": [
      "db/dm8/V095__product_comparison_input.sql",
      "db/dm8/V099__product_comparison_candidate_lease.sql"
    ]
  },
  {
    "path": "db/dm8/V101__product_comparison_prepare_request.sql",
    "sha256": "4a9da6f9cdffb57e6bc8d209fdf12381daf4949ab51aeb8427f5f355ecc6a2cb",
    "dependencies": [
      "db/dm8/V094__product_comparison_feature_confirmation.sql"
    ]
  }
].map(pin => Object.freeze({ ...pin, dependencies:Object.freeze(pin.dependencies) })));
export const REVERSE = Object.freeze([
  {
    "path": "db/dm8/U094__product_comparison_feature_confirmation.sql",
    "sha256": "3b1a3de2fc24a934654cb1c02ce1caf4dcb6ffc5a68166c6eaabc6782e9e29dd"
  },
  {
    "path": "db/dm8/U095__product_comparison_input.sql",
    "sha256": "0c2be5d9f1fa3e50ac6efe1c0ea8419f5eb2cb1f5f9cd3ddfe05df211246ea95"
  },
  {
    "path": "db/dm8/U098__product_comparison_worker_delegation.sql",
    "sha256": "679bae2d0541b9c06b9e56860fc5dabe3f16f5e40cfff1bddb8254b851beb8d4"
  },
  {
    "path": "db/dm8/U099__product_comparison_candidate_lease.sql",
    "sha256": "993a9deebfbd4e6bdc01d8927ab262de26b79d73a3528f093ff4bcd15d18cd07"
  },
  {
    "path": "db/dm8/U100__product_comparison_matrix.sql",
    "sha256": "d3b56790426d07564099be1636f4464c5aa9b00840f58c900d3da2c1b35fcebb"
  },
  {
    "path": "db/dm8/U101__product_comparison_prepare_request.sql",
    "sha256": "29712f39116933a988cef0d40175f5d04e4202bee6177da29ad5b1d31f7f5424"
  }
].map(Object.freeze));

const CLASS_ROOT = 'BOOT-INF/classes/cn/xinji/agentplatform/security/product/';
export const REQUIRED_CLASSES = Object.freeze([
  'ProductComparisonWorkbenchContextController', 'ProductComparisonPreparationController',
  'ProductComparisonSchemaGuard', 'ProductComparisonMatrixController',
  'ProductComparisonMatrixCalculation', 'ProductComparisonModelAdmission', 'ProductComparisonInputs',
].map(name => CLASS_ROOT + name + '.class'));
export const SUCCESSOR_FORWARD = Object.freeze([
  {path:'db/dm8/V102__product_comparison_start_command.sql',sha256:'228bd052bbdd819a2c992ea855dfeaf2708cdc4350240ff4972b6b6370b59106',dependencies:['db/dm8/V095__product_comparison_input.sql','db/dm8/V099__product_comparison_candidate_lease.sql']},
  {path:'db/dm8/V103__comparison_start_fence_presence.sql',sha256:'4920e0603b888690142b9a11c53f07101c8386bcfbe183f2f0447b49adb9daf2',dependencies:['db/dm8/V102__product_comparison_start_command.sql']},
  {path:'db/dm8/V104__product_title_result_build_provenance.sql',sha256:'68ccc9a3407553924ed946fbe09799eb1e86f5524ade317e18e4acbc21d4d7ba',dependencies:[],existingPrerequisites:['CON4_RESULT_SET']} 
].map(pin=>Object.freeze({...pin,dependencies:Object.freeze(pin.dependencies),existingPrerequisites:Object.freeze(pin.existingPrerequisites??[])})));
export const SUCCESSOR_REVERSE = Object.freeze([
  Object.freeze({path:'db/dm8/U102__product_comparison_start_command.sql',sha256:'102f1c5a0acf7cb030ec561f39c2a7a333ee884b956a972edc3fe25d24a910e4',historyTable:'con5_start_command',emptyStatement:'DROP TABLE con5_start_command'}),
  Object.freeze({path:'db/dm8/U103__comparison_start_fence_presence.sql',sha256:'fc42e40b202035c9144f43eed6860a5381952ddd8fa1dc11e3007f73393621cb',historyTable:'con5_start_command',emptyStatement:'ALTER TABLE con5_start_command DROP CONSTRAINT ck_con5_start_fence_present'})
]);
export const SUCCESSOR_CLASSES = Object.freeze([...REQUIRED_CLASSES,...[
  'ProductComparisonStartHandoffGuard','ProductComparisonStartReceiptChecks','ProductComparisonStartApplication','ProductComparisonStartCommands','ProductComparisonStartConfiguration','ProductComparisonStartController','ProductComparisonStartSchema',
  'ProductHistoricalResultIsolation','ProductTitleBuildProvenance','ProductPatentSearchExecution','ProductPatentSearchPages','ProductPatentSearchReceipts','ProductComparisonDocuments',
  'ProductPatentSearchRecoveryVerifier','ProductModelTurnRecoveryVerifier'
].map(name=>CLASS_ROOT+name+'.class')]);
// Current final input + managed, no-model handoff. Older frozen generations remain unchanged.
export const CURRENT_FORWARD = Object.freeze([...SUCCESSOR_FORWARD,
  Object.freeze({path:'db/dm8/V105__comparison_start_handoff_receipt.sql',sha256:'808b56d748290a1a774e81282da775536b6894d1319620cccb31be52ea58303f',dependencies:Object.freeze(['db/dm8/V102__product_comparison_start_command.sql']),existingPrerequisites:Object.freeze([])}),
  Object.freeze({path:'db/dm8/V106__product_display_only_source_scope.sql',sha256:'0bc141cc3340a22e6e776418f0122b76e8fdf2ba58b04f70e709821c9ffd2e5c',dependencies:Object.freeze([]),existingPrerequisites:Object.freeze(['CON4_TASK_SOURCE_REQUEST','CON4_TASK_SOURCE_REQUEST.CK_CON4_SCOPE_QUERY_GRANT (V056); verify actual semantics, never replay initialization'])})
]);
export const CURRENT_REVERSE = Object.freeze([...SUCCESSOR_REVERSE,
  Object.freeze({path:'db/dm8/U105__comparison_start_handoff_receipt.sql',sha256:'1cbff573f6c1a841c6655caba825e0b00384554a99789a65989e12e8bf2d9aa2',historyTable:'con5_start_handoff_receipt',emptyStatement:'DROP TABLE con5_start_handoff_receipt'})
]);
const CURRENT_ANCHORS = ['ProductComparisonHandoffController','ProductComparisonHandoffStore','ProductComparisonDocumentCatalogController'].map(n=>CLASS_ROOT+n+'.class');
export const CURRENT_CLASSES = Object.freeze([...new Set([...SUCCESSOR_CLASSES,...CURRENT_ANCHORS,...[
  'ProductComparisonInputConfiguration','ProductConsultationWorkspacePageController',
  'ProductRetrievalWorkspaceController','ProductRetrievalWorkspaceConfiguration','ProductRetrievalDialogueConfiguration'
].map(n=>CLASS_ROOT+n+'.class')])]);
const APP = 'BOOT-INF/classes/static/app/';
export const LINEAGE_FORWARD = Object.freeze([...CURRENT_FORWARD,
  Object.freeze({path:'db/dm8/V108__product_comparison_lineage.sql',sha256:'3709ae92b30d4d00cfa5f81404fafc99a426d9f3a4517444eeef9dfe15240c18',dependencies:Object.freeze(['db/dm8/V100__product_comparison_matrix.sql']),existingPrerequisites:Object.freeze([])})
]);
export const LINEAGE_REVERSE = Object.freeze([...CURRENT_REVERSE,
  Object.freeze({path:'db/dm8/U108__product_comparison_lineage.sql',sha256:'b889e261c46f759abfbca914059108062dfdf88c16afbdf3af01ed9f02c955d0',historyTable:'con5_matrix_dependency_material',emptyStatement:'DROP TABLE con5_matrix_dependency_original;\nDROP TABLE con5_matrix_dependency_material'})
]);
const LINEAGE_ANCHORS = ['ProductComparisonLineageFacts','ProductComparisonLineageCodec','ProductComparisonLineageStore'].map(n=>CLASS_ROOT+n+'.class');
export const LINEAGE_CLASSES = Object.freeze([...new Set([...CURRENT_CLASSES,...LINEAGE_ANCHORS,...[
  'ProductComparisonMatrixStore','ProductComparisonMatrixExecution','ProductComparisonSchemaGuard','ProductComparisonCalculationConfiguration'
].map(n=>CLASS_ROOT+n+'.class')])]);
const OUTPUT_ROOT = 'docs/evidence/consultation-comparison-release-20261007/package/';
const HEX = /^[a-f0-9]{64}$/;
const SOURCE_EXTENSIONS = new Set(['.java','.xml','.yml','.yaml','.json','.sql','.py','.ps1','.mjs']);
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const fail = code => { throw new Error(code); };
const check = (condition, code) => { if (!condition) fail(code); };

function relativePath(ref) {
  check(typeof ref === 'string' && ref.length > 0 && !ref.includes('\\') &&
    !/[\x00-\x1f:]/.test(ref) && !ref.startsWith('/') &&
    ref.split('/').every(p => p && p !== '.' && p !== '..'), 'BAD_PATH');
  return ref;
}
function resolveSafe(root, ref, missing = false) {
  relativePath(ref);
  let current = root;
  const parts = ref.split('/');
  for (let i = 0; i < parts.length; i++) {
    current = path.join(current, parts[i]);
    if (!fs.existsSync(current)) {
      check(missing, 'MISSING_FILE');
      // lstat also rejects a dangling link, which existsSync reports as missing.
      try { check(!fs.lstatSync(current).isSymbolicLink(), 'LINKED_PATH'); }
      catch (e) { if (e.code !== 'ENOENT') throw e; }
      continue;
    }
    const st = fs.lstatSync(current);
    check(!st.isSymbolicLink(), 'LINKED_PATH');
    if (i < parts.length - 1) check(st.isDirectory(), 'BAD_PATH');
  }
  return current;
}
function read(root, ref) {
  const file = resolveSafe(root, ref);
  check(fs.statSync(file).isFile(), 'NOT_REGULAR_FILE');
  const bytes = fs.readFileSync(file);
  check(bytes.length > 0, 'EMPTY_FILE');
  return bytes;
}
function fileRef(ref, bytes) { return { path: ref, sha256: sha(bytes) }; }

// Exact coverage, not merely a list of whichever inputs a client elects to declare.
// Main-session source policy: java/xml/yml/yaml/json/sql/py/ps1/mjs only.
// Generated build output and dependency directories are not source inputs.
const SUCCESSOR = 'SUCCESSOR_WITH_ISOLATION_V1';
const CURRENT = 'SUCCESSOR_INPUT_HANDOFF_V2';
const LINEAGE = 'SUCCESSOR_MATRIX_LINEAGE_V3';
const FRONTEND_EXTENSIONS = new Set(['.ts','.tsx','.js','.jsx','.mjs','.cjs','.json','.html','.css']);
function sourceGeneration(generation) {
  check(['FIXED_A_V1', SUCCESSOR, CURRENT, LINEAGE].includes(generation), 'UNKNOWN_RELEASE_GENERATION');
  return generation !== 'FIXED_A_V1';
}
function frontendSource(ref) {
  return /^frontend\/(src|public)\//.test(ref) || (/^frontend\/[^/]+$/.test(ref) && FRONTEND_EXTENSIONS.has(path.extname(ref).toLowerCase()));
}
export function captureSnapshot(workspace, generation = 'FIXED_A_V1') {
  const successor = sourceGeneration(generation);
  const root = fs.realpathSync(workspace), files = [];
  function walk(ref) {
    const dir = resolveSafe(root, ref);
    check(fs.statSync(dir).isDirectory(), 'SOURCE_ROOT_MISSING');
    for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a,b) => a.name.localeCompare(b.name))) {
      if (['target', 'node_modules', '.git'].includes(entry.name) && entry.isDirectory()) continue;
      const child = ref + '/' + entry.name;
      const resolved = resolveSafe(root, child);
      const stat = fs.lstatSync(resolved);
      if (stat.isDirectory()) walk(child);
      else {
        check(stat.isFile(), 'NOT_REGULAR_FILE');
        if (ref.startsWith('frontend') ? frontendSource(child) : SOURCE_EXTENSIONS.has(path.extname(child).toLowerCase()))
          files.push({ path: child, sha256: sha(fs.readFileSync(resolved)) });
      }
    }
  }
  for (const dir of ['backend', 'db', 'scripts']) walk(dir);
  if (successor) {
    const frontend = resolveSafe(root, 'frontend');
    check(fs.statSync(frontend).isDirectory(), 'SOURCE_ROOT_MISSING');
    walk('frontend/src');
    if (fs.existsSync(path.join(frontend,'public'))) walk('frontend/public');
    for (const entry of fs.readdirSync(frontend, {withFileTypes:true})) {
      const ref = 'frontend/' + entry.name;
      if (frontendSource(ref) && !entry.isDirectory()) files.push(fileRef(ref,read(root,ref)));
    }
  }
  return files.sort((a,b) => a.path.localeCompare(b.path));
}
function parseSnapshot(bytes, generation) {
  const successor = sourceGeneration(generation);
  const value = JSON.parse(bytes.toString('utf8'));
  const rows = Array.isArray(value) ? value : value.files;
  check(Array.isArray(rows) && rows.length > 0, 'BAD_SNAPSHOT');
  const seen = new Set();
  return rows.map(row => {
    check(row && typeof row === 'object' && Object.keys(row).every(k => ['path','sha','sha256'].includes(k)), 'BAD_SNAPSHOT');
    relativePath(row.path);
    check((/^(backend|db|scripts)\//.test(row.path) && SOURCE_EXTENSIONS.has(path.extname(row.path).toLowerCase())) || (successor && frontendSource(row.path)), 'BAD_SNAPSHOT_PATH');
    check(!seen.has(row.path), 'DUPLICATE_SNAPSHOT_PATH'); seen.add(row.path);
    const digest = row.sha256 ?? row.sha;
    check(HEX.test(digest) && (!row.sha || !row.sha256 || row.sha === row.sha256), 'BAD_SNAPSHOT_SHA');
    return { path: row.path, sha256: digest };
  }).sort((a,b) => a.path.localeCompare(b.path));
}
function verifySnapshots(root, beforeRef, afterRef, generation) {
  const before = read(root, beforeRef), after = read(root, afterRef);
  const expected = parseSnapshot(before,generation), actual = parseSnapshot(after,generation);
  check(JSON.stringify(expected) === JSON.stringify(actual), 'BUILD_INPUT_DRIFT');
  check(JSON.stringify(expected) === JSON.stringify(captureSnapshot(root,generation)), 'WORKSPACE_INPUT_DRIFT');
  return { before: fileRef(beforeRef, before), after: fileRef(afterRef, after),
    inputsSha256: sha(JSON.stringify(expected)), files: expected.length };
}

// Small, fail-closed standard ZIP reader. ZIP64/encrypted/multi-disk archives are not accepted.
// No archive member is ever extracted to the filesystem.
function zipEntries(bytes) {
  let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (bytes.readUInt32LE(i) === 0x06054b50 && i + 22 + bytes.readUInt16LE(i+20) === bytes.length) { end = i; break; }
  }
  check(end >= 0, 'BAD_JAR');
  check(bytes.readUInt16LE(end+4) === 0 && bytes.readUInt16LE(end+6) === 0 &&
    bytes.readUInt16LE(end+8) === bytes.readUInt16LE(end+10), 'UNSUPPORTED_JAR');
  const count = bytes.readUInt16LE(end+10), size = bytes.readUInt32LE(end+12), start = bytes.readUInt32LE(end+16);
  check(count !== 65535 && start + size === end, 'UNSUPPORTED_JAR');
  const entries = new Map(); let offset = start;
  for (let i = 0; i < count; i++) {
    check(offset + 46 <= end && bytes.readUInt32LE(offset) === 0x02014b50, 'BAD_JAR');
    const flags = bytes.readUInt16LE(offset+8), method = bytes.readUInt16LE(offset+10);
    const compressed = bytes.readUInt32LE(offset+20), expanded = bytes.readUInt32LE(offset+24);
    const length = bytes.readUInt16LE(offset+28), extra = bytes.readUInt16LE(offset+30), comment = bytes.readUInt16LE(offset+32);
    const local = bytes.readUInt32LE(offset+42), nameBytes = bytes.subarray(offset+46, offset+46+length);
    const name = nameBytes.toString('utf8');
    check(offset+46+length+extra+comment <= end && Buffer.from(name).equals(nameBytes), 'BAD_JAR');
    relativePath(name.endsWith('/') ? name.slice(0,-1) : name);
    check(!entries.has(name) && !(flags & 1) && [0,8].includes(method) &&
      compressed !== 0xffffffff && expanded !== 0xffffffff && local + 30 <= start, 'UNSUPPORTED_JAR');
    check(bytes.readUInt32LE(local) === 0x04034b50 && bytes.readUInt16LE(local+6) === flags &&
      bytes.readUInt16LE(local+8) === method, 'BAD_JAR');
    const localNameLength = bytes.readUInt16LE(local+26), localExtraLength = bytes.readUInt16LE(local+28);
    check(bytes.subarray(local+30,local+30+localNameLength).equals(nameBytes), 'BAD_JAR');
    if (!(flags & 8)) check(bytes.readUInt32LE(local+14) === bytes.readUInt32LE(offset+16) &&
      bytes.readUInt32LE(local+18) === compressed && bytes.readUInt32LE(local+22) === expanded, 'BAD_JAR');
    const dataStart = local + 30 + localNameLength + localExtraLength;
    check(dataStart + compressed <= start, 'BAD_JAR');
    entries.set(name, { method, expanded, data: bytes.subarray(dataStart,dataStart+compressed), crc: bytes.readUInt32LE(offset+16) });
    offset += 46 + length + extra + comment;
  }
  check(offset === end, 'BAD_JAR');
  return entries;
}
const CRC_TABLE = Uint32Array.from({ length:256 }, (_,value) => {
  let crc = value;
  for (let bit=0;bit<8;bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  return crc >>> 0;
});
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ byte) & 255];
  return (crc ^ 0xffffffff) >>> 0;
}
function member(entries, name, maximum = 32*1024*1024) {
  const item = entries.get(name);
  check(item && item.expanded > 0, 'MISSING_JAR_ENTRY');
  check(item.expanded <= maximum, 'JAR_ENTRY_TOO_LARGE');
  const bytes = item.method === 0 ? item.data : inflateRawSync(item.data, { maxOutputLength: maximum });
  check(bytes.length === item.expanded && crc32(bytes) === item.crc, 'CORRUPT_JAR_ENTRY');
  return bytes;
}
function managedJar(javaHome) {
  check(typeof javaHome === 'string' && path.isAbsolute(javaHome), 'MANAGED_JAVA_HOME_REQUIRED');
  const home = fs.realpathSync(javaHome);
  const executable = resolveSafe(home, process.platform === 'win32' ? 'bin/jar.exe' : 'bin/jar');
  check(fs.statSync(executable).isFile(), 'MANAGED_JAR_MISSING');
  return executable;
}
function inspectJar(root, jarRef, expectedSha, javaHome, entriesRef, requiredClasses = REQUIRED_CLASSES) {
  check(expectedSha === undefined || HEX.test(expectedSha), 'BAD_JAR_PIN');
  const bytes = read(root, jarRef);
  if (expectedSha !== undefined) check(sha(bytes) === expectedSha, 'JAR_HASH_DRIFT');
  const entries = zipEntries(bytes);
  // Check every compressed payload, including dependency jars, without executing classes.
  let expandedTotal = 0;
  for (const [name,item] of entries) {
    expandedTotal += item.expanded;
    check(expandedTotal <= 2*1024*1024*1024, 'JAR_TOO_LARGE');
    if (item.expanded) member(entries,name,512*1024*1024);
    else {
      const empty = item.method === 0 ? item.data : inflateRawSync(item.data,{ maxOutputLength:1 });
      check(empty.length === 0 && item.crc === 0, 'CORRUPT_JAR_ENTRY');
    }
  }
  const executable = managedJar(javaHome);
  const result = spawnSync(executable, ['tf', resolveSafe(root, jarRef)], { encoding:'utf8', windowsHide:true,
    timeout:60000, maxBuffer:32*1024*1024 });
  check(!result.error && result.status === 0 && !result.signal, 'JAR_READ_FAILED');
  const names = text => text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean).sort();
  const realNames = [...entries.keys()].sort();
  if(requiredClasses !== LINEAGE_CLASSES && LINEAGE_ANCHORS.some(name=>entries.has(name))) fail('LINEAGE_GENERATION_REQUIRED');
  if(requiredClasses !== CURRENT_CLASSES && requiredClasses !== LINEAGE_CLASSES && CURRENT_ANCHORS.some(name=>entries.has(name))) fail('CURRENT_GENERATION_REQUIRED');
  if(requiredClasses === REQUIRED_CLASSES && ['ProductComparisonStartApplication','ProductTitleBuildProvenance'].some(n=>entries.has(CLASS_ROOT+n+'.class'))) fail('SUCCESSOR_GENERATION_REQUIRED');
  check(JSON.stringify(names(result.stdout)) === JSON.stringify(realNames), 'JAR_DIRECTORY_MISMATCH');
  let suppliedEntries;
  if (entriesRef) {
    const supplied = read(root, entriesRef);
    check(JSON.stringify(names(supplied.toString('utf8'))) === JSON.stringify(realNames), 'JAR_ENTRIES_DRIFT');
    suppliedEntries = fileRef(entriesRef, supplied);
  }
  for (const name of requiredClasses) check(entries.has(name), 'MISSING_CLASSES');
  for (const name of requiredClasses) {
    check(entries.has(name), 'MISSING_CLASSES');
    const clazz = member(entries,name);
    check(clazz.length >= 8 && clazz.readUInt32BE(0) === 0xcafebabe, 'BAD_CLASS_BYTES');
  }
  let classParser;
  if (requiredClasses === SUCCESSOR_CLASSES || requiredClasses === CURRENT_CLASSES || requiredClasses === LINEAGE_CLASSES) {
    const javap = resolveSafe(fs.realpathSync(javaHome), process.platform === 'win32' ? 'bin/javap.exe' : 'bin/javap');
    check(fs.statSync(javap).isFile(), 'MANAGED_JAVAP_MISSING');
    classParser = {path:javap, sha256:sha(fs.readFileSync(javap)), mode:'VERBOSE_STRUCTURE_AND_EXACT_CLASS_NAME_NOT_EXECUTION'};
    const jarUrl = pathToFileURL(resolveSafe(root,jarRef)).href;
    for (const name of requiredClasses) {
      const parsed = spawnSync(javap, ['-verbose', 'jar:' + jarUrl + '!/' + name], {
        encoding:'utf8', windowsHide:true, timeout:30000, maxBuffer:16*1024*1024 });
      check(!parsed.error && parsed.status === 0 && !parsed.signal && !parsed.stderr.trim() && !/\bError:/.test(parsed.stdout), 'BAD_CLASS_STRUCTURE');
      const actual = parsed.stdout.match(/this_class:\s+#\d+\s+\/\/\s+(\S+)/)?.[1];
      check(actual === name.slice('BOOT-INF/classes/'.length,-'.class'.length), 'CLASS_NAME_MISMATCH');
    }
  }
  check(!realNames.some(n => n.startsWith('BOOT-INF/classes/static/') &&
    n.toLowerCase().endsWith('.html') && n !== APP+'consultation.html'), 'WRONG_ENTRY');
  const html = member(entries,APP+'consultation.html').toString('utf8');
  const assetsBytes = member(entries,APP+'consultation-product-assets.json');
  const assets = JSON.parse(assetsBytes.toString('utf8'));
  check(assets.contract === 'CONSULTATION_PRODUCT_BUNDLE/v1' && assets.entry === 'consultation.html' &&
    Array.isArray(assets.files) && assets.files.length > 1, 'BAD_ASSETS_MANIFEST');
  const refs = new Set();
  const files = assets.files.map(f => {
    relativePath(f.path);
    check(!refs.has(f.path) && f.path !== 'consultation-product-assets.json' && HEX.test(f.sha256), 'BAD_ASSETS_MANIFEST');
    refs.add(f.path);
    check(f.path === 'consultation.html' || f.path.startsWith('assets/'), 'WRONG_ENTRY');
    const data = member(entries,APP+f.path);
    check(sha(data) === f.sha256 && data.length === f.bytes, 'ASSET_HASH_DRIFT');
    return fileRef(APP+f.path,data);
  });
  const packaged = realNames.filter(n => n.startsWith(APP) && !n.endsWith('/') && n !== APP+'consultation-product-assets.json').map(n=>n.slice(APP.length)).sort();
  check(JSON.stringify([...refs].sort()) === JSON.stringify(packaged), 'ASSET_COVERAGE_DRIFT');
  const links = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)].map(m=>m[1]);
  check(links.some(n=>n.endsWith('.js')), 'BUILT_MODULE_MISSING');
  for (const link of links) {
    check(link.startsWith('/app/assets/') && !/[?#%\\]/.test(link), 'BAD_ASSET_LINK');
    check(refs.has(link.slice('/app/'.length)), 'MISSING_ASSET_LINK');
  }
  return { artifact:fileRef(jarRef,bytes), directorySha256:sha(JSON.stringify(realNames)),
    entry:fileRef(APP+'consultation.html',member(entries,APP+'consultation.html')),
    assetsManifest:fileRef(APP+'consultation-product-assets.json',assetsBytes),
    classes:requiredClasses.map(name=>fileRef(name,member(entries,name))), assets:files,
    tool:{ path:executable, sha256:sha(fs.readFileSync(executable)) },
    ...(classParser ? {classParser} : {}),
    ...(suppliedEntries ? { suppliedEntries } : {}) };
}
function inspectMigrations(root, generation = 'FIXED_A_V1') {
  sourceGeneration(generation);
  const extra = generation === LINEAGE ? LINEAGE_FORWARD : generation === CURRENT ? CURRENT_FORWARD : generation === SUCCESSOR ? SUCCESSOR_FORWARD : [];
  const generationReverse = generation === LINEAGE ? LINEAGE_REVERSE : generation === CURRENT ? CURRENT_REVERSE : generation === SUCCESSOR ? SUCCESSOR_REVERSE : [];
  const forward = [...FORWARD,...extra].map(pin => {
    const bytes = read(root,pin.path);
    check(sha(bytes) === pin.sha256, 'MIGRATION_HASH_DRIFT');
    return { ...pin, dependencies:[...pin.dependencies] };
  });
  const reverse = [...REVERSE,...generationReverse].map(pin => {
    const bytes = read(root,pin.path);
    check(sha(bytes) === pin.sha256, 'ROLLBACK_HASH_DRIFT');
    const sql = bytes.toString('utf8').replace(/--[^\r\n]*/g,'').replace(/\/\*[\s\S]*?\*\//g,'').trim();
    if (pin.historyTable) {
      const table=pin.historyTable.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
      check(new RegExp('^DECLARE\\s+history_count\\s+BIGINT\\s*;[\\s\\S]*?SELECT\\s+COUNT\\(\\*\\)\\s+INTO\\s+history_count\\s+FROM\\s+'+table+'\\s*;[\\s\\S]*?IF\\s+history_count\\s*>\\s*0\\s+THEN[\\s\\S]*?RAISE_APPLICATION_ERROR\\s*\\([\\s\\S]*?END\\s+IF\\s*;[\\s\\S]*?END\\s*;\\s*\\/[\\s\\S]*?'+pin.emptyStatement.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'\\s*;?\\s*$','i').test(sql),'ROLLBACK_NOT_REFUSED_FIRST');
      const {historyTable,emptyStatement,...publicPin}=pin;
      return { ...publicPin, firstStepRef:historyTable, nonemptyRollback:'REFUSED_BY_HISTORY_GUARD_NOT_EXECUTED' };
    }
    const statements = sql.split(';').map(s=>s.trim()).filter(Boolean);
    const first = /^ALTER\s+TABLE\s+(\w+)\s+ADD\s+CONSTRAINT\s+\w+\s+CHECK\s*\(\s*1\s*=\s*0\s*\)$/i.exec(statements[0]);
    check(first, 'ROLLBACK_NOT_REFUSED_FIRST');
    return { ...pin, firstStepRef:first[1], nonemptyRollback:'REFUSED_BY_FIRST_STATEMENT_NOT_EXECUTED' };
  });
  return { forward, reverse:reverse.reverse(), formalStatus:'NOT_VERIFIED', databaseState:'NOT_INSPECTED_OR_APPLIED' };
}

/** Exact local script inventory only: no database inspection, execution, or release manifest. */
export function inspectReleaseMigrations({workspace = process.cwd(), generation = 'FIXED_A_V1'} = {}) {
  return {scope:'MIGRATION_INVENTORY_ONLY_NOT_RELEASE_APPROVAL',generation,
    ...inspectMigrations(fs.realpathSync(workspace),generation)};
}

export function verifyRelease({ workspace = process.cwd(), jar, jarSha256, before, after, entries, javaHome = process.env.JAVA_HOME,
  output = OUTPUT_ROOT+'manifest.json', generation = 'FIXED_A_V1' }) {
  const successor = sourceGeneration(generation);
  const requiredClasses = generation === LINEAGE ? LINEAGE_CLASSES : generation === CURRENT ? CURRENT_CLASSES : successor ? SUCCESSOR_CLASSES : REQUIRED_CLASSES;
  const root = fs.realpathSync(workspace);
  relativePath(output);
  check(output.startsWith(OUTPUT_ROOT) && output.endsWith('.json'), 'BAD_OUTPUT_PATH');
  const destination = resolveSafe(root,output,true);
  // Evidence is immutable: refuse any existing output without touching its bytes.
  check(!fs.existsSync(destination), 'EVIDENCE_OUTPUT_EXISTS');
  let temporary;
  try {
    const snapshots = verifySnapshots(root,before,after,generation);
    const migrations = inspectMigrations(root, generation);
    const artifact = inspectJar(root,jar,jarSha256,javaHome,entries,requiredClasses);
    const manifest = { contract:'CONSULTATION_COMPARISON_ENGINEERING_RELEASE/v1',
      task:successor ? 'REL-CON-START-SUCCESSOR-BUNDLE-01' : 'PC-COMPARISON-RELEASE-PACKAGE-01', generation, scope:'OFFLINE_ENGINEERING_ONLY', snapshots, artifact, migrations,
      exclusions:['BUILD','DEPLOYMENT','SQL_EXECUTION','DM8_STATE','BUSINESS_RESULTS','SOURCE_RIGHTS','MODEL_APPROVAL','PROFESSIONAL_ACCEPTANCE'] };
    // Recheck bytes after external jar tf and before publishing.
    check(JSON.stringify(snapshots) === JSON.stringify(verifySnapshots(root,before,after,generation)), 'SNAPSHOT_CHANGED');
    check(sha(read(root,jar)) === artifact.artifact.sha256, 'JAR_HASH_DRIFT');
    if (entries) check(sha(read(root,entries)) === artifact.suppliedEntries.sha256, 'JAR_ENTRIES_DRIFT');
    check(JSON.stringify(inspectMigrations(root, generation)) === JSON.stringify(migrations), 'MIGRATION_HASH_DRIFT');
    resolveSafe(root,output,true);
    fs.mkdirSync(path.dirname(destination),{ recursive:true });
    temporary = destination + '.' + crypto.randomUUID() + '.tmp';
    fs.writeFileSync(temporary,JSON.stringify(manifest,null,2)+'\n',{flag:'wx'});
    // Exclusive publication: unlike rename, link cannot replace a concurrently created output.
    try { fs.linkSync(temporary,destination); }
    catch (error) { if (error.code === 'EEXIST') fail('EVIDENCE_OUTPUT_EXISTS'); throw error; }
    // Publication is now committed. Cleanup failure must not turn valid evidence into a failed check.
    try { fs.unlinkSync(temporary); } catch { /* Only this invocation's temporary file remains. */ }
    temporary = undefined;
    return manifest;
  } catch (error) {
    if (temporary && fs.existsSync(temporary)) fs.unlinkSync(temporary);
    throw error;
  }
}
