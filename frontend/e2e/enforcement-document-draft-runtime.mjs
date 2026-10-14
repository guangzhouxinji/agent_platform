import { chromium } from "playwright";
import { mkdir,writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const run=process.env.ENF_DRAFT_BROWSER_RUN;
if(!run||!/^[A-Za-z0-9-]+$/.test(run)||!process.env.ENF_FOLLOWUP_LOGIN||!process.env.ENF_FOLLOWUP_PASSWORD)throw new Error("Secure identity and fresh evidence ID required");
const output=resolve(process.env.ENF_DRAFT_BROWSER_EVIDENCE,run);await mkdir(output,{recursive:false});
const browser=await chromium.launch({headless:true});const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1080}});
const page=await context.newPage();page.setDefaultTimeout(20000);const responses=[];
page.on("response",r=>{const path=new URL(r.url()).pathname;if(path.startsWith("/v2/enforcement/")||path.startsWith("/oauth2/")){responses.push({at:new Date().toISOString(),path,method:r.request().method(),status:r.status()});console.log("HTTP "+r.status()+" "+path);}});
try {
  console.log("STAGE NAVIGATE");
  await page.goto("https://127.0.0.1:5174/app/enforcement/document-drafts",{waitUntil:"networkidle"});
  await page.getByRole("button",{name:/登\s*录/}).click();await page.locator('input[name="username"]').fill(process.env.ENF_FOLLOWUP_LOGIN);
  await page.locator('input[name="password"]').fill(process.env.ENF_FOLLOWUP_PASSWORD);await page.locator('button[type="submit"]').click();
  await page.getByRole("button",{name:/退\s*出/}).waitFor();
  console.log("STAGE LOGIN_OK");
  const profilesPromise=page.waitForResponse(r=>new URL(r.url()).pathname==="/v2/enforcement/document-drafts/baseline-profiles");
  await page.getByRole("button",{name:"读取项目内置模板基线",exact:true}).click();const profilesResponse=await profilesPromise;assert.equal(profilesResponse.status(),200);const profiles=await profilesResponse.json();assert.equal(profiles.length,3);
  await page.locator('main[aria-busy="false"]').waitFor();
  console.log("STAGE PROFILES_OK");
  const inputPromise=page.waitForResponse(r=>new URL(r.url()).pathname==="/v2/enforcement/document-drafts/input-diagnostics");
  await page.getByRole("button",{name:"读取可消费输入",exact:true}).click();console.log("STAGE INPUT_CLICKED");const inputResponse=await inputPromise;console.log("STAGE INPUT_RESPONSE");const inputBody=await inputResponse.json();
  assert.equal(inputResponse.status(),200,"Current input endpoint must return its actual result");
  await page.locator('main[aria-busy="false"]').waitFor();
  const inputs=Array.isArray(inputBody.inputs)?inputBody.inputs:[];
  console.log("STAGE DIAGNOSTICS_RESPONSE count="+(inputBody.checks?.length??0));
  await writeFile(resolve(output,"result.json"),JSON.stringify({run,observedAt:new Date().toISOString(),url:page.url(),scope:"REAL_BROWSER_RUNTIME_INPUT_READINESS_NOT_COMPLETE_DOCUMENT_I",testNature:"FUNCTIONAL_VALIDATION",profiles:profiles.map(p=>({id:p.id,revision:p.revision,classification:p.classification})),inputHttpStatus:inputResponse.status(),inputCount:inputs.length,inputResult:inputBody,productsGenerated:0,businessAcceptance:"PENDING",responses},null,2));
  await page.screenshot({path:resolve(output,"runtime-input-state.png"),fullPage:true,timeout:5000});
  assert.ok(Array.isArray(inputBody.checks)&&inputBody.checks.length>0,"Actual per-condition diagnostics required");
  assert.equal(inputResponse.status(),200,"Current input endpoint must return its actual result");console.log("Real OAuth/PKCE browser; baseline profiles=3; consumable inputs="+inputs.length+"; document product I NOT_RUN");
} catch(error){const message=String(error).replaceAll(process.env.ENF_FOLLOWUP_PASSWORD,"[REDACTED]");const location=new URL(page.url());const notices=(await page.locator(".alert,.error,[role=alert]").allTextContents()).map(t=>t.replaceAll(process.env.ENF_FOLLOWUP_PASSWORD,"[REDACTED]"));await writeFile(resolve(output,"failure.json"),JSON.stringify({run,error:message,page:location.origin+location.pathname,notices,responses,scope:"ACTUAL_BROWSER_FAILURE_NOT_PASSED"},null,2));if(await page.locator("input[type=password]").count())await page.locator("input[type=password]").fill("",{timeout:5000}).catch(()=>{});await page.screenshot({path:resolve(output,"failure-page.png"),fullPage:true,timeout:5000}).catch(()=>{});throw new Error(message);}
finally {await context.close();await browser.close();}
