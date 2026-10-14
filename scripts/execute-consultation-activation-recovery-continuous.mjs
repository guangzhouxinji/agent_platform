#!/usr/bin/env node
import {prepareContinuous75,productionContinuous75,safeCode75} from './lib/consultation-activation-recovery-continuous-runtime.mjs';
const [mode,input,...extra]=process.argv.slice(2);
try{
  if(extra.length||!['prepare','validate','execute'].includes(mode))throw Error('CONTINUOUS_CLI_ARGUMENTS_REFUSED');
  let result;
  if(mode==='prepare')result=await prepareContinuous75(input||'CASE_EMPTY');
  else{if(!input)throw Error('CONTINUOUS_OWNED_RUN_ARGUMENT_REQUIRED');const pipeline=await productionContinuous75(input);result=mode==='validate'?await pipeline.admit():await pipeline.execute();}
  console.log(JSON.stringify({passed:true,mode,result:result??{admissionOnly:true},nativeCaseCompleted:mode==='execute',businessAcceptance:false}));
}catch(e){console.error(JSON.stringify({passed:false,mode,code:safeCode75(e),sourceReturned:e.sourceReturned??false,controllerReleased:e.controllerReleased??false,automaticRetryAllowed:false}));process.exitCode=1;}
