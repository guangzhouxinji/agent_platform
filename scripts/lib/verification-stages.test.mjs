import test from "node:test";
import assert from "node:assert/strict";
import { runVerificationStages, planHealthExitCode } from "./verification-stages.mjs";
const stages = [{args:["baseline"]},{args:["plan"],deferTestHealth:true},{args:["structure"]},{args:["domain"]}];
function execute(statuses) {
  const called=[],messages=[];
  const result=runVerificationStages(stages,args=>{called.push(args[0]);return statuses[args[0]]??0;},message=>messages.push(message));
  return {...result,called,messages};
}
test("all green visits every independent check",()=>{
  const result=execute({});assert.equal(result.exitCode,0);assert.deepEqual(result.called,["baseline","plan","structure","domain"]);
});
test("old test evidence red continues independent checks but never exits green",()=>{
  const result=execute({plan:2});assert.equal(result.exitCode,1);assert.deepEqual(result.called,["baseline","plan","structure","domain"]);assert.deepEqual(result.deferred,["plan"]);assert.equal(result.fatal,null);
});
test("baseline drift stops immediately even if it returns evidence-specific code",()=>{
  for(const code of [1,2]) {const result=execute({baseline:code});assert.equal(result.exitCode,1);assert.deepEqual(result.called,["baseline"]);}
});
test("unknown dependency or structural failure is not deferred",()=>{
  const result=execute({plan:1});assert.deepEqual(result.called,["baseline","plan"]);assert.equal(result.fatal,"plan");
});
test("deferred test evidence does not swallow a later failure",()=>{
  const result=execute({plan:2,structure:2});assert.equal(result.exitCode,1);assert.deepEqual(result.called,["baseline","plan","structure"]);assert.equal(result.fatal,"structure");
});
test("signal or missing process status fails closed",()=>{
  const result=runVerificationStages(stages,()=>null);assert.equal(result.exitCode,1);assert.equal(result.fatal,"baseline");
});
test("only a purely test-evidence error set is deferrable",()=>{
  const error=code=>({severity:"error",code});
  assert.equal(planHealthExitCode([]),0);
  assert.equal(planHealthExitCode([error("TESTS_FAILING")]),2);
  assert.equal(planHealthExitCode([error("TEST_EVIDENCE_STALE"),error("TESTS_FAILING")]),2);
  assert.equal(planHealthExitCode([error("TESTS_FAILING"),error("UNKNOWN_DEPENDENCY")]),1);
  assert.equal(planHealthExitCode([error("UNKNOWN_DEPENDENCY")]),1);
  assert.equal(planHealthExitCode([{severity:"warning",code:"TESTS_FAILING"}]),0);
});
