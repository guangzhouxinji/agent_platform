import test from 'node:test';import assert from 'node:assert/strict';
import {nativePolicy,hasNativePrivileges,assertNativeDefinition,assertNativeAccountRoles} from '../../../../scripts/lib/single-database-native-privileges.mjs';
const inventory=()=>({nativePrivileges:{format:'DM8_NATIVE_PRIVILEGES/v1',definitions:structuredClone(nativePolicy.definitions)}});
test('exact actual native definition admitted, not a cross-domain application permission',()=>assert.equal(hasNativePrivileges(inventory()),true));
for(const [name,change] of [
 ['ANY system',x=>x.nativePrivileges.definitions.PUBLIC.system.push({PRIVILEGE:'SELECT ANY TABLE',ADMIN_OPTION:'NO'})],
 ['domain PUBLIC table',x=>x.nativePrivileges.definitions.PUBLIC.tables.push({OWNER:'CONAUTH',TABLE_NAME:'ID2_SESSION',PRIVILEGE:'SELECT',GRANTABLE:'NO'})],
 ['extra role',x=>x.nativePrivileges.definitions.PUBLIC.roles.push({GRANTED_ROLE:'DBA',ADMIN_OPTION:'Y'})],
 ['removed builtin',x=>x.nativePrivileges.definitions.SVI.tables.pop()],
 ['column privilege',x=>x.nativePrivileges.definitions.SOI.columns.push({OWNER:'CONAUTH',TABLE_NAME:'ID2_SESSION',COLUMN_NAME:'TOKEN',PRIVILEGE:'SELECT',GRANTABLE:'NO'})]])test(name,()=>{const x=inventory();change(x);assert.throws(()=>hasNativePrivileges(x),/NATIVE_PRIVILEGE_DRIFT/);});
test('real DM inventory cannot silently use legacy empty-native fixture',()=>assert.throws(()=>hasNativePrivileges({databaseProduct:'DM DBMS'}),/NATIVE_PRIVILEGE_INVENTORY_REQUIRED/));
test('extra effective/native subject refused',()=>assert.throws(()=>assertNativeDefinition('DBA',{}),/NATIVE_PRIVILEGE_DRIFT/));
test('SOI present after scheduled removal refused',()=>assert.throws(()=>assertNativeAccountRoles([{GRANTED_ROLE:'PUBLIC',ADMIN_OPTION:'N'},{GRANTED_ROLE:'SOI',ADMIN_OPTION:'N'}],['PUBLIC']),/NATIVE_ACCOUNT_ROLE_DRIFT/));
test('normal intrinsic PUBLIC remains, role escalation refused',()=>{assertNativeAccountRoles([{GRANTED_ROLE:'PUBLIC',ADMIN_OPTION:'N'}],['PUBLIC']);assert.throws(()=>assertNativeAccountRoles([{GRANTED_ROLE:'DBA',ADMIN_OPTION:'Y'}],['PUBLIC']),/NATIVE_ACCOUNT_ROLE_DRIFT/);});
