import {readFile,writeFile} from 'node:fs/promises';
import {canonical,hash} from '../../../../../scripts/lib/single-database-lifecycle-runtime.mjs';
import {checkObjectGrantInventory} from '../../../../../scripts/lib/single-database-object-grants.mjs';
const json=async u=>JSON.parse((await readFile(u,'utf8')).replace(/^\uFEFF/,''));
const base=new URL('../',import.meta.url);
const i=await json(new URL('fresh-prebootstrap-inventory44.json',import.meta.url));
const b=await json(new URL('adjudication-runtime40/publisher42/post-registration-inventory42.json',base));
const c=await json(new URL('primary-grant-config08.json',base));
if(!i.complete||!i.actualJdbc||i.targetId!==b.targetId||i.schemas.length!==6||i.accounts.length!==15)throw Error('COMPLETE_ACTUAL_BASELINE_REQUIRED');
for(const k of ['schemas','nativePrivileges','accounts','foreignKeyDependencies'])if(canonical(i[k])!==canonical(b[k]))throw Error('BASELINE_DRIFT:'+k);
const present=checkObjectGrantInventory(i,c.manifest);
if(c.manifest.steps.length!==674||present.size!==674||c.manifest.steps.some(s=>!present.has(`${s.schema}.${s.object}:${s.grantee}:${s.privilege}`)))throw Error('FULL674_REQUIRED');
const checks=[];
for(const [schema,tables] of Object.entries({ENFPROD:['ID2_SUBJECT','ID2_SUBJECT_EVENT','ID2_KEY_CONTROL','ID2_SESSION','ID2_GRANT','ID2_GRANT_EVENT','ID2_CURRENT_EPOCH','ID2_CLIENT_CURRENT','ID2_CLIENT_EVENT','ID2_CLIENT_REQUEST','OAUTH2_REGISTERED_CLIENT'],ENFAUTH:['ID2_MAINTAINER','ID2_AUTHORITY_WATERMARK','ID2_MAINTENANCE_AUDIT']}))for(const table of tables){const r=i.schemas.find(s=>s.schema===schema)?.rows?.find(r=>r.table===table);if(!r||r.count!==0)throw Error('ENF_INITIAL_IDENTITY_NOT_EMPTY:'+table);checks.push({schema,table,count:r.count});}
const result={format:'FRESH_ACTUAL_674_AND_ENF_IDENTITY_EMPTY/v1',capturedAt:new Date().toISOString(),actualJdbc:i.actualJdbc,targetId:i.targetId,complete:i.complete,presentRequiredObjectGrants:present.size,schemasAccountsPrivilegesAndRowsUnchangedSince42:true,checks,dclExecuted:false,wholeDomainClaimedEmpty:false};
await writeFile(new URL('fresh-prebootstrap-permission44.json',import.meta.url),JSON.stringify(result,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify(result));

