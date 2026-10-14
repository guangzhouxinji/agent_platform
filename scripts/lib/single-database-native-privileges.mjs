import {readFileSync} from 'node:fs';
import {canonical,hash} from './single-database-lifecycle-runtime.mjs';
const raw=readFileSync(new URL('../../deployment/single-database/native-privileges.json',import.meta.url));
if(hash(raw)!=='2dc72c4d238af65698279d2b0cb59ca3e063082445c943b3852440633958ed7e')throw Error('NATIVE_PRIVILEGE_SOURCE_CHANGED');
export const nativePolicy=JSON.parse(raw);
const fields={system:['PRIVILEGE','ADMIN_OPTION'],roles:['GRANTED_ROLE','ADMIN_OPTION'],tables:['OWNER','TABLE_NAME','PRIVILEGE','GRANTABLE'],columns:['OWNER','TABLE_NAME','COLUMN_NAME','PRIVILEGE','GRANTABLE']};
const project=(rows,kind)=>rows.map(row=>Object.fromEntries(fields[kind].map(k=>[k,row[k]]))).sort((a,b)=>canonical(a).localeCompare(canonical(b)));
export function assertNativeDefinition(subject,definition){
 const expected=nativePolicy.definitions[subject];
 if(!expected||!definition||Object.keys(fields).some(k=>!Array.isArray(definition[k])||canonical(project(definition[k],k))!==canonical(project(expected[k],k))))throw Error('NATIVE_PRIVILEGE_DRIFT');
}
export function hasNativePrivileges(inventory){
 if(inventory.nativePrivileges===undefined){if(/dm/i.test(inventory.databaseProduct||''))throw Error('NATIVE_PRIVILEGE_INVENTORY_REQUIRED');return false;}
 const n=inventory.nativePrivileges;
 if(n.format!=='DM8_NATIVE_PRIVILEGES/v1'||canonical(Object.keys(n.definitions||{}).sort())!==canonical(['PUBLIC','SOI','SVI']))throw Error('EXACT_NATIVE_PRIVILEGE_INVENTORY_REQUIRED');
 for(const subject of ['PUBLIC','SOI','SVI'])assertNativeDefinition(subject,n.definitions[subject]);
 return true;
}
export function assertNativeAccountRoles(roles,names){
 if(!Array.isArray(roles)||roles.length!==names.length||canonical(roles.map(r=>r.GRANTED_ROLE).sort())!==canonical([...names].sort())||roles.some(r=>!['Y','N','YES','NO'].includes(r.ADMIN_OPTION)))throw Error('NATIVE_ACCOUNT_ROLE_DRIFT');
}

export const nativeAccountDefaultsSha256=hash(canonical(nativePolicy.accountCreationDefaults));
