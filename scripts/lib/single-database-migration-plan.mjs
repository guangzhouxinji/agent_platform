import { createHash } from 'node:crypto';
import { readFile, realpath, lstat } from 'node:fs/promises';
import path from 'node:path';

// ADR single-database-recovery-assessment-20261007 §9.7: offline planning only.
const DOMAINS = ['consultation', 'enforcement', 'adjudication'];
const IDENT = /^[A-Z][A-Z0-9_]{0,63}$/;
const SHA = /^[a-f0-9]{64}$/;
const TOKEN = /^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,255}$/;
const fail = code => { throw new Error(code); };
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const keys = (value, required, optional = []) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)
      || required.some(k => !Object.hasOwn(value, k))
      || Object.keys(value).some(k => !required.includes(k) && !optional.includes(k))) fail('INVALID_FIELDS');
};
const list = value => { if (!Array.isArray(value)) fail('ARRAY_REQUIRED'); return value; };
const unique = values => { if (new Set(values).size !== values.length) fail('DUPLICATE_ENTRY'); };
const identifier = value => { if (typeof value !== 'string' || !IDENT.test(value)) fail('INVALID_IDENTIFIER'); };
const digest = value => { if (typeof value !== 'string' || !SHA.test(value)) fail('INVALID_SHA256'); };
const token = value => { if (typeof value !== 'string' || !TOKEN.test(value) || value.includes('..')) fail('INVALID_REFERENCE'); };
function evidence(value) {
  keys(value, ['reference', 'sha256']); token(value.reference); digest(value.sha256);
}
function ref(value) {
  keys(value, ['schema', 'id']); identifier(value.schema); token(value.id);
  return `${value.schema}:${value.id}`;
}
function objects(value) {
  list(value).forEach(identifier); unique(value); return value;
}

async function originalSql(root, relative, expectedSha) {
  // No URLs, globs, aliases, traversal or symlink-based redirection; read SQL bytes only.
  if (typeof relative !== 'string' || !/^db\/dm8\/V[0-9]{3,}__[A-Za-z0-9_-]+\.sql$/.test(relative)) fail('EXACT_DM8_SQL_PATH_REQUIRED');
  const absoluteRoot = await realpath(root);
  const absolute = path.resolve(absoluteRoot, ...relative.split('/'));
  for (const component of ['db', 'db/dm8', relative]) {
    const stat = await lstat(path.resolve(absoluteRoot, ...component.split('/')));
    if (stat.isSymbolicLink()) fail('SQL_SYMLINK_REJECTED');
  }
  if (await realpath(absolute) !== absolute || !(await lstat(absolute)).isFile()) fail('SQL_NOT_ORIGINAL_FILE');
  const bytes = await readFile(absolute);
  if (hash(bytes) !== expectedSha) fail('SQL_SHA_MISMATCH');
}

/** Strict metadata-only input. Evidence hashes are references, NOT proof of their truth or approval.
 * Inventory must enumerate ALL objects (not only tables) in each schema. No live introspection here.
 */
