import {readFile,writeFile} from 'node:fs/promises';
import {checkObjectGrantInventory} from '../../../../../scripts/lib/single-database-object-grants.mjs';
const root=new URL('../',import.meta.url);
const c=JSON.parse(await readFile(new URL('primary-grant-config08.json',root),'utf8'));
const i=JSON.parse(await readFile(new URL('fresh-pre-bootstrap40.json',import.meta.url),'utf8'));
const present=checkObjectGrantInventory(i,c.manifest);
if(c.manifest.steps.length!==674||present.size!==674||c.manifest.steps.some(s=>!present.has(`${s.schema}.${s.object}:${s.grantee}:${s.privilege}`)))throw Error('FULL674_REQUIRED');
const required={ADJPROD:['ID2_SUBJECT','ID2_SUBJECT_EVENT','ID2_KEY_CONTROL','ID2_SESSION','ID2_GRANT','ID2_GRANT_EVENT','ID2_CURRENT_EPOCH'],ADJAUTH:['ID2_MAINTAINER','ID2_AUTHORITY_WATERMARK','ID2_MAINTENANCE_AUDIT']};
const checks=[];
for(const [schema,tables] of Object.entries(required))for(const table of tables){const r=i.schemas.find(s=>s.schema===schema)?.rows?.find(r=>r.table===table);if(!r||r.count!==0)throw Error('ADJ_INITIAL_IDENTITY_NOT_EMPTY');checks.push({schema,table,count:r.count});}
const result={format:'FRESH_ACTUAL_674_AND_ADJ_IDENTITY_EMPTY/v1',capturedAt:new Date().toISOString(),actualJdbc:i.actualJdbc,targetId:i.targetId,complete:i.complete,presentRequiredObjectGrants:present.size,checks,dclExecuted:false,wholeDomainClaimedEmpty:false};
await writeFile(new URL('fresh-pre-bootstrap-permission40.json',import.meta.url),JSON.stringify(result,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify(result));

