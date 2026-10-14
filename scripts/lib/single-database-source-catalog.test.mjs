import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, symlink, link } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createSingleDatabaseSourceCatalog as catalog, extractSqlFacts as sql, extractJavaSqlFacts as java, checkReviewedManifest, readReviewedManifest, writeCatalogExclusive } from './single-database-source-catalog.mjs';
const cli = fileURLToPath(new URL('../catalog-single-database-source.mjs', import.meta.url));
const hash = x => createHash('sha256').update(x).digest('hex');
async function fixture(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'source-catalog-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  for (const dir of ['db/dm8', 'db/identity-authority', 'backend/a/src/main/java', 'backend/a/src/main/resources', 'docs/adr', 'out']) await mkdir(path.join(root, dir), { recursive: true });
  const put = async (rel, value) => { await mkdir(path.dirname(path.join(root, rel)), { recursive: true }); await writeFile(path.join(root, rel), value); };
  await put('db/dm8/V001__original.sql', 'CREATE TABLE thing (id INT PRIMARY KEY, CONSTRAINT uq_thing UNIQUE(id)); CREATE INDEX ix_thing ON thing(id);');
  await put('db/identity-authority/V001__original.sql', 'CREATE TABLE authority (id INT NOT NULL);');
  await put('backend/a/src/main/java/A.java', 'class A { String sql = "SELECT * FROM thing JOIN absent ON thing.id=absent.id"; String password = "DO_NOT_OUTPUT_SECRET"; }');
  await put('docs/adr/design.md', '# Explicit reference only');
  return { root, put };
}
function manifest(c) {
  return { format: 'SINGLE_DATABASE_SOURCE_REVIEW/v1', entries: c.migrations.map(m => ({ path: m.path, sha256: m.sha256, version: m.version, decision: 'selected', reason: 'Explicit technical selection, not approval', designReferences: ['docs/adr/design.md#scope'] })) };
}
const rejects = (promise, code) => assert.rejects(promise, e => e.code === code);