export async function createSingleDatabaseMigrationPlan(input, { workspaceRoot, expectedTargetId } = {}) {
  keys(input, ['format', 'targetId', 'domains', 'inventory', 'migrations', 'applied']);
  if (input.format !== 'SINGLE_DM8_MIGRATION_INPUT/v1') fail('INPUT_VERSION_REQUIRED');
  digest(input.targetId); digest(expectedTargetId);
  if (input.targetId !== expectedTargetId) fail('TARGET_ID_MISMATCH');
  if (typeof workspaceRoot !== 'string' || !workspaceRoot) fail('WORKSPACE_ROOT_REQUIRED');
  const bindings = new Map();
  if (list(input.domains).length !== 3) fail('THREE_DOMAINS_REQUIRED');
  unique(input.domains.map(d => d.domain));
  for (const d of input.domains) {
    keys(d, ['domain', 'businessSchema', 'authoritySchema']);
    if (!DOMAINS.includes(d.domain)) fail('UNKNOWN_DOMAIN');
    for (const kind of ['business', 'authority']) {
      const schema = d[`${kind}Schema`]; identifier(schema);
      if (bindings.has(schema)) fail('DUPLICATE_SCHEMA');
      bindings.set(schema, { domain: d.domain, kind });
    }
  }
  const inventories = new Map();
  if (list(input.inventory).length !== 6) fail('SIX_INVENTORIES_REQUIRED');
  for (const i of input.inventory) {
    keys(i, ['schema', 'targetId', 'objects', 'evidence']);
    if (!bindings.has(i.schema) || inventories.has(i.schema)) fail('INVENTORY_SCHEMA_MISMATCH');
    if (i.targetId !== input.targetId) fail('INVENTORY_TARGET_MISMATCH');
    objects(i.objects); evidence(i.evidence); inventories.set(i.schema, i);
  }
  const migrations = new Map();
  for (const m of list(input.migrations)) {
    keys(m, ['schema', 'id', 'domain', 'kind', 'sqlPath', 'sha256', 'dependsOn', 'objects'], ['prechecks', 'nonDestructiveReview']);
    const key = ref({ schema: m.schema, id: m.id });
    if (migrations.has(key)) fail('DUPLICATE_MIGRATION');
    const binding = bindings.get(m.schema);
    if (!binding || binding.domain !== m.domain || binding.kind !== m.kind) fail('MIGRATION_BINDING_MISMATCH');
    digest(m.sha256); objects(m.objects);
    if (!m.objects.length) fail('EXPECTED_OBJECTS_REQUIRED');
    const dependencies = list(m.dependsOn).map(ref); unique(dependencies);
    if (m.nonDestructiveReview !== undefined) evidence(m.nonDestructiveReview);
    if (m.prechecks !== undefined) {
      list(m.prechecks);
      unique(m.prechecks.map(c => c.object));
      for (const c of m.prechecks) {
        keys(c, ['object', 'expected', 'evidence']); identifier(c.object); evidence(c.evidence);
        if (!['present', 'absent'].includes(c.expected)) fail('INVALID_PRECHECK');
        const present = inventories.get(m.schema).objects.includes(c.object);
        if (present !== (c.expected === 'present')) fail('PRECHECK_FAILED');
      }
    }
    migrations.set(key, { source: m, dependencies });
  }
  const applied = new Map();
  for (const a of list(input.applied)) {
    keys(a, ['schema', 'id', 'targetId', 'sha256', 'verifiedObjects', 'objectVerification']);
    const key = ref({ schema: a.schema, id: a.id });
    const migration = migrations.get(key)?.source;
    if (!migration) fail('UNKNOWN_APPLIED_MIGRATION');
    if (applied.has(key)) fail('DUPLICATE_APPLIED_RECORD');
    if (a.targetId !== input.targetId) fail('APPLIED_TARGET_MISMATCH');
    digest(a.sha256); evidence(a.objectVerification); objects(a.verifiedObjects);
    if (a.sha256 !== migration.sha256) fail('APPLIED_SHA_CONFLICT');
    if (a.verifiedObjects.length !== migration.objects.length
        || migration.objects.some(o => !a.verifiedObjects.includes(o)
          || !inventories.get(a.schema).objects.includes(o))) fail('APPLIED_OBJECT_VERIFICATION_MISMATCH');
    applied.set(key, a);
  }
  for (const [schema, inventory] of inventories) {
    const known = new Set([...migrations.values()].filter(m => m.source.schema === schema).flatMap(m => m.source.objects));
    if (inventory.objects.some(o => !known.has(o))) fail('UNKNOWN_OBJECT');
  }
  const sorted = [], visiting = new Set(), visited = new Set();
  function visit(key) {
    if (visiting.has(key)) fail('DEPENDENCY_CYCLE');
    if (visited.has(key)) return;
    const m = migrations.get(key); if (!m) fail('UNKNOWN_DEPENDENCY');
    visiting.add(key);
    for (const dependency of m.dependencies) {
      if (applied.has(key) && !applied.has(dependency)) fail('APPLIED_DEPENDENCY_NOT_APPLIED');
      visit(dependency);
    }
    visiting.delete(key); visited.add(key); sorted.push(key);
  }
  for (const key of migrations.keys()) visit(key);
  for (const [key, { source: m }] of migrations) {
    if (!applied.has(key)) {
      // An existing declared output with no applied record is ambiguous, never infer unapplied.
      const existing = m.objects.filter(o => inventories.get(m.schema).objects.includes(o));
      const logged = new Set([...applied.values()].filter(a => a.schema === m.schema).flatMap(a => a.verifiedObjects));
      if (existing.some(o => !logged.has(o))) fail('UNLOGGED_EXISTING_OBJECT');
      // A forward ALTER may target an already logged object, but never without its explicit check.
      if (existing.some(o => !m.prechecks?.some(c => c.object === o && c.expected === 'present')))
        fail('EXISTING_OBJECT_PRECHECK_REQUIRED');
      if (inventories.get(m.schema).objects.length && (!m.prechecks?.length || !m.nonDestructiveReview)) fail('NONEMPTY_SCHEMA_EVIDENCE_REQUIRED');
    }
    await originalSql(workspaceRoot, m.sqlPath, m.sha256);
  }
  return {
    format: 'SINGLE_DM8_FORWARD_MANIFEST/v1', targetId: input.targetId,
    inputSha256: hash(Buffer.from(JSON.stringify(input))), domains: input.domains,
    pending: sorted.filter(k => !applied.has(k)).map(k => migrations.get(k).source),
    skippedApplied: sorted.filter(k => applied.has(k)).map(k => applied.get(k)),
    inventory: input.inventory,
    migrationAuthorized: false, migrationExecuted: false, fullRecoveryProven: false,
    professionalApprovalVerified: false, operationsApprovalVerified: false,
    limitations: ['Offline metadata and SQL SHA checks only; evidence references are not independently authenticated.',
      'Live complete-object inventory, source rights, protection validity and professional/operations approval remain separate gates.',
      'Does not open initialize-admission, backup, restore or execution; no rollback/DDL atomicity claim.']
  };
}
