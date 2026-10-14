import {readFile,readdir,lstat,realpath} from 'node:fs/promises';
import path from 'node:path';
import {canonical,hash,admission} from './single-database-lifecycle-runtime.mjs';
import {runNativeCommand} from './single-database-lifecycle-native.mjs';
import {validateDeploymentConfiguration} from './single-database-lifecycle.mjs';
const fail=code=>{throw Error(code);};
const SHA=/^[a-f0-9]{64}$/,ENV=/^[A-Z][A-Z0-9_]{1,100}$/,REF=/^[A-Za-z0-9][A-Za-z0-9._/-]{0,499}$/;
const object=x=>x!==null&&typeof x==='object'&&!Array.isArray(x);
function keys(value,expected){if(!object(value)||canonical(Object.keys(value).sort())!==canonical([...expected].sort()))fail('HUMAN_WAITING_FIELDS_INVALID');}
function positive(value,max=Number.MAX_SAFE_INTEGER){return Number.isSafeInteger(value)&&value>0&&value<=max;}
function iso(value){const time=Date.parse(value);if(typeof value!=='string'||!Number.isFinite(time)||new Date(time).toISOString()!==value)fail('HUMAN_WAITING_TIME_INVALID');return time;}
function epoch(text){
  if(!/^\d{1,12}(?:\.\d{1,9})?$/.test(text))fail('HUMAN_WAITING_EPOCH_INVALID');
  const [seconds,fraction='']=text.split('.');const nanos=BigInt(seconds)*1000000000n+BigInt(fraction.padEnd(9,'0'));
  const millis=nanos/1000000n;if(millis>BigInt(Number.MAX_SAFE_INTEGER))fail('HUMAN_WAITING_EPOCH_INVALID');
  try{return new Date(Number(millis)).toISOString();}catch{fail('HUMAN_WAITING_EPOCH_INVALID');}
}
/** Preserve exact decimal epoch lexemes before JSON's double conversion; never round a cutoff up. */
export function parseHumanWaitingStdout(text){
  if(typeof text!=='string'||Buffer.byteLength(text)>65536||text.includes('\\'))fail('HUMAN_WAITING_STDOUT_INVALID');
  let value;try{value=JSON.parse(text);}catch{fail('HUMAN_WAITING_STDOUT_INVALID');}
  keys(value,['mode','businessActivation','sourceAndFenceBindingProven','summary']);
  keys(value.summary,['packages','scopes','closureSha','businessActivation','validUntil','executionUntil']);
  for(const key of ['mode','sourceAndFenceBindingProven','summary','packages','scopes','closureSha','validUntil','executionUntil'])
    if([...text.matchAll(new RegExp('"'+key+'"\\s*:','g'))].length!==1)fail('HUMAN_WAITING_DUPLICATE_OR_ESCAPED_KEY');
  if([...text.matchAll(/"businessActivation"\s*:/g)].length!==2)fail('HUMAN_WAITING_DUPLICATE_OR_ESCAPED_KEY');
  if(value.mode!=='SOURCE_ONLY_WAITING'||value.businessActivation!==false||value.sourceAndFenceBindingProven!==false
      ||value.summary.businessActivation!==false||!positive(value.summary.packages)||!positive(value.summary.scopes)
      ||!SHA.test(value.summary.closureSha))fail('HUMAN_WAITING_SUMMARY_INVALID');
  const deadlines={};
  for(const key of ['validUntil','executionUntil']){
    const match=new RegExp('"'+key+'"\\s*:\\s*(\\d+(?:\\.\\d{1,9})?)(?=\\s*[,}])').exec(text);
    if(!match)fail('HUMAN_WAITING_EPOCH_INVALID');deadlines[key]=epoch(match[1]);
  }
  if(iso(deadlines.executionUntil)>iso(deadlines.validUntil))fail('HUMAN_WAITING_TIME_INVALID');
  return {...value.summary,...deadlines};
}
async function exactPath(value,directory=false){
  if(typeof value!=='string'||path.resolve(value)!==value)fail('HUMAN_WAITING_ABSOLUTE_PATH_REQUIRED');
  const parsed=path.parse(value);let current=parsed.root;
  for(const part of value.slice(parsed.root.length).split(path.sep).filter(Boolean)){
    current=path.join(current,part);const info=await lstat(current);
    if(info.isSymbolicLink()||await realpath(current)!==current)fail('HUMAN_WAITING_PATH_REDIRECT');
  }
  const info=await lstat(value);if(directory?!info.isDirectory():!info.isFile())fail('HUMAN_WAITING_PATH_TYPE');return info;
}
async function pinnedFile(pin){
  keys(pin,['path','sha256']);if(!SHA.test(pin.sha256))fail('HUMAN_WAITING_PIN_INVALID');
  const info=await exactPath(pin.path);if(info.size>256*1024*1024)fail('HUMAN_WAITING_CAPACITY');
  if(hash(await readFile(pin.path))!==pin.sha256)fail('HUMAN_WAITING_PIN_CHANGED');
}
function policy(p,human=false){
  keys(p,['ref','version','sha256',...(human?[]:['maxAge'])]);
  if(!REF.test(p.ref||'')||p.ref.includes('..')||!positive(p.version)||!SHA.test(p.sha256)
      ||!human&&(typeof p.maxAge!=='string'||!/^P(?=.+)[0-9DTHMS.]+$/.test(p.maxAge)))fail('HUMAN_WAITING_POLICY_INVALID');
}
function configuration(c,approval){
  const h=c.humanWaiting;
  keys(h,['format','java','jar','jdbc','authority','issuer','evidenceRoot','evidenceMaxBytes','backupUntil','timeoutMs','humanPolicy','taskPolicy','factsPolicy','originalWriters']);
  validateDeploymentConfiguration(c.deployment);
  const d=c.deployment.domains.find(x=>x.domain==='consultation');
  if(h.format!=='HUMAN_WAITING_SOURCE/v1'||c.targetId!==c.deployment.database.containerId
      ||c.inventoryJdbc?.url!==c.deployment.database.jdbcUrl
      ||approval.targetId!==c.targetId||approval.configurationSha256!==hash(canonical(c.deployment))
      ||approval.humanWaitingConfigurationSha256!==hash(canonical(h)))fail('HUMAN_WAITING_APPROVED_CONFIGURATION_REQUIRED');
  for(const [connection,domain] of [[h.jdbc,d.business],[h.authority,d.authority]]){
    keys(connection,['url','user','passwordEnv','schema']);
    if(!/^jdbc:dm:\/\/127\.0\.0\.1:[0-9]{1,5}$/.test(connection.url)||connection.url!==h.jdbc.url||connection.schema!==domain.schema
        ||connection.user!==domain.runtimeUser||!ENV.test(connection.passwordEnv||''))fail('HUMAN_WAITING_EXACT_SOURCE_REQUIRED');
  }
  if(h.jdbc.user===h.authority.user||h.jdbc.passwordEnv===h.authority.passwordEnv||h.issuer!==d.issuer
      ||!positive(h.evidenceMaxBytes,128*1024*1024)||!positive(h.timeoutMs,120000))fail('HUMAN_WAITING_EXACT_SOURCE_REQUIRED');
  policy(h.humanPolicy,true);policy(h.taskPolicy);policy(h.factsPolicy);iso(h.backupUntil);
  const w=h.originalWriters;keys(w,['root','writerIds','maintenanceWriterId','responsibilitySource','stableTreeEvidence']);
  if(w.root!==h.evidenceRoot||!approval.writerRegistryComplete||!approval.writerRegistryEvidence
      ||!approval.maintenanceExclusivityEvidence||!w.responsibilitySource||!w.stableTreeEvidence
      ||!Array.isArray(w.writerIds)||!w.writerIds.length||new Set(w.writerIds).size!==w.writerIds.length
      ||!w.writerIds.includes(w.maintenanceWriterId)||!Array.isArray(approval.writers)
      ||w.writerIds.some(id=>!approval.writers.some(a=>a.id===id&&a.owner&&a.responsibilitySource))
      ||!approval.writers.some(a=>a.id===w.maintenanceWriterId&&a.kind==='MAINTENANCE'&&a.responsibilitySource===approval.maintenanceExclusivityEvidence))
    fail('HUMAN_WAITING_ORIGINAL_WRITER_FENCE_REQUIRED');
  return h;
}
async function endpoint(c,h,run){
  const raw=await run('docker',['inspect',c.targetId],{timeoutMs:h.timeoutMs,maxBytes:1048576,stage:'SNAPSHOT_SEAL'});
  let values;try{values=JSON.parse(raw.toString('utf8'));}catch{fail('HUMAN_WAITING_ENDPOINT_PROOF_REQUIRED');}
  if(!Array.isArray(values)||values.length!==1)fail('HUMAN_WAITING_ENDPOINT_PROOF_REQUIRED');
  const v=values[0],declared=/^jdbc:dm:\/\/([^:]+):(\d+)$/.exec(c.deployment.database.jdbcUrl),hostPort=Number(h.jdbc.url.split(':').at(-1));
  if(!declared||!positive(hostPort,65535)||v.Id!==c.targetId||v.State?.Running!==true||v.State.Restarting||v.Config?.Labels?.task!==c.deployment.database.ownerTask)fail('HUMAN_WAITING_ENDPOINT_PROOF_REQUIRED');
  const containerPort=Number(declared[2]),ports=v.NetworkSettings?.Ports?.[`${containerPort}/tcp`];
  if(!Array.isArray(ports)||ports.length!==1||ports[0].HostIp!=='127.0.0.1'||Number(ports[0].HostPort)!==hostPort)fail('HUMAN_WAITING_ENDPOINT_PROOF_REQUIRED');
  const alias=declared[1],networks=Object.values(v.NetworkSettings?.Networks||{}).filter(n=>{
    const names=Object.hasOwn(n,'DNSNames')?n.DNSNames:n.Aliases;
    return SHA.test(n.NetworkID||'')&&(['127.0.0.1','localhost'].includes(alias)||Array.isArray(names)&&names.length>0&&names.every(name=>typeof name==='string'&&name.length>0)&&names.includes(alias));
  });
  if(networks.length!==1||['127.0.0.1','localhost'].includes(alias)&&h.jdbc.url!==c.deployment.database.jdbcUrl)fail('HUMAN_WAITING_ENDPOINT_PROOF_REQUIRED');
  return {containerId:v.Id,containerPort,hostIp:'127.0.0.1',hostPort,networkId:networks[0].NetworkID,alias};
}
async function originals(c,approval,h,now){
  await exactPath(h.evidenceRoot,true);
  if(!Array.isArray(c.files)||!Array.isArray(approval.files)||new Set(c.files.map(f=>f.path)).size!==c.files.length)fail('HUMAN_WAITING_FILE_SET_INVALID');
  const rows=[];let total=0,count=0,entries=0;
  async function walk(directory,depth=0){
    if(depth>32)fail('HUMAN_WAITING_CAPACITY');
    for(const name of (await readdir(directory)).sort()){
      if(++entries>10000)fail('HUMAN_WAITING_CAPACITY');
      const file=path.join(directory,name),info=await lstat(file);
      if(info.isSymbolicLink()||await realpath(file)!==file)fail('HUMAN_WAITING_PATH_REDIRECT');
      if(info.isDirectory()){await walk(file,depth+1);continue;}
      if(!info.isFile()||++count>10000||info.size>h.evidenceMaxBytes||(total+=info.size)>128*1024*1024)fail('HUMAN_WAITING_CAPACITY');
      const ref=path.relative(h.evidenceRoot,file).split(path.sep).join('/');
      if(!REF.test(ref)||ref.includes('..'))fail('HUMAN_WAITING_REFERENCE_INVALID');
      const pin=c.files.filter(f=>f.path===file),approved=approval.files.filter(f=>f.path===file);
      if(pin.length!==1||approved.length!==1||!SHA.test(pin[0].sha256)||pin[0].sha256!==approved[0].sha256
          ||!approved[0].licenseReference||!(Date.parse(approved[0].useUntil)>now()))fail('HUMAN_WAITING_APPROVED_ORIGINAL_REQUIRED');
      const bytes=await readFile(file);if(bytes.length!==info.size||hash(bytes)!==pin[0].sha256)fail('HUMAN_WAITING_ORIGINAL_CHANGED');
      rows.push({ref,path:file,sha256:pin[0].sha256});
    }
  }
  await walk(h.evidenceRoot);
  for(const p of [h.humanPolicy,h.taskPolicy,h.factsPolicy])if(!rows.some(f=>f.ref===p.ref&&f.sha256===p.sha256))fail('HUMAN_WAITING_POLICY_NOT_PACKAGED');
  return rows.sort((a,b)=>a.ref.localeCompare(b.ref));
}
function childEnvironment(h,env){
  const result={};for(const key of ['SystemRoot','WINDIR','TEMP','TMP'])if(env[key])result[key]=env[key];
  const put=(suffix,value)=>{result['PRODUCT_HUMAN_SOURCE_'+suffix]=String(value);};
  for(const [prefix,c] of [['',h.jdbc],['AUTHORITY_',h.authority]]){
    const secret=env[c.passwordEnv];if(typeof secret!=='string'||!secret||/[\r\n\0]/.test(secret))fail('HUMAN_WAITING_CREDENTIAL_ENV_REQUIRED');
    put(prefix+(prefix?'USER':'DB_USER'),c.user);put(prefix+(prefix?'PASSWORD':'DB_PASSWORD'),secret);
    put(prefix+'DB_URL',c.url);put(prefix+(prefix?'SCHEMA':'DB_SCHEMA'),c.schema);
  }
  put('AUTHORITY_TOPOLOGY','shared-instance');put('ISSUER',h.issuer);put('EVIDENCE_ROOT',h.evidenceRoot);put('EVIDENCE_MAX_BYTES',h.evidenceMaxBytes);put('BACKUP_UNTIL',h.backupUntil);
  for(const [name,p] of [['HUMAN',h.humanPolicy],['TASK',h.taskPolicy],['FACTS',h.factsPolicy]]){
    for(const [key,value] of [['REF',p.ref],['VERSION',p.version],['SHA256',p.sha256]])put(name+'_POLICY_'+key,value);
    if(name!=='HUMAN')put(name+'_POLICY_MAX_AGE',p.maxAge);
  }
  return result;
}
export function createHumanWaitingInspector(c,approval=c.approval,{run=runNativeCommand,now=Date.now,env=process.env}={}){
  const h=configuration(c,approval),configurationSha256=hash(canonical(h)),approvalSha=hash(canonical(approval));
  const dependencyHash=()=>hash(canonical({targetId:c.targetId,deployment:c.deployment,files:c.files,inventoryJdbc:c.inventoryJdbc}));
  const sourceSha=dependencyHash();
  const bindings=new WeakMap();let heldLease;
  return async function inspect({lease,phase}){
    if(!lease||typeof lease.assertHeld!=='function'||typeof lease.bindWorker!=='function'||!object(lease.proof)||!Object.keys(lease.proof).length
        ||typeof phase!=='string'||!phase||heldLease&&heldLease!==lease)fail('HUMAN_WAITING_SAME_LIVE_LEASE_REQUIRED');
    heldLease=lease;
    const current=()=>{
      if(configurationSha256!==hash(canonical(c.humanWaiting))||approvalSha!==hash(canonical(approval))||sourceSha!==dependencyHash())fail('HUMAN_WAITING_CONFIGURATION_CHANGED');
      configuration(c,approval);admission(approval,c.targetId,'snapshot',now());
      if(bindings.has(lease)&&canonical(bindings.get(lease).leaseProof)!==canonical(lease.proof))fail('HUMAN_WAITING_LEASE_PROOF_CHANGED');
      const at=now(),until=iso(h.backupUntil);if(!Number.isSafeInteger(at)||until<=at||until>at+30*86400000)fail('HUMAN_WAITING_BACKUP_WINDOW_INVALID');
    };
    current();await lease.assertHeld();
    if(!bindings.has(lease))bindings.set(lease,JSON.parse(canonical({worker:await lease.bindWorker(),leaseProof:lease.proof})));
    const {worker,leaseProof}=bindings.get(lease);if(!object(worker)||!Object.keys(worker).length)fail('HUMAN_WAITING_WORKER_PROOF_REQUIRED');
    await pinnedFile(h.java);await pinnedFile(h.jar);const files=await originals(c,approval,h,now),endpointProof=await endpoint(c,h,run);
    current();await lease.assertHeld();
    const raw=await run(h.java.path,['-Xmx512m','-Dloader.main=cn.xinji.agentplatform.security.product.ProductTaskRecoveryCheck','-cp',h.jar.path,'org.springframework.boot.loader.launch.PropertiesLauncher','--source-waiting'],
      {env:childEnvironment(h,env),timeoutMs:h.timeoutMs,maxBytes:65536,stage:'SNAPSHOT_SEAL'});
    if(!Buffer.isBuffer(raw))fail('HUMAN_WAITING_STDOUT_INVALID');const originalStdout=raw.toString('utf8'),summary=parseHumanWaitingStdout(originalStdout);
    current();if(summary.validUntil!==h.backupUntil||iso(summary.executionUntil)<=now())fail('HUMAN_WAITING_SUMMARY_EXPIRED_OR_MISMATCH');
    await pinnedFile(h.java);await pinnedFile(h.jar);
    if(canonical(files)!==canonical(await originals(c,approval,h,now)))fail('HUMAN_WAITING_ORIGINAL_SET_CHANGED');
    if(canonical(endpointProof)!==canonical(await endpoint(c,h,run)))fail('HUMAN_WAITING_ENDPOINT_CHANGED');
    await lease.assertHeld();current();if(iso(summary.executionUntil)<=now())fail('HUMAN_WAITING_SUMMARY_EXPIRED_OR_MISMATCH');
    return {format:'HUMAN_WAITING_SOURCE_BINDING/v1',sourceTargetId:c.targetId,configurationSha256,summary,backupUntil:h.backupUntil,executionUntil:summary.executionUntil,
      originalStdout,stdoutSha256:hash(raw),files,fileSetSha256:hash(canonical(files)),javaSha256:h.java.sha256,jarSha256:h.jar.sha256,
      policiesSha256:hash(canonical({humanPolicy:h.humanPolicy,taskPolicy:h.taskPolicy,factsPolicy:h.factsPolicy})),
      source:{jdbcUrl:h.jdbc.url,deploymentJdbcUrl:c.deployment.database.jdbcUrl,endpointProof,schema:h.jdbc.schema,account:h.jdbc.user,authoritySchema:h.authority.schema,authorityAccount:h.authority.user,issuer:h.issuer,evidenceRoot:h.evidenceRoot},
      workerProof:JSON.parse(canonical({worker,leaseProof}))};
  };
}
