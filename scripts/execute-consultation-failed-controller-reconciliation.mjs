#!/usr/bin/env node
import {createInterface} from 'node:readline/promises';
import {createProductionReconciliation81} from './lib/consultation-failed-controller-reconciliation-runtime.mjs';
import {reconciliationCode81} from './lib/consultation-failed-controller-reconciliation.mjs';
const [mode,...extra]=process.argv.slice(2);
let input;
try{
  if(extra.length||!['validate','execute'].includes(mode))throw Error('RECONCILIATION_FIXED_CLI_REQUIRED');
  const pipeline=await createProductionReconciliation81();
  console.log(JSON.stringify({phase:'HOST_REVIEW_REQUIRED',relative:pipeline.relative,runId:pipeline.runId,observerPid:process.pid,secretRead:false}));
  input=createInterface({input:process.stdin,output:process.stdout});
  const answer=await input.question('');
  if(answer!=='CONTINUE')throw Error('RECONCILIATION_EXPLICIT_CONTINUE_REQUIRED');
  input.close();input=null;
  const result=mode==='execute'?await pipeline.execute():await pipeline.admit();
  console.log(JSON.stringify({passed:true,mode,result:result??{admissionOnly:true},businessAcceptance:false}));
}catch(e){console.error(JSON.stringify({passed:false,mode,code:reconciliationCode81(e),automaticRetryAllowed:false}));process.exitCode=1;}
finally{input?.close();}
