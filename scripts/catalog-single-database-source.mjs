#!/usr/bin/env node
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CatalogError, createSingleDatabaseSourceCatalog, readReviewedManifest, writeCatalogExclusive } from './lib/single-database-source-catalog.mjs';

// node scripts/catalog-single-database-source.mjs --output <workspace-relative.json>
// [--workspace <absolute-workspace>] [--reviewed-manifest <workspace-relative.json>]
// Catalog includes auxiliary SQL and hash-only U history. Review v2 supports explicit multi-schema bindings per selected original.
// Exclusive file output only. Errors never echo source, paths, JSON, stack or credentials.
export async function main(args = process.argv.slice(2)) {
  try {
    const values = {};
    if (args.length === 1 && args[0] === '--help') {
      console.log('Usage: node scripts/catalog-single-database-source.mjs --output <relative.json> [--workspace <root>] [--reviewed-manifest <relative.json>]\nRead-only V/auxiliary SQL catalog and U hash-only history. Review v2 requires explicit selected bindings:[{schema,domain},...] (single schema/domain also supported); unregistered auxiliary SQL is never selected; only pinned A001 may be explicitly selected in registered initialization scope. No SQL execution or product/R/D approval. Existing output is never overwritten.');
      return 0;
    }
    for (let i = 0; i < args.length; i += 2) {
      if (!['--workspace', '--output', '--reviewed-manifest'].includes(args[i]) || values[args[i]] !== undefined || !args[i + 1] || args[i + 1].startsWith('--')) throw new CatalogError('INVALID_ARGUMENTS');
      values[args[i]] = args[i + 1];
    }
    if (!values['--output']) throw new CatalogError('OUTPUT_REQUIRED');
    const workspaceRoot = values['--workspace'] || process.cwd();
    if (values['--output'] === values['--reviewed-manifest']) throw new CatalogError('OUTPUT_INPUT_COLLISION');
    const reviewedManifest = values['--reviewed-manifest'] ? await readReviewedManifest(workspaceRoot, values['--reviewed-manifest']) : undefined;
    const catalog = await createSingleDatabaseSourceCatalog({ workspaceRoot, reviewedManifest });
    await writeCatalogExclusive(workspaceRoot, values['--output'], catalog);
    console.log(JSON.stringify({ format: catalog.format, stats: catalog.stats, reviewSupplied: catalog.review.supplied, completenessProven: false }));
    return 0;
  } catch (error) {
    const code = error instanceof CatalogError ? error.code : ['EEXIST', 'ENOENT', 'EACCES', 'EPERM'].includes(error?.code) ? error.code : 'CATALOG_READ_OR_WRITE_FAILED';
    console.error(`SOURCE_CATALOG_ERROR:${code}`);
    return 1;
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = await main();
