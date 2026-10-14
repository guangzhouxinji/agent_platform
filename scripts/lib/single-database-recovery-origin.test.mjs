import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';import {loadRecoveryOrigin,assertRecoverySource,recoveryBinding} from './single-database-recovery-origin.mjs';import {RESTORE_CATALOG_PROFILE,RESTORE_CATALOG_PROFILE_SHA256} from './single-database-restore-catalog.mjs';
const base=new URL('../../docs/evidence/single-database-installation-20261009/approved-execution/',import.meta.url);
const bytes=p=>fs.readFileSync(new URL(p,base));const inventory=JSON.parse(bytes('restore-profile51/historical-lossless-unit-input51.json'));
// Existing historical source artifacts; still a disconnected owned unit projection, no DM8/restore.
const configuration={workspaceRoot:path.resolve('.'),profileId:RESTORE_CATALOG_PROFILE.profileId,profileSha256:RESTORE_CATALOG_PROFILE_SHA256,originFiles:[['CONTROLLED_SOURCE_ORIGIN_PROOF','controlled-origin-proof50.json'],['CONTROLLED_MIGRATION_HISTORY','approved-migration-history50.json']].map(([kind,file])=>{let url=new URL('restore-catalog-completeness50/'+file,base);return {kind,path:path.resolve(decodeURIComponent(url.pathname.replace(/^\/([A-Za-z]:)/,'$1'))),sha256:hash(fs.readFileSync(url))};})};
// The generic URL-to-path helper is platform-correct even when this test runs outside Windows.
configuration.originFiles.forEach((f,n)=>{const file=configuration.originFiles[n].kind==='CONTROLLED_SOURCE_ORIGIN_PROOF'?'controlled-origin-proof50.json':'approved-migration-history50.json';f.path=path.resolve('docs/evidence/single-database-installation-20261009/approved-execution/restore-catalog-completeness50',file);});
const origin=await loadRecoveryOrigin(configuration);
test('raw and canonical origin files are retained independently and bound to profile/native privileges',()=>{const binding=recoveryBinding(inventory,origin);assert.equal(binding.profileSha256,RESTORE_CATALOG_PROFILE_SHA256);assert.equal(binding.originFiles.length,2);for(const f of origin.files){assert.equal(hash(Buffer.from(f.rawBase64,'base64')),f.rawSha256);assert.equal(hash(canonical(f.value)),f.canonicalSha256);}assert.ok(binding.nativePrivilegesSha256);});
test('manual index names are recovered from exact original statement pins, not absent map keys',()=>assert.equal(assertRecoverySource(inventory,origin),inventory));
for(const [name,mutate,pattern] of [
 ['wrong source target',i=>i.targetId='0'.repeat(64),/SOURCE_OR_HISTORY_MISMATCH/],
 ['generated native ID changed',i=>i.restoreCatalog.categories.nativeConstraints.rows.find(c=>c.CONSTRAINT_NAME.startsWith('CONS')).ID='999999999',/CONSTRAINT_REFERENCE|IDENTITY_MISMATCH/],
 ['creation identity changed',i=>i.restoreCatalog.categories.nativeObjects.rows.find(c=>c.NAME.startsWith('CONS')).CRTDATE='2026-10-10 00:00:00',/GENERATED_IDENTITY_MISMATCH/],
 ['manual index renamed',i=>i.schemas.find(s=>s.indexes.some(i=>i.GENERATED==='N')).indexes.find(i=>i.GENERATED==='N').INDEX_NAME='UNKNOWN',/MANUAL_INDEX_COVERAGE/],
 ['source supplementary category removed',i=>delete i.restoreCatalog.categories.hiddenTexts,/CATEGORY_SET/]
])test(name,()=>{const i=structuredClone(inventory);mutate(i);assert.throws(()=>assertRecoverySource(i,origin),pattern);});
for(const field of ['rawSha256','canonicalSha256'])test('origin '+field+' tampering refuses',()=>{const o=structuredClone(origin);o.files[0][field]='0'.repeat(64);assert.throws(()=>recoveryBinding(inventory,o),/AUTHENTICATED_CONTENT_MISMATCH/);});
test('origin parsed object tampering refuses even though raw original is intact',()=>{const o=structuredClone(origin);o.files[0].value.generatedCandidates[0].name='UNKNOWN';assert.throws(()=>recoveryBinding(inventory,o),/AUTHENTICATED_CONTENT_MISMATCH/);});
test('wrong configured original SHA refuses before reading accepted provenance',async()=>{const c=structuredClone(configuration);c.originFiles[0].sha256='0'.repeat(64);await assert.rejects(()=>loadRecoveryOrigin(c),/RAW_SHA_MISMATCH/);});
test('duplicate origin kind cannot substitute for two originals',async()=>{const c=structuredClone(configuration);c.originFiles[1].kind=c.originFiles[0].kind;await assert.rejects(()=>loadRecoveryOrigin(c),/CONFIGURATION_REQUIRED/);});

test('workspace junction redirects are refused rather than trusting resolved original SQL',async()=>{
 const os=await import('node:os');const root=fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(),'recovery-origin-root-unit-'))),link=path.join(root,'redirect');
 try{fs.symlinkSync(configuration.workspaceRoot,link,process.platform==='win32'?'junction':'dir');const c=structuredClone(configuration);c.workspaceRoot=link;await assert.rejects(()=>loadRecoveryOrigin(c),/RECOVERY_ORIGIN_WORKSPACE_REDIRECT/);}
 finally{if(fs.existsSync(link))fs.unlinkSync(link);assert.equal(path.dirname(root).toLowerCase(),fs.realpathSync(os.tmpdir()).toLowerCase());assert.ok(path.basename(root).startsWith('recovery-origin-root-unit-'));fs.rmdirSync(root);}
});
