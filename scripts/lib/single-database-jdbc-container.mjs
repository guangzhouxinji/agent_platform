import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import path from 'node:path';
const exec=promisify(execFile),fail=c=>{throw new Error(c);};
const SHA=/^[a-f0-9]{64}$/;
/** Exact bounded JDBC tool container on the same owned network. Never starts or mutates a resource. */
export function validateOwnedJdbcToolResource(port,tool,db){
 if(!port||!['containerId','targetId','networkId'].every(k=>SHA.test(port[k]||''))||!/^[A-Z][A-Z0-9-]+$/.test(port.ownerTask||''))fail('EXACT_JDBC_CONTAINER_PORT_REQUIRED');
 if(tool.Id!==port.containerId||db.Id!==port.targetId||tool.State?.Running!==true||db.State?.Running!==true||tool.Config?.Labels?.task!==port.ownerTask||tool.Config?.Labels?.['agent-platform.target-id']!==port.targetId||db.Config?.Labels?.task!==port.ownerTask)fail('EXACT_OWNED_JDBC_CONTAINER_REQUIRED');
 if(tool.HostConfig?.Privileged!==false||tool.HostConfig?.ReadonlyRootfs!==true||!tool.Config?.User?.trim()||/^(root|0)(:|$)/.test(tool.Config.User)||!Number.isSafeInteger(tool.HostConfig.Memory)||tool.HostConfig.Memory<1||tool.HostConfig.Memory>536870912||!Number.isSafeInteger(tool.HostConfig.NanoCpus)||tool.HostConfig.NanoCpus<1||tool.HostConfig.NanoCpus>250000000)fail('BOUNDED_NONROOT_JDBC_TOOL_REQUIRED');
 for(const resource of [tool,db])if(!Object.values(resource.NetworkSettings?.Networks||{}).some(n=>n.NetworkID===port.networkId))fail('EXACT_SHARED_JDBC_NETWORK_REQUIRED');
 return port;
}
export function validateJdbcContainerPort(port,tool,db){
 if(!port||Object.keys(port).some(k=>!['containerId','targetId','networkId','ownerTask','java','classpath','classFile','classSha256'].includes(k))||!SHA.test(port.classSha256||''))fail('EXACT_JDBC_CONTAINER_PORT_REQUIRED');
 validateOwnedJdbcToolResource(port,tool,db);
 if(!/^\/[A-Za-z0-9_./-]+\/java$/.test(port.java||'')||!/^\/[A-Za-z0-9_./-]+\/SingleDatabaseLifecycleJdbc\.class$/.test(port.classFile||'')||port.java.includes('..')||port.classFile.includes('..')||typeof port.classpath!=='string'||port.classpath.split(':').some(p=>!/^\/[A-Za-z0-9_./-]+$/.test(p)||p.includes('..'))||port.classpath.split(':')[0]!==path.posix.dirname(port.classFile))fail('PINNED_CONTAINER_JAVA_CLASSPATH_REQUIRED');
 return port;
}
export async function jdbcContainerCommand(config,request){
 const p=config.dockerJdbc;if(config.mainClass!=='SingleDatabaseLifecycleJdbc')fail('MANAGED_CONTAINER_ADAPTER_REQUIRED');
 const inspect=JSON.parse((await exec('docker',['inspect',p.containerId,p.targetId],{shell:false,maxBuffer:4*1024*1024})).stdout);if(inspect.length!==2)fail('EXACT_JDBC_CONTAINER_PORT_REQUIRED');validateJdbcContainerPort(p,inspect[0],inspect[1]);
 const alias=new URL(config.url.replace('jdbc:dm://','http://'));const db=inspect[1];
 if(alias.port!=='5236'||!(db.Name?.replace(/^\//,'')===alias.hostname||Object.values(db.NetworkSettings?.Networks||{}).some(n=>n.NetworkID===p.networkId&&(n.Aliases||[]).includes(alias.hostname))))fail('EXACT_CONTAINER_JDBC_ALIAS_REQUIRED');
 const digest=(await exec('docker',['exec',p.containerId,'sha256sum',p.classFile],{shell:false,maxBuffer:4096})).stdout.split(/\s+/)[0];if(digest!==p.classSha256)fail('CONTAINER_JDBC_CLASS_PIN_MISMATCH');
 const names=['LIFECYCLE_JDBC_URL','LIFECYCLE_JDBC_USER','LIFECYCLE_JDBC_PASSWORD'];
 if(request.action==='bootstrap-step'&&request.step?.kind==='create-user'){const ref=request.step.passwordEnv;if(!/^[A-Z][A-Z0-9_]{0,127}$/.test(ref||'')||!process.env[ref])fail('MANAGED_ACCOUNT_SECRET_REQUIRED');names.push(ref);}
 return {command:'docker',args:['exec','-i',...names.flatMap(n=>['-e',n]),p.containerId,p.java,'-Xmx256m','-cp',p.classpath,config.mainClass]};
}
