import { chromium } from "playwright";
import { mkdir,readFile,writeFile,rename } from "node:fs/promises";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
const runId=process.env.ENF_DOCUMENT_RULE_RECOVERY_RUN;
if(!/^[A-Za-z0-9_-]+$/.test(runId??""))throw new Error("Fresh run required");
const out=resolve("../docs/evidence/enforcement-followup-final-20260930",runId);await mkdir(out,{recursive:false});
const baseline=JSON.parse(await readFile(process.env.ENF_DOCUMENT_RULE_RECOVERY_BASELINE,"utf8"));if(!baseline.passed||baseline.scenario!=="recovery_seed")throw new Error("Real active product baseline required");
const id=baseline.ruleId,gate=process.env.ENF_DOCUMENT_RULE_RECOVERY_GATE,target="https://127.0.0.1:5450";
const records=[];let authorization,restored=false;
const browser=await chromium.launch({headless:true});const context=await browser.newContext({ignoreHTTPSErrors:true});const page=await context.newPage();page.setDefaultTimeout(90000);
page.on("request",r=>{if(new URL(r.url()).pathname.startsWith("/v2/enforcement")){const value=r.headers().authorization;if(value)authorization=value;}});
page.on("response",async r=>{const path=new URL(r.url()).pathname;if(path.startsWith("/v2/enforcement/document-rules")){const record={path,status:r.status(),restored,at:new Date().toISOString()};try{record.body=await r.json();}catch{}records.push(record);await writeFile(resolve(out,"http-records.json"),JSON.stringify(records,null,2));}});
await page.route("**/v2/enforcement/document-rules**",async route=>{const url=new URL(route.request().url());await route.continue(restored?{url:target+url.pathname+url.search}:{});});
async function signal(name){await writeFile(gate+name,JSON.stringify({runId,at:new Date().toISOString()}),{flag:"wx"});}
async function wait(name){const until=Date.now()+900000;while(Date.now()<until){try{await readFile(gate+name);return;}catch{}await new Promise(r=>setTimeout(r,250));}throw new Error("Gate timeout: "+name);}
async function idle(){await page.locator('main.enf-next[aria-busy="false"]').waitFor();}
async function reload(){await idle();const response=page.waitForResponse(r=>r.request().method()==="GET"&&new URL(r.url()).pathname.endsWith("/"+id));await page.getByRole("button",{name:"读取规则",exact:true}).click();if((await response).status()!==200)throw new Error("Recovered read failed");await idle();await page.getByText(id+" v2",{exact:true}).waitFor();}
async function trial(expected,name){await idle();const response=page.waitForResponse(r=>r.request().method()==="POST"&&new URL(r.url()).pathname.endsWith("/trial"));await page.getByRole("button",{name:"试生成",exact:true}).click();const r=await response;if(r.status()!==expected)throw new Error(name+" expected "+expected+" got "+r.status());records.push({scenario:name,status:r.status(),at:new Date().toISOString()});await idle();if(expected===200){const body=await r.json();if(!body.complete||body.fields.length!==6)throw new Error("Recovered product trial not actually usable");}}
try{
 await page.goto("https://127.0.0.1:5176/app/enforcement/document-rules");await page.getByRole("button",{name:/登\s*录/}).click();await page.locator('input[name="username"]').fill(process.env.ENF_FOLLOWUP_LOGIN);await page.locator('input[name="password"]').fill(process.env.ENF_FOLLOWUP_PASSWORD);await page.locator('button[type="submit"]').click();
 await page.getByLabel("已有规则 ID",{exact:true}).fill(id);await page.getByLabel("版本",{exact:true}).fill("2");await reload();await page.getByText("PUBLISHED",{exact:true}).first().waitFor();if(!authorization)throw new Error("Actual OAuth/PKCE session missing");
 await page.screenshot({path:resolve(out,"01-source-active.png"),fullPage:true});await signal(".session-ready");await wait(".restore-ready");restored=true;
 await reload();await reload();await page.screenshot({path:resolve(out,"02-restored-history-attempts.png"),fullPage:true});await signal(".read-ready");await wait(".reconciled");
 const original=process.env.ENF_DOCUMENT_RULE_RECOVERY_ORIGINAL;const bytes=await readFile(original);const sha=createHash("sha256").update(bytes).digest("hex");
 if(sha!==baseline.db.history[1].templateSha256)throw new Error("Restored original SHA mismatch");
 await rename(original,original+".held");try{await trial(503,"MISSING_ORIGINAL_FAIL_CLOSED");}finally{await rename(original+".held",original);}
 try{await writeFile(original,"CONTROLLED_SHA_FAILURE_NOT_BUSINESS_DATA");await trial(503,"SHA_DRIFT_FAIL_CLOSED");}finally{await writeFile(original,bytes);}
 await reload();await trial(200,"RECOVERED_ACTUAL_USABLE_TRIAL");await page.screenshot({path:resolve(out,"03-restored-usable-trial.png"),fullPage:true});
 const templateId=baseline.db.history[1].templateId;const current=await context.request.get("https://127.0.0.1:5444/v2/enforcement/document-templates/"+templateId+"/revisions/1",{headers:{Authorization:authorization}});if(current.status()!==200)throw new Error("Current upstream template not readable");const prior=await current.json();
 const originalResponse=await context.request.get("https://127.0.0.1:5444/v2/enforcement/document-templates/"+templateId+"/revisions/1/file",{headers:{Authorization:authorization}});if(originalResponse.status()!==200)throw new Error("Current upstream original unavailable");
 const changed=await context.request.post("https://127.0.0.1:5444/v2/enforcement/document-templates/"+templateId+"/revisions",{headers:{Authorization:authorization,"Idempotency-Key":runId+"-dependency-input"},multipart:{metadata:{name:"metadata.json",mimeType:"application/json",buffer:Buffer.from(JSON.stringify({templateId,name:prior.name+" current head",stage:prior.stage,scope:prior.scope,approvalRef:prior.approvalRef,fields:prior.fields.map(f=>f.id==="summary"?{...f,type:"STRING"}:f),fontNames:prior.fontNames}))},file:{name:prior.fileName,mimeType:"application/vnd.openxmlformats-officedocument.wordprocessingml.document",buffer:await originalResponse.body()}}});
 if(changed.status()!==200||(await changed.json()).revision!==2)throw new Error("Current upstream input change failed");
 const drift=await context.request.post(target+"/v2/enforcement/document-rules/"+id+"/validate",{headers:{Authorization:authorization},data:{expectedRevision:2,purpose:"DOCUMENT_MAPPING"}});const body=await drift.json();if(drift.status()!==200||body.valid||!body.reviewRequired||body.ruleRevision!==3||!body.diagnostics.some(d=>d.code==="TEMPLATE_DRIFT"))throw new Error("Old backup restored stale upstream validity");
 records.push({scenario:"CURRENT_UPSTREAM_NOT_ROLLED_BACK",scope:"ACTUAL_AUTHENTICATED_RESTORED_HTTP",status:drift.status(),body});await signal(".effects-ready");await wait(".withdraw-ready");
 for(const[base,status]of [["https://127.0.0.1:5444",403],[target,503]]){
  const r=await context.request.get(base+"/v2/enforcement/document-rules/"+id+"?revision=2",{headers:{Authorization:authorization}});if(r.status()!==status)throw new Error("Old authority not closed: "+r.status());records.push({scenario:"CURRENT_READ_WITHDRAWAL",base,status:r.status()});
  const tried=await context.request.post(base+"/v2/enforcement/document-rules/"+id+"/trial",{headers:{Authorization:authorization},data:{expectedRevision:2,purpose:"DOCUMENT_MAPPING"}});if(tried.status()!==status)throw new Error("Withdrawn input produced a trial result");records.push({scenario:"WITHDRAWN_TRIAL_NO_SUCCESS",base,status:tried.status()});
 }
 restored=false;await idle();const denied=page.waitForResponse(r=>r.request().method()==="GET"&&new URL(r.url()).pathname.endsWith("/"+id));await page.getByRole("button",{name:"读取规则",exact:true}).click();if((await denied).status()!==403)throw new Error("Actual revoked browser read not denied");await idle();if(await page.getByText(id+" v2",{exact:true}).count())throw new Error("Revoked browser retained cached product results");await page.screenshot({path:resolve(out,"04-revoked-cleared.png"),fullPage:true});
 await writeFile(resolve(out,"result.json"),JSON.stringify({runId,passed:true,ruleId:id,records,originalSha256:sha,businessAcceptance:"PENDING"},null,2));console.log("PASSED "+runId);
}catch(e){await page.screenshot({path:resolve(out,"failure.png"),fullPage:true}).catch(()=>{});await writeFile(resolve(out,"result.json"),JSON.stringify({runId,passed:false,error:String(e).split("\n")[0],records},null,2));throw e;}finally{await browser.close();}
