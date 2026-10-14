import { readFile, lstat, realpath } from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';

export const PRIMARY_TARGET_ID = 'f3dfa7aa1911c3d59e4907acdaa478683d89cfb4c2a1c3c40a60fa297aef064f';
const PINS = Object.freeze({
  forward: Object.freeze({ path: 'db/dm8/V107__product_activation_recovery.sql', sha256: '1b36de0c01ad6b5fd1bba76a0a4e43f3050974d1f66101f41d2cb90cbf042cde' }),
  reverse: Object.freeze({ path: 'db/dm8/U107__product_activation_recovery.sql', sha256: '858ce5557dfbe449485abc85f91a1e47b89aff2d3083aec691e9118cb0e72fb6' })
});
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const fail = code => { throw new Error(code); };
const SHA = /^[a-f0-9]{64}$/;
const COUNT = /^(0|[1-9][0-9]{0,18})$/;
const MAX_COUNT = 9223372036854775807n;
function exactKeys(o, names) {
  if (!o || typeof o !== 'object' || Array.isArray(o) || Object.keys(o).length !== names.length || names.some(k => !Object.hasOwn(o,k))) fail('EXACT_OBSERVATION_FIELDS_REQUIRED');
}
function count(value) {
  if (typeof value !== 'string' || !COUNT.test(value) || BigInt(value) > MAX_COUNT) fail('DECIMAL_BIGINT_COUNT_REQUIRED');
  return BigInt(value);
}
/** Fixed V107/U107 only. Returns identifiers/digests, never an executable SQL batch. */
async function controlledStatements(workspaceRoot) {
  const root = path.resolve(workspaceRoot);
  if (await realpath(root) !== root) fail('WORKSPACE_REDIRECT_REJECTED');
  const bytes = {};
  for (const [kind,pin] of Object.entries(PINS)) {
    for (const rel of ['db','db/dm8',pin.path]) {
      const p=path.join(root,...rel.split('/'));
      if ((await lstat(p)).isSymbolicLink() || await realpath(p) !== p) fail('SQL_REDIRECT_REJECTED');
    }
    const p=path.join(root,...pin.path.split('/'));
    if (!(await lstat(p)).isFile()) fail('ORIGINAL_SQL_FILE_REQUIRED');
    bytes[kind]=await readFile(p);
    if (hash(bytes[kind]) !== pin.sha256) fail('CONTROLLED_SQL_SHA_MISMATCH');
  }
  const forward=bytes.forward.toString('utf8').trim().split(';').filter(s => s.trim()).map(s => s.trim()+';');
  if (forward.length !== 3 || !forward[0].startsWith('ALTER TABLE id2_subject_event DROP CONSTRAINT ') || !forward[1].startsWith('ALTER TABLE id2_subject_event ADD CONSTRAINT ') || !forward[2].startsWith('CREATE TABLE id2_activation_recovery_receipt ')) fail('FORWARD_LAYOUT_UNSUPPORTED');
  const inverse=bytes.reverse.toString('utf8');
  const separator=inverse.indexOf('\n/\n');
  if (separator < 0 || inverse.indexOf('\n/\n',separator+3) >= 0) fail('REVERSE_BLOCK_LAYOUT_UNSUPPORTED');
  const block=inverse.slice(inverse.indexOf('DECLARE'),separator).trim();
  if (!block.startsWith('DECLARE\n') || !block.endsWith('END;') || !block.includes('RAISE_APPLICATION_ERROR(-20001,')) fail('REVERSE_GUARD_LAYOUT_UNSUPPORTED');
  const descriptor=(id,sql) => ({id,sha256:hash(sql)});
  return {
    pins: PINS,
    DROP_OLD_OPERATION:descriptor('DROP_OLD_OPERATION',forward[0]),
    ADD_RECOVERY_OPERATION:descriptor('ADD_RECOVERY_OPERATION',forward[1]),
    CREATE_RECEIPT:descriptor('CREATE_RECEIPT',forward[2]),
    GUARD_REVERSE_REQUIRED:descriptor('GUARD_REVERSE_REQUIRED',block)
  };
}

