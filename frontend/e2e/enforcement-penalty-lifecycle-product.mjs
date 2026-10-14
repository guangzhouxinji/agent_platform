import { chromium } from "playwright";
import { readFile, writeFile, mkdir, copyFile } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import assert from "node:assert/strict";
const req = name => { const value=process.env[name];if(!value)throw new Error(name+" required; no skip");return value; };
const out=req("ENF_PENALTY_BROWSER_EVIDENCE");await mkdir(out,{recursive:false});
const prepared=JSON.parse(await readFile(req("ENF_PENALTY_PREP_EVIDENCE"),"utf8"));
const id=prepared.ruleId,run=req("ENF_PENALTY_RUN_ID"),org=req("ENF_ACCEPTANCE_ORGANIZATION");
let grantVersion=1;
// ONLY provisions exact technical grants; all routes continue to real HTTPS. No response mocking.
async function provision(revision) {
  if(revision===grantVersion)return;
  const suffix=run+"-grant-"+revision,output=resolve(out,"grant-"+revision+".json"),logs=[];
  const env={...process.env,ENF_PENALTY_GRANT_REVISION:String(revision),ENF_PENALTY_PREP_EVIDENCE:output,ENF_PENALTY_SEED_INPUT:"false"};
  const exit=await new Promise((accept,reject)=>{const child=spawn("mvn",["-q","-f",req("ENF_MAVEN_POM"),"-pl","apps/platform-api","-am","-Dtest=EnforcementPenaltyPositiveInputIT","-Dsurefire.failIfNoSpecifiedTests=false","-Dsurefire.reportNameSuffix="+suffix,"test"],{cwd:req("ENF_PENALTY_WORKSPACE"),env,shell:true});child.stdout.on("data",c=>logs.push(c.toString()));child.stderr.on("data",c=>logs.push(c.toString()));child.on("error",reject);child.on("close",accept);});
  await writeFile(resolve(out,suffix+".log"),logs.join(""));assert.equal(exit,0,"Real grant preparation must succeed");
  const report=resolve(req("ENF_MAVEN_BUILD_ROOT"),"backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.security.product.EnforcementPenaltyPositiveInputIT-"+suffix+".xml");
  const suite=(await readFile(report,"utf8")).match(/<testsuite[^>]*>/)?.[0]??"";
  for(const [name,value] of Object.entries({tests:1,failures:0,errors:0,skipped:0}))assert.ok(suite.includes(name+'="'+value+'"'));
  await copyFile(report,resolve(out,suffix+".xml"));grantVersion=revision;
}
const browser=await chromium.launch({headless:true});const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1100}});const page=await context.newPage();page.setDefaultTimeout(90000);
const records=[],pending=new Set();let tail=Promise.resolve(),authorization,saveCommand;
page.on("request",r=>{if(new URL(r.url()).pathname.startsWith("/v2/enforcement/penalty")){authorization=r.headers().authorization||authorization;if(r.method()==="POST"&&new URL(r.url()).pathname.endsWith("/rules"))saveCommand={key:r.headers()["idempotency-key"],body:r.postDataJSON()};}});
page.on("response",r=>{const path=new URL(r.url()).pathname;if(!path.startsWith("/v2/enforcement/penalty"))return;const work=(async()=>{const value={path,method:r.request().method(),status:r.status(),at:new Date().toISOString()};try{value.body=await r.json();}catch{}records.push(value);tail=tail.then(()=>writeFile(resolve(out,"http-records.json"),JSON.stringify(records,null,2)));await tail;})();pending.add(work);work.finally(()=>pending.delete(work));});
await page.route("**/v2/enforcement/penalty/rules/*?revision=*",async route=>{const url=new URL(route.request().url());assert.equal(url.pathname.split("/").at(-1),id);await provision(Number(url.searchParams.get("revision")));await route.continue();});
async function idle(){await page.locator('main.enf-next[aria-busy="false"]').waitFor();}
async function click(name,path,status){await idle();const response=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith(path)&&r.request().method()==="POST");const [r]=await Promise.all([response,page.getByRole("button",{name}).click()]);assert.equal(r.status(),status);await idle();return r.json();}
try {
  await page.goto("https://127.0.0.1:"+req("ENF_PENALTY_FRONTEND_PORT")+"/app/enforcement/penalty");await page.getByRole("button",{name:/^登\s*录$/}).click();
  await page.locator('input[name="username"]').fill(req("ENF_FOLLOWUP_LOGIN"));await page.locator('input[name="password"]').fill(req("ENF_FOLLOWUP_PASSWORD"));await page.locator('button[type="submit"]').click();await page.getByRole("heading",{name:"维护草稿"}).waitFor();
  const ref=prepared.legalReference,amount=value=>({B:{value,confirmed:true,sourceRef:ref.sourceId,sourceVersion:"1",locator:"symbolic-input.txt; explicitly simulated technical parameter"}});
  const calculation={organization:org,procedureApplicable:true,basisRef:ref.referenceId,basisRevision:1,formula:{type:"BINARY",operator:"MULTIPLY",left:{type:"INPUT",name:"B"},right:{type:"CONSTANT",value:2,basisRef:ref.referenceId}},semantics:{kind:"PROJECT_SCORE",currency:"NONE",unit:"POINT",scale:2,rounding:"HALF_UP"},range:{minimum:0,maximum:100,legalBasisRef:ref.referenceId,legalBasisRevision:1},boundaries:[{name:"LOWER",inputs:amount(0),expected:0},{name:"UPPER",inputs:amount(50),expected:100}],overlaps:[]};
  const scope={conductType:"PROJECT_SCORE_INPUT_NOT_LEGAL",authorityRef:"PROJECT-TECHNICAL-VALIDATION",procedure:"PROJECT_TEST",region:"PROJECT_TEST",effectiveFrom:"2026-10-01",effectiveUntil:"2026-10-02"};
  await page.getByLabel("规则编号",{exact:true}).fill(id);await page.getByLabel("规则名称",{exact:true}).fill("[TEST ONLY] Symbolic lifecycle "+run);
  await page.getByLabel("行为、职权、程序、地区、有效期（类型化 JSON）",{exact:true}).fill(JSON.stringify(scope));await page.getByLabel("裁量因素及从轻/减轻/不罚条件和证据（JSON 数组）",{exact:true}).fill("[]");await page.getByLabel("依据版本、类型化公式、金额口径、法定范围、边界样例及优先关系（JSON）",{exact:true}).fill(JSON.stringify(calculation));
  const save=await click("保存草稿版本","/penalty/rules",200);assert.equal(save.revision,1);await page.getByText(id+" v1",{exact:true}).waitFor();await page.screenshot({path:resolve(out,"01-draft.png"),fullPage:true});
  const validation=await click("发布前核验","/validate",200);assert.equal(validation.publishable,true);assert.deepEqual(validation.gaps,[]);assert.equal(validation.trials.length,2);
  const publication=await click(/^发\s*布$/,"/publish",200);assert.equal(publication.revision,2);await page.getByText(id+" v2",{exact:true}).waitFor();await page.screenshot({path:resolve(out,"02-active.png"),fullPage:true});
  const disabled=await click(/^停\s*用$/,"/disable",200);assert.equal(disabled.revision,3);await page.getByText(id+" v3",{exact:true}).waitFor();await page.screenshot({path:resolve(out,"03-disabled-history.png"),fullPage:true});
  assert.equal(await page.getByRole("button",{name:/^发\s*布$/}).isEnabled(),false);assert.equal(await page.getByRole("button",{name:/^停\s*用$/}).isEnabled(),false);
  async function http(path,body,key=crypto.randomUUID()){return page.evaluate(async input=>{const response=await fetch(input.path,{method:"POST",headers:{Authorization:input.authorization,"Content-Type":"application/json","Idempotency-Key":input.key},body:JSON.stringify(input.body)});return {status:response.status,body:await response.json().catch(()=>null)};},{path,body,key,authorization});}
  const disabledRepublish=await http("/v2/enforcement/penalty/rules/"+encodeURIComponent(id)+"/publish",{expectedRevision:3});assert.equal(disabledRepublish.status,409);
  const disabledDisable=await http("/v2/enforcement/penalty/rules/"+encodeURIComponent(id)+"/disable",{expectedRevision:3});assert.equal(disabledDisable.status,409);
  await provision(1);const apiChecks=[];
  for(const [name,key,body,status] of [["SAVE_REPLAY",saveCommand.key,saveCommand.body,200],["SAVE_CHANGED_SAME_KEY",saveCommand.key,{...saveCommand.body,rule:{...saveCommand.body.rule,name:"different"}},409],["STALE_SAVE_NEW_KEY",crypto.randomUUID(),saveCommand.body,409]]){const result=await http("/v2/enforcement/penalty/rules",body,key);assert.equal(result.status,status);apiChecks.push({name,...result,scope:"REAL_HTTP_NOT_UI_CLICK"});}
  await provision(3);await page.getByRole("button",{name:"刷新当前列表",exact:true}).click();await idle();await page.screenshot({path:resolve(out,"04-final-persisted-list.png"),fullPage:true});
  await Promise.all([...pending]);await tail;
  await writeFile(resolve(out,"result.json"),JSON.stringify({runId:run,scope:"REAL_PRODUCT_ENF15_LIFECYCLE_CONTROLLED_INPUT_NOT_BUSINESS_ACCEPTANCE",ruleId:id,states:["DRAFT","ACTIVE","DISABLED"],save,validation,publication,disabled,disabledRepublish,disabledDisable,apiChecks,businessAcceptance:"PENDING"},null,2));
} catch(error){await page.screenshot({path:resolve(out,"failed-page.png"),fullPage:true}).catch(()=>{});await writeFile(resolve(out,"failure.json"),JSON.stringify({runId:run,error:String(error),status:"FAILED",url:page.url()},null,2));throw error;} finally {await Promise.all([...pending]);await browser.close();}
