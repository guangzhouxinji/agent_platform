import {hash} from './single-database-lifecycle-runtime.mjs';
// Frozen installed 8.1.4.170 SYS views, approved original capture51/ADR9.27.4.
// Hash only: neither trimmed/reparsed SQL nor a prefix/version whitelist can substitute.
export const RESTORE_VIEW_BODY_SHA256=Object.freeze({
  "SYS:DBA_TRIGGERS": "deefb982e9fcd19191588ee002f557539103c31b7193d0e0e55ed6bca4445510",
  "SYS:DBA_TAB_COLUMNS": "d3febf537929b04cfb3e99cee8ca72accfe8897b856761206f6412cfc76d63ef",
  "SYS:DBA_TABLES": "8035da6e8154663fa739dec9aa6125e5ec7460ad4c81b797975435413b2883bc",
  "SYS:DBA_OBJECTS": "66401f539258ddf5bf53e75f23d0166956db4e085ad379b25c4347c4d608faee",
  "SYS:DBA_IND_EXPRESSIONS": "f2a87034e1cab8e801b98d22af069da1006e2e073b56b0c5e40376cbf5a52356",
  "SYS:DBA_IND_COLUMNS": "d59c865f8d0c003c00fd1f62d92b29ad586baa249fbdd8b7eb9942da81c8ea9b",
  "SYS:DBA_INDEXES": "1eccb5d786a7f64618e5e22718afaa916e4bc59f7b8484b8c9c902088ce8d9f3",
  "SYS:DBA_DEPENDENCIES": "1d797ac20e70885ba0cf0fd4d4abdd5a9270ee73814ada8c5bf0d0121d8bdc00",
  "SYS:DBA_CONS_COLUMNS": "8d410c209f20237946577b892f6f5c2ba6ea5c31422dbab2aa4c33d4c9cfdda3",
  "SYS:DBA_CONSTRAINTS": "bae1a7db0b7b18e4984d3395ae5769ffe9d68971b68997ab6c0d1b204abfdeb4"
});
export function assertKnownRestoreViews(rows){
 if(!Array.isArray(rows)||rows.length!==10||new Set(rows.map(r=>r.OWNER+':'+r.VIEW_NAME)).size!==10||rows.some(r=>typeof r.TEXT!=='string'||RESTORE_VIEW_BODY_SHA256[r.OWNER+':'+r.VIEW_NAME]!==hash(r.TEXT)))throw new Error('RESTORE_CATALOG_VIEW_DEFINITION_DRIFT');
}
