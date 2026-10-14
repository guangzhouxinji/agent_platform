import { chromium,request as playwrightRequest } from "playwright";
import { readFile,writeFile,mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import assert from "node:assert/strict";
const required=n=>{if(!process.env[n])throw Error(n+" required; no skip");return process.env[n]};
const input=JSON.parse(await readFile(required("ADJ_BROWSER_INPUT"),"utf8")),out=required("ADJ_BROWSER_EVIDENCE");
const recovery=process.env.ADJ_BROWSER_RECOVERY==="true";
const {pack,origin,contextInput,contextOriginal,citation}=input;const id=pack.caseInput.id,base="/v2/adjudication/case-works/"+id;
const browser=await chromium.launch({headless:true});const contexts=[],pages=[],events=[],pageErrors=[];const pending=new Set();let serial=Promise.resolve();
async function setup(){const context=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:1050},reducedMotion:"reduce"});contexts.push(context);const page=await context.newPage();observe(page);return page;}
function observe(page){pages.push(page);page.on("pageerror",e=>pageErrors.push(e.message));page.on("response",res=>{const url=new URL(res.url());if(!url.pathname.startsWith("/v2/adjudication/")&&!url.pathname.startsWith("/oauth2/token"))return;const task=(async()=>{const event={path:url.pathname,method:res.request().method(),status:res.status()};if(url.pathname.startsWith("/v2/adjudication/")){try{event.body=await res.json()}catch{}}events.push(event);serial=serial.then(()=>writeFile(resolve(out,"http-records.json"),JSON.stringify(events,null,2)));await serial;})();pending.add(task);task.finally(()=>pending.delete(task));});}
const idle=async p=>p.locator('main.adj-workspace[aria-busy="false"]').waitFor({timeout:40000});
const text=async(p,value)=>p.getByText(value,{exact:false}).first().waitFor();
async function login(p,subject){await p.goto(origin+"/app/adjudication?case="+id);await p.getByRole("button",{name:"可信身份登录",exact:true}).click();if(await p.locator('input[name="username"]').isVisible({timeout:5000}).catch(()=>false)){await p.locator('input[name="username"]').fill(subject);await p.locator('input[name="password"]').fill(required("ADJ_BROWSER_PASSWORD"));await p.locator('button[type="submit"]').click();}await p.getByRole("button",{name:"锁定工作台",exact:true}).waitFor({timeout:20000});await idle(p);}
async function assertProduct(p){const body=await p.locator("main.adj-workspace").innerText();assert.ok(!/非现实案件|非真实专利|非正式法律依据|工程最小输入包|原创工程输入|非正式裁决|Demo/.test(body),"Product UI must not classify itself as a demo");}
async function tab(p,name){await idle(p);await assertProduct(p);await p.getByRole("navigation",{name:"案件工作区"}).getByRole("button",{name,exact:true}).click();}
async function result(p,name,path,status=200){await idle(p);const response=p.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()==="POST");const [r]=await Promise.all([response,p.getByRole("button",{name,exact:true}).click()]);assert.equal(r.status(),status,await r.text());await idle(p);return r.json();}
async function refresh(p){await idle(p);await p.getByRole("button",{name:"进入 / 刷新案件",exact:true}).click();await idle(p);}
async function fill(form,name,value){await form.getByLabel(name,{exact:true}).fill(value??"");}
async function fillSource(form,s){for(const [label,key] of [["来源ID","id"],["来源版本","version"],["原文定位","locator"]])await fill(form,label,s[key]);await form.getByLabel("来源种类",{exact:true}).selectOption(s.kind);await form.getByRole("button",{name:"计算此原文摘要",exact:true}).click();await form.getByLabel("原文SHA-256",{exact:true}).waitFor();await assertEventually(async()=>assert.equal(await form.getByLabel("原文SHA-256",{exact:true}).inputValue(),s.sha256));}
async function assertEventually(check){let error;for(let i=0;i<30;i++){try{return await check()}catch(e){error=e;await new Promise(r=>setTimeout(r,100))}}throw error;}
async function fillCase(form,c,original,creating){if(creating)for(const [label,key] of [["案件ID","id"],["机构","organization"],["数据范围","dataScope"]])await fill(form,label,c[key]);for(const [label,key] of [["案件名称","name"],["申请方","applicant"],["答辩方","respondent"],["适用地域","region"],["程序背景","procedure"],["相关时点","relevantDate"]])await fill(form,label,c[key]);await fill(form,"争议事项（每行一项）",c.issues.join("\n"));await fill(form,"案件原文",original);await fillSource(form,c.source);}
async function material(p,m){await tab(p,"材料");const f=p.getByRole("form",{name:"材料登记"});for(const [label,key] of [["材料ID（新版使用新ID）","id"],["提交人","submitter"],["主张用途","assertedPurpose"],["材料原文","content"]])await fill(f,label,m[key]);await f.getByLabel("材料方别",{exact:true}).selectOption(m.side);if(m.supersedesMaterialId)await f.getByLabel("替换既有材料（可选）",{exact:true}).selectOption(m.supersedesMaterialId);await fillSource(f,m.source);return result(p,"登记材料",base+"/materials");}
async function claim(p,revision){await tab(p,"权项");const c=pack.claim,f=p.getByRole("form",{name:"权项确认"});for(const [label,key] of [["专利来源标识","patentReference"],["权项编号","claimNumber"],["权项原文","originalText"],["对照对象","selectedProduct"],["人工确认理由","confirmationReason"]])await fill(f,label,c[key]);await fill(f,"权项版本",String(revision));for(let i=0;i<c.elements.length;i++){if(i)await f.getByRole("button",{name:"添加必要要素",exact:true}).click();await fill(f,"要素ID "+(i+1),c.elements[i].id);await fill(f,"要素确切原文 "+(i+1),c.elements[i].exactText)}await fillSource(f,c.source);await result(p,"保存权项新版本",base+"/claims");}
async function finding(p,record,element,relation="SUPPORT",materialId=pack.materials[0].id){await tab(p,"特征");const f=p.getByRole("form",{name:"对应记录"});await fill(f,"对应记录ID",record);await f.getByLabel("必要要素",{exact:true}).selectOption(element.id);await f.getByLabel("关联材料",{exact:true}).selectOption(materialId);await f.getByLabel("对应关系",{exact:true}).selectOption(relation);await fill(f,"材料确切片段",element.exactText);await fill(f,"对应说明","根据材料确切片段记录技术特征对应");await result(p,"保存对应记录",base+"/findings");}
async function revise(p,revision,content){
 const f=p.getByRole("form",{name:"报告修订"});
 const original=await f.getByLabel("修订内容",{exact:true}).inputValue();assert.ok(original.length>0,"Selected original section is prefilled");
 await fill(f,"修订内容","尚未保存的案件章节修订");await fill(f,"修订原因","案件章节修订原因");
 await f.getByLabel("修订章节",{exact:true}).selectOption("analysis");
 assert.ok((await f.getByLabel("修订内容",{exact:true}).inputValue()).length>0,"Analysis original is prefilled");
 await fill(f,"修订内容","尚未保存的分析章节修订");await fill(f,"修订原因","分析章节修订原因");
 await f.getByLabel("修订章节",{exact:true}).selectOption("case");
 assert.equal(await f.getByLabel("修订内容",{exact:true}).inputValue(),"尚未保存的案件章节修订");
 assert.equal(await f.getByLabel("修订原因",{exact:true}).inputValue(),"案件章节修订原因");
 await f.getByLabel("修订章节",{exact:true}).selectOption("analysis");
 assert.equal(await f.getByLabel("修订内容",{exact:true}).inputValue(),"尚未保存的分析章节修订");
 p.once("dialog",dialog=>dialog.dismiss());await p.getByRole("button",{name:"材料",exact:true}).click();
 assert.equal(await f.getByLabel("修订内容",{exact:true}).inputValue(),"尚未保存的分析章节修订","Cancel navigation preserves the active edit");
 if(revision===2)await f.screenshot({path:resolve(out,"report-editor-draft.png")});
 p.once("dialog",dialog=>dialog.accept());await f.getByRole("button",{name:"放弃未保存修订",exact:true}).click();
 await f.getByLabel("修订章节",{exact:true}).selectOption("case");assert.equal(await f.getByLabel("修订内容",{exact:true}).inputValue(),original);
 await f.getByLabel("修订章节",{exact:true}).selectOption("analysis");await fill(f,"修订内容",content);await fill(f,"修订原因","针对所选版本补充说明");
 return result(p,"保存人工修订新版本",base+"/reports/"+revision+"/revisions");
}
async function review(p,revision,outcome,status=200){const f=p.getByRole("form",{name:"独立复核"});await f.getByLabel("复核结果",{exact:true}).selectOption(outcome);await fill(f,"复核理由","要求补充对应说明并核对引用适用");return result(p,"确认独立复核结果",base+"/reports/"+revision+"/reviews",status);}
let author,reviewer;
try{
 author=await setup();await login(author,"case-author");await author.getByRole("button",{name:"新建案件",exact:true}).click();await fillCase(author.getByRole("form",{name:"创建案件",exact:true}),pack.caseInput,pack.caseOriginalText,true);await result(author,"创建并进入案件","/v2/adjudication/case-works");await text(author,pack.caseInput.name);
 for(const m of pack.materials)await material(author,m);await claim(author,1);await tab(author,"特征");await text(author,"缺证提示：尚无已记录对应");
 await tab(author,"报告");await result(author,"生成辅助报告",base+"/reports");await text(author,"LEGAL_CONTEXT_MISSING");await result(author,"提交确切版本",base+"/reports/1/submit",409);await text(author,"REPORT_INCOMPLETE");
 await tab(author,"案件");await fillCase(author.getByRole("form",{name:"上下文修订"}),contextInput,contextOriginal,false);await result(author,"保存上下文新版本",base+"/context-revisions");await claim(author,2);
 for(const e of pack.claim.elements)await finding(author,"finding-"+e.id,e);
 await tab(author,"引用");const cf=author.getByRole("form",{name:"引用登记"});for(const [label,key] of [["引用ID","id"],["引用原文","originalText"],["人工解释","explanation"],["采用理由","selectionReason"],["引用地域","region"],["引用程序","procedure"],["引用时点","relevantDate"]])await fill(cf,label,citation[key]);await fillSource(cf,citation.source);await result(author,"登记引用",base+"/citations");await text(author,"原文摘要一致");await author.screenshot({path:resolve(out,"original-citation.png"),fullPage:true});
 await tab(author,"报告");await result(author,"生成辅助报告",base+"/reports");await revise(author,2,"补充逐项技术特征对应解释");await result(author,"提交确切版本",base+"/reports/3/submit");await review(author,3,"RETURNED",409);await text(author,"INDEPENDENT_REVIEW_REQUIRED");
 reviewer=await setup();await login(reviewer,"independent-reviewer");await refresh(reviewer);await tab(reviewer,"报告");await text(reviewer,"待独立复核");await review(reviewer,3,"RETURNED");await text(reviewer,"已退回");await reviewer.screenshot({path:resolve(out,"returned-report.png"),fullPage:true});
 await refresh(author);await tab(author,"报告");await result(author,"提交确切版本",base+"/reports/3/submit",409);await text(author,"REPORT_NOT_DRAFT");await revise(author,3,"按复核意见补充材料及对应说明");await result(author,"提交确切版本",base+"/reports/4/submit");
 await refresh(reviewer);await review(reviewer,4,"REVIEWED_NOT_OFFICIAL");await text(reviewer,"已复核");await review(reviewer,4,"RETURNED",409);await text(reviewer,"REPORT_NOT_SUBMITTED");
 await refresh(author);await tab(author,"历史");await author.getByRole("button",{name:"查看工作版本 1 · 输入 1",exact:true}).click();await idle(author);await text(author,"历史快照只读");assert.equal(await author.getByRole("form").count(),0);await author.getByRole("button",{name:"返回当前案件版本",exact:true}).click();await idle(author);await tab(author,"权项");await text(author,"历史权项");
 await tab(author,"报告");const other=await author.context().newPage();observe(other);await login(other,"case-author");await refresh(other);const replacement={...pack.materials[0],id:"applicant-material-v2",supersedesMaterialId:pack.materials[0].id};await material(other,replacement);
 await result(author,"提交确切版本",base+"/reports/4/submit",409);await text(author,"WORK_VERSION_CONFLICT");await author.screenshot({path:resolve(out,"version-conflict.png"),fullPage:true});
 const rejectedEdit=author.getByRole("form",{name:"报告修订"});await rejectedEdit.getByLabel("修订章节",{exact:true}).selectOption("analysis");
 await fill(rejectedEdit,"修订内容","并发冲突后的人工修订应保留");await fill(rejectedEdit,"修订原因","等待核对当前版本");
 await result(author,"保存人工修订新版本",base+"/reports/4/revisions",409);await text(author,"WORK_VERSION_CONFLICT");
 assert.equal(await rejectedEdit.getByLabel("修订内容",{exact:true}).inputValue(),"并发冲突后的人工修订应保留");
 assert.equal(await rejectedEdit.getByLabel("修订原因",{exact:true}).inputValue(),"等待核对当前版本");
 await rejectedEdit.screenshot({path:resolve(out,"report-editor-rejection.png")});
 author.once("dialog",dialog=>dialog.dismiss());await author.getByRole("button",{name:"进入 / 刷新案件",exact:true}).click();
 assert.equal(await rejectedEdit.getByLabel("修订内容",{exact:true}).inputValue(),"并发冲突后的人工修订应保留");
 author.once("dialog",dialog=>dialog.accept());await rejectedEdit.getByRole("button",{name:"放弃未保存修订",exact:true}).click();

 await refresh(author);await tab(author,"报告");await text(author,"已失效：输入版本已变化");await result(author,"提交确切版本",base+"/reports/4/submit",409);await text(author,"REPORT_INPUT_OR_HEAD_STALE");
 await tab(author,"材料");await text(author,"历史材料 · 已被新版替换");await tab(author,"特征");await text(author,"缺证提示：尚无已记录对应");
 await finding(author,"support-current",pack.claim.elements[0],"SUPPORT",replacement.id);await finding(author,"opposing-current",pack.claim.elements[0],"OPPOSING",replacement.id);await finding(author,"uncertain-current",pack.claim.elements[1],"UNCERTAIN",replacement.id);await text(author,"冲突提示：同时存在支持及反向记录");await text(author,"不确定记录");await author.screenshot({path:resolve(out,"feature-correspondence.png"),fullPage:true});
 await tab(author,"报告");await result(author,"生成辅助报告",base+"/reports");await text(author,"ELEMENT_CONFLICT");await text(author,"ELEMENT_UNCERTAIN");await result(author,"提交确切版本",base+"/reports/5/submit",409);await author.screenshot({path:resolve(out,"blocked-report.png"),fullPage:true});
 await tab(author,"历史");await text(author,"REVIEW_AUXILIARY_REPORT");await author.setViewportSize({width:390,height:844});await author.screenshot({path:resolve(out,"mobile-history.png"),fullPage:true});assert.ok(await author.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),"Mobile content must not overflow page width");await author.setViewportSize({width:1440,height:1050});
 if(recovery){
  await Promise.all([...pending]);await serial;
  const before=events.findLast(e=>e.path===base&&e.method==="GET"&&e.status===200)?.body;assert.equal(before.version,22);
  await writeFile(resolve(out,"recovery-ready.json"),JSON.stringify({caseId:id,headVersion:22}));
  let restarted=false;for(let i=0;i<900;i++){try{const notice=JSON.parse(await readFile(resolve(out,"service-restarted.json"),"utf8"));assert.equal(notice.caseId,id);assert.equal(notice.headVersion,22);restarted=true;break;}catch(e){if(e.code!=="ENOENT")throw e;}await new Promise(r=>setTimeout(r,200));}assert.ok(restarted,"Actual restart deadline exceeded");
  await refresh(author);await Promise.all([...pending]);await serial;assert.deepEqual(events.findLast(e=>e.path===base&&e.method==="GET"&&e.status===200)?.body,before,"Same session must recover exact case/report/audit projection after actual service restart");
  await tab(author,"引用");await text(author,citation.originalText);await tab(author,"报告");await text(author,"ELEMENT_CONFLICT");await author.getByRole("button",{name:/^报告 v4 · 已失效/}).click();await text(author,"已失效：输入版本已变化");
  await login(author,"case-author");await refresh(author);await Promise.all([...pending]);await serial;assert.deepEqual(events.findLast(e=>e.path===base&&e.method==="GET"&&e.status===200)?.body,before,"Fresh PKCE login must recover the same case without reconstructing input");
  await tab(author,"历史");await author.getByRole("button",{name:/^查看工作版本 17 · 输入/}).click();await idle(author);await tab(author,"报告");await text(author,"已复核");await text(author,"历史快照只读");await author.screenshot({path:resolve(out,"recovered-reviewed-history.png"),fullPage:true});await author.getByRole("button",{name:"返回当前案件版本",exact:true}).click();await idle(author);await tab(author,"历史");await author.screenshot({path:resolve(out,"recovered-current-history.png"),fullPage:true});
 }
 await tab(author,"报告");const sensitiveEdit=author.getByRole("form",{name:"报告修订"});
 await sensitiveEdit.getByLabel("修订章节",{exact:true}).selectOption("analysis");
 await fill(sensitiveEdit,"修订内容","来源撤回后必须清除的未保存人工正文");await fill(sensitiveEdit,"修订原因","等待授权核查");
 const maintenance=await playwrightRequest.newContext({ignoreHTTPSErrors:true,clientCertificates:[{origin,pfxPath:required("ADJ_BROWSER_PFX"),passphrase:required("ADJ_BROWSER_PFX_PASSWORD")}]});
 const revoked=await maintenance.post(origin+"/v2/adjudication/source-uses/"+input.sourceKey+"/revoke",{data:{expectedVersion:1}});assert.equal(revoked.status(),200,await revoked.text());await maintenance.dispose();
 await result(author,"保存人工修订新版本",base+"/reports/5/revisions",403);await text(author,"来源已撤回：案件及历史正文已清空");
 assert.equal(await author.getByLabel("修订内容",{exact:true}).count(),0);assert.equal(await author.getByText("来源撤回后必须清除的未保存人工正文",{exact:true}).count(),0);
 await refresh(author);await text(author,"来源已撤回：案件及历史正文已清空");assert.equal(await author.getByRole("form").count(),0);assert.equal(await author.getByText(pack.materials[0].content,{exact:true}).count(),0);assert.equal(await author.getByRole("navigation",{name:"案件工作区"}).count(),0);await author.screenshot({path:resolve(out,"source-withdrawal.png"),fullPage:true});
 assert.deepEqual(pageErrors,[]);await Promise.all([...pending]);await serial;await writeFile(resolve(out,"browser-result.json"),JSON.stringify({passed:true,inputNature:"APPROVED_SUBSTITUTE_CASE_WITH_REAL_PUBLIC_STATUTORY_CITATION",realBrowser:"Chromium",noNetworkMocks:true,realPkceLogin:true,serviceRestartRecoveredSameCase:recovery,freshPkceAfterRestart:recovery,authorAndIndependentReviewer:true,normalLifecycle:true,returnResubmit:true,materialAndClaimHistory:true,conflictAndStaleRefusal:true,missingOpposingUncertainVisible:true,historicalReadOnly:true,sourceRevokedClearsContent:true,mobileNoPageOverflow:true,editorOriginalPrefilled:true,editorChapterDraftsPreserved:true,editorNavigationCancelPreserves:true,editorBackendRejectionPreserves:true,editorRevocationPurgesDraft:true,httpCount:events.length,pageErrors,completedAt:new Date().toISOString()},null,2));
}catch(e){if(author)await author.screenshot({path:resolve(out,"failure.png"),fullPage:true}).catch(()=>{});await writeFile(resolve(out,"browser-failure.json"),JSON.stringify({passed:false,message:String(e),pageErrors},null,2));throw e;}finally{await Promise.allSettled([...pending]);for(const c of contexts)await c.close();await browser.close();}
