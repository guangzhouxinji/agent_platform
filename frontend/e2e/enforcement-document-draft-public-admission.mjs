import {chromium} from 'playwright';
import {spawn} from 'node:child_process';
import {createWriteStream} from 'node:fs';
import {resolve} from 'node:path';
// OAuth tokens remain only in process memory/env. Never report cookies, passwords or tokens.
const browser=await chromium.launch({headless:true,args:["--disable-gpu"]});
try {
  for(const [suffix,target] of [['A','ENF_DRAFT_ACCESS_TOKEN'],['B','ENF_DRAFT_VERIFIER_TOKEN']]) {
    const context=await browser.newContext({ignoreHTTPSErrors:true});const page=await context.newPage();
    await page.goto('https://127.0.0.1:5174/app/enforcement/document-drafts',{waitUntil:'networkidle'});
    await page.getByRole('button',{name:/登\s*录/}).click();
    await page.locator('input[name=username]').fill(process.env['ENF_DRAFT_LOGIN_'+suffix]);
    await page.locator('input[name=password]').fill(process.env['ENF_DRAFT_PASSWORD_'+suffix]);
    const tokenResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/oauth2/token');
    await page.locator('button[type=submit]').click({force:true});
    const response=await tokenResponse;if(response.status()!==200)throw new Error('Normal OAuth/PKCE login failed');
    process.env[target]=(await response.json()).access_token;if(!process.env[target])throw new Error('No product token');
    await page.getByRole('button',{name:/退\s*出/}).waitFor();await context.close();
  }
  const log=createWriteStream(resolve(process.env.ENF_DRAFT_PUBLIC_OUTPUT,'maven.log'),{flags:'wx'});
  const command='& mvn.cmd -q -f $env:ENF_MAVEN_POM -pl apps/platform-api -am -Penf-document-draft-input-audit "-Dtest=$env:ENF_DRAFT_EXECUTION_TEST" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dsurefire.reportNameSuffix=$env:ENF_DRAFT_PUBLIC_RUN" test; exit $LASTEXITCODE';
  const child=spawn('powershell.exe',['-NoProfile','-Command',command],{env:process.env,windowsHide:true});child.stdout.pipe(log);child.stderr.pipe(log);
  const code=await new Promise((ok,no)=>{child.on('error',no);child.on('exit',ok);});
  await new Promise(ok=>log.end(ok));if(code!==0)throw new Error('Real HTTPS admission failed; preserved Maven evidence');
  console.log('Real HTTPS public admission completed through normal product commands; independent controlled technical identities; NOT business acceptance');
} finally {delete process.env.ENF_DRAFT_ACCESS_TOKEN;delete process.env.ENF_DRAFT_VERIFIER_TOKEN;await browser.close();}
