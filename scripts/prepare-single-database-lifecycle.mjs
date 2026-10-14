#!/usr/bin/env node
import { readFileSync, writeFileSync, existsSync, realpathSync } from 'node:fs';
import { resolve, extname, basename, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { prepareLifecycle, sha256 } from './lib/single-database-lifecycle.mjs';

const HELP=`Offline single-DM8 admission (no DB, Docker, migration, backup, decrypt, restore or startup).
Usage: node scripts/prepare-single-database-lifecycle.mjs
  [--mode config-preflight|resource-preflight|initialize-admission|protection-admission|recovery-admission]
  [--config JSON] [--container-inspect JSON] [--volume-inspect JSON] [--writer-inspect JSON]
  [--evidence METADATA_JSON ...] [--output NEW_JSON]
Default protection-admission fails closed: no approved same-point whole-three-domain snapshot is supplied.
config-preflight checks config only; resource-preflight checks captured inspect metadata, never current runtime.
Evidence JSON files are hashed/indexed only, never merged into a fabricated whole snapshot.
Output is a reference index, NOT a physical backup manifest or recovery release. Existing outputs are never overwritten.
Live inventory/execution/native snapshot tools are separate inventory/execute/snapshot-single-database-lifecycle.mjs entry points; this offline tool never dispatches them.\n`;
export function runCli(args, {stdout=s=>process.stdout.write(s),stderr=s=>process.stderr.write(s)}={}) {
    const options={mode:'protection-admission',evidence:[]},seen=new Set();
    try {
        if(args.length===1 && args[0]==='--help'){stdout(HELP);return 0;}
        for(let i=0;i<args.length;i++) {
            const key=args[i];
            if(!['--mode','--config','--container-inspect','--volume-inspect','--writer-inspect','--evidence','--output'].includes(key))throw new Error('UNKNOWN_CLI_ARGUMENT');
            if(key!=='--evidence' && seen.has(key))throw new Error('DUPLICATE_CLI_ARGUMENT');seen.add(key);
            const value=args[++i];if(!value || value.startsWith('--'))throw new Error('CLI_VALUE_REQUIRED');
            if(key==='--evidence')options.evidence.push(value);else options[key.slice(2)]=value;
        }
        const index=[],readPaths=new Set();
        function readMetadata(path,parse=true) {
            const abs=resolve(path),name=basename(abs);
            if(extname(abs)!=='.json' || /(?:goal|secret|credential|vault|\.conbkp|\.gcm)/i.test(name))throw new Error('METADATA_ONLY_NO_GOAL_SECRET_OR_PACKAGE');
            const actual=realpathSync(abs);
            if(extname(actual)!=='.json' || /(?:goal|secret|credential|vault|\.conbkp|\.gcm)/i.test(basename(actual)))throw new Error('METADATA_ONLY_NO_GOAL_SECRET_OR_PACKAGE');
            if(readPaths.has(actual))throw new Error('DUPLICATE_INPUT_REFERENCE');readPaths.add(actual);
            const bytes=readFileSync(actual);
            if(bytes.length>16*1024*1024)throw new Error('METADATA_SIZE_LIMIT');
            const data=JSON.parse(bytes);
            index.push({path:actual,sha256:sha256(bytes),bytes:bytes.length,handling:'OFFLINE_REFERENCE_ONLY_NO_BODY_COPY'});
            return parse?data:undefined;
        }
        const loaded={mode:options.mode};
        for(const [key,target] of [['config','configuration'],['container-inspect','containerInspect'],['volume-inspect','volumeInspect'],['writer-inspect','writerInspect']])
            if(options[key])loaded[target]=readMetadata(options[key]);
        for(const p of options.evidence)readMetadata(p,false);
        for(const e of index)if(sha256(readFileSync(e.path))!==e.sha256)throw new Error('INPUT_CHANGED_DURING_OFFLINE_CHECK');
        loaded.evidenceIndex=index;
        const report=prepareLifecycle(loaded);
        const text=JSON.stringify(report,null,2)+'\n';
        if(options.output) {
            const target=resolve(options.output);
            if(extname(target)!=='.json' || existsSync(target))throw new Error('NEW_JSON_OUTPUT_REQUIRED');
            const parent=realpathSync(dirname(target));
            if(resolve(parent,basename(target))!==target)throw new Error('OUTPUT_PARENT_MUST_NOT_REDIRECT');
            writeFileSync(target,text,{flag:'wx'});
        }
        stdout(text);return report.passed?0:1;
    } catch(e) {stderr(JSON.stringify({passed:false,code:'OFFLINE_CLI_REFUSED',detail:['UNKNOWN_CLI_ARGUMENT','DUPLICATE_CLI_ARGUMENT','CLI_VALUE_REQUIRED','METADATA_ONLY_NO_GOAL_SECRET_OR_PACKAGE','DUPLICATE_INPUT_REFERENCE','METADATA_SIZE_LIMIT','INPUT_CHANGED_DURING_OFFLINE_CHECK','NEW_JSON_OUTPUT_REQUIRED','OUTPUT_PARENT_MUST_NOT_REDIRECT','UNKNOWN_OFFLINE_MODE'].includes(e.message)?e.message:'INPUT_OR_OUTPUT_UNAVAILABLE_OR_INVALID',businessActionsExecuted:false})+'\n');return 1;}
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url))process.exitCode=runCli(process.argv.slice(2));
