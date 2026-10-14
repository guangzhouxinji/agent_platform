import {readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';import {execFileSync} from 'node:child_process';
import {hash,canonical,jdbcRequest} from '../../../../scripts/lib/single-database-lifecycle-runtime.mjs';
import {deploymentAccounts,validateDeploymentConfiguration} from '../../../../scripts/lib/single-database-lifecycle.mjs';
import {assertLiveOwnedTarget} from '../../../../scripts/lib/single-database-lifecycle-target.mjs';
import {compileInstallation} from '../../../../scripts/lib/single-database-installation.mjs';
const root=process.cwd(),out=path.resolve(root,'docs/evidence/single-database-installation-20261009/approved-execution');
const save=async(n,v)=>writeFile(path.join(out,n),JSON.stringify(v,null,2)+'\n',{flag:'wx'});
const read=async p=>JSON.parse(await readFile(p,'utf8'));
const request=await read(path.join(out,'resource-request-original01.json'));
const approval=await read(path.join(out,'resource-approval01.json'));
if(!approval.approvalGranted||Date.now()<Date.parse(request.operationWindowBeijing.from)||Date.now()>=Date.parse(request.operationWindowBeijing.until))throw Error('APPROVAL_WINDOW_REQUIRED');
const inspect=(kind,name)=>JSON.parse(execFileSync('docker',[...kind,'inspect',name],{encoding:'utf8'}))[0];
const db=inspect([],request.targets[0].name),tool=inspect([],'agent-platform-single-final-primary-tools02-20261009'),network=inspect(['network'],request.newNetwork);
const profile=await read('deployment/single-database/installation-profile.json');
const url=request.managedJdbcExecution.primaryUrl;
const deployment={schema:'SINGLE_DM8_DEPLOYMENT/v2',database:{containerId:db.Id,volume:request.targets[0].newVolume,ownerTask:request.taskId,jdbcUrl:url,memoryLimitBytes:request.limits.dm8MemoryBytes,nanoCpuLimit:request.limits.dm8NanoCpuLimit},domains:profile.bindings.map(b=>({domain:b.domain,issuer:request.dnsAndTlsRequested.find(d=>d.domain===b.domain).issuer,business:{...b.business,jdbcUrl:url},authority:{...b.authority,jdbcUrl:url},writerContainerIds:[]}))};
validateDeploymentConfiguration(deployment);
const runtime=path.join(out,'jdbc-runtime02'),libs=['jackson-annotations-2.17.2.jar','jackson-core-2.17.2.jar','jackson-databind-2.17.2.jar','DmJdbcDriver18-8.1.3.140.jar'];
const source=path.resolve('scripts/java/SingleDatabaseLifecycleJdbc.java'),classFile=path.join(runtime,'SingleDatabaseLifecycleJdbc.class');
const port={java:'D:/software/jdk-21/bin/java.exe',classpath:[runtime,...libs.map(l=>path.join(runtime,l))].join(path.delimiter),mainClass:'SingleDatabaseLifecycleJdbc',url,user:'SYSDBA',passwordEnv:'SINGLE_DB_ADMIN_PASSWORD',dockerJdbc:{containerId:tool.Id,targetId:db.Id,networkId:network.Id,ownerTask:request.taskId,java:'/opt/java/openjdk/bin/java',classpath:['/jdbc',...libs.map(l=>'/jdbc/'+l)].join(':'),classFile:'/jdbc/SingleDatabaseLifecycleJdbc.class',classSha256:hash(await readFile(classFile))}};
const base={workspaceRoot:root,targetId:db.Id,deployment,schemas:deployment.domains.flatMap(d=>[d.business.schema,d.authority.schema]),accounts:deploymentAccounts(deployment),inventoryJdbc:port,adminJdbc:port,jdbcSourcePath:source,jdbcClassPath:classFile};
await assertLiveOwnedTarget(base);
const original=await read(path.join(out,'native-public-observation02.json'));
const actual=await jdbcRequest(port,{action:'bootstrap-inventory',targetId:db.Id,accounts:['PUBLIC','SVI']});
if(canonical(actual)!==canonical(original))throw Error('EXACT_NATIVE_PUBLIC_PRESTATE_CHANGED');
const product=await jdbcRequest(port,{action:'bootstrap-inventory',targetId:db.Id,accounts:base.accounts});
if(product.accounts.some(a=>a.users.length||a.objects.length))throw Error('FRESH_TARGET_REQUIRED');
const p=original.accounts.find(a=>a.name==='PUBLIC');
if(p.systemPrivileges.length!==43||p.roles.length!==1||p.roles[0].GRANTED_ROLE!=='SVI'||p.tableGrants.length!==229||p.columnGrants.length)throw Error('NATIVE_SCOPE_CHANGED');
const steps=[...p.systemPrivileges.map(g=>({kind:'system',privilege:g.PRIVILEGE})),...p.roles.map(g=>({kind:'role',role:g.GRANTED_ROLE})),...p.tableGrants.map(g=>({kind:'object',schema:g.OWNER,object:g.TABLE_NAME,privilege:g.PRIVILEGE}))];
if(steps.some(s=>s.kind==='object'&&!['SYS','SYSDBA'].includes(s.schema)))throw Error('NATIVE_SYS_SCOPE_REQUIRED');
const manifest={targetId:db.Id,sourceInventorySha256:hash(canonical(original)),steps};
await save('primary-native-public-hardening-manifest01.json',manifest);
const action={format:'SINGLE_DATABASE_ACTION_APPROVAL/v1',action:'native-public-hardening',targetId:db.Id,owner:'current-main-session',source:path.join(out,'resource-approval01.json'),approvedAt:new Date().toISOString(),expiresAt:request.operationWindowBeijing.until,currentMigration:false,connectionSwitch:false,deleteExisting:false,newEnvironmentOnly:true,manifestSha256:hash(canonical(manifest)),jdbcSourceSha256:hash(await readFile(source)),jdbcClassSha256:port.dockerJdbc.classSha256};
await save('primary-native-public-hardening-decision01.json',action);
await save('primary-managed-port02.json',base);
const {open}=await import('node:fs/promises');const journal=await open(path.join(out,'primary-native-public-hardening01.jsonl'),'wx');
let completed=0;
try {
 for(const step of steps){
  if(Date.now()>=Date.parse(action.expiresAt)||hash(await readFile(source))!==action.jdbcSourceSha256||hash(await readFile(classFile))!==action.jdbcClassSha256)throw Error('ACTION_OR_ADAPTER_CHANGED');
  await assertLiveOwnedTarget(base);await journal.writeFile(JSON.stringify({kind:'START',index:completed,step})+'\n');await journal.sync();
  await jdbcRequest(port,{action:'harden-public-step',step});
  await journal.writeFile(JSON.stringify({kind:'SUCCESS',index:completed})+'\n');await journal.sync();completed++;
 }
 const after=await jdbcRequest(port,{action:'bootstrap-inventory',targetId:db.Id,accounts:base.accounts});
 await save('primary-bootstrap-inventory02.json',after);
 const native=await jdbcRequest(port,{action:'bootstrap-inventory',targetId:db.Id,accounts:['PUBLIC','SVI']});
 await save('primary-native-public-after01.json',native);
 if(after.publicSystemPrivileges.length||after.publicRoles.length||native.accounts.find(a=>a.name==='PUBLIC').tableGrants.length)throw Error('PUBLIC_NOT_MINIMAL');
 await save('primary-native-public-hardening-receipt01.json',{passed:true,actualJdbc:true,targetId:db.Id,completed,productAccountsCreated:0,noDrop:true,noRollbackClaimed:true});
 console.log(JSON.stringify({passed:true,completed,actualJdbc:true}));
}catch(e){
 await save('primary-native-public-hardening-failure01.json',{passed:false,completed,code:e.message,partialDclPreserved:true,rollbackClaimed:false});
 await save('primary-native-public-hardening-failure-scene01.json',await jdbcRequest(port,{action:'bootstrap-inventory',targetId:db.Id,accounts:['PUBLIC','SVI']}));throw e;
}finally{await journal.close();}

