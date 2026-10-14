import {canonical} from './single-database-lifecycle-runtime.mjs';
import {deploymentAccounts} from './single-database-lifecycle.mjs';
import {hasNativePrivileges,nativeAccountDefaultsSha256} from './single-database-native-privileges.mjs';
import {compileInstallation} from './single-database-installation.mjs';

/** Offline preparation, not authorization to execute DCL or import. The caller must
 * re-read the admitted target under the exclusive typed-bootstrap execution fence. */
export async function compileRestoreNativePreparation({workspaceRoot,deployment,inventory}) {
  const accounts=deploymentAccounts(deployment);
  if(accounts.length!==15||new Set(accounts).size!==15)throw Error('EXACT_RESTORE_FIFTEEN_ACCOUNTS_REQUIRED');
  if(inventory?.format!=='SINGLE_DATABASE_BOOTSTRAP_INVENTORY/v1'||inventory.actualJdbc!==true||inventory.targetId!==deployment.database.containerId)throw Error('ACTUAL_RESTORE_BOOTSTRAP_INVENTORY_REQUIRED');
  if(!hasNativePrivileges(inventory))throw Error('ACTUAL_RESTORE_NATIVE_DEFINITIONS_REQUIRED');
  const sorted=rows=>canonical(rows.map(canonical).sort());
  if(!Array.isArray(inventory.publicSystemPrivileges)||!Array.isArray(inventory.publicRoles)||sorted(inventory.publicSystemPrivileges)!==sorted(inventory.nativePrivileges.definitions.PUBLIC.system)||sorted(inventory.publicRoles)!==sorted(inventory.nativePrivileges.definitions.PUBLIC.roles))throw Error('RESTORE_PUBLIC_NATIVE_INVENTORY_CONTRADICTION');
  if(!Array.isArray(inventory.accounts)||canonical(inventory.accounts.map(a=>a.name).sort())!==canonical([...accounts].sort()))throw Error('EXACT_RESTORE_ACCOUNT_SET_REQUIRED');
  const fields=['users','objects','systemPrivileges','roles','tableGrants','columnGrants'];
  if(inventory.accounts.some(a=>fields.some(k=>!Array.isArray(a[k])||a[k].length!==0)))throw Error('RESTORE_PREPARATION_EMPTY_ACCOUNTS_REQUIRED');
  const compiled=await compileInstallation({workspaceRoot,deployment,stage:'bootstrap',inventory});
  if(compiled.manifest.steps.some(s=>!['create-user','grant-system'].includes(s.kind)||s.kind==='create-user'&&s.nativeDefaultsSha256!==nativeAccountDefaultsSha256||s.kind==='grant-system'&&s.privilege==='CREATE SESSION'))throw Error('CURRENT_NATIVE_RESTORE_BOOTSTRAP_ONLY');
  return compiled;
}
