import {chromium} from 'playwright';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {X509Certificate} from 'node:crypto';
import tls from 'node:tls';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
const required=name=>{if(!process.env[name])throw Error(name+' required');return process.env[name];};
const origin=required('CON_WORKSPACE_ORIGIN'),task=required('CON_WORKSPACE_TASK'),out=required('CON_WORKSPACE_EVIDENCE');
const username=required('CON_WORKSPACE_MEMBER'),password=required('CON_WORKSPACE_PASSWORD');
const ca=new X509Certificate(await readFile(required('CON_WORKSPACE_CA'))).toString();
const endpoint=new URL(origin);
assert.equal(endpoint.protocol,'https:');assert.equal(endpoint.hostname,'localhost');
await new Promise((resolve,reject)=>{
  const socket=tls.connect({host:'127.0.0.1',port:Number(endpoint.port),servername:endpoint.hostname,ca,rejectUnauthorized:true},()=>{
    if(!socket.authorized){reject(Error('Project TLS trust validation failed'));return;}socket.end();resolve();
  });socket.setTimeout(10000,()=>{socket.destroy();reject(Error('Project TLS preflight timeout'));});socket.once('error',reject);
});
console.log('strict_project_tls=verified');
await mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true});
// This owned browser accepts the local private CA only after strict chain/hostname preflight above.
// No product TLS policy, auth endpoint, token, source or business behavior is substituted.
const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1050},reducedMotion:'reduce'});
const page=await context.newPage(),events=[],errors=[],pending=new Set();
page.on('pageerror',error=>errors.push(error.message));
page.on('response',response=>{
  const url=new URL(response.url());if(!url.pathname.startsWith('/v2/consultation/'))return;
  const item=(async()=>{
    const event={path:url.pathname,method:response.request().method(),status:response.status()};
    try{event.body=await response.json();}catch{}events.push(event);
  })();pending.add(item);item.finally(()=>pending.delete(item));
});
const idle=()=>page.getByRole('button',{name:'请模型理解／继续补问',exact:true}).waitFor({state:'visible',timeout:30000});
async function action(name,suffix){
  const response=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith(suffix)&&r.request().method()==='POST');
  await page.getByRole('button',{name,exact:true}).click();const actual=await response;assert.equal(actual.status(),200,suffix);
  return actual.json();
}
const began=performance.now();let result;
try{
  await page.goto(origin+'/app/consultation?task='+encodeURIComponent(task));
  console.log('final_page=loaded');
  await page.getByRole('button',{name:'登录并打开任务',exact:true}).click();
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await idle();await page.getByText('核对并确认检索条件',{exact:true}).waitFor();
  console.log('formal_oauth=completed');
  await page.getByLabel('公开非保密检索目标／补充条件',{exact:true}).fill('Find public US patent candidates on vacuum brazing rotor blades. Search titles, all applications, no dates or classifications. Use retrieval guidance only; no legal conclusions.');
  const proposal=await action('请模型理解／继续补问','/dialogue/proposals');
  assert.equal(proposal.modelId,'deepseek-flash');assert.ok(proposal.output.query);
  await page.getByLabel('查询表达式（可修改）',{exact:true}).fill(proposal.output.query);
  const goal=await action('确认条件并开始同任务新一轮','/goals/confirm');
  result=await action('执行已确认的本轮检索','/retrievals/execute');
  assert.ok(result.members.length>0);assert.equal(result.candidateCount,40);
  const explanation=await action('依据实际结果生成说明','/dialogue/explanations');
  assert.equal(explanation.modelId,'deepseek-flash');assert.ok(explanation.citations.length>0);
  assert.equal(explanation.resultSetId,result.resultSetId);
  for(const citation of explanation.citations){
    const member=result.members.find(row=>row.memberId===citation.memberId);assert.ok(member);
    assert.equal(citation.documentVersion,member.documentVersion);
    assert.ok((citation.fieldLocators?.TITLE??citation.locatorRef).includes('utf8='));
  }
  await page.locator('.consultation-result-list input[type="checkbox"]').first().check();
  const selection=await action('保存所选文献（1）','/selections');assert.equal(selection.members.length,1);
  await page.screenshot({path:resolve(out,'workspace-desktop.png'),fullPage:true});
  await page.setViewportSize({width:375,height:812});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await page.screenshot({path:resolve(out,'workspace-mobile.png'),fullPage:true});
  await page.reload();await page.getByRole('button',{name:'登录并打开任务',exact:true}).waitFor();
  await page.getByRole('button',{name:'登录并打开任务',exact:true}).click();
  // The retained browser login cookie may permit OAuth immediately; it never retains an Access Token in storage.
  await idle();await page.getByRole('heading',{name:'真实检索结果',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>!!localStorage.getItem('access_token')||!!sessionStorage.getItem('access_token')),false);
  await Promise.all([...pending]);assert.deepEqual(errors,[]);
  const record={verifiedAt:new Date().toISOString(),taskId:task,taskVersion:goal.taskVersion,
    resultSetId:result.resultSetId,candidateCount:result.candidateCount,modelId:explanation.modelId,
    citations:explanation.citations.length,selectionId:selection.selectionId,selectionRevision:selection.revision,
    automatedElapsedMs:performance.now()-began,humanElapsedMs:null,humanCostMeasurement:'NOT_OBSERVED',
    strictProjectTlsPreflight:true,browserLocalCertificateErrorsIgnored:true,
    scope:'ACTUAL_PRODUCT_UI_OAUTH_GOAL_WORKER_RESULT_MODEL_SELECTION_AND_RELOGIN_NOT_BUSINESS_ACCEPTANCE',passed:true};
  await writeFile(resolve(out,'result.json'),JSON.stringify(record,null,2));
  console.log('actual_product_workspace='+JSON.stringify(record));
}finally{
  await Promise.all([...pending]);await writeFile(resolve(out,'http-records.json'),JSON.stringify(events,null,2));
  await writeFile(resolve(out,'page-errors.json'),JSON.stringify(errors,null,2));await browser.close();
}
