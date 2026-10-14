import {describe,it,expect} from "vitest";
import {assertEnforcementRunResponse,buildEnforcementAgentStart,emptyEnforcementProcedure,enforcementDraftResult,sameEnforcementManifest,type EnforcementManifest} from "./enforcementAgentStart";
const ref=(id:string,revision=2)=>({id,revision,sha256:"a".repeat(64)});
const manifest:EnforcementManifest={organization:"org",type:"FACT_RECORD",caseInput:ref("case",7),template:ref("template"),rule:ref("document-rule"),evidence:[ref("material")],legalReferences:[],conduct:ref("conduct"),penalty:null};
const fields={id:"check",ruleId:"procedure-rule",ruleVersion:"3",calendarRef:"calendar",calendarVersion:"approved-calendar-v2"};
describe("enforcement exact native start contract",()=>{
 it("does not supply any defaults",()=>{expect(Object.values(emptyEnforcementProcedure())).toEqual(["","","","",""]);expect(buildEnforcementAgentStart(manifest,"org",emptyEnforcementProcedure())).toBeUndefined();});
 it("retains the complete native manifest and binds readonly case coordinates",()=>{const input=buildEnforcementAgentStart(manifest,"org",fields)!;expect(input).toEqual({document:manifest,procedure:{...fields,caseRef:"case",caseVersion:"7",ruleVersion:3}});expect(input.document).not.toBe(manifest);expect(input.procedure.ruleId).not.toBe(manifest.rule.id);});
 it.each(Object.keys(fields) as Array<keyof typeof fields>)("requires explicit %s",field=>{expect(buildEnforcementAgentStart(manifest,"org",{...fields,[field]:""})).toBeUndefined();});
 it.each(["0","-1","1.5","1e3"," 2","2 ","02","9007199254740992"])("rejects invalid rule version %s",ruleVersion=>{expect(buildEnforcementAgentStart(manifest,"org",{...fields,ruleVersion})).toBeUndefined();});
 it("requires current organization and an actual selection",()=>{expect(buildEnforcementAgentStart(manifest,"other",fields)).toBeUndefined();expect(buildEnforcementAgentStart(undefined,"org",fields)).toBeUndefined();});
 it("rejects invalid source refs or incomplete native context",()=>{for(const broken of [{...manifest,caseInput:ref("case",0)},{...manifest,evidence:[]},{...manifest,template:{...ref("template"),sha256:"bad"}},{...manifest,type:"CONDUCT_REVIEW"},{...manifest,conduct:ref("conduct",-1)}])expect(buildEnforcementAgentStart(broken,"org",fields)).toBeUndefined();});
 it("rejects blank/control coordinates, without claiming source approval",()=>{for(const id of [" ","check\n"," check"])expect(buildEnforcementAgentStart(manifest,"org",{...fields,id})).toBeUndefined();});
 it("does not share mutable packet data",()=>{const input=buildEnforcementAgentStart(manifest,"org",fields)!;input.document.evidence[0].id="changed";expect(manifest.evidence[0].id).toBe("material");});
 it("uses only actual same-draft positive revision observations",()=>{const view=(...reference:string[])=>({checkpoint:{observations:reference.map(reference=>({reference}))}});expect(enforcementDraftResult(view("document-draft:d/3"),"d")?.revision).toBe(3);for(const ref of ["document-draft:other/3","document-draft:d/0","document-draft:d/3/extra","evidence:d/3"])expect(enforcementDraftResult(view(ref),"d")).toBeUndefined();expect(enforcementDraftResult(view("document-draft:d/2","document-draft:d/4"),"d")?.revision).toBe(4);});
 it("compares all source coordinates, not just case ID",()=>{expect(sameEnforcementManifest(manifest,structuredClone(manifest))).toBe(true);expect(sameEnforcementManifest(manifest,{...manifest,caseInput:ref("case",8)})).toBe(false);expect(sameEnforcementManifest(manifest,{...manifest,conduct:null})).toBe(false);expect(sameEnforcementManifest(manifest,{...manifest,rule:ref("another")})).toBe(false);});
});

describe("enforcement response Run binding",()=>{
 const view=(runId="run",id="draft")=>({checkpoint:{runId,task:{id}}});
 it.each(["/run","/run/advance","/run/execute"])("accepts the exact requested task/Run for %s",suffix=>{expect(()=>assertEnforcementRunResponse(view(),"draft",suffix)).not.toThrow();});
 it("compares the decoded first suffix segment, not the operation name",()=>{expect(()=>assertEnforcementRunResponse(view("run/a"),"draft","/run%2Fa/execute")).not.toThrow();});
 it.each(["/run","/run/advance","/run/execute"])("rejects same-task wrong Run as 409 for %s",suffix=>{try{assertEnforcementRunResponse(view("another-run"),"draft",suffix);expect.fail("wrong Run accepted");}catch(cause){expect((cause as {status:number}).status).toBe(409);}});
 it("checks task ID for creation and read without inventing creation Run ID",()=>{expect(()=>assertEnforcementRunResponse(view("created-run"),"draft","")).not.toThrow();expect(()=>assertEnforcementRunResponse(view("run","other-draft"),"draft","")).toThrow();expect(()=>assertEnforcementRunResponse(view("run","other-draft"),"draft","/run")).toThrow();});
 it.each(["/","/%zz/execute"])("fails closed on an invalid suffix %s",suffix=>{expect(()=>assertEnforcementRunResponse(view(),"draft",suffix)).toThrow();});
 it("requires a real checkpoint/task/Run",()=>{for(const response of [null,{},view(""),{checkpoint:{runId:"run"}}])expect(()=>assertEnforcementRunResponse(response,"draft","")).toThrow();});
});
