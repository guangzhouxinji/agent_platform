import {chromium} from 'playwright';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const run=process.env.ENF_DRAFT_BROWSER_RUN,id=process.env.ENF_DRAFT_FACT_DOCUMENT_ID;
if(!run||!id||!process.env.ENF_FOLLOWUP_PASSWORD)throw new Error('Secure runtime context required');
const output=resolve(process.env.ENF_DRAFT_BROWSER_EVIDENCE,run);await mkdir(output,{recursive:false});
const browser=await chromium.launch({headless:true,args:["--disable-gpu"]});const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1080}});
const page=await context.newPage();page.setDefaultTimeout(20000);const steps=[];
async function click(name,path){const button=page.getByRole('button',{name:name==='准备'?/准\s*备/:name,exact:true});await button.and(page.locator('button:not([disabled])')).waitFor();assert.equal(await button.isEnabled(),true,'Product action must be enabled');const pending=page.waitForResponse(r=>new URL(r.url()).pathname===path).then(r=>({r}),error=>({error}));await button.click({force:true});const outcome=await pending;if(outcome.error)throw outcome.error;const r=outcome.r;const body=await r.json();steps.push({at:new Date().toISOString(),url:new URL(r.url()).origin+new URL(r.url()).pathname,method:r.request().method(),status:r.status(),body});await writeFile(resolve(output,'steps.json'),JSON.stringify({run,id,steps},null,2));assert.equal(r.status(),200);await page.locator('main[aria-busy="false"]').waitFor();return body;}
try {
 await page.goto('https://127.0.0.1:5174/app/enforcement/document-drafts',{waitUntil:'networkidle'});
 await page.getByRole('button',{name:/登\s*录/}).click();await page.locator('input[name=username]').fill(process.env.ENF_FOLLOWUP_LOGIN);await page.locator('input[name=password]').fill(process.env.ENF_FOLLOWUP_PASSWORD);await page.locator('button[type=submit]').click({force:true});await page.getByRole('button',{name:/退\s*出/}).waitFor();
 const audit=await click('读取可消费输入','/v2/enforcement/document-drafts/input-diagnostics');assert.ok(audit.inputs.length>=1,'Actual consumable input required');
 const choice=audit.inputs.findIndex(v=>v.type==='FACT_RECORD'&&v.caseInput.id.startsWith('CNIPA-GUIDANCE-CASE-1-PUBLIC-'));assert.ok(choice>=0);
 await page.locator('.ant-form-item').filter({has:page.getByText('文书标识',{exact:true})}).locator('input').fill(id);
 await page.locator('.ant-form-item').filter({has:page.getByText('运行库输入组合',{exact:true})}).locator('.ant-select').click();
 await page.locator('.ant-select-item-option').filter({hasText:audit.inputs[choice].rule.id}).click();
 const prep=await click('准备','/v2/enforcement/document-drafts/'+id+'/prepare');assert.equal(prep.ready,false);assert.ok(prep.diagnostics.some(d=>d.field==='legal_references'&&d.blocking));
 const receipt=await click('生成草稿','/v2/enforcement/document-drafts/'+id+'/generate');assert.equal(receipt.operation,'GENERATE');
 const draftResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/v2/enforcement/document-drafts/'+id&&r.request().method()==='GET');
 await page.getByRole('button',{name:'查询文书',exact:true}).click();const response=await draftResponse;assert.equal(response.status(),200);const draft=await response.json();await page.locator('main[aria-busy="false"]').waitFor();
 assert.equal(draft.reviewState,'INCOMPLETE_DRAFT');assert.equal(draft.revision,1);assert.equal(draft.manifest.legalReferences.length,0);assert.ok(draft.missingItems.length>0);
 assert.equal(await page.getByRole('button',{name:'提交本版审核',exact:true}).isDisabled(),true);assert.equal(await page.getByRole('button',{name:'导出本审核版 DOCX',exact:true}).isDisabled(),true);
 await page.screenshot({path:resolve(output,'fact-record-product.png'),fullPage:true});
 await writeFile(resolve(output,'result.json'),JSON.stringify({run,id,observedAt:new Date().toISOString(),scope:'REAL_BROWSER_PREPARE_GENERATE_QUERY_INCOMPLETE_FACT_RECORD',testNature:'PUBLIC_SOURCE_FUNCTIONAL_VALIDATION',inputCount:audit.inputs.length,input:audit.inputs[choice],steps,draft,review:'NOT_RUN_INCOMPLETE',export:'NOT_RUN_INCOMPLETE',businessAcceptance:'PENDING'},null,2));console.log('Actual browser prepare/generate/query; INCOMPLETE_DRAFT; review/export blocked');
}catch(error){const message=String(error).replaceAll(process.env.ENF_FOLLOWUP_PASSWORD,'[REDACTED]');await writeFile(resolve(output,'failure.json'),JSON.stringify({run,id,message,steps},null,2));if(await page.locator('input[type=password]').count())await page.locator('input[type=password]').fill('').catch(()=>{});await page.screenshot({path:resolve(output,'failure.png'),fullPage:true,timeout:5000}).catch(()=>{});throw new Error(message);}finally{await context.close();await browser.close();}
