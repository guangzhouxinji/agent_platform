import {chromium} from 'playwright';
import {writeFile} from 'node:fs/promises';
const base='http://127.0.0.1:5187';
const sha=c=>c.repeat(64), dir=new URL('.',import.meta.url);
const summary={taskId:'task',comparisonId:'current',inputSha:sha('a'),state:'IN_PROGRESS',confirmedFeatures:[{featureId:'F1',userText:'确认输入特征：连接结构'}],candidates:[{candidateId:sha('b'),status:'QUEUED',attemptId:null,errorCode:null,executionState:null,reportIds:[]}]};
const key={candidateId:sha('b'),featureId:'F1',claimBranch:[1,2]};
const value={taskId:'task',comparisonId:'current',inputSha:sha('a'),previousComparisonId:'parent',parentInputSha:sha('c'),configurationVersion:'configuration-v1',configurationSha:sha('d'),expiresAt:new Date(Date.now()+600000).toISOString(),expectedRows:[key],invalidated:[],recompute:[],carried:[{key,comparisonId:'parent',inputSha:sha('c'),reportId:'parent-report',reportSha:sha('e'),rowSha:sha('f')}]};
const browser=await chromium.launch({headless:true});
const results=[];
try {
 for(const viewport of [{width:1280,height:900},{width:390,height:844}]){
  const page=await browser.newPage({viewport});const errors=[],requests=[];
  page.on('pageerror',e=>{errors.push(e.message);console.error(e.message)});
  page.on('console',m=>{if(m.type()==='error')console.error(m.text())});
  await page.route('**/v2/**',async route=>{requests.push({url:route.request().url(),method:route.request().method()});if(route.request().method()!=='GET')throw new Error('Unexpected product write');await route.fulfill({json:route.request().url().endsWith('/lineage')?value:summary});});
await page.route(base+'/app/projection-check',route=>route.fulfill({contentType:'text/html',body:`<!doctype html><html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/app/src/features/consultation/product/consultation.css"></head><body><main class="consultation-product"><div id="root"></div></main><script type="module" src="/app/@vite/client"></script><script type="module">import RefreshRuntime from '/app/@react-refresh';RefreshRuntime.injectIntoGlobalHook(window);window.$RefreshReg$=()=>{};window.$RefreshSig$=()=>type=>type;window.__vite_plugin_react_preamble_installed__=true;const React=await import('/app/node_modules/.vite/deps/react.js');const DOM=await import('/app/node_modules/.vite/deps/react-dom_client.js');const C=await import('/app/src/features/consultation/product/ComparisonMatrixResults.tsx');DOM.default.createRoot(document.getElementById('root')).render(React.default.createElement(C.default,{token:'controlled-browser-session',taskId:'task',disabled:false,onDenied:()=>{},defaultComparisonId:'current',defaultInputSha:'${summary.inputSha}'}));</script></body></html>`}));
  await page.goto(base+'/app/projection-check');
  await page.getByRole('button',{name:'读取对照结果'}).click({timeout:10000});await page.getByText(summary.confirmedFeatures[0].userText,{exact:true}).waitFor();
  if(requests.length!==1)throw new Error('Unexpected automatic lineage request');
  await page.getByRole('button',{name:'读取沿用关系'}).click();await page.getByText('沿用参考 1 · 已失效 0 · 待重新计算 0',{exact:true}).waitFor();
  await page.getByText('沿用参考',{exact:true}).click();await page.getByText('原报告引用',{exact:true}).click();await page.getByText('版本依据',{exact:true}).click();
  const layout=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth,buttonRects:[...document.querySelectorAll('button')].map(b=>({x:b.getBoundingClientRect().x,right:b.getBoundingClientRect().right,height:b.getBoundingClientRect().height}))}));
  if(layout.scroll>layout.width||layout.buttonRects.some(r=>r.x<0||r.right>layout.width))throw new Error('Horizontal overflow');
  if(errors.length)throw new Error(errors.join('\n'));
  await page.screenshot({path:new URL(`projection-${viewport.width}.png`,dir).pathname.replace(/^\/([A-Z]:)/,'$1'),fullPage:true});
  results.push({viewport,layout,requests,errors,scope:'CONTROLLED_TRANSPORT_NO_IDENTITY_DB_MODEL_OR_DEPLOYMENT'});await page.close();
 }
 await writeFile(new URL('projection-browser-result.json',dir),JSON.stringify({results},null,2));
} finally {await browser.close();}
