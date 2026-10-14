import { chromium } from "playwright";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const run=process.env.ENF_DRAFT_BROWSER_RUN;
if(!run||!/^[A-Za-z0-9-]+$/.test(run)||!process.env.ENF_FOLLOWUP_LOGIN||!process.env.ENF_FOLLOWUP_PASSWORD)throw new Error("Secure controlled identity and fresh run required");
const output=resolve(process.env.ENF_DRAFT_BROWSER_EVIDENCE,run);await mkdir(output,{recursive:false});
const root=JSON.parse(await readFile(resolve("../backend/enforcement/src/main/resources/enforcement/document-template-baseline-v1/profiles.json"),"utf8"));
const browser=await chromium.launch({headless:true});const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1080}});
const page=await context.newPage();page.setDefaultTimeout(20000);const responses=[];const published=[];
page.on("response",r=>{const path=new URL(r.url()).pathname;if(path.startsWith("/v2/enforcement/")||path.startsWith("/oauth2/"))responses.push({at:new Date().toISOString(),path,method:r.request().method(),status:r.status()});});
try {
  await page.goto("https://127.0.0.1:5174/app/enforcement/document-drafts",{waitUntil:"networkidle"});
  await page.getByRole("button",{name:/登\s*录/}).click();
  await page.locator('input[name="username"]').fill(process.env.ENF_FOLLOWUP_LOGIN);
  await page.locator('input[name="password"]').fill(process.env.ENF_FOLLOWUP_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.getByRole("button",{name:/退\s*出/}).waitFor();
  await page.getByRole("button",{name:"读取项目内置模板基线",exact:true}).click();
  for(const p of root.profiles) {
    await page.getByRole("combobox",{name:"内置模板版本",exact:true}).click();
    await page.getByText(p.title+"@"+p.revision+" · "+p.classification,{exact:true}).last().click();
    const resultPromise=page.waitForResponse(r=>new URL(r.url()).pathname==="/v2/enforcement/document-drafts/baseline-templates"&&r.request().method()==="POST");
    await page.getByRole("button",{name:"导入并发布所选项目基线模板",exact:true}).click();
    const response=await resultPromise;assert.equal(response.status(),200);
    const receipt=await response.json();assert.equal(receipt.templateId,p.id);assert.equal(receipt.state,"PUBLISHED");assert.equal(receipt.revision,1);assert.match(receipt.fileSha256,/^[a-f0-9]{64}$/);
    await page.getByText("模板发布回执",{exact:true}).waitFor();
    await page.getByText(receipt.templateId+"@"+receipt.revision+" · "+receipt.state+" · "+receipt.fileSha256,{exact:true}).waitFor();
    published.push({at:new Date().toISOString(),...receipt});
    await page.screenshot({path:resolve(output,p.id+"-published.png"),fullPage:true});
    // Same visible action replays the same engineering idempotency key, without changing the template.
    const replayPromise=page.waitForResponse(r=>new URL(r.url()).pathname==="/v2/enforcement/document-drafts/baseline-templates"&&r.request().method()==="POST");
    await page.getByRole("button",{name:"导入并发布所选项目基线模板",exact:true}).click();
    const replay=await replayPromise;assert.equal(replay.status(),200);assert.deepEqual(await replay.json(),receipt);
  }
  await writeFile(resolve(output,"result.json"),JSON.stringify({run,scope:"REAL_OAUTH_PKCE_BROWSER_TEMPLATE_IMPORT_PUBLISH_NOT_COMPLETE_DOCUMENT_I",testNature:"FUNCTIONAL_VALIDATION",published,replays:3,responses,businessAcceptance:"PENDING"},null,2));
  console.log("Real browser baseline imports=3; idempotent replays=3; OAuth/PKCE login passed");
} catch(error) {
  const message=String(error).replaceAll(process.env.ENF_FOLLOWUP_PASSWORD,"[REDACTED]");
  await writeFile(resolve(output,"failure.json"),JSON.stringify({run,error:message,published,responses,scope:"ACTUAL_BROWSER_FAILURE_NOT_PASSED"},null,2));
  throw new Error(message);
} finally {await context.close();await browser.close();}
