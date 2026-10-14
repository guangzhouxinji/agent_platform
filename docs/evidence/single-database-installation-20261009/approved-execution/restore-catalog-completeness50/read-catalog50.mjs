import {readFile,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {jdbcContainerCommand} from '../../../../../scripts/lib/single-database-jdbc-container.mjs';
import {runNativeCommand} from '../../../../../scripts/lib/single-database-lifecycle-native.mjs';
const here=new URL('./',import.meta.url), c=JSON.parse(await readFile(new URL('../primary-migration-config07.json',here)));
if(Date.now()>=Date.parse('2026-10-10T18:00:00+08:00'))throw Error('WINDOW_CLOSED');
const jdbc=c.inventoryJdbc, command=await jdbcContainerCommand(jdbc,{action:'inventory'}), p=jdbc.dockerJdbc;
const classBytes=await readFile(new URL('../jdbc-native05/catalog50/ReadCatalog50.class',here)), sha=createHash('sha256').update(classBytes).digest('hex');
if(execFileSync('docker',['exec',p.containerId,'sha256sum','/jdbc/catalog50/ReadCatalog50.class'],{encoding:'utf8',timeout:10000}).split(/\s+/)[0]!==sha)throw Error('PROBE_CLASS_PIN_MISMATCH');
const env={...process.env,LIFECYCLE_JDBC_URL:jdbc.url,LIFECYCLE_JDBC_USER:jdbc.user,LIFECYCLE_JDBC_PASSWORD:process.env.SINGLE_DB_ADMIN_PASSWORD};
if(!env.LIFECYCLE_JDBC_PASSWORD)throw Error('MANAGED_SECRET_REQUIRED');
command.args[command.args.indexOf(p.classpath)]='/jdbc/catalog50:'+p.classpath;command.args[command.args.length-1]='ReadCatalog50';
const result=await runNativeCommand(command.command,command.args,{env,timeoutMs:120000,maxBytes:8*1024*1024});
await writeFile(new URL('installed-catalog-probe50.raw.json',here),result);
await writeFile(new URL('installed-catalog-probe50-receipt.json',here),JSON.stringify({capturedAt:new Date().toISOString(),targetId:p.targetId,toolId:p.containerId,classSha256:sha,exitCode:0,stdoutBytes:result.length,readOnly:true,restoreStarted:false},null,2));



