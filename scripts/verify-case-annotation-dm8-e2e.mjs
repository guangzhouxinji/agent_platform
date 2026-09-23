import { chromium } from "@playwright/test";
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { createServer } from "node:net";
import process from "node:process";

for (const name of ["DM8_JDBC_URL", "DM8_JDBC_USER", "DM8_JDBC_PASSWORD"]) if (!process.env[name]) throw new Error(`${name} is required`);
async function freePort(){return await new Promise((resolve,reject)=>{const server=createServer();server.once("error",reject);server.listen(0,"127.0.0.1",()=>{const address=server.address();server.close(error=>error?reject(error):resolve(String(address.port)));});});}
const port=process.env.CASE_ANNOTATION_E2E_PORT||await freePort(), baseUrl=`http://127.0.0.1:${port}`;
const maven=process.platform==="win32"?"mvn.cmd":"mvn";
const java=process.env.JAVA_HOME?join(process.env.JAVA_HOME,"bin",process.platform==="win32"?"java.exe":"java"):"java";
let application,browser,verified=false;
function run(command,args){const executable=process.platform==="win32"?(process.env.ComSpec||"cmd.exe"):command;const commandArgs=process.platform==="win32"?["/d","/s","/c",command,...args]:args;const result=spawnSync(executable,commandArgs,{cwd:process.cwd(),env:process.env,stdio:"inherit"});if(result.status!==0)throw new Error(`${command} failed`);}
function probe(phase){run(maven,[`-Dcase.annotation.browser.phase=${phase}`,"-Dtest=Dm8CaseAnnotationBrowserDatabaseProbe","test"]);}
function start(){const logs=[];const child=spawn(java,["-jar","backend/apps/platform-api/target/agent-platform-api-0.0.1-SNAPSHOT.jar"],{cwd:process.cwd(),env:{...process.env,CONSULTATION_STORAGE:"memory",PATENT_SEARCH_STORAGE:"memory",TRADEMARK_SEARCH_STORAGE:"memory",CASE_ANNOTATION_STORAGE:"dm8",SERVER_PORT:port,SPRING_DATASOURCE_URL:process.env.DM8_JDBC_URL,SPRING_DATASOURCE_USERNAME:process.env.DM8_JDBC_USER,SPRING_DATASOURCE_PASSWORD:process.env.DM8_JDBC_PASSWORD,SPRING_DATASOURCE_DRIVER_CLASS_NAME:"dm.jdbc.driver.DmDriver"},stdio:["ignore","pipe","pipe"]});child.stdout.on("data",value=>logs.push(value.toString()));child.stderr.on("data",value=>logs.push(value.toString()));child.logs=logs;return child;}
async function ready(child){const end=Date.now()+45000;while(Date.now()<end){if(child.exitCode!==null)throw new Error(child.logs.join(""));try{if((await fetch(`${baseUrl}/index.html`)).ok)return;}catch{}await new Promise(resolve=>setTimeout(resolve,250));}throw new Error("Case annotation application did not start");}
async function stop(){if(!application||application.exitCode!==null)return;application.kill();await Promise.race([new Promise(resolve=>application.once("exit",resolve)),new Promise((_,reject)=>setTimeout(()=>reject(new Error("Application did not stop")),10000))]);}
function assert(value,message){if(!value)throw new Error(message);}
try{
  probe("setup");run(maven,["-DskipTests","package"]);application=start();await ready(application);
  browser=await chromium.launch();const page=await browser.newPage({viewport:{width:1366,height:900}});page.on("pageerror",error=>console.error("Browser page error:",error.message));page.on("response",response=>{if(response.status()>=400)console.error("Browser HTTP error:",response.status(),response.url());});await page.goto(baseUrl);
  await page.getByRole("button",{name:"案例库",exact:true}).click();const workspace=page.locator("#case-library-workspace");await workspace.getByText("案例库查询完成").waitFor();await workspace.getByLabel("案例编号").fill("SYN-CASE-00003");await Promise.all([page.waitForResponse(response=>response.url().includes("caseNumber=SYN-CASE-00003")&&response.status()===200),workspace.getByRole("button",{name:"查询案例库"}).click()]);
  await workspace.getByRole("button",{name:"查看详情"}).first().click();await workspace.getByRole("button",{name:"发起合成提取"}).click();
  await workspace.locator("#case-library-subresult").getByText("DM8",{exact:false}).waitFor();
  await page.getByRole("button",{name:"修订标注"}).click();await page.getByText("DRAFT v2").waitFor();
  await page.getByRole("button",{name:"提交审核"}).click();await page.getByText("PENDING_REVIEW v3").waitFor();
  await page.getByRole("button",{name:"审核发布"}).click();await page.getByText("PUBLISHED v4").waitFor();
  mkdirSync("target/playwright-artifacts",{recursive:true});await page.screenshot({path:"target/playwright-artifacts/case-annotation-dm8-before-restart.png",fullPage:true});
  await stop();application=undefined;application=start();await ready(application);await page.goto(baseUrl);
  const restored=await page.evaluate(async()=>{const response=await fetch("/api/v0/case-annotations/cases/CASE-SYN-003",{headers:{"X-V0-Actor":"v0-authorized-user"}});return{status:response.status,body:await response.json()};});
  assert(restored.status===200&&restored.body.storageMode==="DM8","Restart must read case annotation from DM8");assert(restored.body.annotations.length===4&&restored.body.audits.length===4,"Restart must retain exact version and audit history");assert(restored.body.annotations.at(-1).state==="PUBLISHED","Restart must retain published current version");
  await page.getByRole("button",{name:"案例库",exact:true}).click();const restoredWorkspace=page.locator("#case-library-workspace");await restoredWorkspace.getByText("案例库查询完成").waitFor();await restoredWorkspace.getByLabel("案例编号").fill("SYN-CASE-00003");await Promise.all([page.waitForResponse(response=>response.url().includes("caseNumber=SYN-CASE-00003")&&response.status()===200),restoredWorkspace.getByRole("button",{name:"查询案例库"}).click()]);await restoredWorkspace.getByRole("button",{name:"查看详情"}).first().click();await restoredWorkspace.getByRole("button",{name:"查看标注流程"}).click();await restoredWorkspace.getByText("PUBLISHED v4").waitFor();await page.screenshot({path:"target/playwright-artifacts/case-annotation-dm8-after-restart.png",fullPage:true});
  await browser.close();browser=undefined;await stop();application=undefined;probe("verify");verified=true;console.log("Case annotation DM8 browser E2E passed: browser workflow, HTTP, DM8 persistence, application restart, exact history, and database integrity.");
}finally{if(browser)await browser.close();if(application)await stop();if(!verified)try{probe("cleanup");}catch{}}
