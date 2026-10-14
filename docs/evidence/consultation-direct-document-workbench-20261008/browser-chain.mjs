import {readFileSync,writeFileSync} from 'node:fs';
import {chromium} from 'file:///D:/software/npm-cache/_npx/9833c18b2d85bc59/node_modules/playwright/index.mjs';
const input=JSON.parse(readFileSync(0,'utf8'));
if(Date.now()>=Date.parse(input.until))throw Error('Engineering window expired');
const browser=await chromium.launch({headless:true,executablePath:'C:/Users/60329/AppData/Local/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-win64/chrome-headless-shell.exe'});
const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1280,height:900}});
const page=await context.newPage();page.setDefaultTimeout(20000);
const responses=[],requests=[];let receipt=null;let fault=null;
page.on("request",r=>{const u=new URL(r.url());if(u.origin===input.origin&&u.pathname.startsWith("/v2/"))requests.push({path:u.pathname,method:r.method()});});
page.on('response',async response=>{const url=new URL(response.url());if(url.origin!==input.origin)return;
 if(url.pathname.startsWith('/v2/')||url.pathname==='/oauth2/token')responses.push({path:url.pathname,method:response.request().method(),status:response.status()});
 if(url.pathname.endsWith('/start-commands')&&response.request().method()==='POST'&&response.status()===200)receipt=await response.json();
});
try{
 await page.goto(input.origin+'/app/consultation?task='+encodeURIComponent(input.taskId));
 await page.getByRole('button',{name:'登录并打开任务',exact:true}).click();
 await page.locator('input[name="username"]').fill(input.loginName);await page.locator('input[name="password"]').fill(input.password);await page.locator('button[type="submit"]').click();
 await page.getByRole('heading',{name:'准备并确认技术对照输入'}).waitFor();
 await page.getByLabel('直接选择获准文献',{exact:true}).check();await page.getByRole('button',{name:'读取当前对照上下文',exact:true}).click();
 const target=page.getByLabel('直接目标专利',{exact:true});await target.waitFor();
 const options=await target.locator('option').allTextContents();const p1=options.findIndex(t=>t.includes(' · P1 · '));if(p1<0)throw Error('Server target missing');await target.selectOption({index:p1});
 await page.getByLabel(/^候选 P2 ·/).check();
 for(const id of ['P1','P2'])await page.getByRole('group',{name:new RegExp('^文献 '+id+' ·')}).getByLabel('权项 1',{exact:true}).check();
 await page.getByRole('button',{name:'准备确切对照原文',exact:true}).click();
 const original=page.getByLabel('目标确切原文',{exact:true});await original.waitFor();await original.click();await original.press('ControlOrMeta+A');const selection=await original.evaluate(n=>({start:n.selectionStart,end:n.selectionEnd,length:n.value.length}));if(selection.start===selection.end)throw Error('Real keyboard original selection is empty: '+JSON.stringify(selection));
 await page.getByRole('button',{name:'将选中原文加入特征',exact:true}).click();await page.getByLabel('本次人工确认说明',{exact:true}).fill('Engineering operator explicitly selected original passage; not professional acceptance.');
 await page.getByLabel(/^我已逐项核对所选原文/).check();await page.getByLabel('特征 1 的确认说明',{exact:true}).waitFor();await page.getByRole('button',{name:'确认当前特征版',exact:true}).click();await page.getByRole('button',{name:'创建不可变对照输入',exact:true}).click();
 const candidate=page.getByLabel('计算候选',{exact:true});await candidate.waitFor();await candidate.selectOption({index:1});
 // Real product POST forwarded once, then the browser response delivery is interrupted.
 // Not a fixture response or Worker Transport substitute; the server and mTLS dispatch run normally.
 await page.route('**/start-commands',async route=>{
  if(route.request().method()!=='POST'||fault){await route.continue();return;}
  const actual=await route.fetch();receipt=await actual.json();
  fault={kind:'BROWSER_RESPONSE_DELIVERY_CONNECTIONRESET_AFTER_REAL_SERVER_ACCEPTANCE',serverStatus:actual.status(),requestId:receipt.requestId};
  await route.abort('connectionreset');
 });
 await page.getByRole('button',{name:'明确发起此候选计算',exact:true}).click();
 await page.getByRole('button',{name:'查询原启动回执',exact:true}).waitFor();
 await page.getByRole('button',{name:'查询原启动回执',exact:true}).click();
 const received=page.getByText('受管服务已接收此候选交接，但当前计算准入未满足；尚未开始计算，也没有提交对照结果。查询原回执不会自动执行。',{exact:true});await received.waitFor();
 if(!receipt||receipt.status!=='ADMISSION_REQUIRED'||receipt.attemptId!==null)throw Error('No-model receipt invalid');
 const before=requests.filter(r=>r.path.endsWith('/start-commands')&&r.method==='POST').length;if(before!==1)throw Error('Expected exactly one explicit browser POST');
 await page.getByRole('button',{name:'查询原启动回执',exact:true}).click();await received.waitFor();
 if(requests.filter(r=>r.path.endsWith('/start-commands')&&r.method==='POST').length!==before)throw Error('Receipt read repeated POST');
 await page.setViewportSize({width:390,height:844});await page.waitForTimeout(200);const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth);
 await page.setViewportSize({width:1280,height:900});await page.screenshot({path:input.output+'.png',fullPage:true});
 writeFileSync(input.output,JSON.stringify({scope:'ACTUAL_CHROMIUM_FINAL_WORKFACE_NO_MODEL',time:new Date().toISOString(),taskId:input.taskId,receipt,responses,originalKeyQueryOnly:true,calculationPosts:requests.filter(r=>r.path.endsWith('/start-commands')&&r.method==='POST').length,requests,responseDeliveryFault:fault,mobileOverflow:overflow,engineeringCertificateValidation:'Local self-signed server accepted in browser; real IAM verification and server-side mTLS, Java clients use actual truststore',networkFailureInjection:true,networkFailureScope:"Browser-only response delivery reset; not a Worker mTLS disconnect or provider timeout"},null,2),{flag:'wx'});
 if(overflow)throw Error('Mobile horizontal overflow');
}catch(error){writeFileSync(input.output+'.failure.json',JSON.stringify({scope:'ACTUAL_BROWSER_FAILED_NOT_ACCEPTANCE',time:new Date().toISOString(),responses,error:error.message,pageText:await page.locator('body').innerText()},null,2),{flag:'wx'});console.error(error.message);throw error;}finally{await context.close();await browser.close();}