/** A metadata declaration is NOT authenticated inventory, a fence, or execution permission. */
export async function planConsultationActivationRecoveryMigration(observation, {workspaceRoot}={}) {
  if (typeof workspaceRoot !== 'string' || !workspaceRoot) fail('WORKSPACE_ROOT_REQUIRED');
  exactKeys(observation,['format','targetId','schema','databaseVersion','capturedAt','rawInventorySha256','direction','operationConstraint','receiptStructure','receiptCount','reissueCount','unexpectedOperationCount','lastStatementOutcome']);
  const o=observation;
  if (o.format !== 'CON_ACTIVATION_MIGRATION_OBSERVATION/v1') fail('OBSERVATION_VERSION_REQUIRED');
  if (o.targetId !== PRIMARY_TARGET_ID || o.schema !== 'CONPROD') fail('EXACT_PRIMARY_CONPROD_REQUIRED');
  if (o.databaseVersion !== '8.1.4.170') fail('DATABASE_VERSION_UNSUPPORTED');
  if (typeof o.capturedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(o.capturedAt) || !Number.isFinite(Date.parse(o.capturedAt)) || new Date(o.capturedAt).toISOString() !== o.capturedAt) fail('UTC_OBSERVATION_TIME_REQUIRED');
  if (typeof o.rawInventorySha256 !== 'string' || !SHA.test(o.rawInventorySha256)) fail('RAW_INVENTORY_SHA_REQUIRED');
  if (!['FORWARD','REVERSE'].includes(o.direction) || !['OLD5','RECOVERY6','ABSENT','OTHER'].includes(o.operationConstraint) || !['EXACT','ABSENT','OTHER'].includes(o.receiptStructure) || !['NOT_RUN','KNOWN','UNKNOWN'].includes(o.lastStatementOutcome)) fail('OBSERVATION_STATE_UNSUPPORTED');
  const events=count(o.reissueCount),unexpected=count(o.unexpectedOperationCount);
  const receipts=o.receiptStructure==='ABSENT' ? null : count(o.receiptCount);
  if (o.receiptStructure==='ABSENT' && o.receiptCount!==null) fail('ABSENT_RECEIPT_REQUIRES_NULL_COUNT');
  const statements=await controlledStatements(workspaceRoot);
  const result=(decision,next=null)=>({
    format:'CON_ACTIVATION_MIGRATION_DIAGNOSIS/v1',targetId:o.targetId,schema:o.schema,
    observedAt:o.capturedAt,observationSha256:hash(JSON.stringify(o)),rawInventorySha256:o.rawInventorySha256,
    controlledSql:statements.pins,decision,nextStatement:next,
    metadataTruthProven:false,executionAuthorized:false,migrationExecuted:false,
    requires:['TRUSTED_FRESH_EXACT_INVENTORY','APPROVED_OPERATION_SCOPE','REVIEWED_EXECUTION_PORT','CURRENT_EXCLUSIVE_WRITER_FENCE','CURRENT_PROTECTION_AND_SOURCE_RETURN','DM8_PHYSICAL_ACCEPTANCE']
  });
  // A lost response is not permission to reissue a DDL, even when the old observation looks complete.
  if (o.lastStatementOutcome==='UNKNOWN') return result('REOBSERVE_REQUIRED');
  if (unexpected>0n || o.operationConstraint==='OTHER' || o.receiptStructure==='OTHER') return result('HOLD_UNRECOGNIZED_STRUCTURE_OR_OPERATIONS');
  const history=events>0n || (receipts!==null && receipts>0n);
  if (o.direction==='REVERSE') {
    if (history) return result('COMMITTED_HISTORY_FORWARD_ONLY');
    if (o.operationConstraint==='RECOVERY6' && o.receiptStructure==='EXACT') return result('GUARD_REVERSE_REQUIRED',statements.GUARD_REVERSE_REQUIRED);
    if (o.operationConstraint==='OLD5' && o.receiptStructure==='ABSENT') return result('ORIGINAL_SCHEMA_PRESENT');
    return result('PARTIAL_REVERSE_NOT_ADMITTED');
  }
  if (o.operationConstraint==='RECOVERY6' && o.receiptStructure==='EXACT') return result('SCHEMA_READY_NOT_RUNTIME_READY');
  // History with a missing receipt cannot be repaired by silently inventing an empty historical table.
  if (o.receiptStructure==='ABSENT' && history) return result('HOLD_HISTORY_WITH_MISSING_RECEIPT');
  if (o.operationConstraint==='ABSENT') return result('ADD_RECOVERY_OPERATION',statements.ADD_RECOVERY_OPERATION);
  if (o.operationConstraint==='RECOVERY6' && o.receiptStructure==='ABSENT') return result('CREATE_RECEIPT',statements.CREATE_RECEIPT);
  if (o.operationConstraint==='OLD5' && o.receiptStructure==='ABSENT') return result('DROP_OLD_OPERATION',statements.DROP_OLD_OPERATION);
  return result('HOLD_INCONSISTENT_SCHEMA');
}
