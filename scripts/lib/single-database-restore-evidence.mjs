import { constants } from 'node:fs';
import { lstat, realpath, mkdir, open } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { TextDecoder } from 'node:util';

const MAX_RAW_BYTES = 32 * 1024 * 1024;
const MAX_REPORT_BYTES = 4 * 1024 * 1024;
const MAX_DIFFERENCES = 10000;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const fail = code => { throw new Error(code); };
const hashPattern = /^[0-9a-f]{64}$/;
const codePattern = /^[A-Z0-9_]{1,80}$/;
const catalogIdPattern = /^[1-9][0-9]{0,29}$/;
// JSON-pointer keys only: no object names, account names, SQL, business text or filesystem paths.
// Static field keys from current-profile-full-inventory51 array records/column labels; never dictionary values.
const pathKeys = new Set(`schemas accounts errors complete nativePrivileges restoreCatalog categories columns rows objects constraints foreignKeys indexes indexColumns checkConstraints constraintColumns sequences views triggers dependencies jdbcColumns nativeSchemaObjects name type schema count contentSha256 digestMethod account exists systemPrivileges roleGrants tableGrants columnGrants effectiveGrants subject system roles tables definitions PUBLIC SVI SOI format profileId databaseVersion nativeObjects nativeConstraints nativeIndexes hiddenColumns hiddenTexts hiddenTableCounts hiddenDependencyCount hiddenObjectInfoCount viewDefinitions indexExpressions label jdbcType typeName CRTDATE ID INFO1 INFO2 INFO3 INFO4 INFO5 INFO6 INFO7 INFO8 NAME PID SCHEMA_NAME SCHID SUBTYPE$ TYPE$ VALID VERSION CHECKINFO COLID CONSTRAINT_NAME FACTION FINDEXID INDEXID TABLEID TRIGID FLAG INDEX_ID INDEX_NAME ISUNIQUE KEYNUM TABLE_ID TABLE_NAME XTYPE DEFVAL LENGTH$ NULLABLE$ SCALE SEQNO TXT ROW_COUNT OWNER TEXT VIEW_NAME COLUMN_EXPRESSION COLUMN_POSITION INDEX_OWNER TABLE_OWNER PRIVILEGE ADMIN_OPTION GRANTABLE GRANTEE USERNAME ACCOUNT_STATUS GRANTED_ROLE TABLE_SCHEM FKTABLE_SCHEM FKTABLE_NAME FKCOLUMN_NAME PKTABLE_SCHEM PKTABLE_NAME PKCOLUMN_NAME KEY_SEQ UPDATE_RULE DELETE_RULE FK_NAME PK_NAME DEFERRABILITY binaryBase64 AVG_COL_LEN AVG_DATA_BLOCKS_PER_KEY AVG_LEAF_BLOCKS_PER_KEY AVG_ROW_LEN AVG_SPACE AVG_SPACE_FREELIST_BLOCKS BACKED_UP BAD BLEVEL BLOCKS BUFFER_LENGTH BUFFER_POOL CACHE CELL_FLASH_CACHE CHAIN_CNT CHARACTER_SET_NAME CHAR_COL_DECL_LENGTH CHAR_LENGTH CHAR_OCTET_LENGTH CHAR_USED CLUSTERING_FACTOR CLUSTER_NAME CLUSTER_OWNER COLUMN_DEF COLUMN_ID COLUMN_LENGTH COLUMN_NAME COLUMN_SIZE COMPRESSION COMPRESS_FOR CONSTRAINT_TYPE DATA_DEFAULT DATA_LENGTH DATA_PRECISION DATA_SCALE DATA_TYPE DATA_TYPE_MOD DATA_TYPE_OWNER DATA_UPGRADED DECIMAL_DIGITS DEFAULT_LENGTH DEFAULT_ROLE DEFERRABLE DEFERRED DEGREE DENSITY DEPENDENCIES DESCEND DISTINCT_KEYS DOMIDX_MANAGEMENT DOMIDX_OPSTATUS DOMIDX_STATUS DROPPED DURATION EMPTY_BLOCKS FKTABLE_CAT FLASH_CACHE FREELISTS FREELIST_GROUPS FUNCIDX_STATUS GENERATED GLOBAL_STATS GRANTOR HIERARCHY HIGH_VALUE HISTOGRAM INCLUDE_COLUMN INDEX_TYPE INITIAL_EXTENT INI_TRANS INSTANCES INVALID IOT_NAME IOT_REDUNDANT_PKEY_ELIM IOT_TYPE IS_AUTOINCREMENT IS_GENERATEDCOLUMN IS_NULLABLE ITYP_NAME ITYP_OWNER JOIN_INDEX LAST_ANALYZED LAST_CHANGE LEAF_BLOCKS LOGGING LONG_ROW LOW_VALUE MAX_EXTENTS MAX_TRANS MIN_EXTENTS MONITORING NESTED NEXT_EXTENT NULLABLE NUM_BUCKETS NUM_DISTINCT NUM_FREELIST_BLOCKS NUM_NULLS NUM_PREC_RADIX NUM_ROWS OBJECT_NAME OBJECT_TYPE ORDINAL_POSITION PARAMETERS PARTITIONED PCT_DIRECT_ACCESS PCT_FREE PCT_INCREASE PCT_THRESHOLD PCT_USED PKTABLE_CAT POSITION PREFIX_LENGTH READ_ONLY RELY REMARKS RESULT_CACHE ROW_MOVEMENT R_CONSTRAINT_NAME R_OWNER SAMPLE_SIZE SCOPE_CATALOG SCOPE_SCHEMA SCOPE_TABLE SEARCH_CONDITION SECONDARY SEGMENT_CREATED SKIP_CORRUPT SOURCE_DATA_TYPE SQL_DATA_TYPE SQL_DATETIME_SUB STATUS TABLESPACE_NAME TABLE_CAT TABLE_LOCK TABLE_TYPE TEMPORARY TYPE_NAME UNIQUENESS USER_STATS V80_FMT_IMAGE VALIDATED VIEW_RELATED VISIBILITY constraint effectiveGrantSubjects from jdbcPresent nativeConstraintId orderedColumns ordinal referencedConstraintType table to`.split(/\s+/));
const safePath = value => typeof value === 'string' && value.length <= 512 &&
  (value === '/' || (value.startsWith('/') && value.slice(1).split('/').every(segment =>
    pathKeys.has(segment) || /^(?:0|[1-9][0-9]{0,4})$/.test(segment))));
