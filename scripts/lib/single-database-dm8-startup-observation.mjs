const fail=code=>{throw new Error(code);};
export function observeOwnedDm8Startup(capture,{targetId,previousStartedAt,now=Date.now(),deadline}){
 if(!/^[a-f0-9]{64}$/.test(targetId||'')||capture?.Id!==targetId)fail('STARTUP_TARGET_ID_DRIFT');
 const s=capture.State,prior=Date.parse(previousStartedAt),started=Date.parse(s?.StartedAt),until=Date.parse(deadline);
 if(!Number.isFinite(prior)||!Number.isFinite(started)||!Number.isFinite(until)||!Number.isFinite(now)||!s||typeof s.Running!=='boolean'||typeof s.Restarting!=='boolean'||!Number.isSafeInteger(s.Pid)||s.Pid<0)fail('STARTUP_STATE_UNAVAILABLE_OR_INVALID');
 if(now>=until)fail('STARTUP_READINESS_DEADLINE');if(s.Restarting)fail('STARTUP_UNEXPECTED_RESTART');
 if(s.StartedAt===previousStartedAt||started<=prior){if(!s.Running&&s.Pid===0)return {phase:'WAITING_FOR_NEW_START',readyMayBeChecked:false};fail('STARTUP_STALE_RUNNING_STATE');}
 if(!s.Running){if(s.Pid!==0)fail('STARTUP_STATE_UNAVAILABLE_OR_INVALID');return {phase:'NEW_INSTANCE_EXITED',readyMayBeChecked:false,exitCode:Number.isSafeInteger(s.ExitCode)?s.ExitCode:null};}
 if(s.Pid===0)fail('STARTUP_RUNNING_PID_REQUIRED');return {phase:'NEW_INSTANCE_RUNNING',readyMayBeChecked:true,logsSince:s.StartedAt};
}
