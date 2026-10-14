import assert from 'node:assert/strict';
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {chromium} from '@playwright/test';
import {createServer} from '../../../frontend/node_modules/vite/dist/node/index.js';

// Actual production UI with owned synthetic HTTP values, not admitted business-flow evidence.
process.env.VITE_CONSULTATION_CLIENT_ID='prompt-browser-component';
const output=resolve('docs/evidence/consultation-delivery-20261014');
const workspaceId='11111111-1111-4111-8111-111111111111',promptId='33333333-3333-4333-8333-333333333333';
const revisionId='44444444-4444-4444-8444-444444444444',contentSha='a'.repeat(64),sourceId='22222222-2222-4222-8222-222222222222';
const vite=await createServer({root:resolve('frontend'),configFile:resolve('frontend/vite.consultation.config.ts'),server:{host:'127.0.0.1',port:0,hmr:false,watch:{ignored:['**/dist/**','**/dist-consultation/**']}}});
let browser;
try{
  await vite.listen();const port=vite.httpServer.address().port;browser=await chromium.launch();const observations=[];
  for(const width of [1280,390])for(const scenario of ['exact-numeric','diagnostic','denied']){
    const context=await browser.newContext({viewport:{width,height:900},reducedMotion:'reduce'});
    await context.addInitScript(destination=>sessionStorage.setItem('consultation-pkce',JSON.stringify({verifier:'component-verifier',state:'component-state',taskId:'',destination})),{view:'prompt',promptId,revisionId});
    const page=await context.newPage(),calls=[],errors=[];page.on('pageerror',error=>errors.push(error.message));
    const submittedAt=new Date(Date.now()-1000).toISOString(),until=new Date(Date.now()+3600000).toISOString();
    await page.route('**/oauth2/token',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({access_token:'synthetic-component-session'})}));
    await page.route('**/v2/**',async route=>{
      const request=route.request(),url=new URL(request.url());calls.push({method:request.method(),path:url.pathname,body:request.postData()});
      let response,status=200;
      if(url.pathname===`/v2/consultation/prompts/${promptId}/revisions`)
        response={promptId,page:1,size:20,total:1,revisions:[{revisionId,version:1,submittedAt,bodyUntil:until}]};
      else if(url.pathname===`/v2/consultation/prompts/${promptId}/revisions/${revisionId}`)
        response={workspaceId,contentSha,promptId,revisionId,version:1,kind:'GUIDE',name:'变量预览组件检查',body:'数量：{{count}}',scenarioIds:[],intentIds:[],audienceRef:'internal',language:'zh-CN',displayOrder:0,
          variables:[{name:'count',type:'INTEGER',required:true,sourceRef:'approved-component-source',allowDefault:false,defaultValue:null,allowEmpty:false}],submittedAt,bodyUntil:until};
      else{
        assert.equal(url.pathname,`/v2/consultation/prompt-workspaces/${workspaceId}/prompts/${promptId}/revisions/${revisionId}/preview`);
        assert.equal(request.method(),'POST');assert.equal(url.search,'');assert.equal(request.headers()['idempotency-key'],undefined);
        const command=JSON.parse(request.postData());assert.equal(command.contentSha,contentSha);assert.equal(command.sourceId,sourceId);
        if(scenario==='denied'){status=403;response={code:'PROMPT_NOT_AVAILABLE'};}
        else if(scenario==='diagnostic'){status=400;response={code:'PROMPT_PREVIEW_INVALID_VALUES',expiresAt:until,issues:[{code:'MISSING_VARIABLE',variable:'count'}]};}
        else{
          assert.deepEqual(command.values,[{name:'count',type:'INTEGER',value:'9007199254740993'}]);
          response={status:'OK',mode:'TEST',workspaceId,promptId,revisionId,revisionNumber:'1',contentSha,schemaSha:'b'.repeat(64),policySha:'c'.repeat(64),expiresAt:new Date(Date.now()+600000).toISOString(),
            segments:[{kind:'LITERAL',text:'数量：'},{kind:'DATA',name:'count',type:'INTEGER',sourceEvidenceId:'owned-sample-evidence',value:'9007199254740993'}]};
        }
      }
      await route.fulfill({status,contentType:'application/json',body:JSON.stringify(response)});
    });
    await page.goto(`http://127.0.0.1:${port}/app/consultation.html?code=component-code&state=component-state`);
    await page.getByRole('heading',{name:'变量预览',exact:true}).waitFor();
    await page.getByLabel('样例来源编号',{exact:true}).fill(sourceId);
    if(scenario==='exact-numeric'){
      await page.getByLabel('提供 count 样例值',{exact:true}).check();
      await page.getByLabel('count 样例值',{exact:true}).fill('9007199254740993');
    }
    await page.getByRole('button',{name:'预览样例',exact:true}).click();
    if(scenario==='exact-numeric'){
      await page.getByRole('region',{name:'变量预览结果'}).waitFor();
      assert.match(await page.getByRole('region',{name:'变量预览结果'}).innerText(),/9007199254740993/);
    }else if(scenario==='diagnostic'){
      await page.getByRole('link',{name:'检查 count',exact:true}).click();
      assert.equal(await page.evaluate(()=>document.activeElement?.id),'prompt-preview-variable-0-provided');
      assert.equal(await page.getByRole('region',{name:'变量预览结果'}).count(),0);
    }else{
      await page.getByRole('alert').waitFor();
      assert.equal(await page.getByRole('heading',{name:'提示词正文',exact:true}).count(),0);
      assert.equal(await page.getByLabel('样例来源编号',{exact:true}).count(),0);
    }
    const fits=await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth);assert.ok(fits,`${scenario} overflow at ${width}`);
    await page.screenshot({path:resolve(output,`preview-ui-${scenario}-${width}.png`),fullPage:true});
    if(scenario==='exact-numeric'){
      await page.getByLabel('count 样例值',{exact:true}).fill('2');
      assert.equal(await page.getByRole('region',{name:'变量预览结果'}).count(),0);
    }
    assert.equal(calls.filter(c=>c.method==='POST').length,1);assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);assert.deepEqual(errors,[]);
    observations.push({width,scenario,noHorizontalOverflow:fits,posts:1,noAutomaticResubmit:true,noPersistentSample:true,pageErrors:errors});
    await context.close();
  }
  writeFileSync(resolve(output,'preview-ui-browser-result.json'),JSON.stringify({scope:'Production UI with synthetic HTTP: browser component only, no deployment or business acceptance',at:new Date().toISOString(),observations},null,2));
}finally{await browser?.close();await vite.close();}
