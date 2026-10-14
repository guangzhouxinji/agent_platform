import {chromium} from "playwright";
import {readFile,writeFile,mkdir,copyFile} from "node:fs/promises";
import {resolve} from "node:path";
import {spawn} from "node:child_process";
import assert from "node:assert/strict";
const req=name=>{const v=process.env[name];if(!v)throw new Error(name+" required; no skip");return v;};
const out=req("ENF_PENALTY_BROWSER_EVIDENCE");await mkdir(out,{recursive:false});
const baseline=JSON.parse(await readFile(req("ENF_PENALTY_BASELINE_RESULT"),"utf8"));
assert.equal(baseline.scope,"REAL_PRODUCT_ENF15_LIFECYCLE_CONTROLLED_INPUT_NOT_BUSINESS_ACCEPTANCE");
const id=baseline.ruleId,run=req("ENF_PENALTY_NEGATIVE_RUN_ID"),records=[];let revision=3;
async function provision(v){if(v===revision)return;const suffix=run+"-exact-grant-"+v,logs=[];
 const exit=await new Promise((accept,reject)=>{const child=spawn("mvn",["-q","-f",req("ENF_MAVEN_POM"),"-pl","apps/platform-api","-am","-Dtest=EnforcementPenaltyPositiveInputIT","-Dsurefire.failIfNoSpecifiedTests=false","-Dsurefire.reportNameSuffix="+suffix,"test"],{cwd:req("ENF_PENALTY_WORKSPACE"),env:{...process.env,ENF_PENALTY_GRANT_REVISION:String(v),ENF_PENALTY_SEED_INPUT:"false",ENF_PENALTY_PREP_EVIDENCE:resolve(out,"grant-"+v+".json")},shell:true});child.stdout.on("data",c=>logs.push(c.toString()));child.stderr.on("data",c=>logs.push(c.toString()));child.on("error",reject);child.on("close",accept);});
 await writeFile(resolve(out,suffix+".log"),logs.join(""));assert.equal(exit,0);
 const file=resolve(req("ENF_MAVEN_BUILD_ROOT"),"backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.security.product.EnforcementPenaltyPositiveInputIT-"+suffix+".xml");const suite=(await readFile(file,"utf8")).match(/<testsuite[^>]*>/)?.[0]??"";
 for(const [k,v]of Object.entries({tests:1,failures:0,errors:0,skipped:0}))assert.ok(suite.includes(k+'="'+v+'"'));await copyFile(file,resolve(out,suffix+".xml"));revision=v;}
const browser=await chromium.launch({headless:true});const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1100}});const page=await context.newPage();page.setDefaultTimeout(90000);let authorization;
page.on("request",r=>{if(new URL(r.url()).pathname.startsWith("/v2/enforcement/penalty"))authorization=r.headers().authorization||authorization;});
async function idle(){await page.locator('main.enf-next[aria-busy="false"]').waitFor();}
async function http(name,path,body,key,status){assert.ok(authorization);const result=await page.evaluate(async v=>{const r=await fetch(v.path,{method:"POST",headers:{Authorization:v.authorization,"Content-Type":"application/json","Idempotency-Key":v.key},body:JSON.stringify(v.body)});return {status:r.status,body:await r.json().catch(()=>null)};},{path,body,key,authorization});records.push({name,path,requestBody:body,key,expected:status,...result,at:new Date().toISOString(),scope:"REAL_HTTP_IN_PRODUCT_BROWSER_NOT_UI_CLICK"});await writeFile(resolve(out,"http-records.json"),JSON.stringify(records,null,2));assert.equal(result.status,status,name);return result;}
try {
 await page.goto("https://127.0.0.1:"+req("ENF_PENALTY_FRONTEND_PORT")+"/app/enforcement/penalty");await page.getByRole("button",{name:/^登\s*录$/}).click();await page.locator('input[name="username"]').fill(req("ENF_FOLLOWUP_LOGIN"));await page.locator('input[name="password"]').fill(req("ENF_FOLLOWUP_PASSWORD"));await page.locator('button[type="submit"]').click();await page.getByRole("heading",{name:"维护草稿"}).waitFor();
 await page.getByRole("button",{name:"刷新当前列表",exact:true}).click();await idle();await page.getByRole("row").filter({hasText:"[TEST ONLY] Symbolic lifecycle "+baseline.runId}).getByRole("button",{name:"打开规则",exact:true}).click();await idle();
 await provision(2);const root="/v2/enforcement/penalty/rules/"+encodeURIComponent(id);
 const replay=await http("DISABLE_EXACT_REPLAY",root+"/disable",{expectedRevision:2},baseline.disabled.requestId,200);assert.equal(replay.body.revision,3);
 await http("DISABLE_STALE_NEW_KEY",root+"/disable",{expectedRevision:2},crypto.randomUUID(),409);
 await provision(1);await http("OLD_PUBLISH_RECEIPT_AFTER_DISABLE_FAIL_CLOSED",root+"/publish",{expectedRevision:1},baseline.publication.requestId,503);
 await provision(3);await http("DISABLE_CHANGED_PAYLOAD_SAME_KEY",root+"/disable",{expectedRevision:3},baseline.disabled.requestId,409);
 await http("DISABLED_REPUBLISH",root+"/publish",{expectedRevision:3},crypto.randomUUID(),409);
 await http("DISABLED_DISABLE_AGAIN",root+"/disable",{expectedRevision:3},crypto.randomUUID(),409);
 await page.getByRole("button",{name:"刷新当前列表",exact:true}).click();await idle();await page.getByRole("row").filter({hasText:"[TEST ONLY] Symbolic lifecycle "+baseline.runId}).getByRole("button",{name:"打开规则",exact:true}).click();await idle();await page.getByText(id+" v3",{exact:true}).waitFor();await page.screenshot({path:resolve(out,"final-disabled-after-real-negatives.png"),fullPage:true});
 await writeFile(resolve(out,"result.json"),JSON.stringify({runId:run,ruleId:id,scope:"REAL_PRODUCT_ENF15_TRANSITION_NEGATIVES_NOT_FULL_IMP_ACCEPTANCE",baselineRunId:baseline.runId,records,businessAcceptance:"PENDING"},null,2));
}catch(e){await page.screenshot({path:resolve(out,"failed-page.png"),fullPage:true}).catch(()=>{});await writeFile(resolve(out,"failure.json"),JSON.stringify({runId:run,status:"FAILED",error:String(e)},null,2));throw e;}finally{await browser.close();}
