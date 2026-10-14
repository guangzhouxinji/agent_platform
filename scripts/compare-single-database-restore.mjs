#!/usr/bin/env node
import {readFile,lstat,realpath} from 'node:fs/promises';import {resolve,isAbsolute} from 'node:path';import {pathToFileURL} from 'node:url';
import {openSnapshot} from './lib/single-database-lifecycle-snapshot.mjs';import {writeRestoreComparisonEvidence} from './lib/single-database-restore-evidence.mjs';import {compareRestoreInventories} from './lib/single-database-restore-comparison.mjs';import {hash,canonical} from './lib/single-database-lifecycle-runtime.mjs';import {RESTORE_CATALOG_PROFILE,RESTORE_CATALOG_PROFILE_SHA256} from './lib/single-database-restore-catalog.mjs';
const fail=code=>{throw new Error(code);};const sha=/^[a-f0-9]{64}$/;
async function exactFile(file,max){if(!isAbsolute(file||'')||await realpath(file)!==resolve(file))fail('RESTORE_COMPARISON_FILE_REDIRECT');const st=await lstat(file);if(!st.isFile()||st.isSymbolicLink()||st.size>max)fail('RESTORE_COMPARISON_FILE_LIMIT');const bytes=await readFile(file);if(bytes.length>max)fail('RESTORE_COMPARISON_FILE_LIMIT');return bytes;}
/** Offline diagnostic only: authenticates source package, preserves both inventories, no native import. */
export async function executeRestoreComparison(c,directory){
 if(!c||!sha.test(c.sourceTargetId||'')||!sha.test(c.targetId||'')||!sha.test(c.packageSha256||'')||!sha.test(c.destinationInventorySha256||'')||!/^[_A-Z][_A-Z0-9]{1,79}$/.test(c.keyEnv||'')||!isAbsolute(directory||'')||resolve(directory)!==directory)fail('EXACT_RESTORE_COMPARISON_CONFIGURATION_REQUIRED');
 const destinationBytes=await exactFile(c.destinationInventoryPath,32*1024*1024);if(hash(destinationBytes)!==c.destinationInventorySha256)fail('RESTORE_COMPARISON_TARGET_RAW_SHA_MISMATCH');
 await exactFile(c.packagePath,258*1024*1024);const key=Buffer.from(process.env[c.keyEnv]||'','base64');let opened;
 try{opened=await openSnapshot({file:c.packagePath,key,expectedTargetId:c.sourceTargetId,expectedSha256:c.packageSha256});
  const sourceBytes=Buffer.from(JSON.stringify(opened.content.inventory)+'\n');
  return await writeRestoreComparisonEvidence({directory,sourceBytes,destinationBytes,compare:({source,destination})=>{
   const p=opened.metadata.recoveryCatalog,expected=c.recoveryCatalog;
   if(!p||!opened.content.recoveryOrigin||!expected||expected.profileId!==RESTORE_CATALOG_PROFILE.profileId||expected.profileSha256!==RESTORE_CATALOG_PROFILE_SHA256||p.profileId!==expected.profileId||p.profileSha256!==expected.profileSha256)fail('EXACT_AUTHENTICATED_RESTORE_PROFILE_REQUIRED');
   if(!Array.isArray(expected.originFiles)||canonical(expected.originFiles.map(({kind,sha256})=>({kind,rawSha256:sha256})).sort((a,b)=>a.kind.localeCompare(b.kind)))!==canonical(p.originFiles.map(({kind,rawSha256})=>({kind,rawSha256})).sort((a,b)=>a.kind.localeCompare(b.kind))))fail('RESTORE_COMPARISON_ORIGIN_BINDING_MISMATCH');
   return compareRestoreInventories({source,destination,origin:opened.content.recoveryOrigin,sourceTargetId:c.sourceTargetId,targetId:c.targetId,schemas:c.schemas,accounts:c.accounts,deployment:c.deployment});
  }});
 }finally{key.fill(0);opened?.native.fill(0);if(opened?.content){opened.content.native='';for(const f of opened.content.files)f.bytes='';}}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){const [configPath,directory]=process.argv.slice(2);if(!configPath||!directory||process.argv.length!==4){console.error('Usage: node scripts/compare-single-database-restore.mjs CONFIG.json NEW_EVIDENCE_DIRECTORY');process.exitCode=2;}else try{const c=JSON.parse(await readFile(configPath,'utf8')),r=await executeRestoreComparison(c,resolve(directory));console.log(JSON.stringify({passed:r.passed,code:r.code,directory:r.directory,sourceSha256:r.sourceSha256,destinationSha256:r.destinationSha256,reportSha256:r.reportSha256,actualNativeRestoreProven:false}));process.exitCode=r.passed?0:1;}catch(e){console.error(JSON.stringify({passed:false,code:/^[A-Z][A-Z0-9_]{0,79}$/.test(e.message)?e.message:'RESTORE_COMPARISON_INPUT_OR_EVIDENCE_FAILED',actualNativeRestoreProven:false}));process.exitCode=1;}}
