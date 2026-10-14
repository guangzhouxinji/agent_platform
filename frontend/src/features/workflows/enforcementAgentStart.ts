/** Enforcement-only native input contract. Authority/source validation remains server-owned. */
export type EnforcementObjectVersion = {id:string;revision:number;sha256:string};
export type EnforcementManifest = {
  organization:string;type:string;caseInput:EnforcementObjectVersion;template:EnforcementObjectVersion;
  rule:EnforcementObjectVersion;evidence:EnforcementObjectVersion[];legalReferences:EnforcementObjectVersion[];
  conduct?:EnforcementObjectVersion|null;penalty?:EnforcementObjectVersion|null;
};
export type EnforcementProcedureFields = {id:string;ruleId:string;ruleVersion:string;calendarRef:string;calendarVersion:string};
export type EnforcementCheckCommand = {id:string;caseRef:string;caseVersion:string;ruleId:string;ruleVersion:number;calendarRef:string;calendarVersion:string};
export const emptyEnforcementProcedure = ():EnforcementProcedureFields => ({id:"",ruleId:"",ruleVersion:"",calendarRef:"",calendarVersion:""});
const exactText = (value:string) => !!value && value===value.trim() && !/[\u0000-\u001f\u007f]/.test(value);
const positiveVersion = (value:number) => Number.isSafeInteger(value) && value>0;
const exactRef = (ref:EnforcementObjectVersion|null|undefined) => !!ref && exactText(ref.id) && positiveVersion(ref.revision) && /^[0-9a-f]{64}$/.test(ref.sha256);
export function buildEnforcementAgentStart(manifest:EnforcementManifest|undefined,organization:string,fields:EnforcementProcedureFields) {
  if(!manifest || manifest.organization!==organization || !exactText(organization)
    || !["FACT_RECORD","CONDUCT_REVIEW","PENALTY_INPUT_REVIEW"].includes(manifest.type)
    || ![manifest.caseInput,manifest.template,manifest.rule].every(exactRef)
    || !manifest.evidence?.length || !manifest.evidence.every(exactRef)
    || !Array.isArray(manifest.legalReferences) || !manifest.legalReferences.every(exactRef)
    || (manifest.type!=="FACT_RECORD" && !manifest.legalReferences.length)
    || (manifest.conduct!=null && !exactRef(manifest.conduct)) || (manifest.penalty!=null && !exactRef(manifest.penalty))) return undefined;
  if(![fields.id,fields.ruleId,fields.calendarRef,fields.calendarVersion].every(exactText)
    || !/^[1-9]\d*$/.test(fields.ruleVersion) || !positiveVersion(Number(fields.ruleVersion))) return undefined;
  const procedure:EnforcementCheckCommand = {id:fields.id,caseRef:manifest.caseInput.id,
    caseVersion:String(manifest.caseInput.revision),ruleId:fields.ruleId,ruleVersion:Number(fields.ruleVersion),
    calendarRef:fields.calendarRef,calendarVersion:fields.calendarVersion};
  // Retain every native manifest field, including optional conduct/penalty bindings; no reconstruction/defaults.
  return {document:structuredClone(manifest),procedure};
}
export function enforcementDraftResult(view:{checkpoint:{observations:Array<{reference:string}>}},draftId:string) {
  const prefix="document-draft:"+draftId+"/";
  const references=view.checkpoint.observations.map(o=>o.reference).filter(ref=>ref.startsWith(prefix));
  const last=references.at(-1);if(!last)return undefined;
  const version=last.slice(prefix.length);
  return /^[1-9]\d*$/.test(version)&&positiveVersion(Number(version))?{id:draftId,revision:Number(version),reference:last}:undefined;
}
export function sameEnforcementManifest(left:EnforcementManifest,right:EnforcementManifest) {
  const ref=(value:EnforcementObjectVersion|null|undefined)=>value?[value.id,value.revision,value.sha256]:null;
  const binding=(m:EnforcementManifest)=>JSON.stringify([m.organization,m.type,ref(m.caseInput),ref(m.template),ref(m.rule),
    m.evidence.map(ref),m.legalReferences.map(ref),ref(m.conduct),ref(m.penalty)]);
  return binding(left)===binding(right);
}

/** A same-task response from another Run is a conflict, not evidence that the session lost authority. */
export function assertEnforcementRunResponse(response:unknown,draftId:string,suffix:string):void {
  const conflict=()=>Object.assign(new Error("运行响应与当前文书任务或运行编号不一致，未显示结果"),{status:409});
  let expectedRun:string|undefined;
  if(suffix){
    try{expectedRun=decodeURIComponent(suffix.split("/")[1]??"");}catch{throw conflict();}
    if(!expectedRun)throw conflict();
  }
  const view=response as {checkpoint?:{runId?:unknown;task?:{id?:unknown}}}|null;
  if(!view?.checkpoint || view.checkpoint.task?.id!==draftId
    || typeof view.checkpoint.runId!=="string" || !exactText(view.checkpoint.runId)
    || (expectedRun!==undefined&&view.checkpoint.runId!==expectedRun))throw conflict();
}
