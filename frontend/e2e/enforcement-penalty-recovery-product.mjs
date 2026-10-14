import { chromium } from "playwright";
import { mkdir, readFile, writeFile, rename } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const required=n=>{if(!process.env[n])throw new Error(n+" required; no skip");return process.env[n];};
const out=required("ENF_PENALTY_BROWSER_EVIDENCE");await mkdir(out,{recursive:false});
const id=required("ENF_PENALTY_RULE_ID"),file=required("ENF_PENALTY_RECOVERY_ORIGINAL");
const records=[],pending=new Set();const browser=await chromium.launch({headless:true});
const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1100},reducedMotion:"reduce"});const page=await context.newPage();
page.on("response",r=>{if(!new URL(r.url()).pathname.startsWith("/v2/enforcement/penalty"))return;const p=(async()=>{records.push({path:new URL(r.url()).pathname,status:r.status(),method:r.request().method(),at:new Date().toISOString(),body:await r.json().catch(()=>null)});})();pending.add(p);p.finally(()=>pending.delete(p));});
const idle=()=>page.locator('main.enf-next[aria-busy="false"]').waitFor();
async function trial(status){await idle();const response=page.waitForResponse(r=>new URL(r.url()).pathname.endsWith("/trial"));const [actual]=await Promise.all([response,page.getByRole("button",{name:"无副作用试算",exact:true}).click()]);assert.equal(actual.status(),status);await idle();return actual.json().catch(()=>null);}
let original;
try {
 await page.goto("https://127.0.0.1:5179/app/enforcement/penalty");await page.getByRole("button",{name:/^登\s*录$/}).click();
 await page.locator('input[name="username"]').fill(required("ENF_FOLLOWUP_LOGIN"));await page.locator('input[name="password"]').fill(required("ENF_FOLLOWUP_PASSWORD"));await page.locator('button[type="submit"]').click();
 await page.getByRole("heading",{name:"维护草稿"}).waitFor();await page.getByRole("button",{name:"刷新当前列表",exact:true}).click();await idle();
 // Exact ID is used, never a static screenshot or a hardcoded rendered row.
 const row=page.getByRole("row").filter({hasText:"Explicit technical score trial, not penalty standard"});await row.getByRole("button",{name:"打开规则",exact:true}).click();await idle();await page.getByText(id+" v1",{exact:true}).waitFor();
 await page.locator("#penalty-trial-amounts").fill(JSON.stringify({B:{value:12,confirmed:true,sourceRef:"PROJECT-EXPLICIT-SCORE-INPUT",sourceVersion:"1",locator:"symbolic project parameter, not statutory amount"}}));
 const normal=await trial(200);assert.equal(Number(normal.amount),24);await page.screenshot({path:resolve(out,"01-physical-restore-real-page.png"),fullPage:true});
 original=await readFile(file);await rename(file,file+".fault-held");
 await trial(503);await page.screenshot({path:resolve(out,"02-restored-original-missing-fail-closed.png"),fullPage:true});
 await rename(file+".fault-held",file);await writeFile(file,Buffer.from("deliberately corrupted restored original for fault injection"));
 await trial(503);await page.screenshot({path:resolve(out,"02-restored-original-sha-fail-closed.png"),fullPage:true});
 await writeFile(file,original);const repaired=await trial(200);assert.deepEqual(repaired,normal);
 await page.screenshot({path:resolve(out,"03-byte-repair-restored-runtime-usable.png"),fullPage:true});
 await Promise.all(pending);await writeFile(resolve(out,"result.json"),JSON.stringify({scope:"REAL_PHYSICAL_RESTORE_BROWSER_PARTIAL_NOT_FINAL_ACCEPTANCE",runId:required("ENF_PENALTY_RUN_ID"),ruleId:id,passed:true,inputNature:"FUNCTIONAL_VALIDATION",normal,repaired,missingOriginalFailure:503,shaFailure:503,records},null,2));
}catch(e){await page.screenshot({path:resolve(out,"failure.png"),fullPage:true}).catch(()=>{});await Promise.all(pending);await writeFile(resolve(out,"failure.json"),JSON.stringify({passed:false,error:String(e).split("\n")[0],records},null,2));throw new Error("Actual penalty recovery browser failed; inspect retained evidence");}
finally{if(original){await rename(file+".fault-held",file).catch(()=>{});await writeFile(file,original);}await browser.close();}
