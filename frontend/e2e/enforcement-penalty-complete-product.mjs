import { chromium } from "playwright";
import { readFile, writeFile, mkdir, copyFile, readdir } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";
const req=n=>{if(!process.env[n])throw new Error(n+" required; no skip");return process.env[n];};
const out=req("ENF_PENALTY_BROWSER_EVIDENCE"),run=req("ENF_PENALTY_RUN_ID"),org=req("ENF_ACCEPTANCE_ORGANIZATION");await mkdir(out,{recursive:false});
const prepared=JSON.parse(await readFile(req("ENF_PENALTY_PREP_EVIDENCE"),"utf8"));
const source=JSON.parse(await readFile(req("ENF_PENALTY_CASE_PREP_EVIDENCE"),"utf8"));
const first="ENF15-COMPLETE-"+run,second="ENF15-SECOND-"+run,negative="ENF15-NEGATIVE-"+run,suggestionId="ENF16-POSITIVE-"+run;
const grants=new Map();let count=0;
async function provision(scopes){
 const needed=scopes.filter(s=>grants.get([s.type,s.id,s.action,s.purpose].join("|"))!==s.revision);if(!needed.length)return;
 const suffix=run+"-scopes-"+(++count),file=resolve(out,suffix+".json");await writeFile(file,JSON.stringify(needed));
 const env={...process.env,ENF_PENALTY_SCENARIO_SCOPES:file,ENF_PENALTY_SCENARIO_GRANT_EVIDENCE:resolve(out,suffix+"-result.json")};
 const logs=[];const exit=await new Promise((accept,reject)=>{const child=spawn("mvn",["-q","-f",req("ENF_MAVEN_POM"),"-pl","apps/platform-api","-am","-Dtest=EnforcementPenaltyScenarioGrantsIT","-Dsurefire.failIfNoSpecifiedTests=false","-Dsurefire.reportNameSuffix="+suffix,"test"],{cwd:req("ENF_PENALTY_WORKSPACE"),env,shell:true});child.stdout.on("data",c=>logs.push(String(c)));child.stderr.on("data",c=>logs.push(String(c)));child.on("error",reject);child.on("close",accept);});
 await writeFile(resolve(out,suffix+".log"),logs.join(""));assert.equal(exit,0,"Exact technical grant preparation failed, not domain I");
 const xml=resolve(req("ENF_MAVEN_BUILD_ROOT"),"backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.security.product.EnforcementPenaltyScenarioGrantsIT-"+suffix+".xml");
 const header=(await readFile(xml,"utf8")).match(/<testsuite[^>]*>/)?.[0]??"";for(const [k,v] of Object.entries({tests:1,failures:0,errors:0,skipped:0}))assert.ok(header.includes(k+'="'+v+'"'));
 await copyFile(xml,resolve(out,suffix+".xml"));for(const s of needed)grants.set([s.type,s.id,s.action,s.purpose].join("|"),s.revision);
}
const scope=(type,id,revision,action,purpose="PENALTY_ANALYSIS")=>({type,id,revision,action,purpose});
const ruleGrants=(id,r)=>["READ","EDIT","TRIAL","PUBLISH","DISABLE"].map(a=>scope("PENALTY_RULE",id,r,a));
const suggestionGrants=(r,action)=>[scope("PENALTY_SUGGESTION",suggestionId,r,action)];
await provision([scope("CASE",source.input.caseId,1,"READ","ANALYZE"),...source.input.dependencies.map(d=>scope({EVIDENCE:"EVIDENCE_ORGANIZATION",CONDUCT:"CONDUCT_ANALYSIS",PROCEDURE:"PROCEDURE_REPORT"}[d.kind],d.id,d.revision,d.kind==="PROCEDURE"?"READ_REPORT":"READ",d.kind==="PROCEDURE"?"PROCEDURE_CHECK":"DISPLAY")),...ruleGrants(first,1)]);
const browser=await chromium.launch({headless:true}),context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1100}}),page=await context.newPage();page.setDefaultTimeout(90000);
const records=[],checks=[],pending=new Set(),mutations=[];let tail=Promise.resolve();
page.on("response",r=>{const path=new URL(r.url()).pathname;if(!path.startsWith("/v2/enforcement/penalty"))return;const work=(async()=>{const request=r.request(),value={path,status:r.status(),method:request.method(),at:new Date().toISOString()};if(request.method()==="POST"){value.key=request.headers()["idempotency-key"];value.input=request.postDataJSON();}try{value.body=await r.json();}catch{}records.push(value);tail=tail.then(()=>writeFile(resolve(out,"http-records.json"),JSON.stringify(records,null,2)));await tail;})();pending.add(work);work.finally(()=>pending.delete(work));});
// Permission setup only. Every response remains the actual final HTTPS product response.
await page.route("**/v2/enforcement/penalty/*/*?revision=*",async route=>{const u=new URL(route.request().url()),parts=u.pathname.split("/");const type=parts.at(-2)==="rules"?"PENALTY_RULE":"PENALTY_SUGGESTION";await provision([scope(type,decodeURIComponent(parts.at(-1)),Number(u.searchParams.get("revision")),"READ")]);await route.continue();});
async function idle(){await page.locator('main.enf-next[aria-busy="false"]').waitFor();}
async function click(name,path,status=200){await idle();const wait=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith(path)&&r.request().method()==="POST");const [r]=await Promise.all([wait,page.getByRole("button",{name,exact:typeof name==="string"}).click()]);const body=await r.json().catch(()=>null);assert.equal(r.status(),status,JSON.stringify(body));await idle();if(status===200&&body.resourceKind)mutations.push(body);return body;}
async function files(){const root=req("ENF_PENALTY_PRODUCT_FILE_ROOT");const list=await readdir(root);return Promise.all(list.sort().map(async name=>({name,sha:createHash("sha256").update(await readFile(resolve(root,name))).digest("hex")})));}
const ref=prepared.legalReference;
const scopeInput={conductType:"PROJECT_SCORE_INPUT_NOT_LEGAL",authorityRef:"PROJECT-TECHNICAL-VALIDATION",procedure:"PROJECT_TEST",region:"PROJECT_TEST",effectiveFrom:"2026-10-01",effectiveUntil:"2026-12-31"};
const amount=value=>({B:{value,confirmed:true,sourceRef:ref.sourceId,sourceVersion:"1",locator:"symbolic-input.txt; project-authored technical parameter"}});
const calculation=factor=>({organization:org,procedureApplicable:true,basisRef:ref.referenceId,basisRevision:1,formula:{type:"BINARY",operator:"MULTIPLY",left:{type:"INPUT",name:"B"},right:{type:"CONSTANT",value:factor,basisRef:ref.referenceId}},semantics:{kind:"PROJECT_SCORE",currency:"NONE",unit:"POINT",scale:2,rounding:"HALF_UP"},range:{minimum:0,maximum:100,legalBasisRef:ref.referenceId,legalBasisRevision:1},boundaries:[{name:"LOWER",inputs:amount(0),expected:0},{name:"UPPER",inputs:amount(100/factor),expected:100}],overlaps:[]});
async function saveRule(id,expected,c,name,status=200){await provision(ruleGrants(id,expected+1));await page.getByLabel("规则编号",{exact:true}).fill(id);await page.getByLabel("规则名称",{exact:true}).fill(name);await page.locator('#penalty-rule_expectedRevision').fill(String(expected));await page.getByLabel("行为、职权、程序、地区、有效期（类型化 JSON）",{exact:true}).fill(JSON.stringify(scopeInput));await page.getByLabel("裁量因素及从轻/减轻/不罚条件和证据（JSON 数组）",{exact:true}).fill("[]");await page.getByLabel("依据版本、类型化公式、金额口径、法定范围、边界样例及优先关系（JSON）",{exact:true}).fill(JSON.stringify(c));return click("保存草稿版本","/penalty/rules",status);}
async function shot(name){await page.screenshot({path:resolve(out,name+".png"),fullPage:true});}
async function login(){
 await page.goto("https://127.0.0.1:"+req("ENF_PENALTY_FRONTEND_PORT")+"/app/enforcement/penalty");await page.getByRole("button",{name:/^登\s*录$/}).click();
 await Promise.race([page.locator('input[name="username"]').waitFor(),page.getByRole("heading",{name:"维护草稿"}).waitFor()]);
 if(await page.locator('input[name="username"]').isVisible()){await page.locator('input[name="username"]').fill(req("ENF_FOLLOWUP_LOGIN"));await page.locator('input[name="password"]').fill(req("ENF_FOLLOWUP_PASSWORD"));await page.locator('button[type="submit"]').click();}
 await page.getByRole("heading",{name:"维护草稿"}).waitFor();checks.push({name:"REAL_OAUTH_PKCE_RELOGIN",at:new Date().toISOString(),status:200});
}
try {
 await login();
 await saveRule(first,0,calculation(2),"[TEST ONLY] Complete lifecycle "+run);await click("发布前核验","/validate");await click(/^发\s*布$/,"/publish");await shot("01-published");await provision(ruleGrants(first,2));await click(/^停\s*用$/,"/disable");await shot("02-disabled");await saveRule(first,3,calculation(2),"[TEST ONLY] Complete lifecycle "+run);await click(/^发\s*布$/,"/publish");await shot("03-republished-history");
 await saveRule(second,0,calculation(2),"[TEST ONLY] Parallel option "+run);await click(/^发\s*布$/,"/publish");await shot("04-second-rule");
 const negatives=[
  ["AMOUNT_SEMANTICS_REQUIRED",c=>{c.semantics=null;}],
  ["STATUTORY_RANGE_REQUIRED",c=>{c.range=null;}],
  ["TYPED_FORMULA_REQUIRED",c=>{c.formula=null;}],
  ["BOUNDARY_TRIAL_REQUIRED",c=>{c.boundaries=[];}],
  ["STATUTORY_BOUNDARIES_NOT_VERIFIED",c=>{c.boundaries.pop();}],
  ["BOUNDARY_TRIAL_MISMATCH",c=>{c.boundaries[1].expected=99;}],
  ["PROCEDURE_NOT_APPLICABLE",c=>{c.procedureApplicable=false;}],
  ["OVERLAP_PRIORITY_REQUIRED",c=>{c.overlaps=[{ruleRef:first,revision:5,priorityRelationRef:""}];}],
  ["STATUTORY_BASIS_MISMATCH",c=>{c.range.legalBasisRef="NOT-THE-BASIS";}]
 ];
 await login();let n=0;for(const [name,mutate] of negatives){if(n===5)await login();const c=calculation(2);mutate(c);await saveRule(negative,n++,c,"[TEST ONLY] Publication negatives "+run);const before=await files(),result=await click(/^发\s*布$/,"/publish",409);assert.ok(result.gaps.includes(name),JSON.stringify(result));assert.deepEqual(await files(),before,"Rejected publication added/changed original");checks.push({name,status:409,gaps:result.gaps,originalsUnchanged:true});await shot("negative-"+name);}
 const untyped=calculation(2);untyped.formula={type:"SCRIPT",script:"return B * 2"};const beforeScript=await files();await saveRule(negative,n,untyped,"[TEST ONLY] Publication negatives "+run,400);assert.deepEqual(await files(),beforeScript);checks.push({name:"SCRIPT_EXPRESSION_REJECTED",status:400,originalsUnchanged:true});await shot("negative-script-rejected");
 await login();
 await page.getByRole("tab",{name:"ENF-16 建议"}).click();
 const ruleRefs=[{kind:"PENALTY_RULE",id:first,revision:5,sha256:null},{kind:"PENALTY_RULE",id:second,revision:2,sha256:null}];
 async function generate(expected,input=source.input,status=200){await provision(suggestionGrants(expected+1,expected?"RERUN":"CREATE"));await page.getByLabel("建议编号",{exact:true}).fill(suggestionId);await page.locator('#penalty-suggestion_expectedRevision').fill(String(expected));await page.getByLabel("案件、具名事实、因素、金额来源及上游确切版本/SHA（JSON）",{exact:true}).fill(JSON.stringify(input));await page.getByLabel("ACTIVE 候选规则确切版本（JSON 数组）",{exact:true}).fill(JSON.stringify(ruleRefs));return click("生成 / 重跑建议","/penalty/suggestions",status);}
 await generate(0);await shot("05-generated-parallel");await Promise.all([...pending]);const generated=records.findLast(r=>r.path.endsWith("/"+suggestionId)&&r.method==="GET")?.body;assert.equal(generated?.state,"PENDING_REVIEW",JSON.stringify(generated));assert.equal(generated.options.length,2);assert.deepEqual(generated.options.map(o=>o.calculation.amount),[24,24]);
 await provision(suggestionGrants(1,"ADJUST"));await page.getByLabel("调整方案",{exact:true}).click();await page.getByTitle(first,{exact:true}).click();await page.getByLabel("调整金额（按该方案口径）",{exact:true}).fill("30");await page.getByLabel("调整理由",{exact:true}).fill("TECHNICAL_TEST_ADJUSTMENT_NOT_BUSINESS_DECISION");await page.getByLabel("调整依据",{exact:true}).fill("PROJECT_SCORE_TEST_RANGE");await click("保存人工修订","/adjust");await shot("06-adjusted");
 await provision(suggestionGrants(2,"REVIEW"));await page.getByLabel("具名复核结果",{exact:true}).click();await page.getByText("确认建议（非处罚决定）",{exact:true}).click();await page.getByLabel("复核理由",{exact:true}).fill("TECHNICAL_IDENTITY_REVIEW_NOT_FORMAL_BUSINESS_ACCEPTANCE");await click("提交复核","/review");await shot("07-reviewed");await generate(3);await shot("08-rerun");
 await Promise.all([...pending]);const rerun=records.findLast(r=>r.path.endsWith("/"+suggestionId)&&r.method==="GET")?.body;assert.equal(rerun.state,"PENDING_REVIEW");assert.equal(rerun.reviewedBy,null);assert.ok(rerun.options.every(o=>o.adjustedAmount==null));
 const badDigest=structuredClone(source.input);badDigest.dependencies[0].sha256="0".repeat(64);const beforeDigest=await files();await generate(4,badDigest,409);assert.deepEqual(await files(),beforeDigest);checks.push({name:"DEPENDENCY_SHA_REJECTED",status:409,originalsUnchanged:true});await shot("negative-dependency-sha");
 const unconfirmed=structuredClone(source.input);unconfirmed.confirmedFacts=[];await generate(4,unconfirmed);await Promise.all([...pending]);const missing=records.findLast(r=>r.path.endsWith("/"+suggestionId)&&r.method==="GET")?.body;assert.equal(missing.state,"WAITING_INPUT");assert.deepEqual(missing.options,[]);assert.ok(missing.gaps.includes("FACTS_NOT_CONFIRMED"));assert.equal(await page.getByRole("button",{name:"提交复核",exact:true}).isEnabled(),false);checks.push({name:"UNCONFIRMED_FACTS_WAITING_INPUT",status:200,revision:5,gaps:missing.gaps});await shot("negative-unconfirmed-facts");
 await login();await page.getByRole("tab",{name:"ENF-16 建议"}).click();
 await generate(5);await provision(suggestionGrants(6,"ADJUST"));await page.getByLabel("调整方案",{exact:true}).click();await page.getByTitle(first,{exact:true}).click();await page.getByLabel("调整金额（按该方案口径）",{exact:true}).fill("101");await page.getByLabel("调整理由",{exact:true}).fill("TECHNICAL_TEST_OUTSIDE_RANGE_CANDIDATE");await page.getByLabel("调整依据",{exact:true}).fill("PROJECT_TEST_BOUNDARY");await click("保存人工修订","/adjust");
 await provision(suggestionGrants(7,"REVIEW"));await page.getByLabel("具名复核结果",{exact:true}).click();await page.getByText("确认建议（非处罚决定）",{exact:true}).click();await page.getByLabel("复核理由",{exact:true}).fill("TECHNICAL_TEST_MUST_REJECT_OUTSIDE_RANGE");const beforeReview=await files();await click("提交复核","/review",409);assert.deepEqual(await files(),beforeReview);checks.push({name:"OUTSIDE_RANGE_REVIEW_REJECTED",status:409,originalsUnchanged:true});await shot("negative-outside-range-review");
 await provision(suggestionGrants(7,"ADJUST"));await page.getByLabel("调整方案",{exact:true}).click();await page.getByTitle(first,{exact:true}).click();await page.getByLabel("调整金额（按该方案口径）",{exact:true}).fill("30");await page.getByLabel("调整理由",{exact:true}).fill("TECHNICAL_TEST_CORRECTED_CANDIDATE");await page.getByLabel("调整依据",{exact:true}).fill("PROJECT_TEST_CORRECTED_RANGE");await click("保存人工修订","/adjust");await provision(suggestionGrants(8,"REVIEW"));await page.getByLabel("具名复核结果",{exact:true}).click();await page.getByText("退回",{exact:true}).click();await page.getByLabel("复核理由",{exact:true}).fill("TECHNICAL_TEST_RETURN_NOT_BUSINESS_REVIEW");await click("提交复核","/review");await shot("09-returned");await generate(9);await shot("10-rerun-after-return");
 await Promise.all([...pending]);await tail;await writeFile(resolve(out,"result.json"),JSON.stringify({runId:run,scope:"REAL_ENF15_ENF16_PRODUCT_CONTROLLED_INPUT_NOT_BUSINESS_ACCEPTANCE",ruleIds:[first,second,negative],suggestionId,mutations,checks,generated,rerun,originals:await files(),businessAcceptance:"PENDING"},null,2));
}catch(error){await shot("failed-page").catch(()=>{});await writeFile(resolve(out,"failure.json"),JSON.stringify({runId:run,status:"FAILED",error:String(error),url:page.url(),mutations,checks},null,2));throw error;}finally{await Promise.all([...pending]);await browser.close();}
