import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, unlinkSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { validateDeploymentConfiguration,validateOfflineResources,prepareLifecycle,sha256 } from './single-database-lifecycle.mjs';
import { runCli } from '../prepare-single-database-lifecycle.mjs';

// Mechanical config/resource metadata only; no business identities or product backups.
function fixture() {
    const url='jdbc:dm://127.0.0.1:5493',id='1'.repeat(64),volume='agent-platform-offline-test',task='IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01';
    const config={schema:'SINGLE_DM8_DEPLOYMENT/v1',database:{containerId:id,volume,ownerTask:task,jdbcUrl:url,memoryLimitBytes:4294967296,nanoCpuLimit:2000000000},
        domains:['consultation','enforcement','adjudication'].map((domain,i)=>({domain,issuer:`https://${domain}.example.test`,
            business:{schema:`DOM${i}_BIZ`,jdbcUrl:url,ownerUser:`DOM${i}_BIZ_OWNER`,runtimeUser:`DOM${i}_BIZ_SVC`},
            authority:{schema:`DOM${i}_AUTH`,jdbcUrl:url,ownerUser:`DOM${i}_AUTH_OWNER`,runtimeUser:`DOM${i}_AUTH_SVC`},writerContainerIds:[String(i+2).repeat(64)]}))};
    const container={Id:id,Name:'/offline-db',Config:{Labels:{task},User:'1000:1000'},State:{Running:false},
        HostConfig:{Privileged:false,Memory:4294967296,NanoCpus:2000000000,PortBindings:{'5236/tcp':[{HostIp:'127.0.0.1',HostPort:'5493'}]}},
        Mounts:['/opt/dmdbms/data','/opt/dmdbms/log'].map(Destination=>({Destination,Type:'volume',Name:volume,RW:true})),NetworkSettings:{Ports:{},Networks:{}}};
    const volumeInspect={Name:volume,Labels:{task}},writers=config.domains.map(d=>({Id:d.writerContainerIds[0],Config:{Labels:{'agent-platform.domain':d.domain}},State:{Running:false}}));
    return {config,container,volumeInspect,writers};
}
function resources(patch=()=>{}){const f=fixture();patch(f);return validateOfflineResources(f.config,f.container,f.volumeInspect,f.writers,true);}
const hash='a'.repeat(64);
test('offline config preserves 3 domains 6 schemas 12 accounts 3 Issuers',()=>{
    const p=validateDeploymentConfiguration(fixture().config);assert.equal(p.domainCount,3);assert.equal(p.schemaCount,6);assert.equal(p.accountCount,12);assert.equal(p.issuerCount,3);assert.equal(p.credentialsIncluded,false);
    assert.ok(p.bindings.every(x=>x.settings.PRODUCT_IDENTITY_AUTHORITY_TOPOLOGY==='shared-instance'));
});
for(const [name,patch,code] of [
    ['duplicate domain',f=>f.config.domains[1].domain='consultation','DUPLICATE_DOMAIN'],
    ['duplicate issuer',f=>f.config.domains[1].issuer=f.config.domains[0].issuer,'DUPLICATE_ISSUER'],
    ['duplicate schema',f=>f.config.domains[1].business.schema=f.config.domains[0].business.schema,'DUPLICATE_SCHEMA'],
    ['duplicate account',f=>f.config.domains[1].business.ownerUser=f.config.domains[0].business.ownerUser,'DUPLICATE_ACCOUNT'],
    ['system account',f=>f.config.domains[0].business.ownerUser='SYSDBA','NON_SYSTEM_ACCOUNTS_REQUIRED'],
    ['different authority URL',f=>f.config.domains[0].authority.jdbcUrl='jdbc:dm://127.0.0.1:5492','SIX_SCHEMAS_ONE_ENDPOINT_REQUIRED'],
    ['credential in configuration',f=>f.config.database.password='NOT_A_REAL_SECRET','UNSUPPORTED_DATABASE_FIELDS'],
    ['unbounded memory',f=>f.config.database.memoryLimitBytes=0,'FINITE_RESOURCE_LIMITS_REQUIRED'],
    ['over-limit CPU',f=>f.config.database.nanoCpuLimit=3000000000,'FINITE_RESOURCE_LIMITS_REQUIRED'],
    ['Issuer userinfo',f=>f.config.domains[0].issuer='https://user:pass@example.test','EXACT_HTTPS_ISSUER_REQUIRED'],
    ['duplicate writer',f=>f.config.domains[1].writerContainerIds=f.config.domains[0].writerContainerIds,'DUPLICATE_WRITER']
])test(`configuration rejects ${name}`,()=>{const f=fixture();patch(f);assert.throws(()=>validateDeploymentConfiguration(f.config),new RegExp(code));});
test('captured exact resource metadata verifies without implying live resource, disk or whole backup',()=>{
    const r=resources();assert.equal(r.capturedResourceVerified,true);assert.equal(r.enumeratedWritersStoppedInCapture,true);
    assert.equal(r.currentResourceVerified,false);assert.equal(r.diskCapacityVerified,false);assert.equal(r.unlistedWritersProvenAbsent,false);
});
for(const [name,patch,code] of [
    ['wrong container',f=>f.container.Id='9'.repeat(64),'RESOURCE_IDENTITY_OR_OWNER_MISMATCH'],
    ['wrong owner',f=>f.container.Config.Labels.task='OTHER-TASK','RESOURCE_IDENTITY_OR_OWNER_MISMATCH'],
    ['root',f=>f.container.Config.User='root','NONROOT_UNPRIVILEGED_RESOURCE_REQUIRED'],
    ['privileged',f=>f.container.HostConfig.Privileged=true,'NONROOT_UNPRIVILEGED_RESOURCE_REQUIRED'],
    ['memory exceeds bound',f=>f.container.HostConfig.Memory=4294967297,'RESOURCE_BOUNDS_MISMATCH'],
    ['tmpfs data',f=>f.container.Mounts[0].Type='tmpfs','EXACT_PERSISTENT_VOLUME_REQUIRED'],
    ['wrong volume owner',f=>f.volumeInspect.Labels.task='OTHER-TASK','VOLUME_OWNERSHIP_MISMATCH'],
    ['wrong port',f=>f.container.HostConfig.PortBindings['5236/tcp'][0].HostPort='5301','EXACT_LOOPBACK_BINDING_REQUIRED'],
    ['public bind',f=>f.container.HostConfig.PortBindings['5236/tcp'][0].HostIp='0.0.0.0','EXACT_LOOPBACK_BINDING_REQUIRED'],
    ['running writer',f=>f.writers[0].State.Running=true,'WRITER_ID_DOMAIN_OR_STOPPED_MISMATCH'],
    ['wrong writer domain',f=>f.writers[0].Config.Labels['agent-platform.domain']='adjudication','WRITER_ID_DOMAIN_OR_STOPPED_MISMATCH'],
    ['missing writer',f=>f.writers.pop(),'WRITER_SNAPSHOT_SET_MISMATCH'],
    ['empty writer inventory',f=>{f.config.domains[0].writerContainerIds=[];f.writers.shift();},'NONEMPTY_WRITER_INVENTORY_REQUIRED']
])test(`captured resource rejects ${name}`,()=>assert.throws(()=>resources(patch),new RegExp(code)));
test('running database snapshot uses NetworkSettings port not stopped configuration',()=>{
    const r=resources(f=>{f.container.State.Running=true;f.container.NetworkSettings.Ports={'5236/tcp':[{HostIp:'127.0.0.1',HostPort:'5493'}]};f.container.HostConfig.PortBindings={};});assert.equal(r.databaseRunningInCapture,true);
});
test('empty writers are permitted only for config/resource inspection, not proof of stopped writers',()=>{
    const f=fixture();for(const d of f.config.domains)d.writerContainerIds=[];
    assert.equal(validateOfflineResources(f.config,f.container,f.volumeInspect,[],false).enumeratedWritersStoppedInCapture,false);
});
test('config and captured resource modes have explicitly limited successful exits',()=>{
    const f=fixture();assert.equal(prepareLifecycle({mode:'config-preflight',configuration:f.config}).passed,true);
    const r=prepareLifecycle({mode:'resource-preflight',configuration:f.config,containerInspect:f.container,volumeInspect:f.volumeInspect});
    assert.equal(r.passed,true);assert.equal(r.overallThreeDomainSnapshotVerified,false);assert.equal(r.releaseAuthorized,false);
});
test('three individually successful child references are never a same-point whole snapshot',()=>{
    const f=fixture(),evidenceIndex=['consultation','enforcement','adjudication'].map(path=>({path,sha256:hash,bytes:100}));
    const r=prepareLifecycle({configuration:f.config,containerInspect:f.container,volumeInspect:f.volumeInspect,writerInspect:f.writers,evidenceIndex});
    assert.equal(r.passed,false);assert.ok(r.reasons.includes('SAME_POINT_THREE_DOMAIN_DB_AND_REQUIRED_FILE_SNAPSHOT_UNPROVEN'));
    assert.equal(r.physicalProtectionManifestReady,false);assert.equal(r.newBackupExecuted,false);
});
test('initialization admission refuses blind replay even with correct resources',()=>{
    const f=fixture(),r=prepareLifecycle({mode:'initialize-admission',configuration:f.config,containerInspect:f.container,volumeInspect:f.volumeInspect});
    assert.equal(r.passed,false);assert.equal(r.initializationExecuted,false);
    assert.ok(r.reasons.includes('TARGET_EXISTING_DATA_AND_APPLIED_MIGRATIONS_UNPROVEN'));assert.ok(r.reasons.includes('APPROVED_FORWARD_MIGRATION_PINS_REQUIRED_NO_BLIND_REPLAY'));
});
test('recovery cannot restore current authority or borrow backup audit retention as package validity',()=>{
    const f=fixture(),r=prepareLifecycle({mode:'recovery-admission',configuration:f.config,containerInspect:f.container,volumeInspect:f.volumeInspect,writerInspect:f.writers});
    assert.equal(r.passed,false);assert.equal(r.currentAuthorityRestored,false);assert.equal(r.auditRetentionIsPackageValidity,false);
    assert.ok(r.reasons.includes('CURRENT_AUTHORITY_OUTSIDE_OLD_BACKUP_UNPROVEN'));assert.ok(r.reasons.includes('PACKAGE_VALIDITY_AND_BACKUP_AUDIT_RETENTION_SEPARATE_EVIDENCE_REQUIRED'));
});
test('duplicate evidence and unsupported mode refuse',()=>{
    assert.throws(()=>prepareLifecycle({mode:'fake-mode'}),/UNKNOWN_OFFLINE_MODE/);
    assert.throws(()=>prepareLifecycle({evidenceIndex:[{path:'x',sha256:hash,bytes:1},{path:'x',sha256:hash,bytes:1}]}),/DUPLICATE_EVIDENCE_REFERENCE/);
});
test('CLI actual filesystem config exit0, default protection exit1, exclusive output and read-only inputs',()=>{
    const dir=mkdtempSync(join(tmpdir(),'single-db-offline-test-'));
    const config=join(dir,'config.json'),out=join(dir,'output.json');
    try {
        const bytes=Buffer.from(JSON.stringify(fixture().config));writeFileSync(config,bytes);
        let output='',error='';const io={stdout:s=>output+=s,stderr:s=>error+=s};
        assert.equal(runCli(['--mode','config-preflight','--config',config,'--output',out],io),0);assert.equal(error,'');
        assert.equal(JSON.parse(readFileSync(out)).passed,true);assert.equal(sha256(readFileSync(config)),sha256(bytes));
        assert.equal(runCli(['--mode','config-preflight','--config',config,'--output',out],io),1);assert.match(error,/NEW_JSON_OUTPUT_REQUIRED/);
        output='';error='';assert.equal(runCli(['--config',config],io),1);assert.equal(JSON.parse(output).overallThreeDomainSnapshotVerified,false);
        assert.equal(runCli(['--evidence',join(dir,'missing.json')],io),1);
        assert.equal(runCli(['--evidence',join(dir,'old-package.gcm')],io),1);assert.match(error,/METADATA_ONLY_NO_GOAL_SECRET_OR_PACKAGE/);
        assert.equal(runCli(['--evidence',join(dir,'goal-receipt.json')],io),1);
        assert.equal(runCli(['--evidence',join(dir,'vault.json')],io),1);
        assert.equal(runCli(['--unknown','x'],io),1);
        assert.equal(runCli(['--mode','config-preflight','--mode','resource-preflight'],io),1);
    } finally {for(const p of [config,out]){try{unlinkSync(p);}catch{}}rmdirSync(dir);}
});
