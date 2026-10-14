import {chromium} from 'playwright';
import {writeFile} from 'node:fs/promises';
const base='http://127.0.0.1:5187',sha=c=>c.repeat(64),dir=new URL('.',import.meta.url),a=sha('a'),b=sha('b'),inputSha=sha('c'),configurationSha=sha('d');
const input={taskId:'task',comparisonId:'current',inputSha,taskVersion:2,featureRevisionId:'features',featureRevisionNumber:2,packageSha:sha('f'),purpose:'TECHNICAL_SIMILARITY',methodVersion:'PATENT_FEATURE_MATRIX_V1',expiresAt:new Date(Date.now()+600000).toISOString(),candidates:[{candidateId:a,status:'QUEUED'},{candidateId:b,status:'QUEUED'}]};
const summary={taskId:'task',comparisonId:'current',inputSha,state:'IN_PROGRESS',confirmedFeatures:[{featureId:'F1',userText:'连接结构'}],candidates:input.candidates.map(c=>({...c,attemptId:null,errorCode:null,executionState:null,reportIds:[]}))};
const rowA={candidateId:a,claimBranch:[1],featureId:'F1'},rowB={candidateId:b,claimBranch:[1],featureId:'F1'};
const lineage={taskId:'task',comparisonId:'current',inputSha,previousComparisonId:'parent',parentInputSha:sha('e'),configurationVersion:'v1',configurationSha,expiresAt:input.expiresAt,expectedRows:[rowA,rowB],invalidated:[rowB],recompute:[rowB],carried:[{key:rowA,comparisonId:'parent',inputSha:sha('e'),reportId:'old',reportSha:sha('f'),rowSha:sha('f')}]};
const browser=await chromium.launch({headless:true}),results=[];
try{
 for(const viewport of [{width:1280,height:900},{width:390,height:844}]){
  const page=await browser.newPage({viewport}),errors=[],requests=[];let key;
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/v2/**',async route=>{
   const req=route.request(),url=req.url(),method=req.method();requests.push({url,method});
   if(method==='POST'){
    if(!url.endsWith('/candidates/'+b+'/start-commands/recompute')||JSON.stringify(req.postDataJSON())!==JSON.stringify({expectedTaskVersion:2,inputSha,configurationSha}))throw new Error('Unbound recompute POST');
    key=req.headers()['idempotency-key'];await route.abort('failed');return;
   }
   if(method!=='GET')throw new Error('Unexpected method');
   const data=url.endsWith('/results')?summary:url.endsWith('/lineage')?lineage:url.includes('/latest?')?{receipt:null}:{commandId:'command',taskId:'task',comparisonId:'current',candidateId:b,inputSha,requestId:key,attemptId:null,status:'RECONCILE_REQUIRED',errorCode:null,expiresAt:input.expiresAt};
   await route.fulfill({json:data});
  });
await page.route(base+'/app/recompute-check',route=>route.fulfill({contentType:'text/html',body:`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/app/src/features/consultation/product/consultation.css"></head><body><main class="consultation-product"><section class="consultation-panel"><h2>纠正后的候选计算</h2><div id="root"></div></section></main><script type="module" src="/app/@vite/client"></script><script type="module">import RefreshRuntime from '/app/@react-refresh';RefreshRuntime.injectIntoGlobalHook(window);window.$RefreshReg$=()=>{};window.$RefreshSig$=()=>type=>type;window.__vite_plugin_react_preamble_installed__=true;const React=await import('/app/node_modules/.vite/deps/react.js');const DOM=await import('/app/node_modules/.vite/deps/react-dom_client.js');const C=await import('/app/src/features/consultation/product/ComparisonStartOptions.tsx');DOM.default.createRoot(document.getElementById('root')).render(React.default.createElement(C.default,{token:'controlled-session',input:${JSON.stringify(input)},disabled:false,onDenied:()=>{},onResult:()=>{}}));</script></body></html>`}));
  await page.goto(base+'/app/recompute-check');await page.getByRole('radio',{name:'受影响候选'}).check();
  if(requests.length)throw new Error('Automatic fetch');
  await page.getByRole('button',{name:'读取受影响候选'}).click();await page.getByLabel('计算候选').waitFor();
  const options=await page.getByLabel('计算候选').locator('option').allTextContents();if(options.length!==2||options[1]!=='候选 2')throw new Error('Carried candidate offered for recompute');
  await page.getByLabel('计算候选').selectOption(b);await page.getByRole('button',{name:'明确发起此候选计算'}).click();await page.getByRole('alert').waitFor();
  await page.getByRole('button',{name:'查询原启动回执'}).click();await page.getByText('处理结果尚未确认，请查询原启动回执',{exact:true}).waitFor();
  await page.waitForFunction(()=>[...document.querySelectorAll('button')].some(b=>b.textContent==='查询原启动回执'&&!b.disabled));
  if(requests.filter(r=>r.method==='POST').length!==1||!requests.at(-1).url.endsWith('/start-commands?requestId='+key))throw new Error('Blind redispatch or wrong reconciliation root');
  const layout=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,controls:[...document.querySelectorAll('button,select')].map(c=>({x:c.getBoundingClientRect().x,right:c.getBoundingClientRect().right,height:c.getBoundingClientRect().height}))}));
  if(layout.scroll>layout.width||layout.controls.some(c=>c.x<0||c.right>layout.width))throw new Error('Horizontal overflow');if(errors.length)throw new Error(errors.join('\n'));
  await page.screenshot({path:new URL(`recompute-${viewport.width}.png`,dir).pathname.replace(/^\/([A-Z]:)/,'$1'),fullPage:true});
  results.push({viewport,options,requests,layout,errors,scope:'CONTROLLED_TRANSPORT_NO_AUTHORITY_DB_TLS_MODEL_OR_DEPLOYMENT'});await page.close();
 }
 await writeFile(new URL('recompute-browser-result.json',dir),JSON.stringify({results},null,2));
}finally{await browser.close();}
