import {hash,canonical} from './single-database-lifecycle-runtime.mjs';
import {parseHumanWaitingStdout} from './single-database-human-waiting-source.mjs';
const fail=code=>{throw new Error(code);};
const SHA=/^[a-f0-9]{64}$/;
const iso=value=>typeof value==='string'&&Number.isFinite(Date.parse(value))&&new Date(Date.parse(value)).toISOString()===value;
const object=value=>value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length>0;
export function validateHumanWaitingBinding(b,targetId,files) {
  if(b?.format!=='HUMAN_WAITING_SOURCE_BINDING/v1'||b.sourceTargetId!==targetId||
    !['configurationSha256','stdoutSha256','fileSetSha256','javaSha256','jarSha256','policiesSha256'].every(k=>SHA.test(b[k]||'')))fail('HUMAN_WAITING_BINDING_REQUIRED');
  if(!object(b.source)||!['jdbcUrl','schema','account','authoritySchema','authorityAccount','issuer','evidenceRoot'].every(k=>typeof b.source[k]==='string'&&b.source[k].length>0))fail('HUMAN_WAITING_SOURCE_REQUIRED');
  const e=b.source.endpointProof;
  if(!object(e)||e.containerId!==targetId||e.hostIp!=='127.0.0.1'||!SHA.test(e.networkId||'')||!Number.isSafeInteger(e.hostPort)||e.hostPort<1||e.hostPort>65535||!Number.isSafeInteger(e.containerPort)||e.containerPort<1||e.containerPort>65535||!e.alias||b.source.jdbcUrl!==`jdbc:dm://127.0.0.1:${e.hostPort}`||b.source.deploymentJdbcUrl!==`jdbc:dm://${e.alias}:${e.containerPort}`)fail('HUMAN_WAITING_ENDPOINT_BINDING_REQUIRED');
  if(typeof b.originalStdout!=='string'||Buffer.byteLength(b.originalStdout)>65536||hash(b.originalStdout)!==b.stdoutSha256)fail('HUMAN_WAITING_STDOUT_MISMATCH');
  const summary=parseHumanWaitingStdout(b.originalStdout);
  if(canonical(summary)!==canonical(b.summary)||!Number.isSafeInteger(summary.packages)||summary.packages<1||!Number.isSafeInteger(summary.scopes)||summary.scopes<0||!SHA.test(summary.closureSha||'')||summary.businessActivation!==false)fail('HUMAN_WAITING_NONEMPTY_SUMMARY_REQUIRED');
  if(!iso(b.backupUntil)||!iso(b.executionUntil)||b.backupUntil!==summary.validUntil||b.executionUntil!==summary.executionUntil)fail('HUMAN_WAITING_DEADLINE_MISMATCH');
  if(!object(b.workerProof?.worker)||!object(b.workerProof?.leaseProof))fail('HUMAN_WAITING_ACTUAL_WORKER_REQUIRED');
  const {worker,leaseProof}=b.workerProof;
  if(!Number.isSafeInteger(worker.pid)||worker.pid<1||!Number.isSafeInteger(worker.parentId)||worker.parentId<1||!Number.isFinite(Date.parse(worker.createdAt))||!SHA.test(worker.commandSha256||'')||!SHA.test(worker.invocationSha256||'')||!SHA.test(leaseProof.tableSetSha256||'')||!Number.isSafeInteger(leaseProof.tableCount)||leaseProof.tableCount<1||leaseProof.tableCount>4096)fail('HUMAN_WAITING_ACTUAL_WORKER_REQUIRED');
  if(!Array.isArray(b.files)||b.files.length<1||b.files.length>10000||new Set(b.files.map(f=>f.path)).size!==b.files.length||new Set(b.files.map(f=>f.ref)).size!==b.files.length||hash(canonical(b.files))!==b.fileSetSha256)fail('HUMAN_WAITING_ORIGINAL_SET_MISMATCH');
  for(const f of b.files)if(typeof f.ref!=='string'||!f.ref||typeof f.path!=='string'||!SHA.test(f.sha256||'')||!files.some(a=>(a.path??a.originalKey)===f.path&&a.sha256===f.sha256))fail('HUMAN_WAITING_ORIGINAL_SET_MISMATCH');
  return b;
}
export function humanWaitingSemantic(b) {
  const {originalStdout,stdoutSha256,...semantic}=b;return canonical(semantic);
}
export function assertHumanWaitingCurrent(b,now) {
  if(!(Date.parse(b.backupUntil)>now)||!(Date.parse(b.executionUntil)>now))fail('HUMAN_WAITING_EXECUTION_EXPIRED');
}
export function assertHumanWaitingInventory(b,inventory) {
  for(const s of inventory.schemas){
    const tables=s.objects.filter(o=>['TABLE','BASE TABLE'].includes(o.type)).map(o=>o.name);
    if(new Set(tables).size!==tables.length||new Set(s.rows.map(r=>r.table)).size!==s.rows.length||canonical([...tables].sort())!==canonical(s.rows.map(r=>r.table).sort()))fail('HUMAN_WAITING_COMPLETE_TYPED_INVENTORY_REQUIRED');
    if(s.rows.some(r=>!Number.isSafeInteger(r.count)||r.count<0||!SHA.test(r.contentSha256||'')||r.digestMethod!=='SORTED_TYPED_COLUMN_ROW_SHA256/v1'))fail('HUMAN_WAITING_COMPLETE_TYPED_INVENTORY_REQUIRED');
  }
  const primary=inventory.schemas.find(s=>s.schema===b.source.schema),authority=inventory.schemas.find(s=>s.schema===b.source.authoritySchema);
  if(!primary||!authority)fail('HUMAN_WAITING_INVENTORY_SOURCE_MISMATCH');
  for(const name of ['PACKAGE','HANDLING','EVENT','RECEIPT','CLEANUP_SCOPE','CLEANUP_EVENT'])if(!primary.rows.some(r=>r.table==='CON6_HUMAN_'+name))fail('HUMAN_WAITING_SEVEN_TABLES_REQUIRED');
  if(primary.rows.find(r=>r.table==='CON6_HUMAN_PACKAGE').count!==b.summary.packages||primary.rows.find(r=>r.table==='CON6_HUMAN_CLEANUP_SCOPE').count!==b.summary.scopes||authority.rows.find(r=>r.table==='ID6_HUMAN_CLEANUP_SCOPE')?.count!==b.summary.scopes)fail('HUMAN_WAITING_INVENTORY_SUMMARY_MISMATCH');
}
export function authenticateHumanWaiting(before,after,inventorySha256,targetId,files,configurationSha256) {
  validateHumanWaitingBinding(before,targetId,files);validateHumanWaitingBinding(after,targetId,files);
  if(before.configurationSha256!==configurationSha256||humanWaitingSemantic(before)!==humanWaitingSemantic(after))fail('HUMAN_WAITING_CHANGED_INSIDE_FENCE');
  const content={before,after,inventorySha256};
  const metadata={format:'HUMAN_WAITING_SNAPSHOT/v1',bindingSha256:hash(canonical(content)),inventorySha256,backupUntil:before.backupUntil,configurationSha256};
  return {content,metadata};
}
export function validateAuthenticatedHumanWaiting(metadata,content) {
  if(Boolean(metadata.humanWaiting)!==Boolean(content.humanWaiting))fail('AUTHENTICATED_HUMAN_WAITING_MISSING');
  if(!metadata.humanWaiting)return;
  const h=content.humanWaiting,m=metadata.humanWaiting;
  if(h.inventorySha256!==metadata.inventorySha256)fail('AUTHENTICATED_HUMAN_WAITING_INVENTORY_MISMATCH');
  const verified=authenticateHumanWaiting(h.before,h.after,h.inventorySha256,metadata.targetId,metadata.fileDigests,m.configurationSha256);
  assertHumanWaitingInventory(h.before,content.inventory);
  if(canonical(verified.metadata)!==canonical(m)||!(Date.parse(metadata.expiresAt)<=Date.parse(m.backupUntil)))fail('AUTHENTICATED_HUMAN_WAITING_MISMATCH');
  // executionUntil admitted the historical export. Opening a retained package uses its
  // save deadline; current business authorization is checked separately before activation.
}
