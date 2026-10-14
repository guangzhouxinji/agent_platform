import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';
import {compileRestoreNativePreparation} from './single-database-restore-native-preparation.mjs';
import {deploymentAccounts} from './single-database-lifecycle.mjs';
import {checkBootstrapInventory} from './single-database-bootstrap.mjs';
const b='docs/evidence/single-database-installation-20261009/approved-execution',read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
// Collected target58 read-only inventory seeds compiler components, never execution admission.
const source=read(b+'/source-protection56b/source-snapshot-config56.json'),request=read(b+'/resource-request-original01.json'),d=structuredClone(source.deployment);d.database={...d.database,containerId:'78963630b94072dd64c1a7701200c4c48bf837350a9f85b5c9126cfd934ff481',volume:request.targets[1].newVolume,jdbcUrl:request.managedJdbcExecution.restoreUrl};for(const domain of d.domains){domain.business.jdbcUrl=d.database.jdbcUrl;domain.authority.jdbcUrl=d.database.jdbcUrl;}
const original=read(b+'/independent-restore58/target-state58.json');original.accounts=original.accounts.filter(a=>deploymentAccounts(d).includes(a.name));
const compile=(i=structuredClone(original),deployment=d)=>compileRestoreNativePreparation({workspaceRoot:process.cwd(),deployment,inventory:i});
test('current native preparation preserves approved fifteen accounts and measured defaults without PUBLIC or SOI DCL',async()=>{const c=await compile();assert.equal(c.executionAuthorized,false);assert.equal(c.manifest.steps.filter(s=>s.kind==='create-user').length,15);assert.equal(c.manifest.steps.some(s=>s.kind.includes('revoke')||s.account==='PUBLIC'||s.account==='SOI'||s.privilege==='CREATE SESSION'),false);assert.equal(checkBootstrapInventory(structuredClone(original),c.manifest,0).actualJdbc,true);});
for(const [name,mutate,code] of [
 ['missing native inventory',i=>{delete i.nativePrivileges;},/NATIVE_PRIVILEGE_INVENTORY_REQUIRED|ACTUAL_RESTORE_NATIVE_DEFINITIONS_REQUIRED/],
 ['PUBLIC extra ANY',i=>i.nativePrivileges.definitions.PUBLIC.system.push({PRIVILEGE:'SELECT ANY TABLE',ADMIN_OPTION:'NO'}),/NATIVE_PRIVILEGE_DRIFT/],
 ['SVI changed',i=>i.nativePrivileges.definitions.SVI.tables.pop(),/NATIVE_PRIVILEGE_DRIFT/],
 ['SOI changed',i=>i.nativePrivileges.definitions.SOI.roles.push({GRANTED_ROLE:'DBA',ADMIN_OPTION:'NO'}),/NATIVE_PRIVILEGE_DRIFT/],
 ['unrecorded definition',i=>i.nativePrivileges.definitions.OTHER={},/EXACT_NATIVE_PRIVILEGE_INVENTORY_REQUIRED/],
 ['partial account',i=>i.accounts[0].users.push({USERNAME:i.accounts[0].name}),/RESTORE_PREPARATION_EMPTY_ACCOUNTS_REQUIRED/],
 ['partial schema object',i=>i.accounts[0].objects.push({OBJECT_TYPE:'SCH'}),/RESTORE_PREPARATION_EMPTY_ACCOUNTS_REQUIRED/],
 ['unexpected role',i=>i.accounts[0].roles.push({GRANTED_ROLE:'PUBLIC'}),/RESTORE_PREPARATION_EMPTY_ACCOUNTS_REQUIRED/],
 ['unexpected system grant',i=>i.accounts[0].systemPrivileges.push({PRIVILEGE:'CREATE SESSION'}),/RESTORE_PREPARATION_EMPTY_ACCOUNTS_REQUIRED/],
 ['unexpected object grant',i=>i.accounts[0].tableGrants.push({OWNER:'SYS'}),/RESTORE_PREPARATION_EMPTY_ACCOUNTS_REQUIRED/],
 ['unexpected column grant',i=>i.accounts[0].columnGrants.push({OWNER:'SYS'}),/RESTORE_PREPARATION_EMPTY_ACCOUNTS_REQUIRED/],
 ['missing list',i=>{delete i.accounts[0].objects;},/RESTORE_PREPARATION_EMPTY_ACCOUNTS_REQUIRED/],
 ['wrong target',i=>i.targetId='other',/ACTUAL_RESTORE_BOOTSTRAP_INVENTORY_REQUIRED/],
 ['not actual JDBC',i=>i.actualJdbc=false,/ACTUAL_RESTORE_BOOTSTRAP_INVENTORY_REQUIRED/],
 ['missing account',i=>i.accounts.pop(),/EXACT_RESTORE_ACCOUNT_SET_REQUIRED/],
 ['duplicate account',i=>i.accounts[0]=structuredClone(i.accounts[1]),/EXACT_RESTORE_ACCOUNT_SET_REQUIRED/],
 ['extra account',i=>i.accounts.push({name:'PUBLIC'}),/EXACT_RESTORE_ACCOUNT_SET_REQUIRED/],
 ['PUBLIC roles contradict native definition',i=>i.publicRoles=[],/BOOTSTRAP_PUBLIC_NATIVE_DRIFT|NATIVE_PRIVILEGE_DRIFT|PUBLIC/]
])test('rejects '+name,async()=>{const i=structuredClone(original);mutate(i);await assert.rejects(compile(i),code);});
test('exclusive preparation entry does not reference withdrawn PUBLIC branch and delegates only current native compiler',()=>{const s=fs.readFileSync(b+'/independent-restore59/prepare-target59.mjs','utf8');assert.doesNotMatch(s,/jdbc-runtime02|harden-public-step|primary-native-public-hardening|REVOKE\s+.*PUBLIC/i);assert.match(s,/compileRestoreNativePreparation\(/);assert.match(s,/action:'bootstrap-inventory'/);assert.match(s,/captureRaw/);assert.match(s,/CURRENT_NATIVE_PREPARATION_D_REQUIRED/);assert.doesNotMatch(s,/action:'bootstrap-step'/);});
