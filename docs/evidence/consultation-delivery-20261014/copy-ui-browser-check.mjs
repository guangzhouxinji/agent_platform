import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {chromium} from '@playwright/test';
import {createServer} from '../../../frontend/node_modules/vite/dist/node/index.js';

// Production UI, owned synthetic HTTP inputs only; this is not deployed business-flow evidence.
process.env.VITE_CONSULTATION_CLIENT_ID='prompt-browser-component';
const output=resolve('docs/evidence/consultation-delivery-20261014');
const sourceWorkspaceId='11111111-1111-4111-8111-111111111111',targetWorkspaceId='22222222-2222-4222-8222-222222222222';
const sourcePromptId='33333333-3333-4333-8333-333333333333',sourceRevisionId='44444444-4444-4444-8444-444444444444';
const sourceContentSha='a'.repeat(64),source={sourceWorkspaceId,sourcePromptId,sourceRevisionId,sourceContentSha};
const targetPromptId='55555555-5555-4555-8555-555555555555',targetRevisionId='66666666-6666-4666-8666-666666666666';
const vite=await createServer({root:resolve('frontend'),configFile:resolve('frontend/vite.consultation.config.ts'),server:{host:'127.0.0.1',port:0,hmr:false,watch:{ignored:['**/dist/**','**/dist-consultation/**']}}});
let browser;
try{
  await vite.listen();const port=vite.httpServer.address().port;browser=await chromium.launch();const observations=[];
  for(const width of [1280,390])for(const scenario of ['read-unknown','key-conflict','recovery-only']){
    const context=await browser.newContext({viewport:{width,height:900},reducedMotion:'reduce'});
    const destination=scenario==='read-unknown'?{view:'prompt',promptId:sourcePromptId,revisionId:sourceRevisionId}:
      scenario==='key-conflict'?{view:'prompt-copy',...source}:{view:'prompt-copy'};
    await context.addInitScript(destination=>sessionStorage.setItem('consultation-pkce',JSON.stringify({verifier:'component-verifier',state:'component-state',taskId:'',destination})),destination);
    const page=await context.newPage(),calls=[],errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/oauth2/token',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({access_token:'synthetic-component-session'})}));
    await page.route('**/v2/**',async route=>{
      const request=route.request(),url=new URL(request.url());
      calls.push({method:request.method(),path:url.pathname,query:url.search,key:request.headers()['idempotency-key'],body:request.postData()});
      const submittedAt=new Date(Date.now()-1000).toISOString(),until=new Date(Date.now()+3600000).toISOString();
      let response;
      if(url.pathname===`/v2/consultation/prompts/${sourcePromptId}/revisions`)
        response={promptId:sourcePromptId,page:1,size:20,total:1,revisions:[{revisionId:sourceRevisionId,version:1,submittedAt,bodyUntil:until}]};
      else if(url.pathname===`/v2/consultation/prompts/${sourcePromptId}/revisions/${sourceRevisionId}`)
        response={workspaceId:sourceWorkspaceId,contentSha:sourceContentSha,promptId:sourcePromptId,revisionId:sourceRevisionId,version:1,kind:'SYSTEM',name:'组件检查草稿',body:'项目自有组件输入，不代表业务资料许可。',scenarioIds:[],intentIds:[],audienceRef:'internal',language:'zh-CN',displayOrder:0,variables:[],submittedAt,bodyUntil:until};
      else if(request.method()==='POST')return route.fulfill({status:scenario==='key-conflict'?409:503,contentType:'application/json',body:JSON.stringify({code:scenario==='key-conflict'?'PROMPT_COPY_KEY_CONFLICT':'PROMPT_UNAVAILABLE'})});
      else{
        assert.equal(url.pathname,`/v2/consultation/prompt-workspaces/${targetWorkspaceId}/requests/COPY`);
        assert.equal(url.search,`?sourceWorkspaceId=${sourceWorkspaceId}`);
        response={receiptId:'77777777-7777-4777-8777-777777777777',workspaceId:targetWorkspaceId,promptId:targetPromptId,revisionId:targetRevisionId,generation:'1',contentSha:(scenario==='read-unknown'?'a':'c').repeat(64),resultSha:'b'.repeat(64),submittedAt,auditUntil:until};
      }
      await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(response)});
    });
    await page.goto(`http://127.0.0.1:${port}/app/consultation.html?code=component-code&state=component-state`);
    if(scenario==='read-unknown'){
      await page.getByRole('button',{name:'复制此确切版本'}).click();
      assert.ok(calls.some(c=>c.path===`/v2/consultation/prompts/${sourcePromptId}/revisions/${sourceRevisionId}`));
      assert.ok(calls.every(c=>c.method==='GET'&&[ `/v2/consultation/prompts/${sourcePromptId}/revisions`, `/v2/consultation/prompts/${sourcePromptId}/revisions/${sourceRevisionId}`].includes(c.path)));
      assert.ok(page.url().includes('view=prompt-copy'));
    }
    await page.getByRole('heading',{name:'复制提示词确切版本'}).waitFor();
    if(scenario==='recovery-only'){
      assert.equal(await page.getByRole('button',{name:'复制确切版本',exact:true}).count(),0);
      assert.equal(calls.length,0);
      await page.getByLabel('原来源工作区编号',{exact:true}).fill(sourceWorkspaceId);
      await page.getByLabel('原目标工作区编号',{exact:true}).fill(targetWorkspaceId);
      await page.getByLabel('原提交编号',{exact:true}).fill('owned-original-key');
      await page.getByRole('button',{name:'查询已有复制',exact:true}).click();
    }else{
      await page.getByLabel('目标工作区编号',{exact:true}).fill(targetWorkspaceId);
      await page.getByLabel('已获许可来源编号',{exact:true}).fill(sourceWorkspaceId);
      if(scenario==='read-unknown')await page.screenshot({path:resolve(output,`copy-ui-form-${width}.png`),fullPage:true});
      await page.getByRole('button',{name:'复制确切版本',exact:true}).click();
      await page.getByRole('button',{name:'查询原提交结果'}).waitFor();
      assert.ok(await page.getByLabel('目标工作区编号',{exact:true}).isDisabled());
      assert.equal(await page.getByRole('button',{name:'查询已有复制',exact:true}).count(),0);
      if(scenario==='key-conflict')assert.equal(await page.getByRole('button',{name:'按原内容重试'}).count(),0);
      else assert.equal(await page.getByRole('button',{name:'按原内容重试'}).count(),1);
      await page.getByRole('button',{name:'查询原提交结果'}).click();
    }
    await page.getByRole('heading',{name:'已查回原复制提交',exact:true}).waitFor();
    assert.equal(await page.getByRole('heading',{name:'确切版本已复制',exact:true}).count(),0);
    const copies=calls.filter(c=>c.path.includes('/prompt-workspaces/'));
    assert.deepEqual(copies.map(c=>c.method),scenario==='recovery-only'?['GET']:['POST','GET']);
    if(copies.length===2){assert.equal(copies[0].key,copies[1].key);assert.equal(copies[1].body,null);assert.deepEqual(JSON.parse(copies[0].body),{expectedGeneration:0,targetWorkspaceId,sourceRevisionId,sourceContentSha,sourceId:sourceWorkspaceId});}
    const fits=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth);assert.ok(fits,`${scenario} horizontal overflow at ${width}`);
    assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);assert.deepEqual(errors,[]);
    await page.screenshot({path:resolve(output,`copy-ui-${scenario}-receipt-${width}.png`),fullPage:true});
    observations.push({width,scenario,noHorizontalOverflow:fits,methods:copies.map(c=>c.method),originalKeyPreserved:true,noAutomaticTargetRead:true,noPersistentSubmission:true,pageErrors:errors});
    await context.close();
  }
  writeFileSync(resolve(output,'copy-ui-browser-result.json'),JSON.stringify({scope:'Browser component only, production UI with synthetic HTTP; no real identity, database or business acceptance',at:new Date().toISOString(),observations},null,2));
}finally{await browser?.close();await vite.close();}
