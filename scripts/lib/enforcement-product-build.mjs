export const eligiblePages=Object.freeze(['job-pack','document-templates','document-rules','legal-references']);
export function requireEnforcementBuildBinding(env) {
  const pages=typeof env.VITE_ENFORCEMENT_ENABLED_PAGES==='string'?env.VITE_ENFORCEMENT_ENABLED_PAGES.split(',').map(p=>p.trim()):[];
  if(!pages.length||new Set(pages).size!==pages.length||pages.some(p=>!eligiblePages.includes(p))||!pages.includes('job-pack')||(pages.includes('document-rules')&&!pages.includes('document-templates')))throw Error('EXPLICIT_ENFORCEMENT_PAGE_ADMISSION_REQUIRED');
  const keys=['VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID','VITE_ENFORCEMENT_PRODUCT_CLIENT_ID'];
  if(pages.includes('document-templates'))keys.push('VITE_ENFORCEMENT_DOCUMENT_CLIENT_ID');
  if(pages.includes('document-rules'))keys.push('VITE_ENFORCEMENT_DOCUMENT_RULE_CLIENT_ID');
  if(pages.includes('legal-references'))keys.push('VITE_ENFORCEMENT_LEGAL_CLIENT_ID');
  const clientBindings={};for(const key of keys){const id=env[key];if(typeof id!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/.test(id))throw Error('CURRENT_ENFORCEMENT_CLIENT_BINDING_REQUIRED');clientBindings[key]=id;}
  if(new Set(Object.values(clientBindings)).size!==keys.length)throw Error('DISTINCT_ENFORCEMENT_CLIENT_BINDINGS_REQUIRED');
  return {enabledPages:[...pages].sort(),clientBindings};
}
