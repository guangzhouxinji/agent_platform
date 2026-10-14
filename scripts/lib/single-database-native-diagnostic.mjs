import {AsyncLocalStorage} from 'node:async_hooks';
const context=new AsyncLocalStorage(),trusted=new WeakMap();
const stages=new Set(['TARGET_INSPECT','WRITER_INSPECT','FENCE_METADATA_INSPECT','FENCE_CLASS_DIGEST','FENCE_OPEN','FENCE_ACQUIRE','FENCE_ASSERT','FENCE_RELEASE','INVENTORY_BEFORE','INVENTORY_AFTER','NATIVE_EXPORT','NATIVE_READ','SNAPSHOT_SEAL','UNCLASSIFIED']);
const reasons=new Set(['SPAWN_ERROR','TIMEOUT','OUTPUT_LIMIT','STREAM_ERROR','NONZERO_EXIT','SIGNAL_EXIT','UNKNOWN_EXIT','STAGE_REFUSED']);
const signals=new Set(['SIGTERM','SIGKILL','SIGINT','SIGABRT','SIGSEGV','SIGHUP','SIGPIPE']);
export function nativeStage(stage=context.getStore()??'UNCLASSIFIED') {
  if(!stages.has(stage))throw new Error('FIXED_NATIVE_DIAGNOSTIC_STAGE_REQUIRED');
  return stage;
}
const count=n=>Number.isSafeInteger(n)&&n>=0?n:0;
/** Only this module's own fixed-schema records are public; Error properties are untrusted. */
export function recordNativeFailure(error,{stage,startedAt,started,reason='STAGE_REFUSED',stdoutBytes=0,stderrBytes=0,exitCode=null,signal=null,pid=null,closeObserved=false,notStarted=false}={}) {
  if(!(error instanceof Error)||trusted.has(error))return error;
  const safeStage=nativeStage(stage),end=Date.now();
  trusted.set(error,Object.freeze({format:'NATIVE_COMMAND_DIAGNOSTIC/v1',stage:safeStage,
    startedAt:new Date(Number.isFinite(startedAt)?startedAt:end).toISOString(),finishedAt:new Date(end).toISOString(),
    elapsedMs:Math.max(0,Math.round(performance.now()-(Number.isFinite(started)?started:performance.now()))),
    reason:reasons.has(reason)?reason:'STAGE_REFUSED',stdoutBytes:count(stdoutBytes),stderrBytes:count(stderrBytes),
    exitCode:Number.isInteger(exitCode)&&Math.abs(exitCode)<=2147483647?exitCode:null,
    signal:signal==null?null:signals.has(signal)?signal:'UNKNOWN',pid:Number.isSafeInteger(pid)&&pid>0?pid:null,
    closeObserved:closeObserved===true,termination:closeObserved===true?'CLOSE_OBSERVED':notStarted===true?'NOT_STARTED':'UNCONFIRMED',
    remoteTermination:'NOT_PROVEN'}));
  return error;
}
export const nativeDiagnostic=error=>trusted.get(error);
export async function withNativeStage(stage,work) {
  nativeStage(stage);const startedAt=Date.now(),started=performance.now();
  return context.run(stage,async()=>{try{return await work();}catch(error){recordNativeFailure(error,{stage,startedAt,started});throw error;}});
}
