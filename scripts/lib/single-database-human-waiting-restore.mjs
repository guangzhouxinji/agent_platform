import path from 'node:path';
import {hash,canonical,inventoryDigest} from './single-database-lifecycle-runtime.mjs';
import {assertHumanWaitingInventory} from './single-database-human-waiting-binding.mjs';
const SHA=/^[a-f0-9]{64}$/;
const fail=code=>{throw Error(code);};
export function validateHumanWaitingRestore(c){
  const h=c.humanWaiting,a=c.approval?.humanWaitingRestoreConfigurationSha256;
  if(!Object.hasOwn(c,'humanWaiting')&&!Object.hasOwn(c.approval??{},'humanWaitingRestoreConfigurationSha256'))return;
  if(!h||h.format!=='HUMAN_WAITING_RESTORE/v1'||h.scope!=='SOURCE_PRESERVED_ORDINARY_WAITING'||
    canonical(Object.keys(h).sort())!==canonical(['bindingSha256','format','scope','sourceConfigurationSha256'])||
    !SHA.test(h.bindingSha256||'')||!SHA.test(h.sourceConfigurationSha256||'')||a!==hash(canonical(h)))fail('APPROVED_HUMAN_WAITING_RESTORE_REQUIRED');
}
const normalized=value=>process.platform==='win32'?path.resolve(value).toLowerCase():path.resolve(value);
const contains=(parent,child)=>{const relative=path.relative(parent,child);return relative===''||relative!=='..'&&!relative.startsWith('..'+path.sep)&&!path.isAbsolute(relative);};
export function assertHumanWaitingRestorePackage(c,opened){
  validateHumanWaitingRestore(c);
  const m=opened.metadata.humanWaiting,h=c.humanWaiting;
  if(Boolean(m)!==Boolean(h))fail('EXPLICIT_HUMAN_WAITING_RESTORE_REQUIRED');
  if(!m)return;
  if(h.bindingSha256!==m.bindingSha256||h.sourceConfigurationSha256!==m.configurationSha256)fail('HUMAN_WAITING_RESTORE_BINDING_MISMATCH');
  const before=opened.content.humanWaiting.before,sourceRoot=before.source.evidenceRoot;
  if(typeof sourceRoot!=='string'||!path.isAbsolute(sourceRoot)||!path.isAbsolute(c.fileRoot))fail('HUMAN_WAITING_RESTORE_ROOT_REQUIRED');
  const source=normalized(sourceRoot),destination=normalized(c.fileRoot);
  if(contains(source,destination)||contains(destination,source))fail('HUMAN_WAITING_RESTORE_SOURCE_ROOT_OVERLAP');
  for(const original of before.files){
    const mapping=c.files.filter(f=>f.originalKey===original.path&&f.sha256===original.sha256);
    if(mapping.length!==1||mapping[0].relativePath!==original.ref)fail('HUMAN_WAITING_RESTORE_REFERENCE_CHANGED');
  }
}
export function humanWaitingRestoreReceipt(c,opened,after){
  assertHumanWaitingRestorePackage(c,opened);
  if(!c.humanWaiting)return undefined;
  assertHumanWaitingInventory(opened.content.humanWaiting.before,after);
  return {format:'HUMAN_WAITING_RESTORE_RECEIPT/v1',bindingSha256:c.humanWaiting.bindingSha256,
    sourceConfigurationSha256:c.humanWaiting.sourceConfigurationSha256,
    sourceInventorySha256:opened.metadata.inventorySha256,targetInventorySha256:inventoryDigest(after),
    scope:c.humanWaiting.scope,businessActivation:false};
}