test('DDL table/index/sequence/view and named/unnamed constraints', () => {
  const facts = sql('CREATE TABLE "Mixed" (id INT NOT NULL PRIMARY KEY, CONSTRAINT ck_x CHECK(id>0)); CREATE UNIQUE INDEX ix ON "Mixed"(id); CREATE SEQUENCE seq; CREATE OR REPLACE VIEW vv AS SELECT * FROM "Mixed"; ALTER TABLE "Mixed" ADD CONSTRAINT fk FOREIGN KEY(id) REFERENCES other(id);');
  for (const kind of ['TABLE', 'INDEX', 'SEQUENCE', 'VIEW', 'CONSTRAINT']) assert.ok(facts.objects.some(o => o.kind === kind));
  assert.ok(facts.objects.some(o => o.name === '"Mixed"'));
  assert.equal(facts.objects.filter(o => o.name === 'ck_x'.toUpperCase()).length, 1);
  assert.ok(facts.objects.some(o => o.named === false && o.constraintType === 'PRIMARY'));
  assert.ok(facts.references.some(r => r.name === 'OTHER'));
});
test('comments and data literals cannot invent objects or references', () => {
  const facts = sql("-- CREATE TABLE fake(id INT);\nCREATE TABLE real (v VARCHAR(100) DEFAULT 'FROM hidden CREATE TABLE evil'); /* CREATE VIEW bad AS SELECT * FROM wrong */");
  assert.deepEqual(facts.objects.filter(o => o.kind === 'TABLE').map(o => o.name), ['REAL']); assert.equal(facts.references.length, 0);
});
test('DML, qualified names, comma joins, subqueries and sequences', () => {
  const facts = sql('SELECT s.seq.NEXTVAL FROM s.foo f, bar b JOIN baz z ON z.id=b.id; INSERT INTO target SELECT * FROM (SELECT * FROM nested); UPDATE changed SET id=1; DELETE FROM deleted; MERGE INTO merged USING source ON (1=1); SELECT NEXT VALUE FOR seq FROM DUAL;');
  for (const name of ['S.SEQ', 'S.FOO', 'BAR', 'BAZ', 'TARGET', 'NESTED', 'CHANGED', 'DELETED', 'MERGED', 'SOURCE', 'SEQ']) assert.ok(facts.references.some(r => r.name === name), name);
  assert.ok(!facts.references.some(r => r.name === 'DUAL'));
});
test('CTE aliases are local, base relation remains visible', () => {
  const facts = sql('WITH local AS (SELECT * FROM base), second AS (SELECT * FROM other) SELECT * FROM local JOIN second ON 1=1;');
  assert.deepEqual(facts.references.map(r => r.name), ['BASE', 'OTHER']);
});
test('unsupported DDL and unresolved dynamic SQL are explicit diagnostics', () => {
  const facts = sql("CREATE SYNONYM alias FOR other; EXECUTE IMMEDIATE 'CREATE TABLE generated (id INT)'; SELECT * FROM ${table};");
  for (const code of ['UNSUPPORTED_CREATE', 'DYNAMIC_SQL_NOT_RESOLVED', 'UNRESOLVED_RELATION']) assert.ok(facts.diagnostics.some(d => d.code === code));
});
test('malformed comments/literals diagnosed without body leakage', () => {
  assert.ok(sql('/* secret').diagnostics.some(d => d.code === 'UNTERMINATED_COMMENT'));
  assert.ok(sql("SELECT 'secret").diagnostics.some(d => d.code === 'UNTERMINATED_LITERAL'));
  assert.ok(!JSON.stringify(sql("SELECT 'secret")).includes('secret'));
});
test('Java static concatenation, text blocks, unicode escapes and comments', () => {
  const facts = java('/* "FROM ignored" */ class A { String a = "SELECT * FR" + "OM visible"; String b = """\n SELECT * FROM block_table\n"""; String c = "SELECT * FROM \\u0074able_c"; }');
  for (const name of ['VISIBLE', 'BLOCK_TABLE', 'TABLE_C']) assert.ok(facts.references.some(r => r.name === name));
  assert.ok(!facts.references.some(r => r.name === 'IGNORED')); assert.equal(facts.sqlLiteralCount, 3);
});
test('Java variable concatenations/formats and indirect calls cannot prove completeness', () => {
  const facts = java('String q = "SELECT * FROM " + table; String r = "SELECT * FROM %s".formatted(name); db.prepareStatement(q);');
  assert.ok(facts.diagnostics.some(d => d.code === 'DYNAMIC_SQL_NOT_RESOLVED')); assert.ok(facts.diagnostics.some(d => d.code === 'INDIRECT_SQL_CALL_NOT_RESOLVED'));
});
test('full catalog SHA, versions, providers and unresolved references', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root });
  assert.equal(c.stats.migrations, 2); assert.equal(c.stats.runtimeFiles, 1); assert.equal(c.stats.duplicateVersionGroups, 0);
  assert.equal(c.migrations[0].sha256, hash(await readFile(path.join(root, c.migrations[0].path))));
  assert.equal(c.runtimeFiles[0].references.find(r => r.name === 'THING').coverage, 'LEXICAL_NAME_MATCH_ONLY');
  assert.ok(c.uncoveredReferences.some(r => r.name === 'ABSENT')); assert.equal(c.completeness.proven, false); assert.equal(c.review.productApproval, false);
  assert.ok(!JSON.stringify(c).includes('DO_NOT_OUTPUT_SECRET')); assert.ok(!JSON.stringify(c).includes('class A'));
});
test('excluded paths not read; U history is hash-only, never a provider', async t => {
  const { root, put } = await fixture(t);
  for (const rel of ['backend/a/target/Bad.java', 'backend/evidence/Bad.java', 'backend/requirements/Bad.java', 'backend/a/src/test/java/Bad.java', 'backend/secrets/Bad.java', 'db/dm8/U001__undo.sql']) await put(rel, 'CREATE TABLE never_read (id INT);');
  const c = await catalog({ workspaceRoot: root }); assert.equal(c.stats.runtimeFiles, 1); assert.equal(c.stats.migrations, 2); assert.ok(!JSON.stringify(c).includes('NEVER_READ'));
});
test('YML records hashes but never scalar secrets; SQL block and runtime binding', async t => {
  const { root, put } = await fixture(t);
  await put('backend/a/src/main/resources/application.yml', 'password: DO_NOT_OUTPUT_PASSWORD\nsql: |\n  SELECT * FROM thing\nschema: ${SECRET_SCHEMA}\n');
  const c = await catalog({ workspaceRoot: root }); const f = c.runtimeFiles.find(f => f.kind === 'YML');
  assert.ok(f.references.some(r => r.name === 'THING')); assert.ok(f.diagnostics.some(d => d.code === 'CONFIG_RUNTIME_BINDING_NOT_RESOLVED')); assert.ok(!JSON.stringify(c).includes('DO_NOT_OUTPUT_PASSWORD')); assert.ok(!JSON.stringify(c).includes('SECRET_SCHEMA'));
});
test('backend SQL objects are lexical providers', async t => {
  const { root, put } = await fixture(t); await put('backend/a/src/main/resources/schema.sql', 'CREATE TABLE absent(id INT);');
  const c = await catalog({ workspaceRoot: root }); assert.ok(!c.uncoveredReferences.some(r => r.name === 'ABSENT'));
});
test('schema-qualified references never resolved by an unqualified provider', async t => {
  const { root, put } = await fixture(t); await put('backend/a/src/main/java/B.java', 'String q = "SELECT * FROM other.thing";');
  const c = await catalog({ workspaceRoot: root }); assert.ok(c.uncoveredReferences.some(r => r.name === 'OTHER.THING'));
});
test('duplicate normalized migration versions recorded, not automatically accepted', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/V0001__duplicate.sql', 'CREATE TABLE second(id INT);');
  const c = await catalog({ workspaceRoot: root }); assert.equal(c.duplicateVersions.length, 1); assert.equal(c.duplicateVersions[0].paths.length, 2);
  await rejects(checkReviewedManifest(c, manifest(c), root), 'REVIEW_SELECTED_DUPLICATE_VERSION');
  const m = manifest(c); m.entries.find(e => e.path.includes('duplicate')).decision = 'excluded'; const result = await checkReviewedManifest(c, m, root); assert.equal(result.excluded, 1);
});
test('review requires explicit choice/reason/reference for every original', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifest(c);
  const result = await catalog({ workspaceRoot: root, reviewedManifest: m }); assert.equal(result.review.coverageValidated, true); assert.equal(result.review.productApproval, false);
  m.entries.pop(); await rejects(checkReviewedManifest(c, m, root), 'REVIEW_COMPLETE_COVERAGE_REQUIRED');
});
test('review SHA drift rejected including catalog rerun on changed original', async t => {
  const { root, put } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifest(c);
  await put(c.migrations[0].path, 'CREATE TABLE changed(id INT);'); await rejects(catalog({ workspaceRoot: root, reviewedManifest: m }), 'REVIEW_SHA_CHANGED');
});
test('review new original cannot silently disappear from selection', async t => {
  const { root, put } = await fixture(t); const c = await catalog({ workspaceRoot: root });
  await put('db/dm8/V002__new.sql', 'CREATE SEQUENCE newseq;'); await rejects(catalog({ workspaceRoot: root, reviewedManifest: manifest(c) }), 'REVIEW_COMPLETE_COVERAGE_REQUIRED');
});
for (const [title, mutate, code] of [
  ['duplicate entries', m => { m.entries[1] = { ...m.entries[0] }; }, 'REVIEW_DUPLICATE_PATH'],
  ['unknown original', m => { m.entries[0].path = 'db/dm8/V999__unknown.sql'; }, 'REVIEW_UNKNOWN_SQL'],
  ['version mismatch', m => { m.entries[0].version = 'V999'; }, 'REVIEW_VERSION_CHANGED'],
  ['missing reason', m => { m.entries[0].reason = ' '; }, 'REVIEW_REASON_REQUIRED'],
  ['missing design', m => { m.entries[0].designReferences = []; }, 'REVIEW_DESIGN_REFERENCE_REQUIRED'],
  ['absent design path', m => { m.entries[0].designReferences = ['docs/adr/missing.md#scope']; }, 'ENOENT'],
  ['unsafe design path', m => { m.entries[0].designReferences = ['docs/../secret.md#scope']; }, 'UNSAFE_PATH'],
  ['invalid choice', m => { m.entries[0].decision = 'approved'; }, 'REVIEW_DECISION_REQUIRED'],
  ['unknown fields', m => { m.extra = 'approval'; }, 'INVALID_MANIFEST_FIELDS']
]) test(`review rejects ${title}`, async t => { const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifest(c); mutate(m); await rejects(checkReviewedManifest(c, m, root), code); });
test('selected subset separately reports runtime references excluded from selection', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifest(c); m.entries.forEach(e => { e.decision = 'excluded'; });
  const result = await checkReviewedManifest(c, m, root); assert.equal(result.selected, 0); assert.ok(result.selectedUncoveredReferences.some(r => r.name === 'THING'));
});
test('invalid V original filename rejects rather than silently omitting', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/Vbroken.sql', 'CREATE TABLE lost(id INT);'); await rejects(catalog({ workspaceRoot: root }), 'INVALID_ORIGINAL_MIGRATION_PATH');
});
test('symlinked original file/directory rejected without reading target', async t => {
  const { root } = await fixture(t);
  try { await symlink(path.join(root, 'db/dm8/V001__original.sql'), path.join(root, 'db/dm8/V002__link.sql'), 'file'); }
  catch (e) { if (e.code === 'EPERM') { t.skip('OS denies creating file symlinks'); return; } throw e; }
  await rejects(catalog({ workspaceRoot: root }), 'SYMLINK_REJECTED');
});
test('directory junction rejected on Windows and symlink rejected elsewhere', async t => {
  const { root } = await fixture(t); await symlink(path.join(root, 'backend/a'), path.join(root, 'backend/redirect'), process.platform === 'win32' ? 'junction' : 'dir');
  await rejects(catalog({ workspaceRoot: root }), 'SYMLINK_REJECTED');
});
test('oversize and binary original rejected with bounded codes', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/V002__large.sql', Buffer.alloc(8 * 1024 * 1024 + 1)); await rejects(catalog({ workspaceRoot: root }), 'SOURCE_TOO_LARGE');
  await put('db/dm8/V002__large.sql', Buffer.from([0])); await rejects(catalog({ workspaceRoot: root }), 'BINARY_SOURCE_REJECTED');
});
test('exclusive output refuses overwrite, traversal and source tree', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); await writeCatalogExclusive(root, 'out/catalog.json', c);
  await rejects(writeCatalogExclusive(root, 'out/catalog.json', c), 'EEXIST');
  for (const rel of ['../escape.json', 'out/../escape.json', 'C:/escape.json', 'out\\escape.json']) await rejects(writeCatalogExclusive(root, rel, c), 'UNSAFE_PATH');
  await rejects(writeCatalogExclusive(root, 'backend/catalog.json', c), 'UNSAFE_OUTPUT_PATH');
});
test('manifest JSON errors never expose input and forbids excluded path reads', async t => {
  const { root, put } = await fixture(t); await put('out/review.json', '{SECRET_INVALID_JSON'); await rejects(readReviewedManifest(root, 'out/review.json'), 'INVALID_MANIFEST_JSON');
  await rejects(readReviewedManifest(root, 'requirements/review.json'), 'UNSAFE_MANIFEST_PATH');
});
test('CLI creates exclusive output and emits stats only; bounded failures', async t => {
  const { root } = await fixture(t); const args = [cli, '--workspace', root, '--output', 'out/catalog.json'];
  const first = spawnSync(process.execPath, args, { encoding: 'utf8' }); assert.equal(first.status, 0, first.stderr); assert.equal(JSON.parse(first.stdout).stats.migrations, 2);
  const second = spawnSync(process.execPath, args, { encoding: 'utf8' }); assert.equal(second.status, 1); assert.equal(second.stderr.trim(), 'SOURCE_CATALOG_ERROR:EEXIST');
  const bad = spawnSync(process.execPath, [cli, '--secret-SECRET_VALUE'], { encoding: 'utf8' }); assert.equal(bad.status, 1); assert.equal(bad.stderr.trim(), 'SOURCE_CATALOG_ERROR:INVALID_ARGUMENTS');
});
test('CLI drift rejects before any output is created', async t => {
  const { root, put } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifest(c); m.entries[0].sha256 = '0'.repeat(64); await put('out/review.json', JSON.stringify(m));
  const result = spawnSync(process.execPath, [cli, '--workspace', root, '--reviewed-manifest', 'out/review.json', '--output', 'out/catalog.json'], { encoding: 'utf8' });
  assert.equal(result.status, 1); assert.equal(result.stderr.trim(), 'SOURCE_CATALOG_ERROR:REVIEW_SHA_CHANGED'); await assert.rejects(readFile(path.join(root, 'out/catalog.json')), { code: 'ENOENT' });
});


