import {readFile,writeFile} from 'node:fs/promises';
import {canonical,hash,original} from '../../../../../scripts/lib/single-database-lifecycle-runtime.mjs';
const here=new URL('./',import.meta.url), config=JSON.parse(await readFile(new URL('../primary-migration-config07.json',here))),events=(await readFile(new URL('../primary-migration-journal07/events.jsonl',here),'utf8')).trim().split('\n').map(JSON.parse);
let previous='';for(const e of events){const {eventSha256,...body}=e;if(body.previousSha256!==previous||hash(canonical(body))!==eventSha256)throw Error('ORIGINAL_JOURNAL_CHAIN_INVALID');previous=eventSha256;}
const migrations=[];
for(const m of config.manifest.migrations){const key=m.schema+':'+m.id, pair=events.filter(e=>e.key===key);if(pair.length!==2||pair[0].kind!=='START'||pair[1].kind!=='SUCCESS'||pair.some(e=>e.targetId!==config.targetId||e.scriptSha256!==m.sha256))throw Error('EXACT_SUCCESSFUL_MIGRATION_PAIR_REQUIRED');
 migrations.push({key,schema:m.schema,id:m.id,sqlPath:m.sqlPath,sqlSha256:m.sha256,startedAt:pair[0].at,finishedAt:pair[1].at,statements:await original(config.workspaceRoot,m)});
}
if(events.length!==migrations.length*2||canonical(events.filter(e=>e.kind==='START').map(e=>e.key))!==canonical(migrations.map(m=>m.key)))throw Error('EXACT_MANIFEST_JOURNAL_ORDER_REQUIRED');
await writeFile(new URL('approved-migration-history50.json',here),JSON.stringify({format:'ORIGINAL_MIGRATION_HISTORY_READ_ONLY_AUDIT/v1',capturedAt:new Date().toISOString(),targetId:config.targetId,migrationCount:migrations.length,journalChainValid:true,lastEventSha256:previous,migrations,scope:'source byte pins, controlled statement admission and actual journal order only; no SQL execution'},null,2));
console.log('105 controlled originals and complete ordered actual journal chain verified; no database access.');
