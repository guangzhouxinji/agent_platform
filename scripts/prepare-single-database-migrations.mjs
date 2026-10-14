#!/usr/bin/env node
import { readFile, writeFile, realpath, lstat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createSingleDatabaseMigrationPlan } from './lib/single-database-migration-plan.mjs';

// Usage: node scripts/prepare-single-database-migrations.mjs --input metadata.json --output NEW.json --target-id <64 lowercase hex>
// Reads only strict metadata JSON and exact db/dm8 SQL; never connects to a DB or reads credentials.
export async function main(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!['--input', '--output', '--target-id'].includes(args[i]) || !args[i + 1]
        || Object.hasOwn(options, args[i])) throw new Error('EXPECTED_INPUT_OUTPUT_TARGET_ID');
    options[args[i]] = args[i + 1];
  }
  if (Object.keys(options).length !== 3) throw new Error('EXPECTED_INPUT_OUTPUT_TARGET_ID');
  const workspaceRoot = fileURLToPath(new URL('../', import.meta.url));
  const inputPath = path.resolve(options['--input']);
  const outputPath = path.resolve(options['--output']);
  if (inputPath === outputPath) throw new Error('OUTPUT_MUST_BE_NEW');
  // Protect SQL/requirements even if a user chooses a new filename there.
  const parent = await realpath(path.dirname(outputPath));
  const root = await realpath(workspaceRoot);
  const relative = path.relative(root, parent).replaceAll('\\', '/');
  if (['db', 'requirements'].some(p => relative === p || relative.startsWith(`${p}/`))) throw new Error('READONLY_OUTPUT_LOCATION');
  const inputStat = await lstat(inputPath);
  if (path.extname(inputPath) !== '.json' || /(?:goal|secret|credential|vault)/i.test(path.basename(inputPath))
      || inputStat.isSymbolicLink() || !inputStat.isFile() || inputStat.size > 16 * 1024 * 1024
      || await realpath(inputPath) !== inputPath) throw new Error('METADATA_ONLY_INPUT_REQUIRED');
  const input = JSON.parse(await readFile(inputPath, 'utf8'));
  const plan = await createSingleDatabaseMigrationPlan(input, { workspaceRoot, expectedTargetId: options['--target-id'] });
  await writeFile(outputPath, `${JSON.stringify(plan, null, 2)}\n`, { flag: 'wx', encoding: 'utf8' });
  return plan;
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(plan => {
    console.log(`Offline manifest: ${plan.pending.length} pending, ${plan.skippedApplied.length} not replayed; NOT migration authorization or recovery proof.`);
  }).catch(() => {
    // Do not echo potentially sensitive JSON, filesystem paths or supplied metadata.
    console.error('Forward manifest rejected; no migration executed. Check strict input contract, SQL/evidence hashes, dependencies and exclusive output.');
    process.exitCode = 1;
  });
}
