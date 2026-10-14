import { chromium } from "playwright";
import { mkdir, readFile, writeFile, access } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const required=n=>{if(!process.env[n])throw new Error(n+" required; no skip");return process.env[n];};
const out=required("ENF_PENALTY_BROWSER_EVIDENCE"),gate=required("ENF_PENALTY_REVOCATION_GATE");await mkdir(out,{recursive:false});
const inventory=JSON.parse(await readFile(required("ENF_PENALTY_SOURCE_INVENTORY"),"utf8"));const id=required("ENF_PENALTY_RULE_ID");
const seed=inventory.records.find(r=>r.row.RESOURCE_ID===id&&r.row.REVISION===1);assert.ok(seed);
const receipt=inventory.receipts.find(r=>r.RESOURCE_ID===id&&r.REVISION===1);assert.ok(receipt);
const browser=await chromium.launch({headless:true});const records=[],pending=new Set(),sessions=[];
async function login(tag,port){
 const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1100},reducedMotion:"reduce"});const page=await context.newPage();let authorization;
 page.on("request",r=>{if(new URL(r.url()).pathname.startsWith("/v2/enforcement/penalty"))authorization=r.headers().authorization||authorization;});
 page.on("response",r=>{if(!new URL(r.url()).pathname.startsWith("/v2/enforcement/penalty"))return;const p=(async()=>records.push({tag,path:new URL(r.url()).pathname,status:r.status(),at:new Date().toISOString(),body:await r.json().catch(()=>null)}))();pending.add(p);p.finally(()=>pending.delete(p));});
 await page.goto("https://127.0.0.1:"+port+"/app/enforcement/penalty");await page.getByRole("button",{name:/^登\s*录$/}).click();await page.locator('input[name="username"]').fill(required("ENF_FOLLOWUP_LOGIN"));await page.locator('input[name="password"]').fill(required("ENF_FOLLOWUP_PASSWORD"));await page.locator('button[type="submit"]').click();
 await page.getByRole("heading",{name:"维护草稿"}).waitFor();await page.getByRole("button",{name:"刷新当前列表",exact:true}).click();await page.locator('main.enf-next[aria-busy="false"]').waitFor();
 await page.getByRole("row").filter({hasText:required("ENF_PENALTY_CURRENT_RULE_LABEL")}).getByRole("button",{name:"打开规则",exact:true}).click();await page.getByText(id+" v"+required("ENF_PENALTY_CURRENT_RULE_REVISION"),{exact:true}).waitFor();
 await page.locator("#penalty-trial-amounts").fill(JSON.stringify({B:{value:12,confirmed:true,sourceRef:"PROJECT-EXPLICIT-SCORE-INPUT",sourceVersion:"1",locator:"symbolic project parameter, not statutory amount"}}));
 async function trial(status){const response=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith("/trial"));const [r]=await Promise.all([response,page.getByRole("button",{name:"无副作用试算",exact:true}).click()]);assert.equal(r.status(),status);await page.locator('main.enf-next[aria-busy="false"]').waitFor();return r.json().catch(()=>null);}
 assert.equal(Number((await trial(200)).amount),24);return {tag,page,trial,authorization:()=>authorization};
}
try {
 sessions.push(await login("SOURCE",Number(required("ENF_PENALTY_SOURCE_FRONTEND_PORT"))));sessions.push(await login("OLD_PHYSICAL_RESTORE",Number(required("ENF_PENALTY_RESTORE_FRONTEND_PORT"))));
 await writeFile(gate+".sessions-ready","actual source and restored product PKCE sessions ready");
 const deadline=Date.now()+240000;while(true){try{await access(gate+".revoked");break;}catch{}if(Date.now()>deadline)throw new Error("Actual revocation gate timeout; not passed");await new Promise(r=>setTimeout(r,250));}
 const source=await sessions[0].trial(403);await sessions[0].page.getByRole("button",{name:/^登\s*录$/}).waitFor();assert.equal(await sessions[0].page.getByText(id+" v"+required("ENF_PENALTY_CURRENT_RULE_REVISION"),{exact:true}).count(),0);
 await sessions[0].page.screenshot({path:resolve(out,"01-source-revoked-403-cleared.png"),fullPage:true});
 const restored=await sessions[1].trial(503);await sessions[1].page.screenshot({path:resolve(out,"02-old-restore-authority-mismatch-503.png"),fullPage:true});
 const replays=[];for(const [session,status] of [[sessions[0],403],[sessions[1],503]]){
  const result=await session.page.evaluate(async input=>{const r=await fetch("/v2/enforcement/penalty/rules",{method:"POST",headers:{Authorization:input.authorization,"Content-Type":"application/json","Idempotency-Key":input.key},body:JSON.stringify(input.body)});return {status:r.status,body:await r.json().catch(()=>null)};},{authorization:session.authorization(),key:receipt.REQUEST_KEY,body:{expectedRevision:0,rule:JSON.parse(seed.row.PAYLOAD)}});
  assert.equal(result.status,status);replays.push({tag:session.tag,scope:"ACTUAL_HTTP_REPLAY_NOT_BROWSER_CLICK",...result});
 }
 await Promise.all(pending);await writeFile(resolve(out,"result.json"),JSON.stringify({scope:"REAL_SOURCE_AND_OLD_RESTORE_REVOCATION_HTTP_BROWSER_NOT_FINAL_ACCEPTANCE",runId:required("ENF_PENALTY_RUN_ID"),ruleId:id,sourceTrial:403,restoredTrial:503,sourceSessionCleared:true,replays,source,restored,records},null,2));
}catch(e){await Promise.all(pending);for(const s of sessions)await s.page.screenshot({path:resolve(out,"failure-"+s.tag+".png"),fullPage:true}).catch(()=>{});await writeFile(resolve(out,"failure.json"),JSON.stringify({passed:false,error:String(e).split("\n")[0],records},null,2));throw new Error("Actual penalty revocation browser failed; evidence retained");}
finally{await browser.close();}