test('ordinary Java prose and action labels never become SQL candidates', () => {
  const facts = java('String a = "different from indexes"; String b = "CREATE"; String c = "references unknown";');
  assert.equal(facts.sqlLiteralCount, 0); assert.equal(facts.references.length, 0); assert.equal(facts.diagnostics.length, 0);
});
test('runtime Java DDL is reported but never provides migration coverage', async t => {
  const { root, put } = await fixture(t);
  await put('backend/a/src/main/java/Generated.java', 'String create = "CREATE TABLE missing_install(id INT PRIMARY KEY)"; String use = "SELECT * FROM missing_install";');
  const c = await catalog({ workspaceRoot: root });
  assert.ok(c.runtimeFiles.flatMap(f => f.objects).some(o => o.name === 'MISSING_INSTALL' && o.origin === 'JAVA_LITERAL_NOT_MIGRATION_PROVIDER'));
  assert.ok(c.uncoveredReferences.some(r => r.name === 'MISSING_INSTALL'));
});
test('constraint predicates do not invent unnamed column constraints', () => {
  const facts = sql('CREATE TABLE x (id INT NOT NULL, CONSTRAINT ck CHECK (id IS NOT NULL), CONSTRAINT uk UNIQUE(id));');
  assert.equal(facts.objects.filter(o => o.constraintType === 'NOT NULL').length, 1);
  assert.equal(facts.objects.filter(o => o.kind === 'CONSTRAINT').length, 3);
});
test('Java string replacement and stored routine SQL remain unresolved', () => {
  assert.ok(java('String q = "SELECT * FROM foo".replace("foo", name);').diagnostics.some(d => d.code === 'DYNAMIC_SQL_NOT_RESOLVED'));
  assert.ok(java('db.execute("CALL unsafe_routine()");').diagnostics.some(d => d.code === 'STORED_ROUTINE_NOT_RESOLVED'));
});
test('hardlinked originals are not accepted as independent original bytes', async t => {
  const { root } = await fixture(t); await link(path.join(root, 'db/dm8/V001__original.sql'), path.join(root, 'db/dm8/V002__alias.sql'));
  await rejects(catalog({ workspaceRoot: root }), 'HARDLINK_REJECTED');
});
test('workspace junction cannot redirect discovery', async t => {
  const { root } = await fixture(t); const alias = `${root}-redirect`;
  await symlink(root, alias, process.platform === 'win32' ? 'junction' : 'dir');
  t.after(() => rm(alias, { force: true }));
  await rejects(catalog({ workspaceRoot: alias }), 'SYMLINK_REJECTED');
});
test('output parent junction rejected without writing redirected output', async t => {
  const { root } = await fixture(t); await symlink(path.join(root, 'docs'), path.join(root, 'out/redirect'), process.platform === 'win32' ? 'junction' : 'dir');
  await rejects(writeCatalogExclusive(root, 'out/redirect/catalog.json', {}), 'SYMLINK_REJECTED');
  await assert.rejects(readFile(path.join(root, 'docs/catalog.json')), { code: 'ENOENT' });
});
test('nested V aliases are not accepted as original migration paths', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/nested/V002__alias.sql', 'CREATE TABLE alias(id INT);');
  await rejects(catalog({ workspaceRoot: root }), 'INVALID_ORIGINAL_MIGRATION_PATH');
});
test('non UTF-8 source rejected instead of normalizing original bytes', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/V002__encoding.sql', Buffer.from([0xff, 0xfe]));
  await rejects(catalog({ workspaceRoot: root }), 'SOURCE_UTF8_REQUIRED');
});
test('removing an original invalidates prior complete review', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifest(c);
  await rm(path.join(root, c.migrations[0].path)); await rejects(catalog({ workspaceRoot: root, reviewedManifest: m }), 'REVIEW_COMPLETE_COVERAGE_REQUIRED');
});
test('review output accounts for every decision without echoing reasons', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifest(c);
  m.entries[0].reason = 'DO_NOT_ECHO_MANIFEST_TEXT'; m.entries[0].decision = 'excluded';
  const review = await checkReviewedManifest(c, m, root);
  assert.equal(review.entries.length, c.migrations.length); assert.ok(review.entries.some(e => e.reasonSha256 === hash('DO_NOT_ECHO_MANIFEST_TEXT')));
  assert.ok(!JSON.stringify(review).includes('DO_NOT_ECHO_MANIFEST_TEXT'));
});

