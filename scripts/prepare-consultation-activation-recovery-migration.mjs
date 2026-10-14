import { lstat, realpath, open } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { planConsultationActivationRecoveryMigration } from './lib/consultation-activation-recovery-migration.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const fail=code=>{throw Error(code);};
async function workspaceFile(input,{output=false}={}) {
  const p=path.resolve(root,input),rel=path.relative(root,p);
  if (!rel || rel==='..' || rel.startsWith('..'+path.sep) || path.isAbsolute(rel)) fail('FILE_OUTSIDE_WORKSPACE');
  if(await realpath(root)!==root)fail('WORKSPACE_REDIRECT_REJECTED');
  const parts=rel.split(path.sep); let current=root;
  for (let i=0;i<parts.length;i++) {
    current=path.join(current,parts[i]);
    if(output&&i===parts.length-1)break;
    if((await lstat(current)).isSymbolicLink()||await realpath(current)!==current)fail('FILE_REDIRECT_REJECTED');
  }
  return p;
}
try {
  const args=process.argv.slice(2);
  if(args.length!==4 || args[0]!=='--observation' || args[2]!=='--output')fail('EXACT_CLI_ARGUMENTS_REQUIRED');
  const input=await workspaceFile(args[1]),output=await workspaceFile(args[3],{output:true});
  if(input===output)fail('INPUT_OUTPUT_COLLISION');
  const stat=await lstat(input);
  if(!stat.isFile()||stat.size>65536)fail('BOUNDED_OBSERVATION_FILE_REQUIRED');
  const source=await open(input,'r');
  let bytes;
  try {
    const actual=await source.stat();
    if(!actual.isFile()||actual.size>65536)fail('BOUNDED_OBSERVATION_FILE_REQUIRED');
    const bounded=Buffer.alloc(65537);let used=0;
    while(used<bounded.length){const {bytesRead}=await source.read(bounded,used,bounded.length-used,null);if(!bytesRead)break;used+=bytesRead;}
    if(used>65536)fail('BOUNDED_OBSERVATION_FILE_REQUIRED');
    bytes=bounded.subarray(0,used);
  }finally{await source.close();}
  let data;try{data=JSON.parse(bytes.toString('utf8'));}catch{fail('OBSERVATION_JSON_REQUIRED');}
  const result=await planConsultationActivationRecoveryMigration(data,{workspaceRoot:root});
  const file=await open(output,'wx',0o600);
  try{await file.writeFile(JSON.stringify(result,null,2)+'\n');}finally{await file.close();}
  console.log('Metadata diagnosis saved; SQL execution and runtime authorization are NOT granted.');
}catch(e){
  const code=typeof e.message==='string'&&/^[A-Z][A-Z0-9_]+$/.test(e.message)?e.message:'INPUT_OR_OUTPUT_UNAVAILABLE';
  console.error(code);process.exitCode=1;
}
