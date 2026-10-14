import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { requireAdjudicationClientId } from './adjudication-product-build.mjs';
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function guarded(root, relative) {
  const base=fs.realpathSync(root), target=path.resolve(base,relative), rel=path.relative(base,target);
  if(!rel || rel==='..' || rel.startsWith('..'+path.sep) || path.isAbsolute(rel))throw Error('OUTPUT_OUTSIDE_WORKSPACE');
  let current=base;
  for(const part of rel.split(path.sep)) {
    current=path.join(current,part);
    if(!fs.existsSync(current))continue;
    if(fs.lstatSync(current).isSymbolicLink())throw Error('LINKED_BUILD_PATH');
    if(!fs.statSync(current).isDirectory())throw Error('BUILD_PATH_NOT_DIRECTORY');
  }
  return target;
}
function inventory(directory,prefix='') {
  const result=[];
  for(const entry of fs.readdirSync(directory).sort()) {
    const file=path.join(directory,entry),relative=prefix+entry,stat=fs.lstatSync(file);
    if(stat.isSymbolicLink())throw Error('LINKED_ASSET');
    if(stat.isDirectory())result.push(...inventory(file,relative+'/'));
    else if(stat.isFile())result.push({path:relative,sha256:sha(fs.readFileSync(file)),bytes:stat.size});
    else throw Error('NON_REGULAR_ASSET');
  }
  return result;
}
export function inspectAdjudicationAssets(directory) {
  if(fs.lstatSync(directory).isSymbolicLink())throw Error('LINKED_ASSET');
  const files=inventory(directory),names=new Set(files.map(f=>f.path));
  if(!names.has('adjudication.html'))throw Error('ADJUDICATION_ENTRY_MISSING');
  if(files.some(f=>f.path.endsWith('.html')&&f.path!=='adjudication.html'))throw Error('OTHER_PRODUCT_ENTRY');
  if(names.has('adjudication-product-assets.json'))throw Error('SOURCE_MANIFEST_NOT_BUILD_INPUT');
  const html=fs.readFileSync(path.join(directory,'adjudication.html'),'utf8');
  const refs=[...html.matchAll(/(?:src|href)=["']([^"'#]+)["']/g)].map(m=>m[1]);
  if(!refs.some(ref=>ref.endsWith('.js')))throw Error('BUILT_MODULE_MISSING');
  for(const ref of refs) {
    if(!ref.startsWith('/app/assets/')||/[?#%\\]/.test(ref))throw Error('UNSAFE_ENTRY_REFERENCE');
    const relative=ref.slice('/app/'.length);
    if(relative.split('/').some(part=>part==='..'||part==='.'||!part))throw Error('ENTRY_TRAVERSAL');
    if(!names.has(relative))throw Error('ENTRY_ASSET_MISSING');
  }
  return {contract:'ADJUDICATION_PRODUCT_BUNDLE/v1',entry:'adjudication.html',files};
}
export function stageAdjudicationAssets(root) {
  const source=guarded(root,'frontend/dist-adjudication');
  const destination=guarded(root,'backend/apps/platform-api/target/adjudication-product-classes/static/app');
  const manifest=inspectAdjudicationAssets(source);
  const bindingRoot=guarded(root,'frontend/target'), bindingPath=path.join(bindingRoot,'adjudication-build-binding.json');
  if(!fs.existsSync(bindingPath)||fs.lstatSync(bindingPath).isSymbolicLink())throw Error('ADJUDICATION_BUILD_BINDING_REQUIRED');
  const binding=JSON.parse(fs.readFileSync(bindingPath,'utf8'));
  requireAdjudicationClientId({VITE_ADJUDICATION_CLIENT_ID:binding.clientId});
  if(binding.contract!==manifest.contract||binding.entry!==manifest.entry||JSON.stringify(binding.files)!==JSON.stringify(manifest.files))throw Error('ADJUDICATION_BUILD_BINDING_DRIFT');
  // Never replace a prior candidate or partial failure; a fresh isolated build owns this output.
  if(fs.existsSync(destination))throw Error('OWNED_OUTPUT_ALREADY_EXISTS');
  guarded(root,'backend/apps/platform-api/target/adjudication-product-classes/static/app');
  fs.mkdirSync(destination,{recursive:true});
  for(const file of manifest.files) {
    const target=path.join(destination,...file.path.split('/'));
    fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(path.join(source,...file.path.split('/')),target,fs.constants.COPYFILE_EXCL);
  }
  if(JSON.stringify(inspectAdjudicationAssets(destination))!==JSON.stringify(manifest))throw Error('STAGED_BYTES_CHANGED');
  fs.writeFileSync(path.join(destination,'adjudication-product-assets.json'),JSON.stringify(manifest,null,2),{flag:'wx'});
  return manifest;
}