test('dropping/disabling a constraint never invents a created constraint', () => {
  const facts = sql('ALTER TABLE existing DROP CONSTRAINT removed; ALTER TABLE existing DISABLE CONSTRAINT disabled; ALTER TABLE existing ADD CONSTRAINT added CHECK(id > 0);');
  assert.deepEqual(facts.objects.filter(o => o.kind === 'CONSTRAINT').map(o => o.name), ['ADDED']);
});
test('qualified sequence references never invent an unqualified sequence', () => {
  const facts = sql('SELECT s.seq.NEXTVAL FROM DUAL;');
  assert.deepEqual(facts.references.map(r => r.name), ['S.SEQ']);
});

// Explicit fixture bindings supplied by the reviewer; production never derives them from paths.
function manifestV2(c) {
  const bindings = new Map(c.migrations.map((m, i) => [m.path, { schema: `TEST_SCHEMA_${i}`, domain: 'consultation' }]));
  return { format: 'SINGLE_DATABASE_SOURCE_REVIEW/v2', entries: [...c.migrations, ...c.auxiliarySql].map(m => ({ path: m.path, sha256: m.sha256, version: m.logicalVersion ?? m.version, decision: bindings.has(m.path) ? 'selected' : 'excluded', reason: 'Explicit fixture technical decision only', designReferences: ['docs/adr/design.md#scope'], ...(bindings.get(m.path) || {}) })) };
}
test('auxiliary SQL SHA and definitions prevent missing-source false positives without migration approval', async t => {
  const { root, put } = await fixture(t);
  const original = 'CREATE TABLE adj2_source_use(id INT PRIMARY KEY); CREATE INDEX ix_source_use ON adj2_source_use(id);';
  await put('db/dm8/adjudication_source_authority.sql', original);
  await put('backend/a/src/main/java/Authority.java', 'String q = "SELECT * FROM adj2_source_use";');
  const c = await catalog({ workspaceRoot: root });
  assert.equal(c.stats.auxiliarySql, 1); assert.equal(c.stats.migrations, 2);
  const a = c.auxiliarySql[0]; assert.equal(a.sha256, hash(original)); assert.equal(a.version, null);
  assert.equal(a.controlledMigration, false); assert.equal(a.selectionStatus, 'UNVERSIONED_NOT_SELECTED'); assert.equal(a.selectable, false);
  assert.ok(a.objects.some(o => o.kind === 'TABLE' && o.name === 'ADJ2_SOURCE_USE'));
  assert.ok(a.objects.some(o => o.kind === 'INDEX')); assert.ok(a.objects.some(o => o.kind === 'CONSTRAINT'));
  assert.ok(!c.uncoveredReferences.some(r => r.name === 'ADJ2_SOURCE_USE'));
  assert.ok(c.unversionedReferences.some(r => r.name === 'ADJ2_SOURCE_USE' && r.coverage === 'LEXICAL_AUXILIARY_ONLY_NOT_VERSIONED_OR_SELECTED'));
  assert.ok(c.unversionedReferences.flatMap(r => r.providers).every(p => p.sourceCategory === 'AUXILIARY_UNVERSIONED'));
  assert.equal(c.review.automaticMigrationSelection, false);
});
test('other auxiliary originals in both roots are cataloged, not inferred as domain migrations', async t => {
  const { root, put } = await fixture(t);
  await put('db/dm8/setup.sql', 'CREATE SEQUENCE helper_seq;');
  await put('db/identity-authority/unversioned.sql', 'CREATE VIEW helper_view AS SELECT * FROM authority;');
  const c = await catalog({ workspaceRoot: root }); assert.equal(c.auxiliarySql.length, 2);
  assert.ok(c.auxiliarySql.every(a => !Object.hasOwn(a, 'domain') && a.version === null && a.selectable === false));
  assert.ok(c.auxiliarySql.flatMap(a => a.objects).some(o => o.kind === 'SEQUENCE'));
  assert.ok(c.auxiliarySql.flatMap(a => a.objects).some(o => o.kind === 'VIEW'));
});
test('U SQL is SHA-only historical reverse original, never an object provider', async t => {
  const { root, put } = await fixture(t); const original = 'CREATE TABLE reverse_only(id INT); DROP TABLE thing;';
  await put('db/dm8/U001__reverse.sql', original); await put('backend/a/src/main/java/Reverse.java', 'String q = "SELECT * FROM reverse_only";');
  const c = await catalog({ workspaceRoot: root }); assert.equal(c.historicalReverseSql.length, 1);
  assert.equal(c.historicalReverseSql[0].sha256, hash(original)); assert.equal(c.historicalReverseSql[0].contributesProviders, false);
  assert.equal(c.historicalReverseSql[0].selectable, false); assert.equal(c.historicalReverseSql[0].executesMigrations, false);
  assert.ok(!Object.hasOwn(c.historicalReverseSql[0], 'objects')); assert.ok(!Object.hasOwn(c.historicalReverseSql[0], 'references'));
  assert.ok(c.uncoveredReferences.some(r => r.name === 'REVERSE_ONLY')); assert.ok(!JSON.stringify(c).includes('DROP TABLE thing'));
});
test('auxiliary original digest drift invalidates complete v2 review', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/authority.sql', 'CREATE TABLE aux(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  await put('db/dm8/authority.sql', 'CREATE TABLE changed_aux(id INT);');
  await rejects(catalog({ workspaceRoot: root, reviewedManifest: m }), 'REVIEW_SHA_CHANGED');
});
test('v2 requires every auxiliary choice, reason and design reference', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/authority.sql', 'CREATE TABLE aux(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  const valid = await checkReviewedManifest(c, m, root); assert.equal(valid.auxiliaryExcluded, 1); assert.equal(valid.coverageValidated, true);
  assert.equal(valid.entries.find(e => e.version === null).decision, 'excluded');
  m.entries.pop(); await rejects(checkReviewedManifest(c, m, root), 'REVIEW_COMPLETE_COVERAGE_REQUIRED');
  const missingReason = manifestV2(c); missingReason.entries.at(-1).reason = ''; await rejects(checkReviewedManifest(c, missingReason, root), 'REVIEW_REASON_REQUIRED');
  const missingRef = manifestV2(c); missingRef.entries.at(-1).designReferences = []; await rejects(checkReviewedManifest(c, missingRef, root), 'REVIEW_DESIGN_REFERENCE_REQUIRED');
});
test('unversioned auxiliary SQL cannot be selected as a controlled migration', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/authority.sql', 'CREATE TABLE aux(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  Object.assign(m.entries.at(-1), { decision: 'selected', schema: 'AUX_SCHEMA', domain: 'adjudication' });
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_AUXILIARY_NOT_VERSIONED');
});
test('legacy v1 cannot silently omit auxiliary originals from full coverage', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/authority.sql', 'CREATE TABLE aux(id INT);');
  const c = await catalog({ workspaceRoot: root }); await rejects(checkReviewedManifest(c, manifest(c), root), 'REVIEW_V2_REQUIRED_FOR_AUXILIARY_SQL');
});
test('two V074 originals can be explicitly selected for different schemas/domains', async t => {
  const { root, put } = await fixture(t);
  await put('db/dm8/V074__first.sql', 'CREATE TABLE first_table(id INT);');
  await put('db/dm8/V074__second.sql', 'CREATE TABLE second_table(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  Object.assign(m.entries.find(e => e.path.endsWith('V074__first.sql')), { schema: 'CON_APP', domain: 'consultation' });
  Object.assign(m.entries.find(e => e.path.endsWith('V074__second.sql')), { schema: 'ENF_APP', domain: 'enforcement' });
  const r = await checkReviewedManifest(c, m, root); assert.equal(r.selected, 4); assert.equal(r.productApproval, false);
  assert.equal(r.entries.find(e => e.schema === 'CON_APP').schemaVersionKey, 'CON_APP:V74');
  assert.equal(r.entries.find(e => e.schema === 'ENF_APP').schemaVersionKey, 'ENF_APP:V74');
  assert.equal(r.uniquenessScope, 'EXPLICIT_SCHEMA_AND_NORMALIZED_VERSION');
});
test('v2 rejects same-schema same-version even across different source roots', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  m.entries.forEach(e => { e.schema = 'SHARED_SCHEMA'; });
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_SELECTED_DUPLICATE_SCHEMA_VERSION');
});
test('v2 normalizes version aliases before schema-version uniqueness', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/V0001__alias.sql', 'CREATE TABLE alias_table(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  m.entries.filter(e => e.path.startsWith('db/dm8/')).forEach(e => { e.schema = 'SAME_SCHEMA'; });
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_SELECTED_DUPLICATE_SCHEMA_VERSION');
});
for (const [name, change, code] of [
  ['missing schema', e => { delete e.schema; }, 'REVIEW_SELECTED_SCHEMA_DOMAIN_REQUIRED'],
  ['missing domain', e => { delete e.domain; }, 'REVIEW_SELECTED_SCHEMA_DOMAIN_REQUIRED'],
  ['unsafe schema', e => { e.schema = 'SCHEMA;DROP'; }, 'REVIEW_SELECTED_SCHEMA_REQUIRED'],
  ['lowercase schema alias', e => { e.schema = 'schema'; }, 'REVIEW_SELECTED_SCHEMA_REQUIRED'],
  ['unknown domain', e => { e.domain = 'inferred'; }, 'REVIEW_SELECTED_DOMAIN_REQUIRED'],
  ['null domain', e => { e.domain = null; }, 'REVIEW_SELECTED_DOMAIN_REQUIRED']
]) test(`v2 selected rejects ${name} without inferring a binding`, async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  change(m.entries[0]); await rejects(checkReviewedManifest(c, m, root), code);
});
test('one explicit schema cannot silently bind to two domains', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/V002__second.sql', 'CREATE TABLE second(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  Object.assign(m.entries.find(e => e.path.endsWith('V001__original.sql') && e.path.startsWith('db/dm8/')), { schema: 'SAME_SCHEMA', domain: 'consultation' });
  Object.assign(m.entries.find(e => e.path.endsWith('V002__second.sql')), { schema: 'SAME_SCHEMA', domain: 'enforcement' });
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_SCHEMA_DOMAIN_CONFLICT');
});
test('review v2 cannot insert a U reverse original into selectable coverage', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/U001__reverse.sql', 'DROP TABLE thing;');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  Object.assign(m.entries[0], { path: c.historicalReverseSql[0].path, sha256: c.historicalReverseSql[0].sha256, version: 'U001' });
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_UNKNOWN_SQL');
});
test('auxiliary definitions resolve source coverage but remain missing from selected V installation', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/authority.sql', 'CREATE TABLE aux_install(id INT);');
  await put('backend/a/src/main/java/Aux.java', 'String q = "SELECT * FROM aux_install";');
  const c = await catalog({ workspaceRoot: root, reviewedManifest: manifestV2(await catalog({ workspaceRoot: root })) });
  assert.ok(!c.uncoveredReferences.some(r => r.name === 'AUX_INSTALL'));
  assert.ok(c.unversionedReferences.some(r => r.name === 'AUX_INSTALL'));
  assert.ok(c.review.selectedUncoveredReferences.some(r => r.name === 'AUX_INSTALL'));
  assert.equal(c.auxiliarySql[0].controlledMigration, false);
});

