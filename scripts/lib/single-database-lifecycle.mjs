import { createHash } from 'node:crypto';

// Reuses ADR §9.4 and SINGLE_DM8_DEPLOYMENT/v1 only. No lifecycle execution or new restore contract.
const DOMAINS=['consultation','enforcement','adjudication'];
const ID=/^[a-f0-9]{64}$/;
const IDENT=/^[A-Z][A-Z0-9_]{0,63}$/;
const fail=code=>{throw new Error(code);};
const record=x=>x!==null && typeof x==='object' && !Array.isArray(x);
const exactKeys=(x,keys,code)=>{if(!record(x) || Object.keys(x).some(k=>!keys.includes(k)) || keys.some(k=>!(k in x)))fail(code);};
const integer=(n,max)=>Number.isSafeInteger(n) && n>0 && n<=max;
const unique=(values,code)=>{if(new Set(values).size!==values.length)fail(code);};
export const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');

export function validateDeploymentConfiguration(config) {
    exactKeys(config,['schema','database','domains'],'UNSUPPORTED_CONFIGURATION_FIELDS');
    if(!['SINGLE_DM8_DEPLOYMENT/v1','SINGLE_DM8_DEPLOYMENT/v2'].includes(config.schema))fail('DEPLOYMENT_VERSION_REQUIRED');
    const full=config.schema==='SINGLE_DM8_DEPLOYMENT/v2';
    const db=config.database;
    exactKeys(db,['containerId','volume','ownerTask','jdbcUrl','memoryLimitBytes','nanoCpuLimit'],'UNSUPPORTED_DATABASE_FIELDS');
    if(!ID.test(db.containerId) || !/^agent-platform-[a-z0-9-]+$/.test(db.volume)
        || !/^[A-Z][A-Z0-9-]+$/.test(db.ownerTask))fail('EXACT_OWNED_DATABASE_REQUIRED');
    const match=/^jdbc:dm:\/\/([A-Za-z0-9.-]+):([0-9]{1,5})$/.exec(db.jdbcUrl);
    if(!match || !integer(Number(match[2]),65535))fail('EXACT_DM8_ENDPOINT_REQUIRED');
    if(!integer(db.memoryLimitBytes,4294967296) || !integer(db.nanoCpuLimit,2000000000))fail('FINITE_RESOURCE_LIMITS_REQUIRED');
    if(!Array.isArray(config.domains) || config.domains.length!==3)fail('THREE_DOMAINS_REQUIRED');
    const domains=[],issuers=[],schemas=[],accounts=[],writers=[];
    for(const d of config.domains) {
        exactKeys(d,['domain','issuer','business','authority','writerContainerIds'],'UNSUPPORTED_DOMAIN_FIELDS');
        if(!DOMAINS.includes(d.domain))fail('UNKNOWN_DOMAIN');domains.push(d.domain);
        let issuer;try{issuer=new URL(d.issuer);}catch{fail('EXACT_HTTPS_ISSUER_REQUIRED');}
        if(issuer.protocol!=='https:' || issuer.username || issuer.password || issuer.search || issuer.hash)fail('EXACT_HTTPS_ISSUER_REQUIRED');
        if(full&&(d.issuer.endsWith('/')||['localhost','127.0.0.1','[::1]'].includes(issuer.hostname)))fail('FIXED_NONLOCAL_ISSUER_REQUIRED');
        issuers.push(issuer.href);
        for(const kind of ['business','authority']) {
            const s=d[kind];exactKeys(s,['schema','jdbcUrl','ownerUser','runtimeUser',...(full&&kind==='authority'?['backupUser']:[])],'UNSUPPORTED_ACCOUNT_BINDING_FIELDS');
            if(s.jdbcUrl!==db.jdbcUrl || !/^[A-Z][A-Z0-9_]{2,63}$/.test(s.schema))fail('SIX_SCHEMAS_ONE_ENDPOINT_REQUIRED');
            schemas.push(s.schema.toUpperCase());
            for(const key of ['ownerUser','runtimeUser',...(full&&kind==='authority'?['backupUser']:[])]) {
                if(!IDENT.test(s[key]) || ['SYSDBA','SYSAUDITOR','SYSSSO'].includes(s[key]))fail('NON_SYSTEM_ACCOUNTS_REQUIRED');
                accounts.push(s[key].toUpperCase());
            }
        }
        if(!Array.isArray(d.writerContainerIds) || d.writerContainerIds.some(id=>!ID.test(id)))fail('EXACT_WRITER_INVENTORY_REQUIRED');
        writers.push(...d.writerContainerIds);
    }
    unique(domains,'DUPLICATE_DOMAIN');unique(issuers,'DUPLICATE_ISSUER');unique(schemas,'DUPLICATE_SCHEMA');unique(accounts,'DUPLICATE_ACCOUNT');unique(writers,'DUPLICATE_WRITER');
    return {domainCount:3,schemaCount:6,accountCount:full?15:12,issuerCount:3,
        configurationSha256:sha256(Buffer.from(JSON.stringify(config))),credentialsIncluded:false,
        endpoint:{host:match[1],port:Number(match[2])},bindings:config.domains.map(d=>({domain:d.domain,issuer:d.issuer,
            settings:{SPRING_DATASOURCE_URL:db.jdbcUrl,SPRING_DATASOURCE_USERNAME:d.business.runtimeUser,
                SPRING_DATASOURCE_HIKARI_SCHEMA:d.business.schema,PRODUCT_IDENTITY_ISSUER:d.issuer,
                PRODUCT_IDENTITY_AUTHORITY_TOPOLOGY:'shared-instance',PRODUCT_IDENTITY_AUTHORITY_JDBC_URL:db.jdbcUrl,
                PRODUCT_IDENTITY_AUTHORITY_SCHEMA:d.authority.schema,PRODUCT_IDENTITY_AUTHORITY_USERNAME:d.authority.runtimeUser}}))};
}

