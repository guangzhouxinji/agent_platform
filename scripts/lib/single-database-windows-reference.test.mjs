import test from 'node:test';import assert from 'node:assert/strict';import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';import {spawnSync} from 'node:child_process';
const lib=path.join(process.cwd(),'scripts/lib/single-database-final-deployment.ps1'),q=s=>"'"+s.replaceAll("'","''")+"'";
function shell(code){const r=spawnSync('pwsh',['-NoProfile','-EncodedCommand',Buffer.from(`$ErrorActionPreference='Stop';. ${q(lib)};function docker {throw 'REAL_DOCKER_FORBIDDEN'};${code}`,'utf16le').toString('base64')],{encoding:'utf8',timeout:15000});assert.ifError(r.error);assert.equal(r.status,0,r.stdout+r.stderr);return r.stdout.trim();}
function scope(mutate=''){return shell(`$local=Get-FinalWindowsReferencePath ([Environment]::GetFolderPath('LocalApplicationData'));$root=$local+'/AgentPlatform/single-final-20261009';$logical=$root+'/consultation/server.p12';$physicalRoot=$local+'/Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/AgentPlatform/single-final-20261009';$physical=$physicalRoot+'/consultation/server.p12';${mutate};try{Assert-FinalPhysicalReferencePaths $logical $root $physical $physicalRoot;'ALLOWED'}catch{'REFUSED '+$_.Exception.Message}`);}
test('normal unchanged physical root allowed',()=>assert.equal(scope('$physicalRoot=$root;$physical=$logical'),'ALLOWED'));
test('exact known package physical original allowed',()=>assert.equal(scope(),'ALLOWED'));
for(const [name,body] of [
 ['other package',"$physicalRoot=$physicalRoot.Replace('OpenAI.Codex_2p2nqsd0c76g0','Other.Package');$physical=$physicalRoot+'/consultation/server.p12'"],
 ['sibling root',"$physicalRoot+='-other';$physical=$physicalRoot+'/consultation/server.p12'"],
 ['other file',"$physical=$physicalRoot+'/consultation/trust.p12'"],
 ['other domain',"$physical=$physicalRoot+'/enforcement/server.p12'"],
 ['extra directory',"$physical=$physicalRoot+'/nested/consultation/server.p12'"],
 ['outside logical root',"$logical=$local+'/AgentPlatform/unapproved/consultation/server.p12'"],
 ['logical root prefix sibling',"$logical=$root+'-other/consultation/server.p12'"],
 ['unapproved logical origin',"$root=$local+'/OtherProduct/single-final-20261009';$logical=$root+'/consultation/server.p12'"],
 ['device path',"$physical='//?/C:/unapproved/key.p12'"],
 ['dot segment',"$physical=$physicalRoot+'/consultation/../server.p12'"],
 ['trailing alias',"$physical=$physicalRoot+'/consultation/server.p12.'"],
 ['drive drift',"$physical=$physical.Replace('C:/','D:/')"]
])test('physical scope refuses '+name,()=>assert.match(scope(body),/^REFUSED /));
for(const [name,body] of [['file suffix drift',"$script:physical='D:/synthetic/other.p12'"],['root handle drift',"$script:rootHandle='D:/synthetic-other'"],['handle unavailable',"function Get-FinalHandleWindowsPath {throw 'FINAL_REFERENCE_HANDLE_UNAVAILABLE'}"],['physical SHA drift',"function Assert-FinalFile($r){if($r.path -ceq 'D:/synthetic/consultation/marker.txt'){throw 'FINAL_FILE_SHA_MISMATCH'}}"]])test('resolver refuses '+name,()=>{
 const out=shell(`$root='D:/synthetic';$r=@{path='D:/synthetic/consultation/marker.txt';sha256=('a'*64)};$script:physical=$r.path;$script:rootHandle=$root;function Assert-FinalFile {};function Get-FinalHandleWindowsPath($p,$directory){if($directory){return $script:rootHandle};return $script:physical};${body};try{Get-FinalPhysicalWindowsReference $r $root;'ALLOWED'}catch{'REFUSED '+$_.Exception.Message}`);assert.match(out,/^REFUSED /);
});
const evidence=path.join(process.cwd(),'docs/evidence/single-database-installation-20261009/approved-execution/owned-image-binding26'),work=fs.mkdtempSync(path.join(evidence,'physical-reference-test-'));
fs.mkdirSync(path.join(work,'consultation'));const marker=path.join(work,'consultation/marker.txt');fs.writeFileSync(marker,'NON_SECRET_PHYSICAL_REFERENCE_TEST');const sha=crypto.createHash('sha256').update(fs.readFileSync(marker)).digest('hex');
test('actual ordinary non-secret file binds original by handle',()=>{const actual=shell(`$r=@{path=${q(marker)};containerSource=${q(marker)};sha256=${q(sha)};target='/product/refs/consultation/marker.txt'};Get-FinalReferenceBinding $r consultation ${q(work)}|ConvertTo-Json -Compress`);const v=JSON.parse(actual);assert.equal(v.source,marker.replaceAll('\\','/'));assert.equal(v.windows,true);assert.ok(v.inspectSource.endsWith('/consultation/marker.txt'));});
for(const [name,p,hash] of [['wrong SHA',marker,'b'.repeat(64)],['missing file',path.join(work,'consultation/missing.txt'),sha],['directory',path.join(work,'consultation'),sha]])test('actual non-secret file refuses '+name,()=>assert.match(shell(`$r=@{path=${q(p)};sha256=${q(hash)}};try{Get-FinalPhysicalWindowsReference $r ${q(work)};'ALLOWED'}catch{'REFUSED '+$_.Exception.Message}`),/^REFUSED /));
test.after(()=>{const target=path.resolve(work);assert.ok(target.startsWith(path.resolve(evidence)+path.sep));assert.ok(path.basename(target).startsWith('physical-reference-test-'));fs.rmSync(target,{recursive:true,force:true});});