const sharedBindings = () => [
  { schema: 'CON_APP', domain: 'consultation' },
  { schema: 'ENF_APP', domain: 'enforcement' },
  { schema: 'ADJ_APP', domain: 'adjudication' }
];
function bindEntry(entry, bindings) {
  delete entry.schema; delete entry.domain; entry.bindings = bindings;
}
test('one shared original decision explicitly installs three schema-domain instances', async t => {
  const { root, put } = await fixture(t);
  await put('db/dm8/V032__product_identity.sql', 'CREATE TABLE shared_identity(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  const e = m.entries.find(e => e.path.endsWith('V032__product_identity.sql'));
  bindEntry(e, sharedBindings());
  const r = await checkReviewedManifest(c, m, root);
  assert.equal(r.entries.filter(row => row.path === e.path).length, 1);
  assert.equal(r.entries.find(row => row.path === e.path).bindings.length, 3);
  assert.equal(r.selected, 3); assert.equal(r.selectedInstanceCount, 5);
  assert.deepEqual(r.selectedInstances.filter(i => i.path === e.path).map(i => i.schemaVersionKey), ['CON_APP:V32', 'ENF_APP:V32', 'ADJ_APP:V32']);
  assert.equal(r.productApproval, false); assert.equal(r.automaticMigrationSelection, false);
});
test('shared identity and authority originals each support three distinct explicit schemas', async t => {
  const { root, put } = await fixture(t);
  await put('db/dm8/V032__product_identity.sql', 'CREATE TABLE shared_identity(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  const shared = m.entries.find(e => e.path.endsWith('V032__product_identity.sql'));
  const authority = m.entries.find(e => e.path.startsWith('db/identity-authority/'));
  bindEntry(shared, sharedBindings());
  bindEntry(authority, [
    { schema: 'CON_AUTH', domain: 'consultation' },
    { schema: 'ENF_AUTH', domain: 'enforcement' },
    { schema: 'ADJ_AUTH', domain: 'adjudication' }
  ]);
  const r = await checkReviewedManifest(c, m, root);
  assert.equal(r.selected, 3); assert.equal(r.selectedInstanceCount, 7);
  assert.equal(r.selectedInstances.filter(i => i.path === shared.path).length, 3);
  assert.equal(r.selectedInstances.filter(i => i.path === authority.path).length, 3);
});
test('duplicate instance in one original bindings array is refused', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  bindEntry(m.entries[0], [sharedBindings()[0], sharedBindings()[0]]);
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_SELECTED_DUPLICATE_SCHEMA_VERSION');
});
test('cross-file instance collision remains globally refused with multi-bindings', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  bindEntry(m.entries[0], sharedBindings()); bindEntry(m.entries[1], [{ schema: 'ENF_APP', domain: 'enforcement' }]);
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_SELECTED_DUPLICATE_SCHEMA_VERSION');
});
test('multi-bindings enforce schema-domain consistency within the same original', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  bindEntry(m.entries[0], [{ schema: 'CON_APP', domain: 'consultation' }, { schema: 'CON_APP', domain: 'enforcement' }]);
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_SCHEMA_DOMAIN_CONFLICT');
});
test('multi-bindings enforce schema-domain consistency across different versions/files', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/V002__another.sql', 'CREATE TABLE another(id INT);');
  const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  bindEntry(m.entries.find(e => e.path.endsWith('V002__another.sql')), [{ schema: 'CON_APP', domain: 'enforcement' }]);
  bindEntry(m.entries.find(e => e.path.endsWith('V001__original.sql') && e.path.startsWith('db/dm8/')), sharedBindings());
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_SCHEMA_DOMAIN_CONFLICT');
});
for (const [name, bindings, code] of [
  ['empty array', [], 'REVIEW_SELECTED_BINDINGS_REQUIRED'],
  ['null array', null, 'REVIEW_SELECTED_BINDINGS_REQUIRED'],
  ['non-array', { schema: 'CON_APP', domain: 'consultation' }, 'REVIEW_SELECTED_BINDINGS_REQUIRED'],
  ['missing domain', [{ schema: 'CON_APP' }], 'INVALID_MANIFEST_FIELDS'],
  ['extra binding field', [{ schema: 'CON_APP', domain: 'consultation', automatic: true }], 'INVALID_MANIFEST_FIELDS'],
  ['unsafe schema', [{ schema: '../SECRET', domain: 'consultation' }], 'REVIEW_SELECTED_SCHEMA_REQUIRED'],
  ['unknown domain', [{ schema: 'CON_APP', domain: 'shared' }], 'REVIEW_SELECTED_DOMAIN_REQUIRED']
]) test(`multi-bindings reject ${name} rather than infer shared bindings`, async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  bindEntry(m.entries[0], bindings); await rejects(checkReviewedManifest(c, m, root), code);
});
test('single binding compatibility preserved; ambiguous mixed representations refused', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  const valid = await checkReviewedManifest(c, m, root);
  assert.equal(valid.selectedInstanceCount, 2); assert.equal(valid.entries[0].bindings.length, 1);
  m.entries[0].bindings = sharedBindings(); await rejects(checkReviewedManifest(c, m, root), 'REVIEW_AMBIGUOUS_BINDINGS');
});
test('one shared original still has exactly one choose/exclude decision', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  bindEntry(m.entries[0], sharedBindings()); m.entries[1] = structuredClone(m.entries[0]);
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_DUPLICATE_PATH');
});
test('excluded original cannot conceal installation bindings', async t => {
  const { root } = await fixture(t); const c = await catalog({ workspaceRoot: root }); const m = manifestV2(c);
  bindEntry(m.entries[0], []); m.entries[0].decision = 'excluded';
  const valid = await checkReviewedManifest(c, m, root); assert.equal(valid.selectedInstanceCount, 1);
  m.entries[0].bindings = sharedBindings(); await rejects(checkReviewedManifest(c, m, root), 'REVIEW_EXCLUDED_BINDING_MUST_BE_NULL');
});

