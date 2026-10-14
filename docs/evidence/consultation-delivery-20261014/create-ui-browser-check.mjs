import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {chromium} from '@playwright/test';
import {createServer} from '../../../frontend/node_modules/vite/dist/node/index.js';

// Component-only browser evidence: production page, synthetic owned content and intercepted HTTP.
process.env.VITE_CONSULTATION_CLIENT_ID='prompt-browser-component';
const output=resolve('docs/evidence/consultation-delivery-20261014');
const workspaceId='11111111-1111-4111-8111-111111111111';
const vite=await createServer({root:resolve('frontend'),configFile:resolve('frontend/vite.consultation.config.ts'),server:{host:'127.0.0.1',port:0,hmr:false,watch:{ignored:['**/dist/**','**/dist-consultation/**']}}});
let browser;
try {
  await vite.listen();
  const port=vite.httpServer.address().port;
  browser=await chromium.launch();
  const observations=[];
  for(const width of [1280,390]){
    const context=await browser.newContext({viewport:{width,height:900},reducedMotion:'reduce'});
    await context.addInitScript(({workspaceId})=>sessionStorage.setItem('consultation-pkce',JSON.stringify({verifier:'component-verifier',state:'component-state',taskId:'',destination:{view:'prompt-create',workspaceId}})),{workspaceId});
    const page=await context.newPage();const calls=[],errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/oauth2/token',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({access_token:'synthetic-component-session'})}));
    await page.route('**/v2/**',async route=>{
      const request=route.request();calls.push({method:request.method(),url:new URL(request.url()).pathname,key:request.headers()['idempotency-key'],body:request.postData()});
      if(request.method()==='POST')return route.fulfill({status:503,contentType:'application/json',body:'{"code":"PROMPT_UNAVAILABLE"}'});
      assert.equal(request.method(),'GET');assert.ok(request.url().endsWith('/requests/CREATE'));
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({receiptId:'22222222-2222-4222-8222-222222222222',workspaceId,promptId:'33333333-3333-4333-8333-333333333333',revisionId:'44444444-4444-4444-8444-444444444444',generation:1,contentSha:'a'.repeat(64),resultSha:'b'.repeat(64),submittedAt:new Date(Date.now()-1000).toISOString(),auditUntil:new Date(Date.now()+3600000).toISOString()})});
    });
    await page.goto(`http://127.0.0.1:${port}/app/consultation.html?code=component-code&state=component-state`);
    await page.getByRole('heading',{name:'创建提示词草稿'}).waitFor();
    assert.equal(calls.length,0);
    await page.getByLabel('名称',{exact:true}).fill('浏览器组件检查草稿');
    await page.getByLabel('已获许可来源编号',{exact:false}).fill(workspaceId);
    await page.getByLabel('受众引用',{exact:true}).fill('component-owned');
    await page.getByLabel('语言',{exact:true}).fill('zh-CN');
    await page.getByLabel('提示词正文',{exact:true}).fill('组件检查内容，不作为真实业务或来源授权。');
    const fits=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth);
    assert.ok(fits,`horizontal overflow at ${width}`);
    await page.screenshot({path:resolve(output,`create-ui-${width}.png`),fullPage:true});
    await page.getByRole('button',{name:'创建草稿',exact:true}).click();
    await page.getByRole('button',{name:'查询原提交结果'}).waitFor();
    writeFileSync(resolve(output,`create-ui-unknown-${width}.txt`),await page.locator('body').ariaSnapshot());
    await page.screenshot({path:resolve(output,`create-ui-unknown-${width}.png`),fullPage:true});
    assert.equal(calls.length,1);assert.ok(await page.locator('#prompt-create-body').isDisabled());
    await page.getByRole('button',{name:'查询原提交结果'}).click();
    await page.getByRole('heading',{name:'草稿已创建'}).waitFor();
    assert.equal(calls.length,2);assert.equal(calls[0].key,calls[1].key);assert.equal(calls[1].body,null);
    assert.equal(await page.getByLabel('提示词正文',{exact:true}).count(),0);
    assert.equal(await page.evaluate(()=>localStorage.length),0);assert.deepEqual(errors,[]);
    await page.screenshot({path:resolve(output,`create-ui-receipt-${width}.png`),fullPage:true});
    observations.push({width,noHorizontalOverflow:fits,methods:calls.map(c=>c.method),sameOriginalKey:true,noAutomaticRead:true,noPersistentBody:true,pageErrors:errors});
    await context.close();
  }
  writeFileSync(resolve(output,'create-ui-browser-result.json'),JSON.stringify({scope:'Browser component only; synthetic HTTP responses, no real identity, database or business acceptance',at:new Date().toISOString(),observations},null,2));
} finally {await browser?.close();await vite.close();}
