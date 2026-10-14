import {chromium} from 'playwright';
import {createHash} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
const base='http://127.0.0.1:5187',dir=new URL('.',import.meta.url),hash=c=>c.repeat(64);
const canonical=v=>Array.isArray(v)?v.map(canonical):v&&typeof v==='object'?Object.fromEntries(Object.entries(v).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([k,x])=>[k,canonical(x)])):v;
const digest=v=>createHash('sha256').update(v).digest('hex'),expiry=new Date(Date.now()+600000).toISOString(),candidate=hash('b');
const row={candidateId:candidate,featureId:'F1',claimBranch:[1],relation:'UNKNOWN',sameAspects:[],differences:[],reasoning:'父版原矩阵分析理由',uncertainty:'尚缺原文依据',analyzedRange:[],citations:[]};
const result={taskId:'task',comparisonId:'parent',candidateId:candidate,inputSha:hash('c'),attemptId:'attempt',token:1,reportId:'report',resultVersion:1,methodVersion:'PATENT_FEATURE_MATRIX_V1',workerSubject:'worker',configurationVersion:'old-config',approvalSha:hash('d'),responseSha:hash('e'),createdAt:new Date(Date.now()-60000).toISOString(),expiresAt:expiry,rows:[row]};
const originalJson=' \n'+JSON.stringify(result)+'\n',reportSha=digest(originalJson);
const report={result,reportSha,receipt:{taskId:'task',comparisonId:'parent',candidateId:candidate,inputSha:hash('c'),attemptId:'attempt',token:1,reportId:'report',reportSha,resultVersion:1,status:'COMMITTED'}};
const key={candidateId:candidate,featureId:'F1',claimBranch:[1]};
const lineage={taskId:'task',comparisonId:'current',inputSha:hash('a'),previousComparisonId:'parent',parentInputSha:hash('c'),configurationVersion:'new-config',configurationSha:hash('f'),expiresAt:expiry,expectedRows:[key],invalidated:[],recompute:[],carried:[{key,comparisonId:'parent',inputSha:hash('c'),reportId:'report',reportSha,rowSha:digest(JSON.stringify(canonical(row)))}]};
const summary={taskId:'task',comparisonId:'current',inputSha:hash('a'),state:'IN_PROGRESS',confirmedFeatures:[{featureId:'F1',userText:'当前确认特征'}],candidates:[{candidateId:candidate,status:'QUEUED',attemptId:null,errorCode:null,executionState:null,reportIds:[]}]};
const value={taskId:'task',comparisonId:'current',inputSha:hash('a'),previousComparisonId:'parent',parentInputSha:hash('c'),expiresAt:expiry,confirmedFeatures:[{featureId:'F1',userText:'父版确认特征'}],report,originalJson};
const browser=await chromium.launch({headless:true}),results=[];
try{
 for(const viewport of [{width:1280,height:900},{width:390,height:844}]){
  const page=await browser.newPage({viewport}),errors=[],requests=[];let completed=false;page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/v2/**',async route=>{const req=route.request();requests.push({url:req.url(),method:req.method()});if(req.method()!=='GET')throw new Error('Unexpected write');
   if(req.url().endsWith('/results'))await route.fulfill({json:completed?{...summary,state:'COMMITTED',candidates:[{...summary.candidates[0],status:'COMMITTED',attemptId:'new-attempt',executionState:'COMMITTED',reportIds:['new-report']}]}:summary});else if(req.url().endsWith('/lineage'))await route.fulfill({json:completed?{...lineage,carried:[],invalidated:[key],recompute:[key]}:lineage});else if(req.url().endsWith('/current/parent-reports/report'))await route.fulfill({json:value});else throw new Error('Unbound request');});
  await page.route(base+'/app/parent-matrix-check',route=>route.fulfill({contentType:'text/html',body:`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/app/src/features/consultation/product/consultation.css"></head><body><main class="consultation-product"><div id="root"></div></main><script type="module" src="/app/@vite/client"></script><script type="module">import RefreshRuntime from '/app/@react-refresh';RefreshRuntime.injectIntoGlobalHook(window);window.$RefreshReg$=()=>{};window.$RefreshSig$=()=>type=>type;window.__vite_plugin_react_preamble_installed__=true;const React=await import('/app/node_modules/.vite/deps/react.js');const DOM=await import('/app/node_modules/.vite/deps/react-dom_client.js');const C=await import('/app/src/features/consultation/product/ComparisonMatrixResults.tsx');DOM.default.createRoot(document.getElementById('root')).render(React.default.createElement(C.default,{token:'controlled-session',taskId:'task',defaultComparisonId:'current',defaultInputSha:'${hash('a')}',disabled:false,onDenied:()=>{}}));</script></body></html>`}));
  await page.goto(base+'/app/parent-matrix-check');await page.getByRole('button',{name:'读取对照结果'}).waitFor();if(requests.length)throw new Error('Automatic read');
  await page.getByRole('button',{name:'读取对照结果'}).click();await page.getByRole('button',{name:'读取沿用关系'}).click();await page.getByRole('button',{name:'查看沿用原矩阵 1'}).click();await page.getByText('父版原矩阵分析理由',{exact:true}).waitFor();
  await page.getByRole('heading',{name:'父版本矩阵参考 1'}).waitFor();await page.getByText('尚未全部提交',{exact:true}).waitFor();
  const layout=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,controls:[...document.querySelectorAll('button')].map(c=>({left:c.getBoundingClientRect().left,right:c.getBoundingClientRect().right}))}));
  if(requests.length!==3||errors.length||layout.scroll>layout.width||layout.controls.some(c=>c.left<0||c.right>layout.width))throw new Error(JSON.stringify({requests,errors,layout}));
  await page.screenshot({path:new URL(`parent-matrix-${viewport.width}.png`,dir).pathname.replace(/^\/([A-Z]:)/,'$1'),fullPage:true});
  await page.getByRole('button',{name:'关闭父矩阵'}).click();if(await page.getByText('父版原矩阵分析理由',{exact:true}).count())throw new Error('Close retained content');
  completed=true;await page.getByRole('button',{name:'读取对照结果'}).click();await page.getByRole('button',{name:'读取沿用关系'}).click();await page.getByText('本版已提交（父版差异）',{exact:true}).waitFor();await page.getByText('沿用参考 0 · 已失效 1 · 待重新计算 0',{exact:true}).waitFor();
  if(requests.length!==5||errors.length)throw new Error('Integration failed');
  results.push({viewport,requests,errors,layout,scope:'CONTROLLED_TRANSPORT_NOT_REAL_IDENTITY_DM8_TLS_MODEL_OR_ACCEPTANCE'});await page.close();
 }
 await writeFile(new URL('parent-matrix-browser-result.json',dir),JSON.stringify({results},null,2));
}finally{await browser.close();}
