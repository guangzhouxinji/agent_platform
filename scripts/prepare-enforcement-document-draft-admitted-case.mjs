import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const [run,admissionFile]=process.argv.slice(2);
assert.match(run??'',/^[A-Za-z0-9-]+$/);assert.ok(admissionFile);
const admission=JSON.parse(await readFile(admissionFile,'utf8'));
assert.equal(admission.nature,'FUNCTIONAL_VALIDATION_NOT_BUSINESS_ACCEPTANCE');
assert.equal(admission.materials.length,2);
const root=resolve('docs/evidence/enforcement-document-draft-20261002',run);await mkdir(root,{recursive:false});
const materials=admission.materials.map(({material,actions,originalSha256})=>{
  assert.equal(material.state,'VERIFIED');assert.equal(material.version,3);assert.equal(material.sha256,originalSha256);
  assert.deepEqual(actions.map(a=>a.action),['UPLOAD','COLLECT_ATTEST','VERIFY']);assert.notEqual(actions[1].actor,actions[2].actor);
  return {id:material.id,version:String(material.version),sha256:material.sha256};
});
const context={caseRef:'CNIPA-GUIDANCE-CASE-1-PUBLIC-'+run,version:'1',caseType:'PUBLIC_GUIDANCE_CASE',organization:admission.materials[0].material.organization,
  nature:'PUBLIC_HISTORICAL_SOURCE_FUNCTIONAL_VALIDATION_NOT_BUSINESS_ACCEPTANCE',source:admission.publicSource,
  eventRef:'material/'+materials[0].id+'/actions/3',materials,
  unknownFacts:'No current case events, official approval, recorded date or legal findings inferred from this registry linkage'};
const caseBytes=Buffer.from(JSON.stringify(context,null,2)+'\n');await writeFile(resolve(root,'case.json'),caseBytes,{flag:'wx'});
const oldCatalogFile=resolve('data/enforcement/document-rule-v2-product-input-v1/catalog.json');
const catalog=JSON.parse(await readFile(oldCatalogFile,'utf8'));
for(const row of catalog.schemas)row.sourceFile=resolve(dirname(oldCatalogFile),row.sourceFile);
const assetRoot=resolve('docs/evidence/enforcement-document-draft-20261002/ENF14-PUBLIC-INPUT-20261002-40');
const fresh=JSON.parse(await readFile(resolve(assetRoot,'mapping-catalog.json'),'utf8'));
for(const row of fresh.schemas){row.sourceFile=resolve(assetRoot,row.sourceFile);assert.equal(createHash('sha256').update(await readFile(row.sourceFile)).digest('hex'),row.sourceSha256);catalog.schemas.push(row);}
await writeFile(resolve(root,'mapping-catalog.json'),JSON.stringify(catalog,null,2)+'\n',{flag:'wx'});
await writeFile(resolve(root,'case-linkage.json'),JSON.stringify({run,scope:'EXISTING_VERSIONED_CASE_SOURCE_ADAPTER_LINKAGE_NOT_DB_STATE_CHANGE',caseRef:context.caseRef,caseSha256:createHash('sha256').update(caseBytes).digest('hex'),materials,admissionFile:resolve(admissionFile),source:admission.publicSource,oldSchemaCount:catalog.schemas.length-fresh.schemas.length,addedSchemaCount:fresh.schemas.length,businessAcceptance:'PENDING'},null,2)+'\n',{flag:'wx'});
console.log('Same-source case adapter linked to '+materials.length+' product-verified materials; prior catalog snapshots retained');
