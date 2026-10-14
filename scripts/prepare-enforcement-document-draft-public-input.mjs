import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve,dirname,sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const workspace=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const run=process.argv[2];
if(!run||!/^[A-Za-z0-9-]+$/.test(run))throw new Error('Fresh safe execution ID required');
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const catalogPath=resolve(workspace,'data/enforcement/public-job-pack-candidates-v1/candidates.json');
const catalogBytes=await readFile(catalogPath);const catalog=JSON.parse(catalogBytes);
const candidate=catalog.candidates.find(c=>c.publicSourceFacts.sourceId==='CNIPA_GUIDANCE_CASE_1_INTERPRETATION');
assert.ok(candidate?.collection.allowed&&candidate.collection.decision==='COLLECTION_ALLOWED','Existing recorded collection purpose required');
const facts=candidate.publicSourceFacts;const sourceRoot=dirname(catalogPath);
const attemptsBytes=await readFile(resolve(sourceRoot,'collection-attempts.json'));const attempts=JSON.parse(attemptsBytes);
const capture=attempts.attempts.find(a=>a.sourceId===facts.sourceId&&a.requestedUrl===facts.originalUrl&&a.httpStatus===200&&a.result==='SUCCEEDED');
assert.ok(capture,'Retained successful actual HTTP capture required; no invented access result');
const originalPath=resolve(sourceRoot,facts.rawFile);assert.ok(originalPath.startsWith(sourceRoot+sep));
const original=await readFile(originalPath);assert.equal(sha(original),facts.rawSha256,'Original raw SHA must match retained capture');
const html=new TextDecoder('utf-8',{fatal:true}).decode(original);
const beginning=html.indexOf('<meta name="ContentStart"/>');const end=html.indexOf('<meta name="ContentEnd"/>',beginning);
assert.ok(beginning>=0&&end>beginning,'Exact public article locator required');
const article=html.slice(beginning,end);const title=facts.title;
assert.ok(html.includes(title),'Recorded title must exist in original');
const parties=title.match(/查处(.+?)侵犯/);assert.ok(parties&&article.includes(parties[1]),'Party must be present in original, not inferred');
const out=resolve(workspace,'docs/evidence/enforcement-document-draft-20261002',run);
await mkdir(out,{recursive:false});
const sourceId='CNIPA-GUIDANCE-CASE-1-DERIVED-'+run;
const provenance={sourceRef:sourceId,sourceVersion:'1',confirmed:true};
const value=(v,locator)=>({...provenance,value:v,locator});
// Source-document facts only. No current case, actor, approval or legal applicability is synthesized.
const values={
  'party.name':value(parties[1],'official-title#case-party;source-raw-sha:'+facts.rawSha256),
  'analysis.summary':{...value(title,'meta[name=ArticleTitle]'),confirmed:false}
};
const encoded=Buffer.from(JSON.stringify(values,null,2)+'\n');
const profiles=JSON.parse(await readFile(resolve(workspace,'backend/enforcement/src/main/resources/enforcement/document-template-baseline-v1/profiles.json')));
const profile=profiles.profiles.find(p=>p.documentType==='FACT_RECORD');assert.ok(profile);
const fields=profile.defaultMappings.map(m=>{
  const field=profile.fields.find(f=>f.id===m.fieldId);assert.ok(field);
  return {path:m.sourcePath,type:field.type,sourceRef:sourceId,confirmed:values[m.sourcePath]?.confirmed??false};
});
const schemaId='CNIPA-GUIDANCE-CASE-1-FACT-SCHEMA-'+run;
await writeFile(resolve(out,'official-original.html'),original,{flag:'wx'});
await writeFile(resolve(out,'capture-evidence.json'),JSON.stringify({scope:'REUSED_ORIGINAL_CAPTURE_NOT_NEW_NETWORK_REQUEST',queryStrategy:attempts.queryStrategy,capture,collectionAttemptsSha256:sha(attemptsBytes),originalSha256:sha(original)},null,2)+'\n',{flag:'wx'});
await writeFile(resolve(out,'derived-values.json'),encoded,{flag:'wx'});
await writeFile(resolve(out,'mapping-catalog.json'),JSON.stringify({schemas:[{organization:'ORG-ENF-FUNCTIONAL',schema:{id:schemaId,version:'1',fields},authorizationRevision:1,sourceId,sourceRevision:1,sourceFile:'derived-values.json',sourceSha256:sha(encoded)}]},null,2)+'\n',{flag:'wx'});
const report={run,preparedAt:new Date().toISOString(),scope:'PUBLIC_SNAPSHOT_INPUT_PREPARATION_NOT_RUNTIME_ADMISSION_OR_PRODUCT_I',
  source:{...facts,originalFile:'official-original.html',candidateCatalogSha256:sha(catalogBytes),captureEvidence:'capture-evidence.json',originalHttpStatus:capture.httpStatus},
  collection:candidate.collection,rights:candidate.rights,
  derived:{file:'derived-values.json',sha256:sha(encoded),sourceId,revision:1,sourceOriginalSha256:sha(original),method:'EXACT_RECORDED_TITLE_AND_PARTY_SUBSTRING_ONLY',knownPaths:Object.keys(values)},
  template:{id:profile.id,revision:profile.revision,classification:profile.classification},
  mapping:{schemaId,schemaVersion:'1',fieldCount:fields.length,defaultMappings:profile.defaultMappings,publication:'NOT_RUN'},
  missingPaths:profile.defaultMappings.filter(m=>!Object.hasOwn(values,m.sourcePath)).map(m=>({field:m.fieldId,path:m.sourcePath,missing:m.missing})),
  runtimeAdmission:'NOT_RUN',caseCreated:false,materialUploaded:false,legalReferenceConfirmed:false,documentGenerated:false,
  businessAcceptance:'PENDING',nextRequiredProductActions:['Register actual public source using recorded COLLECTION_ALLOWED purpose','Upload original and separately identified derived values through material product workflow','Associate actual case/material versions without changing source facts','Publish exact template mapping through DocumentRuleService','Bind an actually valid reviewed legal reference; do not replace with symbolic technical score','Run browser prepare/generate/query and DB/file/SHA/event/receipt reconciliation']};
await writeFile(resolve(out,'public-input-preparation.json'),JSON.stringify(report,null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({run,originalSha256:sha(original),derivedSha256:sha(encoded),knownFieldCount:Object.keys(values).length,missingFieldCount:report.missingPaths.length,runtimeAdmission:'NOT_RUN',productsGenerated:0}));