const safeCount = value => Number.isSafeInteger(value) && value >= 0;
// Read data properties only; never invoke getters or toJSON supplied by compare.
function ownValue(object, key) {
  const descriptor = Object.getOwnPropertyDescriptor(object, key);
  if (!descriptor) return undefined;
  if (!Object.hasOwn(descriptor, 'value')) fail('RESTORE_EVIDENCE_REPORT_INVALID');
  return descriptor.value;
}
function safeDifference(item) {
  if (!item || typeof item !== 'object' || Array.isArray(item)) return null;
  const pointer = ownValue(item, 'path');
  if (!safePath(pointer)) return null;
  const out = { path: pointer };
  for (const key of ['hash', 'sha256', 'sourceSha256', 'destinationSha256', 'targetSha256']) {
    const value = ownValue(item, key);
    if (typeof value === 'string' && hashPattern.test(value)) out[key] = value;
  }
  for (const key of ['count', 'sourceCount', 'destinationCount']) {
    const value = ownValue(item, key);
    if (safeCount(value)) out[key] = value;
  }
  return out;
}
function exactKeys(value, expected) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Reflect.ownKeys(value).length !== expected.length ||
      Reflect.ownKeys(value).some(key => !expected.includes(key))) fail('RESTORE_EVIDENCE_REPORT_INVALID');
}
function projectTransforms(value) {
  exactKeys(value, ['targetIdentity', 'generatedNames', 'objectIds', 'selfReferenceGraphs', 'constraintCreationTimes']);
  const targetIdentity = ownValue(value, 'targetIdentity');
  if (typeof targetIdentity !== 'boolean') fail('RESTORE_EVIDENCE_REPORT_INVALID');
  const out = { targetIdentity };
  for (const key of ['generatedNames', 'objectIds', 'selfReferenceGraphs']) {
    const count = ownValue(value, key);
    if (!safeCount(count)) fail('RESTORE_EVIDENCE_REPORT_INVALID');
    out[key] = count;
  }
  const times = ownValue(value, 'constraintCreationTimes');
  if (!Array.isArray(times) || times.length > MAX_DIFFERENCES) fail('RESTORE_EVIDENCE_REPORT_INVALID');
  out.constraintCreationTimes = [];
  for (let i = 0; i < times.length; i++) {
    const item = ownValue(times, String(i));
    exactKeys(item, ['identitySha256', 'sourceSha256', 'targetSha256']);
    const projected = {};
    for (const key of ['identitySha256', 'sourceSha256', 'targetSha256']) {
      const digest = ownValue(item, key);
      if (typeof digest !== 'string' || !hashPattern.test(digest)) fail('RESTORE_EVIDENCE_REPORT_INVALID');
      projected[key] = digest;
    }
    out.constraintCreationTimes.push(projected);
  }
  return out;
}
function projectReport(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail('RESTORE_EVIDENCE_REPORT_INVALID');
  const passed = ownValue(value, 'passed'), code = ownValue(value, 'code');
  if (typeof passed !== 'boolean' || (code !== undefined && (typeof code !== 'string' || !codePattern.test(code))))
    fail('RESTORE_EVIDENCE_REPORT_INVALID');
  const extra = {};
  const sourceInventorySha256 = ownValue(value, 'sourceInventorySha256');
  const targetInventorySha256 = ownValue(value, 'targetInventorySha256');
  if (sourceInventorySha256 !== undefined || targetInventorySha256 !== undefined) {
    if (typeof sourceInventorySha256 !== 'string' || !hashPattern.test(sourceInventorySha256) ||
        typeof targetInventorySha256 !== 'string' || !hashPattern.test(targetInventorySha256))
      fail('RESTORE_EVIDENCE_REPORT_INVALID');
    extra.sourceInventorySha256 = sourceInventorySha256; extra.targetInventorySha256 = targetInventorySha256;
  }
  const transforms = ownValue(value, 'transforms');
  if (transforms !== undefined) extra.transforms = projectTransforms(transforms);
  const supplied = ownValue(value, 'differences');
  if (supplied !== undefined && (!Array.isArray(supplied) || supplied.length > MAX_DIFFERENCES))
    fail('RESTORE_EVIDENCE_REPORT_INVALID');
  const differences = []; let redactedDifferenceCount = 0;
  for (let i = 0; i < (supplied?.length ?? 0); i++) {
    const safe = safeDifference(ownValue(supplied, String(i)));
    if (safe) differences.push(safe); else redactedDifferenceCount++;
  }
  // Preserve complete physical-catalog ID mappings; never serialize unknown keys or names.
  const mapping = ownValue(value, 'mappingEvidence');
  if (mapping !== undefined && (!Array.isArray(mapping) || mapping.length > MAX_DIFFERENCES))
    fail('RESTORE_EVIDENCE_REPORT_INVALID');
  const mappingEvidence = []; let redactedMappingCount = 0;
  const sourceIds = new Set(), targetIds = new Set();
  for (let i = 0; i < (mapping?.length ?? 0); i++) {
    const item = ownValue(mapping, String(i));
    if (!item || typeof item !== 'object' || Array.isArray(item)) { redactedMappingCount++; continue; }
    const sourceId = ownValue(item, 'sourceId'), targetId = ownValue(item, 'targetId');
    const identitySha256 = ownValue(item, 'identitySha256');
    if (sourceId !== undefined || targetId !== undefined || identitySha256 !== undefined) {
      if (typeof sourceId !== 'string' || !catalogIdPattern.test(sourceId) ||
          typeof targetId !== 'string' || !catalogIdPattern.test(targetId) ||
          typeof identitySha256 !== 'string' || !hashPattern.test(identitySha256) ||
          sourceIds.has(sourceId) || targetIds.has(targetId)) fail('RESTORE_EVIDENCE_REPORT_INVALID');
      sourceIds.add(sourceId); targetIds.add(targetId);
      mappingEvidence.push({ sourceId, targetId, identitySha256 });
    } else {
      // Compatibility for the original hash-only evidence projection.
      const digest = ownValue(item, 'sha256');
      if (typeof digest === 'string' && hashPattern.test(digest)) mappingEvidence.push({ sha256: digest });
      else redactedMappingCount++;
    }
  }
  return { passed, code: code ?? (passed ? 'RESTORE_COMPARISON_PASSED' : 'RESTORE_COMPARISON_FAILED'),
    differenceCount: supplied?.length ?? 0, differences, redactedDifferenceCount,
    mappingEvidence, redactedMappingCount, ...extra };
}
function compareFailureCode(error) {
  try {
    if (!(error instanceof Error)) return 'RESTORE_EVIDENCE_COMPARE_FAILED';
    const descriptor = Object.getOwnPropertyDescriptor(error, 'message');
    if (descriptor && Object.hasOwn(descriptor, 'value') && typeof descriptor.value === 'string' &&
        codePattern.test(descriptor.value)) return descriptor.value;
  } catch { /* Untrusted accessors/proxies are never copied into evidence. */ }
  return 'RESTORE_EVIDENCE_COMPARE_FAILED';
}
function sameIdentity(a, b) { return a.dev === b.dev && a.ino === b.ino; }
async function ordinaryExactDirectory(directory) {
  // Walk every ancestor: realpath equality alone misses some link/junction alias cases.
  for (let current = directory; ; current = path.dirname(current)) {
    const stat = await lstat(current);
    if (!stat.isDirectory() || stat.isSymbolicLink() || await realpath(current) !== current)
      fail('RESTORE_EVIDENCE_DIRECTORY_REFUSED');
    if (path.dirname(current) === current) break;
  }
  return lstat(directory);
}
async function exclusiveSyncedWrite(file, bytes, check) {
  await check();
  const flags = constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | (constants.O_NOFOLLOW ?? 0);
  const handle = await open(file, flags, 0o600);
  try {
    if (!(await handle.stat()).isFile()) fail('RESTORE_EVIDENCE_IO_FAILED');
    await handle.writeFile(bytes); await handle.sync();
    await check();
  } finally { await handle.close(); }
}
async function verifySavedRaw(file, expectedBytes, expectedSha, check) {
  await check();
  const handle = await open(file, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size !== expectedBytes) fail('RESTORE_EVIDENCE_IO_FAILED');
    const digest = createHash('sha256'), chunk = Buffer.alloc(64 * 1024); let total = 0;
    while (total < expectedBytes) {
      const { bytesRead } = await handle.read(chunk, 0, Math.min(chunk.length, expectedBytes - total), total);
      if (bytesRead === 0) fail('RESTORE_EVIDENCE_IO_FAILED');
      digest.update(chunk.subarray(0, bytesRead)); total += bytesRead;
    }
    if ((await handle.read(chunk, 0, 1, total)).bytesRead !== 0 ||
        (await handle.stat()).size !== expectedBytes || digest.digest('hex') !== expectedSha)
      fail('RESTORE_EVIDENCE_IO_FAILED');
    await check();
  } finally { await handle.close(); }
}
/** Preserve raw bytes before comparison. No console output, secret handling or destructive cleanup.
 * Callers must control the parent directory against hostile concurrent renames: Node does not expose
 * portable dirfd-relative creation on Windows. Recheck realpaths/identities around each write.
 * Complete receipt means three files were saved, not that recovery equivalence passed.
 */
