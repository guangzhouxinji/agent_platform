import { chromium } from "playwright";
import { mkdir,readFile,writeFile,copyFile,readdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
const runId=process.env.ENF_DOCUMENT_RULE_BROWSER_RUN;
if(!/^[A-Za-z0-9_-]+$/.test(runId??""))throw new Error("Fresh run ID required");
const root=resolve("..");const out=resolve(root,"docs/evidence/enforcement-followup-final-20260930",runId);
await mkdir(out,{recursive:false});const records=[];
const ruleId=process.env.ENF_DOCUMENT_RULE_ID;const grants=new Map();
const scenario=process.env.ENF_DOCUMENT_RULE_SCENARIO??"lifecycle";
if(!["lifecycle","recovery_seed","unknown_path","wrong_type","missing_party","missing_date","missing_material","no_interview","unconfirmed_amount","unconfirmed_legal","dependency","unreadable_source"].includes(scenario))throw new Error("Known frozen scenario required");
const coordinates=rows=>rows.map(r=>[r.REQUEST_KEY,r.OPERATION,r.REVISION]);
async function filesAt(directory){const files=[];for(const e of await readdir(directory,{withFileTypes:true})){const p=resolve(directory,e.name);if(e.isDirectory()&&e.name==="document-rule-attempt")continue;else if(e.isDirectory())files.push(...await filesAt(p));else if(e.isFile())files.push({path:p,sha256:createHash("sha256").update(await readFile(p)).digest("hex")});else throw new Error("Unexpected file root entry");}return files.sort((a,b)=>a.path.localeCompare(b.path));}
const originalFiles=scenario!=="lifecycle"?await filesAt(process.env.ENF_DOCUMENT_RULE_TEMPLATE_ROOT):null;
async function provision(revision){
 if(grants.has(revision))return grants.get(revision);
 const pending=(async()=>{const suffix=runId+"-revision-"+revision;const output=resolve(out,"db-"+revision+".json");
 const env={...process.env,ENF_DOCUMENT_RULE_REVISION:String(revision),ENF_DOCUMENT_RULE_PREP_OUTPUT:output};const logs=[];
 const exit=await new Promise((accept,reject)=>{const child=spawn("mvn",["-q","-f",process.env.ENF_MAVEN_POM??"backend/pom.xml","-pl","apps/platform-api","-am","-Dtest=EnforcementDocumentRuleGrantFixtureTest","-Dsurefire.failIfNoSpecifiedTests=false","-Dsurefire.reportNameSuffix="+suffix,"test"],{cwd:root,env,shell:true});child.stdout.on("data",c=>logs.push(c.toString()));child.stderr.on("data",c=>logs.push(c.toString()));child.on("error",reject);child.on("close",accept);});
 await writeFile(resolve(out,"grant-"+revision+".log"),logs.join(""));if(exit!==0)throw new Error("Exact grant preparation failed");
 const path=resolve(process.env.ENF_MAVEN_BUILD_ROOT??root,"backend/apps/platform-api/target/surefire-reports/TEST-cn.xinji.agentplatform.security.product.EnforcementDocumentRuleGrantFixtureTest-"+suffix+".xml");const xml=await readFile(path,"utf8");
 const suite=xml.match(/<testsuite[^>]*>/)?.[0]??"";for(const [name,value]of Object.entries({tests:1,failures:0,errors:0,skipped:0}))if(!suite.includes(name+'="'+value+'"'))throw new Error("Fresh zero skip report required");
 await copyFile(path,resolve(out,suffix+".xml"));})();grants.set(revision,pending);return pending;
}
const browser=await chromium.launch({headless:true});const context=await browser.newContext({ignoreHTTPSErrors:true});const page=await context.newPage();page.setDefaultTimeout(90000);
let currentAuthorization;
let originalSave;
page.on("request",r=>{if(new URL(r.url()).pathname.startsWith("/v2/enforcement")){const h=r.headers().authorization;if(h)currentAuthorization=h;}});
page.on("request",r=>{if(r.method()==="POST"&&new URL(r.url()).pathname==="/v2/enforcement/document-rules"&&!originalSave)originalSave={body:r.postDataJSON(),key:r.headers()["idempotency-key"]};});
async function idle(){await page.locator('main.enf-next[aria-busy="false"]').waitFor();}
page.on("response",async r=>{const path=new URL(r.url()).pathname;if(path.startsWith("/v2/enforcement")){const entry={path,method:r.request().method(),status:r.status(),at:new Date().toISOString()};try{entry.body=await r.json();}catch{}records.push(entry);await writeFile(resolve(out,"http-records.json"),JSON.stringify(records,null,2));}});
await page.route("**/v2/enforcement/document-rules/*?revision=*",async route=>{try{const url=new URL(route.request().url());if(url.pathname.split("/").at(-1)!==ruleId)throw new Error("Unexpected rule ID");await provision(Number(url.searchParams.get("revision")));await route.continue();}catch(e){records.push({error:String(e).split("\n")[0]});await route.abort();}});
try{
 await page.goto("https://127.0.0.1:5176/app/enforcement/document-rules");await page.getByRole("button",{name:/登\s*录/}).click();
 await page.locator('input[name="username"]').fill(process.env.ENF_FOLLOWUP_LOGIN);await page.locator('input[name="password"]').fill(process.env.ENF_FOLLOWUP_PASSWORD);await page.locator('button[type="submit"]').click();
 await page.getByRole("heading",{name:"映射维护"}).waitFor();
 const tplResult=JSON.parse(await readFile(process.env.ENF_DOCUMENT_RULE_TEMPLATE_RESULT,"utf8"));
 for(const[label,value]of Object.entries({"规则 ID":ruleId,"模板 ID":process.env.ENF_ACCEPTANCE_TEMPLATE_ID,"模板 SHA":tplResult.sourceSha256,"来源 Schema ID":"PROJECT-ENF-DOCUMENT-SCHEMA-01","来源 Schema 版本":"1"}))await page.getByLabel(label,{exact:true}).fill(value);
 await page.getByLabel("模板版本",{exact:true}).fill("1");
 for(let i=0;i<5;i++)await page.getByRole("button",{name:"添加映射",exact:true}).click();
 const manifest=JSON.parse(await readFile(resolve(root,"data/enforcement/document-rule-v2-d-input-v1/manifest.json"),"utf8"));
 if(scenario==="unknown_path")manifest.ruleCandidate.mappings[0].sourcePath="task.pathThatDoesNotExist";
 if(scenario==="wrong_type")manifest.ruleCandidate.mappings[0].sourcePath="record.recordedDate";
 if(scenario==="no_interview"){manifest.ruleCandidate.mappings[3].sourcePath="interview.questionsAnswers";manifest.ruleCandidate.mappings[3].mode="DIRECT";}
 if(scenario==="unconfirmed_amount"){manifest.ruleCandidate.mappings[3].sourcePath="penalty.amount";manifest.ruleCandidate.mappings[3].mode="DIRECT";}
 if(scenario==="unconfirmed_legal")manifest.ruleCandidate.mappings[4].sourcePath="legal.references[]";
 for(const[i,m]of manifest.ruleCandidate.mappings.entries()){
  for(const[name,value]of Object.entries({fieldId:m.fieldId,sourcePath:m.sourcePath,schemaVersion:m.schemaVersion}))await page.locator('#mappings_'+i+'_'+name).fill(value);
  if(m.transform)await page.locator('#mappings_'+i+'_transform').fill(m.transform);
  if(m.mode!=="DIRECT"){await page.locator('#mappings_'+i+'_mode').click();await page.getByText(m.mode,{exact:true}).last().click();}
  if(m.missing!=="BLOCK"){await page.locator('#mappings_'+i+'_missing').click();await page.getByText(m.missing,{exact:true}).last().click();}
 }
 const savePromise=page.waitForResponse(r=>r.request().method()==="POST"&&new URL(r.url()).pathname==="/v2/enforcement/document-rules");await page.getByRole("button",{name:"保存草稿版本",exact:true}).click();const saved=await savePromise;
 if(!saved.ok())throw new Error("Actual product save failed: "+saved.status());await page.getByText(ruleId+" v1",{exact:true}).waitFor();await page.screenshot({path:resolve(out,"01-draft.png"),fullPage:true});
 if(scenario==="recovery_seed"){
  await idle();if(!originalSave?.key||!currentAuthorization)throw new Error("Actual save and secure session required for HTTP replay");
  const checks=[];
  for(const[name,key,body,expected]of [
   ["SAME_KEY_SAME_PAYLOAD",originalSave.key,originalSave.body,200],
   ["SAME_KEY_CHANGED_PAYLOAD",originalSave.key,{...originalSave.body,rule:{...originalSave.body.rule,mappings:[]}},409],
   ["STALE_CAS",runId+"-stale-cas",originalSave.body,409]
  ]){
   const response=await context.request.post("https://127.0.0.1:5444/v2/enforcement/document-rules",{headers:{Authorization:currentAuthorization,"Idempotency-Key":key},data:body});
   if(response.status()!==expected)throw new Error("Real HTTP replay/CAS failed: "+name+" "+response.status());
   checks.push({scenario:name,status:response.status(),body:await response.json().catch(()=>null),at:new Date().toISOString()});
  }
  await writeFile(resolve(out,"http-replay-cas.json"),JSON.stringify({scope:"ACTUAL_AUTHENTICATED_HTTP_NOT_BROWSER_CLICK",checks},null,2));
 }
 if(!["lifecycle","dependency","recovery_seed"].includes(scenario)){
  const missing={missing_party:["party_name","party.name"],missing_date:["recorded_date","record.recordedDate"],missing_material:["materials[].ref","materials[].ref"]}[scenario];
  const protectedFields={no_interview:"summary",unconfirmed_amount:"summary",unconfirmed_legal:"materials[].ref"};
  const code=scenario==="unknown_path"?"UNKNOWN_SOURCE_PATH":scenario==="wrong_type"?"TYPE_MISMATCH":"SOURCE_REVIEW_REQUIRED";
  let baselineCatalog;
  if(scenario==="unreadable_source"){
   baselineCatalog=await readFile(process.env.ENF_DOCUMENT_RULE_CATALOG);
   const broken=JSON.parse(baselineCatalog);broken.schemas[0].sourceFile=resolve(out,"deliberately-absent-source.json");
   await writeFile(resolve(out,"catalog-before.json"),baselineCatalog);await writeFile(resolve(out,"catalog-fault.json"),JSON.stringify(broken,null,2));
   await copyFile(resolve(out,"catalog-fault.json"),process.env.ENF_DOCUMENT_RULE_CATALOG);
  }
  let validation,trial;
  try{
   for(const[action,label]of [["validate","校验映射"],["trial","试生成"]]){
    const wait=page.waitForResponse(r=>r.request().method()==="POST"&&new URL(r.url()).pathname.endsWith("/"+action));
    await idle();await page.getByRole("button",{name:label,exact:true}).click();const response=await wait;
    if(scenario==="unreadable_source"){if(response.status()!==503)throw new Error("Unavailable original did not fail closed");continue;}
    if(!response.ok())throw new Error("Actual diagnostic response unavailable");const value=await response.json();
    if(action==="validate"){
     validation=value;
     if(missing){if(!value.valid)throw new Error("Missing value was incorrectly classified as a schema failure");}
     else if(value.valid||!value.diagnostics.some(d=>d.fieldId===(protectedFields[scenario]??"task_ref")&&d.code===code&&d.blocking))throw new Error("Expected exact product blocking diagnostic absent");
    }else{
     trial=value;if(value.complete)throw new Error("Invalid or missing source produced complete success");
     if(missing){if(value.fields.some(f=>f.fieldId===missing[0])||!value.diagnostics.some(d=>d.code==="MISSING_SOURCE_VALUE"&&d.fieldId===missing[0]&&d.blocking))throw new Error("Missing field was fabricated or not blocked");}
     else if(value.fields.length!==0)throw new Error("Protected/invalid mapping produced fabricated fields");
    }
   }
  }finally{if(baselineCatalog){await writeFile(resolve(out,"catalog-restored.json"),baselineCatalog);await copyFile(resolve(out,"catalog-restored.json"),process.env.ENF_DOCUMENT_RULE_CATALOG);}}
  if(!missing&&scenario!=="unreadable_source"){
   const wait=page.waitForResponse(r=>r.request().method()==="POST"&&new URL(r.url()).pathname.endsWith("/publish"));
   await idle();await page.getByRole("button",{name:/发\s*布/}).click();if((await wait).status()!==409)throw new Error("Invalid mapping publish did not fail closed");
  }
  await page.screenshot({path:resolve(out,"02-negative-fail-closed.png"),fullPage:true});
  grants.delete(1);await provision(1);const db=JSON.parse(await readFile(resolve(out,"db-1.json"),"utf8"));
  if(db.history.length!==1||db.history[0].state!=="DRAFT"||db.receipts.length!==1||db.events.length!==1||db.receipts[0].OPERATION!=="SAVE_DOCUMENT_RULE"||JSON.stringify(coordinates(db.receipts))!==JSON.stringify(coordinates(db.events)))throw new Error("Rejected operations left forbidden rule/receipt/event side effects");
  if(db.attempts.length!==2||db.attemptEvents.length!==2)throw new Error("Two actual attempt/event records required");
  if(scenario==="unreadable_source"&&db.attempts.some(a=>a.OUTCOME!=="FAILED"))throw new Error("Source failure was recorded as a successful execution");
  const finalFiles=await filesAt(process.env.ENF_DOCUMENT_RULE_TEMPLATE_ROOT);if(JSON.stringify(originalFiles)!==JSON.stringify(finalFiles))throw new Error("Negative mapping changed original template files");
  await writeFile(resolve(out,"file-side-effects.json"),JSON.stringify({scope:"ORIGINAL_TEMPLATE_FILES_EXCLUDING_INDEPENDENT_ATTEMPT_ARTIFACTS",before:originalFiles,after:finalFiles,unchanged:true},null,2));
  await writeFile(resolve(out,"result.json"),JSON.stringify({runId,passed:true,scope:"PRODUCT_BROWSER_FROZEN_NEGATIVE_NOT_FINAL_ACCEPTANCE",scenario,businessAcceptance:"PENDING",ruleId,validation,trial,records,db,filesUnchanged:true},null,2));console.log("PASSED "+runId);
 }else{
 for(const[action,label]of [["validate","校验映射"],["trial","试生成"]]){const wait=page.waitForResponse(r=>r.request().method()==="POST"&&new URL(r.url()).pathname.endsWith("/"+action)).catch(()=>null);await idle();await page.getByRole("button",{name:label,exact:true}).click();const response=await wait;if(!response)throw new Error("No actual HTTP response for "+action);const result=await response.json();if(!response.ok()||(action==="validate"?!result.valid:!result.complete))throw new Error("Actual product "+action+" failed");if(action==="trial"&&(result.fields.length!==6||!result.reviewRequired))throw new Error("Trial provenance or auxiliary review mismatch");}
 await page.screenshot({path:resolve(out,"02-trial-provenance.png"),fullPage:true});
 await idle();await page.getByRole("button",{name:/发\s*布/}).click();await page.getByText(ruleId+" v2",{exact:true}).waitFor();await page.getByText("PUBLISHED",{exact:true}).first().waitFor();await page.screenshot({path:resolve(out,"03-published.png"),fullPage:true});
 if(scenario==="recovery_seed"){
  await idle();grants.delete(2);await provision(2);const db=JSON.parse(await readFile(resolve(out,"db-2.json"),"utf8"));
  if(db.history.map(r=>r.state).join(",")!=="DRAFT,PUBLISHED"||db.receipts.length!==2||db.events.length!==2||db.attempts.length!==2||db.attemptEvents.length!==2)throw new Error("Active real product recovery seed incomplete");
  await writeFile(resolve(out,"result.json"),JSON.stringify({runId,passed:true,scenario,businessAcceptance:"PENDING",ruleId,records,db},null,2));console.log("PASSED "+runId);
 }else if(scenario==="dependency"){
  if(process.env.ENF_ACCEPTANCE_TEMPLATE_ID==="ENF13-TEMPLATE-ENF13-PRODUCT-20260930-36")throw new Error("Dependency change requires an isolated new template input, not the shared successful template");
  await idle();
  const changed=await page.evaluate(async ({authorization,id})=>{
   const headers={Authorization:authorization};
   const prior=await fetch("/v2/enforcement/document-templates/"+encodeURIComponent(id)+"/revisions/1",{headers});
   if(!prior.ok)throw new Error("Current template input unavailable: "+prior.status);
   const metadata=await prior.json();
   const download=await fetch("/v2/enforcement/document-templates/"+encodeURIComponent(id)+"/revisions/1/file",{headers});
   if(!download.ok)throw new Error("Real template original unavailable: "+download.status);
   const raw=await download.blob();const input={templateId:id,name:metadata.name+" dependency input revision",stage:metadata.stage,scope:metadata.scope,approvalRef:metadata.approvalRef,fields:metadata.fields,fontNames:metadata.fontNames};
   input.fields=input.fields.map(f=>f.id==="summary"?{...f,type:"STRING"}:f);
   const form=new FormData();form.append("metadata",new Blob([JSON.stringify(input)],{type:"application/json"}));form.append("file",raw,metadata.fileName);
   const response=await fetch("/v2/enforcement/document-templates/"+encodeURIComponent(id)+"/revisions",{method:"POST",headers:{...headers,"Idempotency-Key":crypto.randomUUID()},body:form});
   return {status:response.status,body:await response.json(),input,oldFileSha:metadata.fileSha256};
  },{authorization:currentAuthorization,id:process.env.ENF_ACCEPTANCE_TEMPLATE_ID});
  if(changed.status!==200||changed.body.revision!==2||changed.body.fileSha256!==changed.oldFileSha)throw new Error("Actual upstream template revision did not preserve the original");
  await writeFile(resolve(out,"dependency-input-update.json"),JSON.stringify({scope:"ACTUAL_UPSTREAM_PRODUCT_INPUT_NOT_A_LAW_CHANGE",...changed},null,2));
  for(let n=0;n<2;n++){
   await idle();const waited=page.waitForResponse(r=>r.request().method()==="POST"&&new URL(r.url()).pathname.endsWith("/validate"));
   await page.getByRole("button",{name:"校验映射",exact:true}).click();const response=await waited;const body=await response.json();
   if(response.status()!==200||body.valid||!body.reviewRequired||body.ruleRevision!==3||!body.diagnostics.some(d=>d.code==="TEMPLATE_DRIFT"&&d.blocking))throw new Error("Dependency update was not persisted as REVIEW_REQUIRED");
   await page.getByText(ruleId+" v3",{exact:true}).waitFor();await page.getByText("REVIEW_REQUIRED",{exact:true}).first().waitFor();
   await page.getByRole("row").filter({hasText:ruleId}).first().getByText("REVIEW_REQUIRED",{exact:true}).waitFor();
  }
  await idle();await page.screenshot({path:resolve(out,"04-dependency-review-required.png"),fullPage:true});
  grants.delete(3);await provision(3);const db=JSON.parse(await readFile(resolve(out,"db-3.json"),"utf8"));
  if(db.history.map(r=>r.state).join(",")!=="DRAFT,PUBLISHED,REVIEW_REQUIRED"||db.receipts.length!==3||db.events.length!==3||JSON.stringify(coordinates(db.receipts))!==JSON.stringify(coordinates(db.events)))throw new Error("Dependency review/history/receipt replay mismatch");
  if(JSON.stringify(db.history[1].mappings)!==JSON.stringify(db.history[2].mappings)||db.attempts.length!==4||db.attemptEvents.length!==4||db.attempts.filter(a=>a.OUTCOME==="REVIEW_REQUIRED").length!==2)throw new Error("Immutable mapping or attempt audit mismatch");
  const finalFiles=await filesAt(process.env.ENF_DOCUMENT_RULE_TEMPLATE_ROOT);for(const old of originalFiles)if(!finalFiles.some(f=>f.path===old.path&&f.sha256===old.sha256))throw new Error("Dependency update lost an old original");
  await writeFile(resolve(out,"file-side-effects.json"),JSON.stringify({before:originalFiles,after:finalFiles,oldOriginalsPreserved:true},null,2));
  await writeFile(resolve(out,"result.json"),JSON.stringify({runId,passed:true,scenario,businessAcceptance:"PENDING",ruleId,records,db,oldOriginalsPreserved:true},null,2));console.log("PASSED "+runId);
 }else{
 await idle();await page.getByRole("button",{name:/停\s*用/}).click();await page.getByText(ruleId+" v3",{exact:true}).waitFor();await page.getByText("DISABLED",{exact:true}).first().waitFor();
 const rejected=page.waitForResponse(r=>r.request().method()==="POST"&&new URL(r.url()).pathname.endsWith("/trial"));await idle();await page.getByRole("button",{name:"试生成",exact:true}).click();if((await rejected).status()!==409)throw new Error("Disabled rule trial did not fail closed");
 await page.screenshot({path:resolve(out,"04-disabled-fail-closed.png"),fullPage:true});
 const db=JSON.parse(await readFile(resolve(out,"db-3.json"),"utf8"));if(db.history.length!==3||db.receipts.length!==3||db.events.length!==3)throw new Error("DB revision/receipt/event mismatch");
 if(db.history.map(r=>r.state).join(",")!=="DRAFT,PUBLISHED,DISABLED")throw new Error("History mismatch");if(JSON.stringify(db.receipts)!==JSON.stringify(db.events))throw new Error("Receipt/event mismatch");
 await writeFile(resolve(out,"result.json"),JSON.stringify({runId,passed:true,scope:"PRODUCT_BROWSER_R01_LIFECYCLE_NOT_FINAL_ACCEPTANCE",businessAcceptance:"PENDING",ruleId,records,db},null,2));console.log("PASSED "+runId);
 }
 }
}catch(e){try{await page.screenshot({path:resolve(out,"failure.png"),fullPage:true,timeout:10000});}catch{}await writeFile(resolve(out,"result.json"),JSON.stringify({runId,passed:false,error:String(e).split("\n")[0],records},null,2));throw new Error(String(e).split("\n")[0]);}finally{await browser.close();}
