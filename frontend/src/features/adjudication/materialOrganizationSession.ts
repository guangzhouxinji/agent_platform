import { AdjudicationError, digest, request } from "./api";
import { validateBody } from "./materialOrganizationBinding";
import type { Binding, DecisionView } from "./materialOrganizationBinding";
export type Packet = { path:string; key:string; binding:Binding; bodySHA:string };
export type Unknown = { caseId:string; runId:string; candidateId:string; requestKey:string; bodySHA:string };
export type SessionState = { busy:boolean; packet?:Packet; unknown?:Unknown; confirmed?:DecisionView; confirmedId?:string; error?:Error };
type Transport = (token:string,path:string,body?:unknown,key?:string)=>Promise<unknown>;
type Confirm = (raw:unknown,packet:Packet)=>Promise<DecisionView>;
const refusals=new Set(["INVALID_COMMAND_BODY","MATERIAL_DECISION_VERSION_CONFLICT","WORK_VERSION_CONFLICT","MATERIAL_CANDIDATE_ALREADY_DECIDED","MATERIAL_DECISION_REVISION_NOT_ALLOWED","MATERIAL_DECISION_BASE_VERSION_CONFLICT","MATERIAL_MODEL_RUN_STALE","MATERIAL_HUMAN_TEXT_REQUIRED","MATERIAL_DECISION_REASON_REQUIRED","MATERIAL_DECISION_HUMAN_TEXT_NOT_ALLOWED","MATERIAL_DECISION_REASON_INVALID","MATERIAL_HUMAN_TEXT_TOO_LONG"]);
// This owner outlives material-tab remounts. Denial never erases an unconfirmed remote write.
export class MaterialOrganizationSession {
 private states=new Map<string,SessionState>();private listeners=new Set<()=>void>();private generation=0;private identity:string|null=null;
 private transport:Transport;
 constructor(transport:Transport=request){this.transport=transport;}
 subscribe=(listener:()=>void)=>{this.listeners.add(listener);return()=>{this.listeners.delete(listener);};};
 private put(id:string,state:SessionState){this.states.set(id,state);for(const listener of this.listeners)listener();}
 state(id:string):SessionState{return this.states.get(id)??{busy:false};}
 blocked(id:string){const s=this.state(id);return s.busy||!!s.packet||!!s.unknown||!!s.confirmedId;}
 anyPending(){return [...this.states.values()].some(s=>s.busy||s.packet||s.unknown||s.confirmedId);}
 anyActive(){return [...this.states.values()].some(s=>s.busy||s.packet);}
 activate(identity:string|null){if(identity!==this.identity){this.scrub();this.identity=identity;}}
 scrub(){this.generation++;for(const [id,s] of this.states){const p=s.packet;this.put(id,{busy:false,unknown:p?{caseId:id,runId:p.binding.runId,candidateId:p.binding.candidate.id,requestKey:p.key,bodySHA:p.bodySHA}:s.unknown,confirmedId:s.confirmed?.version.versionId??s.confirmedId});}}
 clearConfirmed(id:string){const s=this.state(id);if(!s.packet&&!s.unknown)this.put(id,{busy:false});}
 async begin(token:string,path:string,binding:Binding,confirm:Confirm,isCurrent:()=>boolean):Promise<DecisionView|undefined>{
  const id=binding.caseId;if(this.blocked(id)||this.anyActive())throw new Error("已有材料整理结果待确认，请先对账或仅查看案件。");validateBody(binding.body);
  const generation=this.generation;this.put(id,{busy:true});
  let bodySHA:string;try{bodySHA=await digest(JSON.stringify(binding.body));}catch(error){if(generation===this.generation)this.put(id,{busy:false,error:error as Error});return;}
  if(generation!==this.generation||!isCurrent()){if(generation===this.generation)this.put(id,{busy:false});return;}
  const packet:Packet={path,key:crypto.randomUUID(),binding:structuredClone(binding),bodySHA};this.put(id,{busy:true,packet});return this.send(token,id,packet,confirm,isCurrent,false);
 }
 async retry(token:string,id:string,confirm:Confirm,isCurrent:()=>boolean){const s=this.state(id);if(!s.packet||s.busy||s.unknown)return;return this.send(token,id,s.packet,confirm,isCurrent,true);}
 private async send(token:string,id:string,packet:Packet,confirm:Confirm,isCurrent:()=>boolean,replay:boolean):Promise<DecisionView|undefined>{
  const generation=this.generation;this.put(id,{busy:true,packet});
  const current=()=>generation===this.generation&&isCurrent();
  try{const raw=await this.transport(token,packet.path,packet.binding.body,packet.key);if(!current()){if(generation===this.generation)this.scrub();return;}
   const bound=await confirm(raw,packet);if(!current()){if(generation===this.generation)this.scrub();return;}this.put(id,{busy:false,confirmed:bound,confirmedId:bound.version.versionId});return bound;
  }catch(cause){if(!current()){if(generation===this.generation)this.scrub();return;}const error=cause instanceof Error?cause:new Error("材料整理请求失败。");
   if(error instanceof AdjudicationError&&(error.status===401||error.status===403)){this.scrub();const state=this.state(id);this.put(id,{...state,error});}
   else if(!replay&&error instanceof AdjudicationError&&(error.status===400||error.status===409)&&refusals.has(error.code))this.put(id,{busy:false,error});
   else this.put(id,{busy:false,packet,error});
  }finally{if(current()&&this.state(id).busy)this.put(id,{...this.state(id),busy:false});}
 }
}