const one=(input,code)=>{const items=Array.isArray(input)?input:[input];if(items.length!==1 || !record(items[0]))fail(code);return items[0];};
export function validateOfflineResources(config, containerInput, volumeInput, writerInput, requireStoppedWriters=false) {
    const plan=validateDeploymentConfiguration(config),db=config.database;
    const c=one(containerInput,'EXACT_CONTAINER_SNAPSHOT_REQUIRED'),v=one(volumeInput,'EXACT_VOLUME_SNAPSHOT_REQUIRED');
    if(c.Id!==db.containerId || c.Config?.Labels?.task!==db.ownerTask)fail('RESOURCE_IDENTITY_OR_OWNER_MISMATCH');
    if(typeof c.State?.Running!=='boolean' || c.HostConfig?.Privileged!==false || typeof c.Config?.User!=='string'
        || !c.Config.User.trim() || /^(?:root|0)(?::|$)/.test(c.Config.User))fail('NONROOT_UNPRIVILEGED_RESOURCE_REQUIRED');
    if(!integer(c.HostConfig.Memory,db.memoryLimitBytes) || !integer(c.HostConfig.NanoCpus,db.nanoCpuLimit))fail('RESOURCE_BOUNDS_MISMATCH');
    for(const destination of ['/opt/dmdbms/data','/opt/dmdbms/log']) {
        const mounts=(c.Mounts||[]).filter(m=>m.Destination===destination);
        if(mounts.length!==1 || mounts[0].Type!=='volume' || mounts[0].Name!==db.volume || mounts[0].RW!==true)fail('EXACT_PERSISTENT_VOLUME_REQUIRED');
    }
    if(v.Name!==db.volume || v.Labels?.task!==db.ownerTask)fail('VOLUME_OWNERSHIP_MISMATCH');
    const {host,port}=plan.endpoint;
    if(['127.0.0.1','localhost','host.docker.internal'].includes(host)) {
        const bindings=c.State.Running?c.NetworkSettings?.Ports?.['5236/tcp']:c.HostConfig.PortBindings?.['5236/tcp'];
        if(!Array.isArray(bindings) || bindings.filter(p=>p.HostIp==='127.0.0.1' && p.HostPort===String(port)).length!==1)fail('EXACT_LOOPBACK_BINDING_REQUIRED');
    } else {
        const aliases=Object.values(c.NetworkSettings?.Networks||{}).flatMap(n=>n.Aliases||[]);
        if(port!==5236 || (c.Name?.replace(/^\//,'')!==host && !aliases.includes(host)))fail('EXACT_MANAGED_ALIAS_REQUIRED');
    }
    if(requireStoppedWriters) {
        if(!Array.isArray(writerInput))fail('STOPPED_WRITER_SNAPSHOTS_REQUIRED');
        const expected=config.domains.flatMap(d=>d.writerContainerIds);
        if(writerInput.length!==expected.length)fail('WRITER_SNAPSHOT_SET_MISMATCH');
        for(const d of config.domains) {
            if(!d.writerContainerIds.length)fail('NONEMPTY_WRITER_INVENTORY_REQUIRED');
            for(const id of d.writerContainerIds) {
                const rows=writerInput.filter(w=>w.Id===id);
                if(rows.length!==1 || rows[0].Config?.Labels?.['agent-platform.domain']!==d.domain || rows[0].State?.Running!==false)fail('WRITER_ID_DOMAIN_OR_STOPPED_MISMATCH');
            }
        }
    }
    return {scope:'OFFLINE_CAPTURED_RESOURCE_PREFLIGHT_NOT_CURRENT_RUNTIME_OR_RECOVERY',capturedResourceVerified:true,
        databaseRunningInCapture:c.State.Running,enumeratedWritersStoppedInCapture:requireStoppedWriters,
        currentResourceVerified:false,unlistedWritersProvenAbsent:false,diskCapacityVerified:false,databaseConnectivityVerified:false};
}

/** Reference-only metadata index; it is not a native/physical backup or a release/recovery authorization. */
export function prepareLifecycle({mode='protection-admission',configuration,containerInspect,volumeInspect,writerInspect,evidenceIndex=[]}={}) {
    if(!['config-preflight','resource-preflight','initialize-admission','protection-admission','recovery-admission'].includes(mode))fail('UNKNOWN_OFFLINE_MODE');
    if(!Array.isArray(evidenceIndex) || evidenceIndex.some(e=>!record(e) || typeof e.path!=='string' || !ID.test(e.sha256) || !Number.isSafeInteger(e.bytes) || e.bytes<0))fail('INVALID_EVIDENCE_INDEX');
    unique(evidenceIndex.map(e=>e.path),'DUPLICATE_EVIDENCE_REFERENCE');
    const report={taskId:'IMP-AGENT-SINGLE-DB-LIFECYCLE-SCRIPTS-01',mode,
        manifestKind:'OFFLINE_RESOURCE_AND_REFERENCE_INDEX_NOT_PHYSICAL_BACKUP',sourceFixedScope:'ADR_SECTION_9_4_RESOURCE_CONFIG_PREFLIGHT_ONLY',
        passed:false,reasons:[],evidenceIndex,configuration:null,resources:null,
        schemaCount:null,accountCount:null,issuerCount:null,
        initializationExecuted:false,migrationsExecuted:false,newBackupExecuted:false,packageOpened:false,
        overallThreeDomainSnapshotVerified:false,physicalProtectionManifestReady:false,restoreExecuted:false,
        currentAuthorityVerified:false,currentAuthorityRestored:false,releaseAuthorized:false,
        sourceRightsVerified:false,auditRetentionIsPackageValidity:false,credentialsIncluded:false};
    try {
        if(!configuration)fail('CONFIGURATION_REQUIRED');
        report.configuration=validateDeploymentConfiguration(configuration);
        report.schemaCount=6;report.accountCount=report.configuration.accountCount;report.issuerCount=3;
        if(mode!=='config-preflight') report.resources=validateOfflineResources(configuration,containerInspect,volumeInspect,writerInspect,
            ['protection-admission','recovery-admission'].includes(mode));
    } catch(e) {report.reasons.push(e.message);}
    if(['initialize-admission','protection-admission','recovery-admission'].includes(mode)) {
        // No approved overall snapshot/pin/retention contract supplied. Child receipts never imply completeness.
        report.reasons.push(...(mode==='initialize-admission'
            ? ['TARGET_EXISTING_DATA_AND_APPLIED_MIGRATIONS_UNPROVEN','APPROVED_FORWARD_MIGRATION_PINS_REQUIRED_NO_BLIND_REPLAY']
            : ['SAME_POINT_THREE_DOMAIN_DB_AND_REQUIRED_FILE_SNAPSHOT_UNPROVEN',
                'APPROVED_FORWARD_MIGRATION_PINS_AND_DEPENDENCIES_UNPROVEN',
                'COMPLETE_BEFORE_AFTER_TABLE_AND_FILE_DIGEST_EQUALITY_UNPROVEN',
                'CURRENT_AUTHORITY_OUTSIDE_OLD_BACKUP_UNPROVEN',
                'PACKAGE_VALIDITY_AND_BACKUP_AUDIT_RETENTION_SEPARATE_EVIDENCE_REQUIRED']));
    }
    report.passed=report.reasons.length===0;
    return report;
}

/** Exact account scope derived from validated versioned deployment; backup users own no domain objects. */
export function deploymentAccounts(config) {
  validateDeploymentConfiguration(config);
  return config.domains.flatMap(d=>[d.business.ownerUser,d.business.runtimeUser,d.authority.ownerUser,d.authority.runtimeUser,...(config.schema==='SINGLE_DM8_DEPLOYMENT/v2'?[d.authority.backupUser]:[])]);
}