const registryPath = 'deployment/single-database/auxiliary-migrations.json';
async function registeredFixture(t) {
  const f = await fixture(t);
  const bytes = await readFile(new URL('../../db/dm8/adjudication_source_authority.sql', import.meta.url));
  await f.put('db/dm8/adjudication_source_authority.sql', bytes);
  const registry = { format: 'SINGLE_DATABASE_AUXILIARY_MIGRATIONS/v1', migrations: [{ id: 'A001', sqlPath: 'db/dm8/adjudication_source_authority.sql', sha256: hash(bytes), domain: 'adjudication', kind: 'authority', initializationOnly: true, designReference: 'docs/adr/design.md', technicalReference: 'docs/adr/design.md#scope' }] };
  await f.put(registryPath, JSON.stringify(registry));
  return { ...f, registry, bytes };
}
function selectRegistered(c) {
  const m = manifestV2(c);
  const e = m.entries.find(e => e.path === 'db/dm8/adjudication_source_authority.sql');
  e.decision = 'selected';
  bindEntry(e, [{ schema: 'EXPLICIT_UNCLASSIFIED_SCHEMA', domain: 'adjudication' }]);
  return m;
}
test('only registered A001 reports logical version and fixed initialization scope', async t => {
  const { root, put } = await registeredFixture(t); await put('db/dm8/other_aux.sql', 'CREATE TABLE never_registered(id INT);');
  const c = await catalog({ workspaceRoot: root }); const a = c.auxiliarySql.find(a => a.logicalVersion === 'A001');
  assert.equal(c.auxiliaryRegistry.status, 'VALIDATED'); assert.equal(c.stats.registeredAuxiliarySql, 1);
  assert.equal(a.version, null); assert.equal(a.sourceCategory, 'REGISTERED_AUXILIARY'); assert.equal(a.selectionStatus, 'REGISTERED_NOT_SELECTED');
  assert.equal(a.registrationScope.kind, 'authority'); assert.equal(a.registrationScope.domain, 'adjudication'); assert.equal(a.registrationScope.initializationOnly, true);
  assert.equal(a.registrationScope.targetSchemaVerified, false); assert.equal(a.controlledMigration, true); assert.equal(a.installationVerified, false);
  assert.equal(c.auxiliarySql.find(a => a.path.endsWith('other_aux.sql')).selectable, false);
});
test('review v2 can explicitly select A001 by schema:A001 without inferring authority schema', async t => {
  const { root, put } = await registeredFixture(t);
  await put('backend/a/src/main/java/Registered.java', 'String q = "SELECT * FROM adj2_source_use";');
  const first = await catalog({ workspaceRoot: root }); const m = selectRegistered(first);
  const c = await catalog({ workspaceRoot: root, reviewedManifest: m }); const instance = c.review.selectedInstances.find(i => i.version === 'A001');
  assert.equal(instance.schemaVersionKey, 'EXPLICIT_UNCLASSIFIED_SCHEMA:A001'); assert.equal(instance.kind, 'authority'); assert.equal(instance.domain, 'adjudication');
  assert.equal(instance.initializationOnly, true); assert.equal(instance.targetSchemaVerified, false);
  assert.equal(c.review.registeredAuxiliarySelected, 1); assert.equal(c.review.auxiliaryExcluded, 0);
  assert.equal(c.auxiliarySql[0].selectionStatus, 'REGISTERED_EXPLICITLY_SELECTED_NOT_INSTALLED');
  assert.ok(!c.review.selectedUncoveredReferences.some(r => r.name === 'ADJ2_SOURCE_USE'));
  assert.ok(c.registeredAuxiliaryReferences.some(r => r.name === 'ADJ2_SOURCE_USE'));
  assert.equal(c.review.productApproval, false);
});
test('missing registry is diagnosed and never defaults auxiliary SQL to registered', async t => {
  const { root, put } = await fixture(t); await put('db/dm8/adjudication_source_authority.sql', 'CREATE TABLE unregistered(id INT);');
  const c = await catalog({ workspaceRoot: root }); assert.equal(c.auxiliaryRegistry.status, 'MISSING');
  assert.equal(c.auxiliaryRegistry.diagnostics[0].code, 'AUXILIARY_REGISTRY_MISSING'); assert.equal(c.stats.registeredAuxiliarySql, 0);
  const m = selectRegistered(c); await rejects(checkReviewedManifest(c, m, root), 'REVIEW_AUXILIARY_NOT_VERSIONED');
});
test('empty registry remains unregistered with explicit missing-entry diagnostic', async t => {
  const { root, put } = await fixture(t); await put(registryPath, JSON.stringify({ format: 'SINGLE_DATABASE_AUXILIARY_MIGRATIONS/v1', migrations: [] }));
  const c = await catalog({ workspaceRoot: root }); assert.equal(c.auxiliaryRegistry.status, 'ENTRY_MISSING'); assert.equal(c.stats.registeredAuxiliarySql, 0);
});
for (const [name, mutate, code] of [
  ['wrong SHA', r => { r.migrations[0].sha256 = '0'.repeat(64); }, 'AUXILIARY_REGISTRY_SHA_MISMATCH'],
  ['wrong original path', r => { r.migrations[0].sqlPath = 'db/dm8/other.sql'; }, 'AUXILIARY_REGISTRY_A001_PATH_REQUIRED'],
  ['path traversal', r => { r.migrations[0].sqlPath = 'db/dm8/../other.sql'; }, 'UNSAFE_PATH'],
  ['absolute root path', r => { r.migrations[0].sqlPath = 'D:/db/dm8/adjudication_source_authority.sql'; }, 'UNSAFE_PATH'],
  ['duplicate logical ID', r => { r.migrations.push({ ...r.migrations[0] }); }, 'AUXILIARY_REGISTRY_DUPLICATE_ID'],
  ['unapproved logical ID', r => { r.migrations[0].id = 'A002'; }, 'AUXILIARY_REGISTRY_A001_PATH_REQUIRED'],
  ['wrong domain', r => { r.migrations[0].domain = 'consultation'; }, 'AUXILIARY_REGISTRY_SCOPE_MISMATCH'],
  ['wrong kind', r => { r.migrations[0].kind = 'business'; }, 'AUXILIARY_REGISTRY_SCOPE_MISMATCH'],
  ['non-init execution', r => { r.migrations[0].initializationOnly = false; }, 'AUXILIARY_REGISTRY_SCOPE_MISMATCH']
]) test(`registered auxiliary refuses ${name}`, async t => {
  const { root, put, registry } = await registeredFixture(t); mutate(registry); await put(registryPath, JSON.stringify(registry));
  await rejects(catalog({ workspaceRoot: root }), code);
});
test('editing source and registration SHA together cannot authorize new DDL/DML', async t => {
  const { root, put, registry } = await registeredFixture(t); const changed = 'CREATE TABLE changed(id INT); INSERT INTO changed VALUES(1);';
  await put(registry.migrations[0].sqlPath, changed); registry.migrations[0].sha256 = hash(changed); await put(registryPath, JSON.stringify(registry));
  await rejects(catalog({ workspaceRoot: root }), 'AUXILIARY_REGISTRY_SHA_MISMATCH');
});
test('selected registered binding cannot change registered domain or inject kind', async t => {
  const { root } = await registeredFixture(t); const c = await catalog({ workspaceRoot: root }); const m = selectRegistered(c);
  m.entries.at(-1).bindings[0].domain = 'enforcement'; await rejects(checkReviewedManifest(c, m, root), 'REVIEW_REGISTERED_AUXILIARY_DOMAIN_MISMATCH');
  m.entries.at(-1).bindings[0].domain = 'adjudication'; m.entries.at(-1).bindings[0].kind = 'business';
  await rejects(checkReviewedManifest(c, m, root), 'INVALID_MANIFEST_FIELDS');
});
test('duplicate schema:A001 installation instance is globally refused', async t => {
  const { root } = await registeredFixture(t); const c = await catalog({ workspaceRoot: root }); const m = selectRegistered(c);
  const e = m.entries.find(e => e.version === 'A001'); e.bindings.push({ ...e.bindings[0] });
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_SELECTED_DUPLICATE_SCHEMA_VERSION');
});
test('previous registered catalog cannot select after registry removal', async t => {
  const { root } = await registeredFixture(t); const c = await catalog({ workspaceRoot: root }); const m = selectRegistered(c);
  await rm(path.join(root, registryPath)); await rejects(checkReviewedManifest(c, m, root), 'REVIEW_AUXILIARY_REGISTRATION_REQUIRED');
});
test('previous registered catalog cannot select after registry byte drift', async t => {
  const { root, put, registry } = await registeredFixture(t); const c = await catalog({ workspaceRoot: root }); const m = selectRegistered(c);
  await put(registryPath, JSON.stringify(registry, null, 2)); await rejects(checkReviewedManifest(c, m, root), 'REVIEW_AUXILIARY_REGISTRY_CHANGED');
});
test('previous registered catalog cannot select after source drift', async t => {
  const { root, put, bytes } = await registeredFixture(t); const c = await catalog({ workspaceRoot: root }); const m = selectRegistered(c);
  await put('db/dm8/adjudication_source_authority.sql', Buffer.concat([bytes, Buffer.from('\n-- changed')]));
  await rejects(checkReviewedManifest(c, m, root), 'AUXILIARY_REGISTRY_SHA_MISMATCH');
});
test('review uses A001 logical version rather than treating registered source as unversioned', async t => {
  const { root } = await registeredFixture(t); const c = await catalog({ workspaceRoot: root }); const m = selectRegistered(c);
  m.entries.find(e => e.version === 'A001').version = null;
  await rejects(checkReviewedManifest(c, m, root), 'REVIEW_VERSION_CHANGED');
});