export async function writeRestoreComparisonEvidence({ directory, sourceBytes, destinationBytes, compare } = {}) {
  if (typeof directory !== 'string' || !path.isAbsolute(directory) || path.resolve(directory) !== directory ||
      !Buffer.isBuffer(sourceBytes) || !Buffer.isBuffer(destinationBytes) || typeof compare !== 'function')
    fail('RESTORE_EVIDENCE_INPUT_INVALID');
  if (sourceBytes.length > MAX_RAW_BYTES || destinationBytes.length > MAX_RAW_BYTES)
    fail('RESTORE_EVIDENCE_RAW_LIMIT');
  // Snapshot caller buffers before the first await; later mutations cannot alter the saved originals/SHA.
  const sourceRaw = Buffer.from(sourceBytes), destinationRaw = Buffer.from(destinationBytes);
  const parent = path.dirname(directory);
  let parentIdentity, identity;
  try {
    parentIdentity = await ordinaryExactDirectory(parent);
    await mkdir(directory, { recursive: false, mode: 0o700 });
    identity = await ordinaryExactDirectory(directory);
  } catch { fail('RESTORE_EVIDENCE_DIRECTORY_REFUSED'); }
  const check = async () => {
    if (!sameIdentity(parentIdentity, await ordinaryExactDirectory(parent)) ||
        !sameIdentity(identity, await ordinaryExactDirectory(directory))) fail('RESTORE_EVIDENCE_IO_FAILED');
  };
  const sourcePath = path.join(directory, 'source-inventory.json');
  const destinationPath = path.join(directory, 'target-inventory.json');
  const comparisonPath = path.join(directory, 'comparison.json');
  try {
    await exclusiveSyncedWrite(sourcePath, sourceRaw, check);
    await exclusiveSyncedWrite(destinationPath, destinationRaw, check);
  } catch { fail('RESTORE_EVIDENCE_IO_FAILED'); }
  const sourceSha256 = sha256(sourceRaw), destinationSha256 = sha256(destinationRaw);
  let projected, source, destination;
  try {
    const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
    source = JSON.parse(decoder.decode(sourceRaw)); destination = JSON.parse(decoder.decode(destinationRaw));
  } catch { projected = { passed: false, code: 'RESTORE_EVIDENCE_JSON_INVALID' }; }
  if (!projected) {
    let compared;
    try { compared = await compare({ source, destination }); }
    catch (error) { projected = { passed: false, code: compareFailureCode(error) }; }
    if (!projected) {
      try { projected = projectReport(compared); }
      catch { projected = { passed: false, code: 'RESTORE_EVIDENCE_REPORT_INVALID' }; }
    }
  }
  const report = { format: 'SINGLE_DATABASE_RESTORE_COMPARISON_EVIDENCE/v1', sourceSha256, destinationSha256,
    sourceBytes: sourceRaw.length, destinationBytes: destinationRaw.length, ...projected, actualNativeRestoreProven: false };
  let reportBytes;
  try {
    reportBytes = Buffer.from(JSON.stringify(report) + '\n');
    if (reportBytes.length > MAX_REPORT_BYTES) fail('RESTORE_EVIDENCE_REPORT_LIMIT');
    // Refuse a complete receipt if a callback/other writer altered either saved raw.
    await verifySavedRaw(sourcePath, sourceRaw.length, sourceSha256, check);
    await verifySavedRaw(destinationPath, destinationRaw.length, destinationSha256, check);
    await exclusiveSyncedWrite(comparisonPath, reportBytes, check);
  } catch { fail('RESTORE_EVIDENCE_IO_FAILED'); }
  return { format: 'SINGLE_DATABASE_RESTORE_COMPARISON_EVIDENCE_RECEIPT/v1', complete: true,
    passed: report.passed, code: report.code, actualNativeRestoreProven: false, directory, sourcePath, destinationPath, comparisonPath,
    sourceSha256, destinationSha256, reportSha256: sha256(reportBytes) };
}
