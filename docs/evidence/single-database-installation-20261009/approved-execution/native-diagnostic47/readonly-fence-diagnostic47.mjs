import {readFile,writeFile} from 'node:fs/promises';
import {runNativeCommand} from '../../../../../scripts/lib/single-database-lifecycle-native.mjs';
import {prepareContainerFence} from '../../../../../scripts/lib/single-database-native-fence-container.mjs';
import {nativeDiagnostic,withNativeStage} from '../../../../../scripts/lib/single-database-native-diagnostic.mjs';
const c=JSON.parse(await readFile(new URL('../mainline-execution46/source-snapshot-config46-02.json',import.meta.url),'utf8'));
const records=[];
const read=async (exe,args,options)=>{const startedAt=new Date().toISOString();try{const out=await runNativeCommand(exe,args,options);records.push({stage:options.stage,startedAt,finishedAt:new Date().toISOString(),passed:true,bytes:out.length});return out;}catch(e){records.push({passed:false,diagnostic:nativeDiagnostic(e)});throw e;}};
let failure;try{await withNativeStage('FENCE_OPEN',()=>prepareContainerFence(c,read));}catch(e){failure={code:/^[A-Z0-9_]+$/.test(e.message)?e.message:'UNCLASSIFIED',diagnostic:nativeDiagnostic(e)};}
await writeFile(new URL('readonly-fence-diagnostic47.json',import.meta.url),JSON.stringify({format:'ACTUAL_READONLY_FENCE_DIAGNOSTIC/v1',targetId:c.targetId,passed:!failure,records,failure,fenceAcquired:false,nativeExportAttempted:false,oldFailureStage:'UNKNOWN'},null,2)+'\n',{flag:'wx'});
console.log(JSON.stringify({passed:!failure,stages:records.map(r=>r.stage),nativeExportAttempted:false}));if(failure)process.exitCode=1;
