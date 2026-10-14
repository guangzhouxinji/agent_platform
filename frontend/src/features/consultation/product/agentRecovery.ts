import type { AgentRecoveryPort, AgentRecoveryState } from "../../agent/AgentTaskPanel";
import type { Workspace } from "./types";

// A local receipt hint, never authorization. Every restored Run is re-read by the server.
export function consultationAgentRecovery(token:string,workspace:Workspace,
  storage?:Storage,now:()=>number=Date.now):AgentRecoveryPort|undefined {
  let identity:{iss?:unknown;sub?:unknown};
  try {
    const segment=token.split(".")[1];if(!segment)return;
    const bytes=Uint8Array.from(atob(segment.replace(/-/g,"+").replace(/_/g,"/")),c=>c.charCodeAt(0));
    identity=JSON.parse(new TextDecoder().decode(bytes));
  }catch{return;}
  if(typeof identity.iss!=="string"||typeof identity.sub!=="string"
    ||!identity.iss||!identity.sub||identity.iss.length>512||identity.sub.length>200)return;
  const goal=workspace.goal;if(!goal?.expiresAt)return;
  const expires=Math.min(Date.parse(workspace.task.expiresAt),Date.parse(goal.expiresAt));
  if(!Number.isFinite(expires))return;
  const binding={issuer:identity.iss,subject:identity.sub,taskId:workspace.task.id,
    inputId:goal.inputId,taskVersion:workspace.task.version};
  // Access can itself throw (privacy policy / unavailable session storage).
  // Keep a failing port rather than silently sending an unrecorded creation.
  let storageFailure:unknown;
  if(!storage){try{storage=window.sessionStorage;}catch(cause){storageFailure=cause;}}
  function store():Storage {
    if(storageFailure||!storage)throw new Error("浏览器恢复存储不可用，未发送新的运行创建请求。");
    return storage;
  }
  const key="consultation-agent-recovery/v1:"+JSON.stringify(Object.values(binding));
  const fail=()=>new Error("浏览器恢复记录不可核验，未重新创建运行。请输入已有运行编号重新读取对账。");
  function valid(state:AgentRecoveryState):boolean {
    if(!state||typeof state.runId!=="string"||state.runId.length>200
      ||Object.keys(state).sort().join(",")!=="creation,runId")return false;
    if(state.creation===null)return !!state.runId;
    const c=state.creation,b=c?.body as {expectedTaskVersion?:unknown};
    return state.runId===""&&!!c&&Object.keys(c).sort().join(",")==="body,key"
      &&typeof c.key==="string"&&/^[A-Za-z0-9._:-]{1,120}$/.test(c.key)
      &&!!b&&typeof b==="object"&&Object.keys(b).join(",")==="expectedTaskVersion"
      &&b.expectedTaskVersion===binding.taskVersion;
  }
  return {
    load(){
      const text=store().getItem(key);if(text===null)return null;
      let record;try{record=JSON.parse(text);}catch{throw fail();}
      if(!record||Object.keys(record).sort().join(",")!=="binding,expires,state"||record.expires!==expires||!record.binding
        ||JSON.stringify(record.binding)!==JSON.stringify(binding)||!valid(record.state))throw fail();
      if(expires<=now()){store().removeItem(key);return null;}
      return record.state as AgentRecoveryState;
    },
    save(state){
      if(expires<=now()||!valid(state))throw fail();
      // No body other than the already confirmed task version is accepted.
      store().setItem(key,JSON.stringify({binding,expires,state}));
    },
    clear(){store().removeItem(key);}
  };
}
