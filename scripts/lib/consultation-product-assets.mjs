import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const inside = (parent, child) => { const rel = path.relative(parent,child); return rel !== '' && !rel.startsWith('..'+path.sep) && rel !== '..' && !path.isAbsolute(rel); };
function guarded(root,relative) {
  const base=fs.realpathSync(root),resolved=path.resolve(base,relative);
  if(!inside(base,resolved)) throw Error('OUTPUT_OUTSIDE_WORKSPACE');
  let current=base;
  for(const part of path.relative(base,resolved).split(path.sep)) {
    current=path.join(current,part);
    if(!fs.existsSync(current))continue;
    const stat=fs.lstatSync(current);
    if(stat.isSymbolicLink())throw Error('LINKED_BUILD_PATH');
    if(!stat.isDirectory())throw Error('BUILD_PATH_NOT_DIRECTORY');
    if(!inside(base,fs.realpathSync(current)))throw Error('REAL_PATH_OUTSIDE_WORKSPACE');
  }
  return resolved;
}
function inventory(directory,prefix='') {
  const files=[];
  for(const entry of fs.readdirSync(directory).sort()) {
    const absolute=path.join(directory,entry),relative=prefix+entry,stat=fs.lstatSync(absolute);
    if(stat.isSymbolicLink())throw Error('LINKED_ASSET');
    if(stat.isDirectory())files.push(...inventory(absolute,relative+'/'));
    else if(stat.isFile())files.push({path:relative,sha256:sha(fs.readFileSync(absolute)),bytes:stat.size});
    else throw Error('NON_REGULAR_ASSET');
  }
  return files;
}
export function inspectConsultationAssets(directory) {
  const files=inventory(directory),names=new Set(files.map(f=>f.path));
  if(!names.has('consultation.html'))throw Error('CONSULTATION_ENTRY_MISSING');
  if(files.some(f=>f.path.endsWith('.html') && f.path!=='consultation.html'))throw Error('OTHER_PRODUCT_ENTRY');
  if(names.has('consultation-product-assets.json'))throw Error('SOURCE_MANIFEST_NOT_BUILD_INPUT');
  const html=fs.readFileSync(path.join(directory,'consultation.html'),'utf8');
  const refs=[...html.matchAll(/(?:src|href)="([^"#]+)"/g)].map(m=>m[1]);
  if(!refs.some(ref=>ref.endsWith('.js')))throw Error('BUILT_MODULE_MISSING');
  for(const ref of refs) {
    if(!ref.startsWith('/app/assets/') || /[?#%\\]/.test(ref))throw Error('UNSAFE_ENTRY_REFERENCE');
    const relative=ref.slice('/app/'.length);
    if(relative.split('/').some(part=>part==='..' || part==='.' || !part))throw Error('ENTRY_TRAVERSAL');
    if(!names.has(relative))throw Error('ENTRY_ASSET_MISSING');
  }
  return {contract:'CONSULTATION_PRODUCT_BUNDLE/v1',entry:'consultation.html',files};
}
export function stageConsultationAssets(root) {
  const source=guarded(root,'frontend/dist-consultation');
  const destination=guarded(root,'backend/apps/platform-api/target/consultation-product-classes/static/app');
  if(!fs.statSync(source).isDirectory())throw Error('BUILD_INPUT_NOT_DIRECTORY');
  const manifest=inspectConsultationAssets(source); // Validate ALL inputs before removing prior owned output.
  // Resolved fixed target and every existing ancestor were checked above; never touches shared target/classes.
  if(guarded(root,'backend/apps/platform-api/target/consultation-product-classes/static/app')!==destination)throw Error('OUTPUT_PATH_CHANGED');
  fs.rmSync(destination,{recursive:true,force:true});fs.mkdirSync(destination,{recursive:true});
  for(const file of manifest.files) {
    const target=path.join(destination,...file.path.split('/'));
    fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(path.join(source,...file.path.split('/')),target);
  }
  const actual=inspectConsultationAssets(destination);
  if(JSON.stringify(actual)!==JSON.stringify(manifest))throw Error('STAGED_BYTES_CHANGED');
  fs.writeFileSync(path.join(destination,'consultation-product-assets.json'),JSON.stringify(manifest,null,2));
  return manifest;
}
