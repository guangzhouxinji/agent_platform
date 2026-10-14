#!/usr/bin/env node
import { captureSnapshot, verifyRelease, inspectReleaseMigrations } from './lib/consultation-comparison-release.mjs';

const HELP = `Offline consultation comparison engineering package check (no build/DB/deployment).
Check:
  node scripts/verify-consultation-comparison-release.mjs --workspace ROOT --jar RELATIVE_JAR
    --before RELATIVE_JSON --after RELATIVE_JSON
    [--jar-sha256 SHA256] [--entries RELATIVE_JAR_TF_TXT] [--java-home ABSOLUTE_JDK]
    [--generation FIXED_A_V1|SUCCESSOR_WITH_ISOLATION_V1|SUCCESSOR_INPUT_HANDOFF_V2|SUCCESSOR_MATRIX_LINEAGE_V3]
    [--output docs/evidence/consultation-comparison-release-20261007/package/manifest.json]
Snapshot to stdout (redirect outside backend/db/scripts):
  node scripts/verify-consultation-comparison-release.mjs --workspace ROOT --snapshot [--generation FIXED_A_V1|SUCCESSOR_WITH_ISOLATION_V1|SUCCESSOR_INPUT_HANDOFF_V2|SUCCESSOR_MATRIX_LINEAGE_V3]
Migration inventory to stdout (no database access or release manifest):
  node scripts/verify-consultation-comparison-release.mjs --workspace ROOT --migrations --generation SUCCESSOR_INPUT_HANDOFF_V2
Snapshot JSON: array or {files:[{path,sha256}]} (sha alias accepted).
Successor also freezes frontend/src, public and root build/config inputs; no generated output.
Coverage: backend/db/scripts java/xml/yml/yaml/json/sql/py/ps1/mjs regular files,
excluding target/node_modules/.git directories. Workspace defaults to current directory.
No caller-supplied classes, migration pins or package declarations are trusted.
Relative file paths must remain inside the workspace with no links or traversal.
Output must not exist: EVIDENCE_OUTPUT_EXISTS preserves original bytes.
Engineering evidence only; not a formal release approval.`;
try {
  const args = process.argv.slice(2), options = {};
  const names = new Map([['workspace','workspace'],['jar','jar'],['jar-sha256','jarSha256'],
    ['before','before'],['after','after'],['entries','entries'],['java-home','javaHome'],['output','output'],['generation','generation']]);
  if (args.length === 1 && args[0] === '--help') { console.log(HELP); }
  else {
    for (let i=0;i<args.length;i++) {
      const key = args[i].slice(2);
      if (!args[i].startsWith('--') || Object.hasOwn(options,key === 'jar-sha256' ? 'jarSha256' : names.get(key) ?? key)) throw Error('BAD_ARGUMENTS');
      if (key === 'snapshot' || key === 'migrations') { options[key] = true; continue; }
      if (!names.has(key) || !args[i+1] || args[i+1].startsWith('--')) throw Error('BAD_ARGUMENTS');
      options[names.get(key)] = args[++i];
    }
    options.workspace ??= process.cwd();
    if (options.migrations) {
      if (Object.keys(options).some(k => !['workspace','migrations','generation'].includes(k))) throw Error('BAD_ARGUMENTS');
      console.log(JSON.stringify(inspectReleaseMigrations(options),null,2));
    } else if (options.snapshot) {
      if (Object.keys(options).some(k => !['workspace','snapshot','generation'].includes(k))) throw Error('BAD_ARGUMENTS');
      console.log(JSON.stringify(captureSnapshot(options.workspace,options.generation),null,2));
    } else {
      const manifest = verifyRelease(options);
      console.log(JSON.stringify({ contract:manifest.contract, scope:manifest.scope,
        jarSha256:manifest.artifact.artifact.sha256, output:options.output ?? 'docs/evidence/consultation-comparison-release-20261007/package/manifest.json' }));
    }
  }
} catch (error) {
  // Never echo file contents, JVM diagnostics, or configuration/credential text.
  const code = /^[A-Z][A-Z0-9_]+$/.test(error.message) ? error.message : error.code ?? 'RELEASE_CHECK_FAILED';
  console.error(JSON.stringify({status:'REJECTED',code}));
  process.exitCode = 1;
}
