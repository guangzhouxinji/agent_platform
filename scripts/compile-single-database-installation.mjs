#!/usr/bin/env node
import {readFile,writeFile,realpath} from 'node:fs/promises';
import path from 'node:path';
import {compileInstallation} from './lib/single-database-installation.mjs';
const [input,output]=process.argv.slice(2);
try{
 if(!input||!output||process.argv.length!==4)throw new Error('INSTALLATION_INPUT_OUTPUT_REQUIRED');
 const root=await realpath(process.cwd()),parent=await realpath(path.dirname(path.resolve(output))),relative=path.relative(root,parent).replaceAll('\\','/');
 if(relative.startsWith('..')||path.isAbsolute(relative)||/^(requirements|db)(\/|$)/.test(relative))throw new Error('INSTALLATION_OUTPUT_LOCATION_REFUSED');
 const request=JSON.parse(await readFile(input,'utf8'));if(request.workspaceRoot&&path.resolve(request.workspaceRoot)!==root)throw new Error('EXACT_WORKSPACE_REQUIRED');
 const result=await compileInstallation({...request,workspaceRoot:root});
 await writeFile(path.resolve(output),JSON.stringify(result,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({compiled:true,stage:result.stage,executionAuthorized:false}));
}catch(e){console.error(JSON.stringify({compiled:false,code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'INSTALLATION_INPUT_OR_SOURCE_UNAVAILABLE'}));process.exitCode=1;}
