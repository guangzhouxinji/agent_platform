import {createCipheriv,createDecipheriv,randomBytes} from 'node:crypto';
import {readFile,writeFile,open,realpath,lstat} from 'node:fs/promises';
import path from 'node:path';
import {hash,canonical,inventoryDigest,assertInventory,admission} from './single-database-lifecycle-runtime.mjs';
import {loadRecoveryOrigin,recoveryBinding} from './single-database-recovery-origin.mjs';
import {validateHumanWaitingBinding,assertHumanWaitingCurrent,assertHumanWaitingInventory,authenticateHumanWaiting,validateAuthenticatedHumanWaiting} from './single-database-human-waiting-binding.mjs';
const fail=s=>{throw new Error(s);};
const MAGIC=Buffer.from('XJSNAP02');const LIMIT=128*1024*1024;
export async function sealSnapshot({approval,inventory,exportDatabase,files=[],key,output,acquireFence,schemas,accounts,deployment,recoveryCatalog,humanWaiting,now=Date.now()}) {
  admission(approval,approval.targetId,'snapshot',now);
  if(!Buffer.isBuffer(key)||key.length!==32||!acquireFence||!exportDatabase)fail('KEY_AND_ACTUAL_EXPORT_AND_FENCE_REQUIRED');
  if(Boolean(humanWaiting)!==Boolean(approval.humanWaitingConfigurationSha256)||(humanWaiting&&typeof humanWaiting!=='function'))fail('EXPLICIT_APPROVED_HUMAN_WAITING_REQUIRED');
  if(!Array.isArray(approval.writers)||!approval.writers.length||approval.writers.some(w=>!w.id||!w.owner||!w.responsibilitySource))fail('COMPLETE_WRITER_RESPONSIBILITY_REQUIRED');
  const rights=approval.databaseRights;
  if(!rights?.owner||!rights.licenseReference||!rights.purpose||!(Date.parse(rights.useUntil)>now)||!Array.isArray(rights.schemas)||canonical([...rights.schemas].sort())!==canonical([...schemas].sort()))fail('CURRENT_DATABASE_SOURCE_RIGHTS_REQUIRED');
  if(!Array.isArray(files)||new Set(files.map(f=>f.path)).size!==files.length)fail('EXACT_UNIQUE_ORIGINAL_SET_REQUIRED');
  const currentTime=()=>Math.max(now,Date.now());
  let humanBefore;
  const assertCurrent=()=>{
    const at=currentTime();admission(approval,approval.targetId,'snapshot',at);
    if(!(Date.parse(rights.useUntil)>at))fail('CURRENT_DATABASE_SOURCE_RIGHTS_REQUIRED');
    if(!Array.isArray(approval.files)||approval.files.some(f=>!f.licenseReference||!(Date.parse(f.useUntil)>at)))fail('CURRENT_ORIGINAL_RIGHTS_REQUIRED');
    if(humanBefore)assertHumanWaitingCurrent(humanBefore,at);
  };
  const lease=await acquireFence(approval.writers);
  if(!lease?.assertHeld||!lease.release)fail('WRITER_FENCE_REQUIRED');
  let payload,native;
  try {
    await lease.assertHeld();assertCurrent();
    if(humanWaiting){
      humanBefore=structuredClone(await humanWaiting({lease,phase:'before'}));
      validateHumanWaitingBinding(humanBefore,approval.targetId,files);
      if(humanBefore.configurationSha256!==approval.humanWaitingConfigurationSha256)fail('APPROVED_HUMAN_WAITING_CONFIGURATION_REQUIRED');
      await lease.assertHeld();assertCurrent();
    }
    const before=assertInventory(await inventory(),approval.targetId,schemas,accounts,deployment);assertCurrent();
    if(humanBefore)assertHumanWaitingInventory(humanBefore,before);
    if(Boolean(recoveryCatalog)!==Boolean(before.restoreCatalog))fail('EXACT_RECOVERY_CATALOG_CONFIGURATION_REQUIRED');
    if(recoveryCatalog&&approval.recoveryCatalogConfigurationSha256!==hash(canonical(recoveryCatalog)))fail('APPROVED_RECOVERY_CATALOG_CONFIGURATION_REQUIRED');
    const recoveryOrigin=recoveryCatalog?await loadRecoveryOrigin(recoveryCatalog):null;
    const sourceRecovery=recoveryOrigin?recoveryBinding(before,recoveryOrigin):null;
    assertCurrent();native=await exportDatabase();assertCurrent();if(!Buffer.isBuffer(native)||!native.length||native.length>LIMIT)fail('ACTUAL_BOUNDED_DATABASE_EXPORT_REQUIRED');
    const originals=[];let total=native.length;
    if(files.length!==approval.files?.length)fail('EXACT_APPROVED_FILE_SET_REQUIRED');
    for(const f of files){assertCurrent();const approvalFile=approval.files.find(a=>a.path===f.path&&a.sha256===f.sha256);
      if(!approvalFile?.licenseReference||!(Date.parse(approvalFile.useUntil)>currentTime()))fail('CURRENT_ORIGINAL_RIGHTS_REQUIRED');
      const actual=await realpath(f.path);if(actual!==path.resolve(f.path)||(await lstat(actual)).isSymbolicLink()||!(await lstat(actual)).isFile())fail('ORIGINAL_FILE_REDIRECT');
      assertCurrent();const bytes=await readFile(actual);assertCurrent();total+=bytes.length;if(total>LIMIT||hash(bytes)!==f.sha256)fail('ORIGINAL_DIGEST_OR_CAPACITY');
      originals.push({originalKey:actual,sha256:hash(bytes),licenseReference:approvalFile.licenseReference,bytes:bytes.toString('base64')});
    }
    await lease.assertHeld();assertCurrent();const after=assertInventory(await inventory(),approval.targetId,schemas,accounts,deployment);
    if(inventoryDigest(before)!==inventoryDigest(after))fail('DATABASE_CHANGED_INSIDE_FENCE');
    // Re-read pinned origin bytes and original SQL under the same fence; the cached envelope
    // alone cannot detect a filesystem change during native export.
    const recoveryOriginAfter=recoveryCatalog?await loadRecoveryOrigin(recoveryCatalog):null;
    if(sourceRecovery&&canonical(recoveryBinding(after,recoveryOriginAfter))!==canonical(sourceRecovery))fail('RECOVERY_CATALOG_OR_NATIVE_PRIVILEGES_CHANGED_INSIDE_FENCE');
    for(const f of files)if(hash(await readFile(f.path))!==f.sha256)fail('FILE_CHANGED_INSIDE_FENCE');
    let human;
    if(humanWaiting){
      const humanAfter=structuredClone(await humanWaiting({lease,phase:'after'}));
      await lease.assertHeld();assertCurrent();
      human=authenticateHumanWaiting(humanBefore,humanAfter,inventoryDigest(before),approval.targetId,files,approval.humanWaitingConfigurationSha256);
    }
    const expiresAt=[approval.expiresAt,rights.useUntil,...approval.files.map(f=>f.useUntil),...(human?[humanBefore.backupUntil]:[])].sort((a,b)=>Date.parse(a)-Date.parse(b))[0];
    const metadata={format:'SINGLE_DATABASE_AUTHENTICATED_SNAPSHOT/v2',targetId:approval.targetId,createdAt:new Date(now).toISOString(),expiresAt,inventorySha256:inventoryDigest(before),approvalSha256:hash(canonical(approval)),databaseRights: rights,currentAuthorityOutsideBackup:approval.currentAuthorityOutsideBackup===true,exportMethod:approval.exportMethod,...(approval.nativeExportKeyReference?{nativeExportKeyReference:approval.nativeExportKeyReference}:{}),scope:approval.scope,nativeSha256:hash(native),nativeBytes:native.length,fileDigests:originals.map(({bytes,...f})=>f),writers:approval.writers,...(sourceRecovery?{recoveryCatalog:sourceRecovery}:{})};
    if(human)metadata.humanWaiting=human.metadata;
    payload=Buffer.from(JSON.stringify({native:native.toString('base64'),files:originals,inventory:before,...(recoveryOrigin?{recoveryOrigin}:{}),...(human?{humanWaiting:human.content}:{})}));native.fill(0);
    if(payload.length>LIMIT*2)fail('PACKAGE_CAPACITY');const header=Buffer.from(canonical(metadata));if(header.length>1024*1024)fail('HEADER_CAPACITY');
    const nonce=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,nonce);cipher.setAAD(header);
    const encrypted=Buffer.concat([cipher.update(payload),cipher.final()]);const size=Buffer.alloc(4);size.writeUInt32BE(header.length);
    const sealed=Buffer.concat([MAGIC,size,header,nonce,cipher.getAuthTag(),encrypted]);
    await lease.assertHeld();assertCurrent();await writeFile(output,sealed,{flag:'wx',mode:0o600});assertCurrent();
    return {format:'SINGLE_DATABASE_SNAPSHOT_RECEIPT/v2',actualSnapshot:true,metadata,sealedSha256:hash(sealed),sealedBytes:sealed.length,auditRetentionSeparateFromUseValidity:true,fullThreeDomainProductRecoveryProven:false};
  }finally{payload?.fill(0);native?.fill(0);await lease.release();}
}
export async function openSnapshot({file,key,expectedTargetId,expectedSha256,now=Date.now()}) {
  if(!Buffer.isBuffer(key)||key.length!==32)fail('MANAGED_KEY_REQUIRED');
  const currentTime=()=>Math.max(now,Date.now());
  // Read only bounded public header to reject expired material before opening its encrypted body.
  const fd=await open(file,'r');let metadata,header,headerSize;
  try {const first=Buffer.alloc(12);if((await fd.read(first,0,12,0)).bytesRead!==12||!first.subarray(0,8).equals(MAGIC))fail('PACKAGE_FORMAT');headerSize=first.readUInt32BE(8);if(headerSize<2||headerSize>1024*1024)fail('HEADER_CAPACITY');header=Buffer.alloc(headerSize);if((await fd.read(header,0,headerSize,12)).bytesRead!==headerSize)fail('PACKAGE_TRUNCATED');metadata=JSON.parse(header);
    if(metadata.format!=='SINGLE_DATABASE_AUTHENTICATED_SNAPSHOT/v2'||metadata.targetId!==expectedTargetId||!(Date.parse(metadata.expiresAt)>currentTime()))fail('EXPIRED_OR_WRONG_TARGET_NO_BODY_OPEN');
    // One checked size drives allocation; later stats only detect changes.
    const size=(await fd.stat()).size;
    if(!Number.isSafeInteger(size)||size<12+headerSize+28||size>LIMIT*2+1024*1024+40)fail('PACKAGE_CAPACITY');
    if(!(Date.parse(metadata.expiresAt)>currentTime()))fail('EXPIRED_OR_WRONG_TARGET_NO_BODY_OPEN');
    const bytes=Buffer.alloc(size);if((await fd.read(bytes,0,size,0)).bytesRead!==size)fail('PACKAGE_TRUNCATED');
    if((await fd.stat()).size!==size)fail('PACKAGE_CHANGED_DURING_READ');
    if(expectedSha256&&hash(bytes)!==expectedSha256)fail('PACKAGE_SHA_MISMATCH');
    const start=12+headerSize,d=createDecipheriv('aes-256-gcm',key,bytes.subarray(start,start+12));d.setAAD(header);d.setAuthTag(bytes.subarray(start+12,start+28));
    let plain;try{plain=Buffer.concat([d.update(bytes.subarray(start+28)),d.final()]);}catch{fail('PACKAGE_AUTHENTICATION_FAILED_NO_TARGET_WRITE');}
    let native,returned=false;
    try {
      if(!(Date.parse(metadata.expiresAt)>currentTime()))fail('EXPIRED_NO_PLAINTEXT_RETURN');
      const content=JSON.parse(plain);
      if(typeof content.native!=='string'||!Number.isSafeInteger(metadata.nativeBytes)||metadata.nativeBytes<1||metadata.nativeBytes>LIMIT)fail('AUTHENTICATED_CONTENT_MISMATCH');
      native=Buffer.from(content.native,'base64');
      if(native.toString('base64')!==content.native||hash(native)!==metadata.nativeSha256||native.length!==metadata.nativeBytes||inventoryDigest(content.inventory)!==metadata.inventorySha256)fail('AUTHENTICATED_CONTENT_MISMATCH');
      if(Boolean(metadata.recoveryCatalog)!==Boolean(content.inventory?.restoreCatalog)||Boolean(metadata.recoveryCatalog)!==Boolean(content.recoveryOrigin))fail('AUTHENTICATED_RECOVERY_CATALOG_MISSING');
      if(metadata.recoveryCatalog&&canonical(recoveryBinding(content.inventory,content.recoveryOrigin))!==canonical(metadata.recoveryCatalog))fail('AUTHENTICATED_RECOVERY_CATALOG_MISMATCH');
      if(!Array.isArray(content.files)||!Array.isArray(metadata.fileDigests)||content.files.length!==metadata.fileDigests.length||new Set(content.files.map(f=>f.originalKey)).size!==content.files.length||new Set(metadata.fileDigests.map(f=>f.originalKey)).size!==metadata.fileDigests.length)fail('AUTHENTICATED_FILE_SET_MISMATCH');
      let total=native.length;
      for(const f of content.files){
        if(typeof f.bytes!=='string'||!metadata.fileDigests.some(m=>m.originalKey===f.originalKey&&m.sha256===f.sha256&&m.licenseReference===f.licenseReference))fail('AUTHENTICATED_FILE_SET_MISMATCH');
        const original=Buffer.from(f.bytes,'base64');
        try{total+=original.length;if(total>LIMIT||original.toString('base64')!==f.bytes||hash(original)!==f.sha256)fail('AUTHENTICATED_FILE_MISMATCH');}finally{original.fill(0);}
      }
      validateAuthenticatedHumanWaiting(metadata,content);
      if(!(Date.parse(metadata.expiresAt)>currentTime()))fail('EXPIRED_NO_PLAINTEXT_RETURN');
      returned=true;return {metadata,content,native};
    }finally{plain.fill(0);if(!returned)native?.fill(0);}
  }finally{await fd.close();}
}
