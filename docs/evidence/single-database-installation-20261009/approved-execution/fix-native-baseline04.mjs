import fs from 'node:fs';import {hash,canonical} from '../../../../scripts/lib/single-database-lifecycle-runtime.mjs';
const root=process.cwd(),p='docs/evidence/single-database-installation-20261009/approved-execution';const actual=JSON.parse(fs.readFileSync(p+'/native-public-soi-observation04.json'));
const definitions=Object.fromEntries(actual.accounts.map(a=>[a.name,{system:a.systemPrivileges,roles:a.roles,tables:a.tableGrants,columns:a.columnGrants}]));
const policy={format:'DM8_NATIVE_PRIVILEGES/v1',imageReference:'sha256:6409a1d5435e933d8317a4ed141f4c8ef08bbd4ec91d3b4fe05155b8a3a88760',sourceInventoryPath:p+'/native-public-soi-observation04.json',sourceInventorySha256:hash(fs.readFileSync(p+'/native-public-soi-observation04.json')),officialSourcePath:p+'/dm8-native-role-rule-source01.json',definitions};
const file='deployment/single-database/native-privileges.json';fs.writeFileSync(file,JSON.stringify(policy,null,2)+'\n',{flag:'wx'});console.log(hash(fs.readFileSync(file)));
