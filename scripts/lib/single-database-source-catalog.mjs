import { createHash } from 'node:crypto';
import { lstat, realpath, readdir, open } from 'node:fs/promises';
import path from 'node:path';

// Technical source facts only; never executes SQL or grants product / R / D approval.
const FORMAT = 'SINGLE_DATABASE_SOURCE_CATALOG/v1';
const MAX_BYTES = 8 * 1024 * 1024;
const SKIP = new Set(['target', 'evidence', 'requirements', '.git', 'node_modules', 'test', 'tests', 'build', 'dist']);
const SECRET = /(?:^|[._-])(?:secrets?|credentials?|keystore|truststore|private|passwords?)(?:[._-]|$)/i;
const SHA = /^[a-f0-9]{64}$/;
const ID = /^[A-Za-z_][A-Za-z0-9_$#]{0,127}$/;
const SCHEMA = /^[A-Z][A-Z0-9_]{0,63}$/;
const DOMAINS = new Set(['consultation', 'enforcement', 'adjudication']);
const digest = data => createHash('sha256').update(data).digest('hex');
const fail = code => { throw new CatalogError(code); };
export class CatalogError extends Error { constructor(code) { super(code); this.code = code; } }
function relativePath(value) {
  if (typeof value !== 'string' || !value || value.length > 512 || value.includes('\\') || value.includes(':') || value.includes('\0') || value.split('/').some(p => !p || p === '.' || p === '..' || !/^[A-Za-z0-9_.\-\u4e00-\u9fff]+$/.test(p))) fail('UNSAFE_PATH');
  return value;
}
async function workspace(root) {
  if (typeof root !== 'string' || !root) fail('WORKSPACE_REQUIRED');
  const absolute = path.resolve(root);
  // Verify every ancestor: junctions/symlinks must not redirect the original workspace.
  let cursor = path.parse(absolute).root;
  for (const part of absolute.slice(cursor.length).split(path.sep).filter(Boolean)) {
    cursor = path.join(cursor, part);
    if ((await lstat(cursor)).isSymbolicLink()) fail('SYMLINK_REJECTED');
  }
  if (!(await lstat(absolute)).isDirectory() || (await realpath(absolute)).toLowerCase() !== absolute.toLowerCase()) fail('WORKSPACE_NOT_ORIGINAL');
  return absolute;
}
async function checked(root, rel, kind = 'file') {
  relativePath(rel);
  let current = root;
  for (const part of rel.split('/')) {
    current = path.join(current, part);
    if ((await lstat(current)).isSymbolicLink()) fail('SYMLINK_REJECTED');
  }
  const stat = await lstat(current);
  if (kind === 'file' ? !stat.isFile() : !stat.isDirectory()) fail('INVALID_FILE_KIND');
  if ((await realpath(current)).toLowerCase() !== current.toLowerCase()) fail('PATH_REDIRECTION');
  if (kind === 'file' && stat.nlink > 1) fail('HARDLINK_REJECTED');
  return { current, stat };
}
async function readOriginal(root, rel) {
  const { current, stat } = await checked(root, rel);
  if (stat.size > MAX_BYTES) fail('SOURCE_TOO_LARGE');
  const handle = await open(current, 'r');
  try {
    const before = await handle.stat();
    if (before.ino !== stat.ino || before.dev !== stat.dev) fail('SOURCE_CHANGED');
    const bytes = await handle.readFile();
    const after = await handle.stat();
    const final = (await checked(root, rel)).stat;
    if (bytes.length > MAX_BYTES || before.size !== after.size || before.mtimeMs !== after.mtimeMs || final.ino !== after.ino || final.dev !== after.dev || final.size !== after.size || final.mtimeMs !== after.mtimeMs) fail('SOURCE_CHANGED');
    let text;
    try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch { fail('SOURCE_UTF8_REQUIRED'); }
    if (text.includes('\0')) fail('BINARY_SOURCE_REJECTED');
    return { text, sha256: digest(bytes), bytes: bytes.length };
  } finally { await handle.close(); }
}
async function walk(root, rel, accept, exclusions) {
  const { current } = await checked(root, rel, 'directory');
  const result = [];
  for (const entry of (await readdir(current, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
    const child = `${rel}/${entry.name}`;
    if (SKIP.has(entry.name) || SECRET.test(entry.name)) { exclusions.add(child); continue; }
    relativePath(child);
    if (entry.isSymbolicLink()) fail('SYMLINK_REJECTED');
    if (entry.isDirectory()) result.push(...await walk(root, child, accept, exclusions));
    else if (entry.isFile() && accept(child)) { await checked(root, child); result.push(child); }
    else if (!entry.isFile()) fail('SPECIAL_FILE_REJECTED');
  }
  return result;
}

// Lexical SQL inventory, not a dialect parser. Literals/comments never become identifiers.
function lex(text, java = false) {
  const tokens = [], issues = [];
  let i = 0, line = 1;
  const advance = end => { line += (text.slice(i, end).match(/\n/g) || []).length; i = end; };
  while (i < text.length) {
    if (/\s/.test(text[i])) { advance(i + 1); continue; }
    if (text.startsWith('--', i) && !java || text.startsWith('//', i) && java) { const end = text.indexOf('\n', i); advance(end < 0 ? text.length : end); continue; }
    if (text.startsWith('/*', i)) { const end = text.indexOf('*/', i + 2); if (end < 0) issues.push({ code: 'UNTERMINATED_COMMENT', line }); advance(end < 0 ? text.length : end + 2); continue; }
    const startLine = line;
    if (java && text.startsWith('"""', i)) {
      const end = text.indexOf('"""', i + 3);
      if (end < 0) issues.push({ code: 'UNTERMINATED_LITERAL', line });
      tokens.push({ type: 'string', value: text.slice(i + 3, end < 0 ? text.length : end), line }); advance(end < 0 ? text.length : end + 3); continue;
    }
    const q = text[i];
    if (q === '"' || q === "'" || !java && q === '`') {
      let j = i + 1, value = '', closed = false;
      while (j < text.length) {
        if (java && text[j] === '\\') {
          const esc = text[j + 1];
          if (esc === 'u') { const m = /^u+([a-fA-F0-9]{4})/.exec(text.slice(j + 1)); if (m) { value += String.fromCharCode(parseInt(m[1], 16)); j += m[0].length + 1; continue; } }
          value += ({ n: '\n', r: '\r', t: '\t' })[esc] ?? esc ?? ''; j += 2; continue;
        }
        if (text[j] === q) { if (!java && text[j + 1] === q) { value += q; j += 2; continue; } closed = true; j++; break; }
        value += text[j++];
      }
      if (!closed) issues.push({ code: 'UNTERMINATED_LITERAL', line: startLine });
      tokens.push({ type: !java && q !== "'" ? 'quoted' : 'string', value, line: startLine }); advance(j); continue;
    }
    const match = /^[A-Za-z_][A-Za-z0-9_$#]*/.exec(text.slice(i));
    if (match) { tokens.push({ type: 'word', value: match[0], line }); advance(i + match[0].length); }
    else { tokens.push({ type: 'punct', value: text[i], line }); advance(i + 1); }
  }
  return { tokens, issues };
}
const word = t => t?.type === 'word' ? t.value.toUpperCase() : '';
function nameAt(tokens, offset) {
  const parts = []; let i = offset;
  while ((tokens[i]?.type === 'word' || tokens[i]?.type === 'quoted') && ID.test(tokens[i].value)) {
    parts.push(tokens[i].type === 'quoted' ? `"${tokens[i].value}"` : tokens[i].value.toUpperCase());
    i++;
    if (tokens[i]?.value !== '.') break;
    i++;
  }
  return parts.length && tokens[i - 1]?.value !== '.' ? { name: parts.join('.'), end: i } : null;
}
export function extractSqlFacts(text) {
  const { tokens: t, issues } = lex(text);
  const objects = [], references = [], ctes = new Set();
  const object = (kind, pos, extra = {}) => {
    const n = nameAt(t, pos);
    if (!n) { issues.push({ code: 'UNPARSED_OBJECT_NAME', line: t[pos]?.line ?? 1 }); return; }
    objects.push({ kind, name: n.name, line: t[pos].line, ...extra }); return n;
  };
  // WITH aliases are local relations, not installed tables; retain scope uncertainty explicitly.
  for (let i = 0; i < t.length; i++) {
    if (word(t[i]) === 'WITH' || t[i]?.value === ',') {
      let j = i + 1; if (word(t[j]) === 'RECURSIVE') j++;
      const n = nameAt(t, j); let k = n?.end;
      if (t[k]?.value === '(') { let depth = 1; k++; while (k < t.length && depth) { if (t[k].value === '(') depth++; if (t[k].value === ')') depth--; k++; } }
      if (n && word(t[k]) === 'AS' && t[k + 1]?.value === '(') ctes.add(n.name);
    }
  }
  let table = null, unnamed = 0, depth = 0, tableBodyDepth = 0;
  for (let i = 0; i < t.length; i++) {
    const w = word(t[i]);
    if (t[i].value === '(') depth++;
    if (t[i].value === ')') depth = Math.max(0, depth - 1);
    if (t[i].value === ';') { table = null; depth = 0; }
    if (w === 'CREATE') {
      let j = i + 1;
      if (word(t[j]) === 'OR' && word(t[j + 1]) === 'REPLACE') j += 2;
      while (['UNIQUE', 'GLOBAL', 'LOCAL', 'TEMPORARY', 'CLUSTERED', 'NONCLUSTERED', 'BITMAP', 'MATERIALIZED'].includes(word(t[j]))) j++;
      const kind = word(t[j]);
      if (['TABLE', 'INDEX', 'SEQUENCE', 'VIEW'].includes(kind)) {
        j++; if (word(t[j]) === 'IF' && word(t[j + 1]) === 'NOT' && word(t[j + 2]) === 'EXISTS') j += 3;
        const n = object(kind, j); if (kind === 'TABLE') { table = n?.name ?? null; tableBodyDepth = depth + 1; }
      } else issues.push({ code: 'UNSUPPORTED_CREATE', line: t[i].line });
    }
    if (w === 'ALTER' && word(t[i + 1]) === 'TABLE') { table = nameAt(t, i + 2)?.name ?? null; tableBodyDepth = depth; }
    if (w === 'CONSTRAINT' && !['DROP', 'RENAME', 'ENABLE', 'DISABLE', 'MODIFY'].includes(word(t[i - 1]))) object('CONSTRAINT', i + 1, { table, named: true });
    if (table && depth === tableBodyDepth && (w === 'PRIMARY' && word(t[i + 1]) === 'KEY' || w === 'UNIQUE' || w === 'CHECK' || w === 'FOREIGN' && word(t[i + 1]) === 'KEY' || w === 'NOT' && word(t[i + 1]) === 'NULL')) {
      // Named definitions already recorded; unnamed constraints have no invented DB identifier.
      if (word(t[i - 2]) !== 'CONSTRAINT') objects.push({ kind: 'CONSTRAINT', name: null, table, constraintType: w === 'NOT' ? 'NOT NULL' : w, ordinal: ++unnamed, named: false, line: t[i].line });
    }
    let pos, kind = 'RELATION', context = w;
    if (['FROM', 'JOIN', 'UPDATE', 'REFERENCES'].includes(w) && !(w === 'UPDATE' && word(t[i - 1]) === 'ON')) pos = i + 1;
    if (w === 'INTO' && ['INSERT', 'MERGE'].includes(word(t[i - 1]))) pos = i + 1;
    if (w === 'USING' && t.slice(0, i).reverse().find(v => v.value === ';' || word(v) === 'MERGE')?.value.toUpperCase() === 'MERGE') pos = i + 1;
    if (w === 'TABLE' && ['ALTER', 'TRUNCATE', 'DROP'].includes(word(t[i - 1]))) pos = i + 1;
    if (w === 'ON' && t.slice(Math.max(0, i - 5), i).some(v => word(v) === 'INDEX')) pos = i + 1;
    if (w === 'FOR' && word(t[i - 1]) === 'VALUE' && word(t[i - 2]) === 'NEXT') { pos = i + 1; kind = 'SEQUENCE'; }
    if (pos !== undefined) {
      const n = nameAt(t, pos);
      if (n && !['SELECT', 'SET', 'WHERE', 'DUAL'].includes(n.name) && !ctes.has(n.name)) references.push({ name: n.name, kind, context, line: t[pos].line });
      else if (!n && t[pos]?.value !== '(') issues.push({ code: 'UNRESOLVED_RELATION', line: t[i].line });
      // Old comma-join syntax. Scan only this FROM level, never select-list commas.
      if (w === 'FROM') {
        let depth = 0;
        for (let j = pos; j < t.length; j++) {
          if (t[j].value === '(') depth++;
          if (t[j].value === ')') { if (!depth) break; depth--; }
          if (!depth && (t[j].value === ';' || ['WHERE', 'GROUP', 'ORDER', 'HAVING', 'UNION', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'FULL', 'CROSS', 'FETCH', 'FOR'].includes(word(t[j])))) break;
          if (!depth && t[j].value === ',') { const next = nameAt(t, j + 1); if (next && !ctes.has(next.name)) references.push({ name: next.name, kind: 'RELATION', context: 'FROM_COMMA', line: t[j + 1].line }); }
        }
      }
    }
    const n = nameAt(t, i);
    if (t[i - 1]?.value !== '.' && n && /\.(NEXTVAL|CURRVAL)$/.test(n.name)) references.push({ name: n.name.replace(/\.(NEXTVAL|CURRVAL)$/, ''), kind: 'SEQUENCE', context: 'SEQUENCE_VALUE', line: t[i].line });
    if (w === 'CALL') issues.push({ code: 'STORED_ROUTINE_NOT_RESOLVED', line: t[i].line });
    if (['EXECUTE', 'EXEC', 'PREPARE'].includes(w)) issues.push({ code: 'DYNAMIC_SQL_NOT_RESOLVED', line: t[i].line });
  }
  const unique = rows => [...new Map(rows.map(row => [JSON.stringify(row), row])).values()];
  return { objects: unique(objects), references: unique(references), diagnostics: unique(issues) };
}

export function extractJavaSqlFacts(text) {
  // Java Unicode escapes are translated before lexical processing by the compiler.
  text = text.replace(/\\u+([a-fA-F0-9]{4})/g, (_, h) => String.fromCharCode(parseInt(h, 16)));
  const { tokens: t, issues } = lex(text, true);
  const objects = [], references = [], diagnostics = [...issues]; let sqlLiteralCount = 0;
  const sqlLike = /^\s*(?:CALL\s+|EXECUTE\s+|SELECT\b|WITH\s+(?:RECURSIVE\s+)?[A-Za-z_]|INSERT\s+INTO\b|UPDATE\s+|DELETE\s+FROM\b|MERGE\s+INTO\b|CREATE\s+(?:OR\s+REPLACE|UNIQUE|TABLE|INDEX|SEQUENCE|VIEW|SYNONYM|GLOBAL|LOCAL|TEMPORARY|MATERIALIZED)\b|ALTER\s+TABLE\b|DROP\s+(?:TABLE|INDEX|SEQUENCE|VIEW)\b|TRUNCATE\s+TABLE\b|FROM\s+|JOIN\s+)|\b[A-Za-z_][A-Za-z0-9_.]*\.(?:NEXTVAL|CURRVAL)\b/i;
  for (let i = 0; i < t.length; i++) {
    if (t[i].type !== 'string') continue;
    let value = t[i].value, j = i;
    while (t[j + 1]?.value === '+' && t[j + 2]?.type === 'string') { value += t[j + 2].value; j += 2; }
    if (sqlLike.test(value)) {
      sqlLiteralCount++;
      const facts = extractSqlFacts(value);
      objects.push(...facts.objects.map(obj => ({ ...obj, line: t[i].line + obj.line - 1, origin: 'JAVA_LITERAL_NOT_MIGRATION_PROVIDER' })));
      references.push(...facts.references.map(ref => ({ ...ref, line: t[i].line + ref.line - 1 })));
      diagnostics.push(...facts.diagnostics.map(d => ({ ...d, line: t[i].line + d.line - 1 })));
      if (t[j + 1]?.value === '+' || t[i - 1]?.value === '+' || t[j + 1]?.value === '.' && /^(?:formatted|replace|replaceAll|replaceFirst|concat|transform)$/.test(t[j + 2]?.value ?? '') || /(?:\$\{|%[sSdD]|\{\d+\})/.test(value)) diagnostics.push({ code: 'DYNAMIC_SQL_NOT_RESOLVED', line: t[i].line });
    }
    i = j;
  }
  for (let i = 0; i < t.length; i++) {
    if (/^(?:prepareStatement|prepareCall|execute|executeQuery|executeUpdate|executeLargeUpdate|addBatch|query|queryForObject|queryForList|update|batchUpdate)$/.test(t[i].value) && t[i].type === 'word' && t[i + 1]?.value === '(' && t[i + 2]?.type !== 'string') diagnostics.push({ code: 'INDIRECT_SQL_CALL_NOT_RESOLVED', line: t[i].line });
  }
  return { objects, references, diagnostics, sqlLiteralCount };
}
function configFacts(text) {
  // Only explicit SQL-valued keys are inspected; no scalar values / credentials are emitted.
  const objects = [], references = [], diagnostics = []; let sqlLiteralCount = 0;
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const m = /^\s*(sql|query|statement|init-sql|connection-init-sql|schema|default-schema|current-schema|locations)\s*:\s*(.*)$/i.exec(lines[i]);
    if (!m) continue;
    if (/schema|locations/i.test(m[1])) { diagnostics.push({ code: 'CONFIG_RUNTIME_BINDING_NOT_RESOLVED', line: i + 1 }); continue; }
    sqlLiteralCount++;
    let value = m[2];
    if (/^[|>]/.test(value)) {
      const indent = lines[i].match(/^\s*/)[0].length;
      value = ''; let j = i + 1;
      while (j < lines.length && (lines[j].trim() === '' || lines[j].match(/^\s*/)[0].length > indent)) value += `${lines[j++]}\n`;
    }
    value = value.replace(/^['"]|['"]$/g, '');
    const facts = extractSqlFacts(value);
    objects.push(...facts.objects.map(o => ({ ...o, line: o.line + i, origin: 'CONFIG_LITERAL_NOT_MIGRATION_PROVIDER' })));
    references.push(...facts.references.map(r => ({ ...r, line: r.line + i })));
    diagnostics.push(...facts.diagnostics.map(d => ({ ...d, line: d.line + i })));
    if (/\$\{|%s/.test(value)) diagnostics.push({ code: 'DYNAMIC_SQL_NOT_RESOLVED', line: i + 1 });
  }
  return { objects, references, diagnostics, sqlLiteralCount };
}
function resolveReferences(rows, providers) {
  return rows.map(ref => {
    const matches = providers.filter(p => p.name === ref.name && (ref.kind === 'SEQUENCE' ? p.kind === 'SEQUENCE' : ['TABLE', 'VIEW'].includes(p.kind)));
    const auxiliaryOnly = matches.length > 0 && matches.every(p => p.sourceCategory === 'AUXILIARY_UNVERSIONED');
    const registeredOnly = matches.length > 0 && matches.every(p => p.sourceCategory === 'REGISTERED_AUXILIARY');
    return { ...ref, coverage: registeredOnly ? 'LEXICAL_REGISTERED_AUXILIARY_ONLY_NO_INSTALL_PROOF' : auxiliaryOnly ? 'LEXICAL_AUXILIARY_ONLY_NOT_VERSIONED_OR_SELECTED' : matches.length ? 'LEXICAL_NAME_MATCH_ONLY' : 'NOT_PROVIDED_BY_SCANNED_SQL', providers: matches.map(p => ({ path: p.path, name: p.name, kind: p.kind, line: p.line, sourceCategory: p.sourceCategory })) };
  });
}

// The sole approved auxiliary identity is pinned; this is not an extensible SQL allow-list.
const AUX_REGISTRY = 'deployment/single-database/auxiliary-migrations.json';
const A001_PATH = 'db/dm8/adjudication_source_authority.sql';
const A001_SHA = 'f0ff3a6ded31bebf608f81eae2b83b700974ced4a681332a6a86a23ff30a3a0f';
async function registerAuxiliary(root, auxiliarySql) {
  let source;
  try { source = await readOriginal(root, AUX_REGISTRY); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return { path: AUX_REGISTRY, status: 'MISSING', diagnostics: [{ code: 'AUXILIARY_REGISTRY_MISSING' }] };
  }
  let registry;
  try { registry = JSON.parse(source.text); } catch { fail('AUXILIARY_REGISTRY_INVALID_JSON'); }
  exactKeys(registry, ['format', 'migrations']);
  if (registry.format !== 'SINGLE_DATABASE_AUXILIARY_MIGRATIONS/v1' || !Array.isArray(registry.migrations)) fail('AUXILIARY_REGISTRY_INVALID_FORMAT');
  const ids = new Set();
  for (const entry of registry.migrations) {
    exactKeys(entry, ['id', 'sqlPath', 'sha256', 'domain', 'kind', 'initializationOnly', 'designReference', 'technicalReference']);
    if (ids.has(entry.id)) fail('AUXILIARY_REGISTRY_DUPLICATE_ID');
    ids.add(entry.id);
  }
  if (registry.migrations.length > 1) fail('AUXILIARY_REGISTRY_A001_ONLY');
  for (const entry of registry.migrations) {
    relativePath(entry.sqlPath);
    if (entry.id !== 'A001' || entry.sqlPath !== A001_PATH) fail('AUXILIARY_REGISTRY_A001_PATH_REQUIRED');
    if (entry.domain !== 'adjudication' || entry.kind !== 'authority' || entry.initializationOnly !== true) fail('AUXILIARY_REGISTRY_SCOPE_MISMATCH');
    const original = auxiliarySql.find(a => a.path === entry.sqlPath);
    if (!original) fail('AUXILIARY_REGISTRY_ORIGINAL_MISSING');
    if (entry.sha256 !== A001_SHA || entry.sha256 !== original.sha256) fail('AUXILIARY_REGISTRY_SHA_MISMATCH');
    for (const reference of [entry.designReference, entry.technicalReference]) {
      if (typeof reference !== 'string' || reference.length > 768) fail('AUXILIARY_REGISTRY_REFERENCE_REQUIRED');
      const [file, section, ...rest] = reference.split('#');
      relativePath(file);
      if (!file.startsWith('docs/') || !file.endsWith('.md') || file.split('/').some(p => SKIP.has(p) || SECRET.test(p)) || rest.length || section !== undefined && (!section.trim() || /[\x00-\x1f]/.test(section))) fail('AUXILIARY_REGISTRY_REFERENCE_REQUIRED');
      await checked(root, file);
    }
    original.sourceCategory = 'REGISTERED_AUXILIARY';
    original.logicalVersion = entry.id;
    original.controlledMigration = true; // registered technical input, not an executed or accepted installation
    original.installationVerified = false;
    original.selectable = true;
    original.selectionStatus = 'REGISTERED_NOT_SELECTED';
    original.registrationScope = { domain: entry.domain, kind: entry.kind, initializationOnly: true, targetSchemaVerified: false, runtimeAdmission: 'EXACT_DEPLOYMENT_ADJUDICATION_AUTHORITY_SCHEMA_AND_EMPTY_THREE_TABLE_INITIALIZATION_ONLY' };
  }
  return { path: AUX_REGISTRY, sha256: source.sha256, status: registry.migrations.length ? 'VALIDATED' : 'ENTRY_MISSING', diagnostics: registry.migrations.length ? [] : [{ code: 'AUXILIARY_REGISTRY_ENTRY_MISSING' }] };
}

/** Original db SQL only: V migrations, non-V/non-U auxiliary definitions, U hash-only history.
 * Review v2 covers every V and auxiliary original. Unregistered auxiliary entries must remain excluded; only the pinned registered A001 may be selected.
 * Selected V entries require explicit bindings:[{schema,domain},...] (or legacy schema/domain);
 * one source decision may bind multiple instances, each unique by schema:normalized-version.
 * Review v1 is retained only for V-only inventories and uses conservative root:version uniqueness.
 * Reasons/design references are required metadata, not approval; no domain is inferred.
 */
export async function createSingleDatabaseSourceCatalog({ workspaceRoot, reviewedManifest } = {}) {
  const root = await workspace(workspaceRoot), exclusions = new Set();
  const sqlPaths = [];
  for (const base of ['db/dm8', 'db/identity-authority']) sqlPaths.push(...await walk(root, base, p => /\.sql$/i.test(p), exclusions));
  const runtimePaths = await walk(root, 'backend', p => /\.(java|ya?ml|sql)$/i.test(p), exclusions);
  const migrations = [], auxiliarySql = [], historicalReverseSql = [], runtimeFiles = [];
  for (const rel of sqlPaths.sort()) {
    const filename = path.posix.basename(rel);
    if (rel.split('/').length !== 3) fail('INVALID_ORIGINAL_MIGRATION_PATH');
    const category = /^V/i.test(filename) ? 'V' : /^U[0-9]/i.test(filename) ? 'U' : 'AUXILIARY';
    const match = /^([VU])([0-9]{3,}(?:[._][0-9]+)*)__([A-Za-z0-9_-]+)\.sql$/.exec(filename);
    if (category !== 'AUXILIARY' && !match || category === 'AUXILIARY' && !/^[A-Za-z0-9_][A-Za-z0-9_-]*\.sql$/.test(filename)) fail('INVALID_ORIGINAL_MIGRATION_PATH');
    const source = await readOriginal(root, rel);
    const metadata = { path: rel, sha256: source.sha256, bytes: source.bytes };
    if (category === 'U') {
      historicalReverseSql.push({ ...metadata, version: `U${match[2]}`, sourceCategory: 'HISTORICAL_REVERSE', contributesProviders: false, selectable: false, executesMigrations: false });
      continue; // Do not parse reverse DDL as definitions or dependencies.
    }
    const facts = extractSqlFacts(source.text);
    if (category === 'V') migrations.push({ ...metadata, version: `V${match[2]}`, versionKey: match[2].split(/[._]/).map(n => BigInt(n).toString()).join('.'), sourceCategory: 'VERSIONED_ORIGINAL_NOT_AUTOMATICALLY_SELECTED', ...facts });
    else auxiliarySql.push({ ...metadata, version: null, sourceCategory: 'AUXILIARY_UNVERSIONED', controlledMigration: false, selectionStatus: 'UNVERSIONED_NOT_SELECTED', selectable: false, ...facts });
  }
  for (const rel of runtimePaths.sort()) {
    const source = await readOriginal(root, rel), extension = path.posix.extname(rel).toLowerCase();
    const facts = extension === '.java' ? extractJavaSqlFacts(source.text) : extension === '.sql' ? extractSqlFacts(source.text) : configFacts(source.text);
    runtimeFiles.push({ path: rel, kind: extension.slice(1).toUpperCase(), sha256: source.sha256, bytes: source.bytes, sourceCategory: 'RUNTIME_SOURCE_NOT_MIGRATION_SELECTION', ...facts });
  }
  const auxiliaryRegistry = await registerAuxiliary(root, auxiliarySql);
  const versions = new Map();
  for (const m of migrations) { const key = `${path.posix.dirname(m.path)}:${m.versionKey}`; const paths = versions.get(key) || []; paths.push(m.path); versions.set(key, paths); }
  const duplicateVersions = [...versions.entries()].filter(([, paths]) => paths.length > 1).map(([key, paths]) => ({ key, paths }));
  const factFiles = [...migrations, ...auxiliarySql, ...runtimeFiles];
  const objectsOf = files => files.flatMap(file => (file.objects || []).map(obj => ({ ...obj, path: file.path, sourceCategory: file.sourceCategory })));
  const allObjects = objectsOf(factFiles);
  const providers = objectsOf([...migrations, ...auxiliarySql, ...runtimeFiles.filter(f => f.kind === 'SQL')]);
  for (const file of factFiles) file.references = resolveReferences(file.references, providers);
  const referenceRows = factFiles.flatMap(file => file.references.map(r => ({ path: file.path, ...r })));
  const uncoveredReferences = referenceRows.filter(r => r.coverage === 'NOT_PROVIDED_BY_SCANNED_SQL');
  const unversionedReferences = referenceRows.filter(r => r.coverage === 'LEXICAL_AUXILIARY_ONLY_NOT_VERSIONED_OR_SELECTED');
  const registeredAuxiliaryReferences = referenceRows.filter(r => r.coverage === 'LEXICAL_REGISTERED_AUXILIARY_ONLY_NO_INSTALL_PROOF');
  const catalog = {
    format: FORMAT, purpose: 'TECHNICAL_SOURCE_FACTS_ONLY', executesMigrations: false, grantsProductApproval: false,
    scope: { migrationRoots: ['db/dm8', 'db/identity-authority'], databaseSqlCategories: ['V_VERSIONED_ORIGINAL', 'AUXILIARY_UNVERSIONED', 'U_HASH_ONLY_HISTORY'], runtimeRoot: 'backend', runtimeExtensions: ['java', 'yml', 'yaml', 'sql'], excludedPaths: [...exclusions].sort() },
    completeness: { proven: false, limitations: [
      'Lexical extraction, not a DM8 or Java compiler; unsupported dialect, generated SQL and indirect calls remain unresolved.',
      'No runtime schema/search-path, migration order, object lifecycle, synonym, privilege, deployment or R/D inference.',
      'Quoted identifiers preserve case; schema-qualified names require exact lexical providers; built-in DUAL is excluded.',
      'Unregistered auxiliary SQL remains unversioned and unselectable. Only pinned A001 registration permits explicit selection in adjudication/authority initialization scope; no target schema, empty tables or runtime approval is verified. U SQL is hash-only history, never a provider or executable selection.',
      'Root-level duplicate versions are source facts, not schema conflicts. Review v2 requires explicit selected bindings (or single schema/domain) and globally checks every instance schema:normalized-version; v1 cannot resolve root duplicates.',
      'Java/YML DDL objects are literal facts, never migration providers.',
      'CTE aliases are filtered per input fragment, not proven statement scope; SQL fragments can yield false positives/negatives.',
      'Java constant concatenations only; variable/interprocedural/reflection/resource SQL and formatted identifiers cannot prove completeness.',
      'YML extraction inspects explicit SQL/schema/location keys only; configuration values and source bodies are never output.',
      'SHA describes scanned original bytes, not license, business correctness, product eligibility or a coherent live filesystem snapshot.'
    ] },
    migrations, auxiliarySql, auxiliaryRegistry, historicalReverseSql, runtimeFiles,
    duplicateVersionScope: 'SOURCE_ROOT_ONLY_NOT_TARGET_SCHEMA', duplicateVersions, uncoveredReferences, unversionedReferences, registeredAuxiliaryReferences,
    stats: { migrations: migrations.length, auxiliarySql: auxiliarySql.length, registeredAuxiliarySql: auxiliarySql.filter(a => a.sourceCategory === 'REGISTERED_AUXILIARY').length, historicalReverseSql: historicalReverseSql.length, runtimeFiles: runtimeFiles.length, objects: allObjects.length, namedObjects: allObjects.filter(p => p.name !== null).length, references: referenceRows.length, uncoveredReferences: uncoveredReferences.length, unversionedReferences: unversionedReferences.length, registeredAuxiliaryReferences: registeredAuxiliaryReferences.length, diagnostics: auxiliaryRegistry.diagnostics.length + factFiles.reduce((n, f) => n + f.diagnostics.length, 0), duplicateVersionGroups: duplicateVersions.length },
    review: { supplied: false, productApproval: false, automaticMigrationSelection: false }
  };
  if (reviewedManifest !== undefined) {
    catalog.review = await checkReviewedManifest(catalog, reviewedManifest, root);
    for (const auxiliary of auxiliarySql) if (catalog.review.entries.some(e => e.path === auxiliary.path && e.decision === 'selected')) auxiliary.selectionStatus = 'REGISTERED_EXPLICITLY_SELECTED_NOT_INSTALLED';
  }
  return catalog;
}
function exactKeys(obj, names) {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj) || Object.keys(obj).length !== names.length || names.some(k => !Object.hasOwn(obj, k))) fail('INVALID_MANIFEST_FIELDS');
}
export async function checkReviewedManifest(catalog, manifest, root) {
  exactKeys(manifest, ['format', 'entries']);
  const v2 = manifest.format === 'SINGLE_DATABASE_SOURCE_REVIEW/v2';
  if (!v2 && manifest.format !== 'SINGLE_DATABASE_SOURCE_REVIEW/v1') fail('REVIEW_FORMAT_REQUIRED');
  const auxiliary = catalog.auxiliarySql || [];
  if (!v2 && auxiliary.length) fail('REVIEW_V2_REQUIRED_FOR_AUXILIARY_SQL');
  const originals = new Map([...catalog.migrations, ...auxiliary].map(m => [m.path, m]));
  if (!Array.isArray(manifest.entries) || manifest.entries.length !== originals.size) fail('REVIEW_COMPLETE_COVERAGE_REQUIRED');
  // Selected registered originals recheck live source and registry, even for a previously created catalog.
  if (manifest.entries.some(e => e?.decision === 'selected' && originals.get(e.path)?.sourceCategory === 'REGISTERED_AUXILIARY')) {
    const currentAuxiliary = auxiliary.map(a => ({ ...a }));
    for (const a of currentAuxiliary) if (a.path === A001_PATH) a.sha256 = (await readOriginal(root, a.path)).sha256;
    const currentRegistry = await registerAuxiliary(root, currentAuxiliary);
    if (currentRegistry.status !== 'VALIDATED') fail('REVIEW_AUXILIARY_REGISTRATION_REQUIRED');
    if (currentRegistry.sha256 !== catalog.auxiliaryRegistry?.sha256) fail('REVIEW_AUXILIARY_REGISTRY_CHANGED');
  }
  const seen = new Set(), choices = new Map(), entries = [], selectedKeys = new Set(), schemaDomains = new Map();
  const fields = ['path', 'sha256', 'version', 'decision', 'reason', 'designReferences'];
  for (const e of manifest.entries) {
    let requestedBindings = [];
    if (v2 && e?.decision === 'selected') {
      if (Object.hasOwn(e, 'bindings')) {
        if (Object.hasOwn(e, 'schema') || Object.hasOwn(e, 'domain')) fail('REVIEW_AMBIGUOUS_BINDINGS');
        exactKeys(e, [...fields, 'bindings']);
        if (!Array.isArray(e.bindings) || !e.bindings.length || e.bindings.length > 256) fail('REVIEW_SELECTED_BINDINGS_REQUIRED');
        for (const binding of e.bindings) exactKeys(binding, ['schema', 'domain']);
        requestedBindings = e.bindings;
      } else {
        if (!Object.hasOwn(e, 'schema') || !Object.hasOwn(e, 'domain')) fail('REVIEW_SELECTED_SCHEMA_DOMAIN_REQUIRED');
        exactKeys(e, [...fields, 'schema', 'domain']);
        requestedBindings = [{ schema: e.schema, domain: e.domain }];
      }
    } else {
      // Excluded v2 entries have no instances: omitted/null legacy fields, omitted/null/[] bindings.
      const bindingFields = v2 ? ['schema', 'domain', 'bindings'].filter(k => Object.hasOwn(e || {}, k)) : [];
      exactKeys(e, [...fields, ...bindingFields]);
      if (bindingFields.some(k => k === 'bindings' ? e[k] !== null && (!Array.isArray(e[k]) || e[k].length !== 0) : e[k] !== null)) fail('REVIEW_EXCLUDED_BINDING_MUST_BE_NULL');
    }
    relativePath(e.path);
    if (seen.has(e.path)) fail('REVIEW_DUPLICATE_PATH'); seen.add(e.path);
    const original = originals.get(e.path);
    if (!original) fail('REVIEW_UNKNOWN_SQL');
    if (!SHA.test(e.sha256) || e.sha256 !== original.sha256) fail('REVIEW_SHA_CHANGED');
    if (e.version !== (original.logicalVersion ?? original.version)) fail('REVIEW_VERSION_CHANGED');
    if (!['selected', 'excluded'].includes(e.decision)) fail('REVIEW_DECISION_REQUIRED');
    if (typeof e.reason !== 'string' || !e.reason.trim() || e.reason.length > 2048 || /[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(e.reason)) fail('REVIEW_REASON_REQUIRED');
    if (!Array.isArray(e.designReferences) || !e.designReferences.length || e.designReferences.length > 32 || new Set(e.designReferences).size !== e.designReferences.length) fail('REVIEW_DESIGN_REFERENCE_REQUIRED');
    for (const reference of e.designReferences) {
      if (typeof reference !== 'string' || reference.length > 768) fail('REVIEW_DESIGN_REFERENCE_REQUIRED');
      const [file, section, ...rest] = reference.split('#');
      relativePath(file);
      if (!file.startsWith('docs/') || file.split('/').some(p => SKIP.has(p) || SECRET.test(p)) || !file.endsWith('.md') || !section?.trim() || rest.length || /[\x00-\x1f]/.test(section)) fail('REVIEW_DESIGN_REFERENCE_REQUIRED');
      await checked(root, file); // Safe existence only, never proof of product or D approval.
    }
    const bindings = [];
    if (e.decision === 'selected') {
      if (original.sourceCategory === 'AUXILIARY_UNVERSIONED') fail('REVIEW_AUXILIARY_NOT_VERSIONED');
      if (v2) {
        for (const { schema, domain } of requestedBindings) {
          if (typeof schema !== 'string' || !SCHEMA.test(schema)) fail('REVIEW_SELECTED_SCHEMA_REQUIRED');
          if (!DOMAINS.has(domain)) fail('REVIEW_SELECTED_DOMAIN_REQUIRED');
          if (original.sourceCategory === 'REGISTERED_AUXILIARY' && domain !== original.registrationScope.domain) fail('REVIEW_REGISTERED_AUXILIARY_DOMAIN_MISMATCH');
          if (schemaDomains.has(schema) && schemaDomains.get(schema) !== domain) fail('REVIEW_SCHEMA_DOMAIN_CONFLICT');
          schemaDomains.set(schema, domain);
          const schemaVersionKey = schema + ':' + (original.logicalVersion ?? 'V' + original.versionKey);
          if (selectedKeys.has(schemaVersionKey)) fail('REVIEW_SELECTED_DUPLICATE_SCHEMA_VERSION');
          selectedKeys.add(schemaVersionKey);
          bindings.push({ schema, domain, schemaVersionKey, ...(original.registrationScope ? { kind: original.registrationScope.kind, initializationOnly: true, targetSchemaVerified: false } : {}) });
        }
      }
    }
    choices.set(e.path, e.decision);
    entries.push({ path: e.path, sha256: e.sha256, version: e.version, decision: e.decision, logicalVersion: original.logicalVersion ?? null, registrationScope: original.registrationScope ?? null, schema: bindings.length === 1 ? bindings[0].schema : null, domain: bindings.length === 1 ? bindings[0].domain : null, schemaVersionKey: bindings.length === 1 ? bindings[0].schemaVersionKey : null, bindings, reasonSha256: digest(e.reason), designReferenceCount: e.designReferences.length, designReferenceSha256: e.designReferences.map(reference => digest(reference)) });
  }
  if (!v2) for (const group of catalog.duplicateVersions) if (group.paths.filter(p => choices.get(p) === 'selected').length > 1) fail('REVIEW_SELECTED_DUPLICATE_VERSION');
  const selected = [...catalog.migrations, ...auxiliary].filter(m => choices.get(m.path) === 'selected');
  const selectedProviders = selected.flatMap(m => m.objects.map(o => ({ ...o, path: m.path, sourceCategory: m.sourceCategory })));
  const selectedUncovered = catalog.runtimeFiles.flatMap(f => resolveReferences(f.references, selectedProviders).filter(r => !r.providers.length).map(r => ({ path: f.path, ...r })));
  const selectedInstances = entries.flatMap(e => e.bindings.map(b => ({ path: e.path, sha256: e.sha256, version: e.version, ...b })));
  return { supplied: true, format: manifest.format, coverageValidated: true, selectedInstanceCount: selectedInstances.length, selectedInstances, entries: entries.sort((a, b) => a.path.localeCompare(b.path, 'en')), selected: selected.length, excluded: originals.size - selected.length, auxiliaryExcluded: auxiliary.filter(a => choices.get(a.path) === 'excluded').length, registeredAuxiliarySelected: auxiliary.filter(a => choices.get(a.path) === 'selected').length, selectedUncoveredReferences: selectedUncovered, uniquenessScope: v2 ? 'EXPLICIT_SCHEMA_AND_NORMALIZED_VERSION' : 'LEGACY_SOURCE_ROOT_AND_NORMALIZED_VERSION_NO_SCHEMA_BINDING', designReferencesAreMetadataOnly: true, automaticMigrationSelection: false, productApproval: false };
}

export async function readReviewedManifest(workspaceRoot, rel) {
  const root = await workspace(workspaceRoot);
  relativePath(rel);
  if (!rel.endsWith('.json') || rel.split('/').some(p => SKIP.has(p) || SECRET.test(p)) || /^(?:db|backend)\//.test(rel)) fail('UNSAFE_MANIFEST_PATH');
  const { text } = await readOriginal(root, rel);
  try { return JSON.parse(text); } catch { fail('INVALID_MANIFEST_JSON'); }
}

export async function writeCatalogExclusive(workspaceRoot, rel, catalog) {
  const root = await workspace(workspaceRoot);
  relativePath(rel);
  if (!rel.endsWith('.json') || /^(?:db|backend|requirements|scripts)\//.test(rel) || rel.split('/').some(p => SECRET.test(p))) fail('UNSAFE_OUTPUT_PATH');
  const parent = path.posix.dirname(rel);
  if (parent !== '.') await checked(root, parent, 'directory');
  const dest = path.join(root, ...rel.split('/'));
  const handle = await open(dest, 'wx', 0o600);
  try { await handle.writeFile(`${JSON.stringify(catalog, null, 2)}\n`, 'utf8'); } finally { await handle.close(); }
}



