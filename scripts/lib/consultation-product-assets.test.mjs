import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { stageConsultationAssets,inspectConsultationAssets } from './consultation-product-assets.mjs';
function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'consultation-bundle-'));const source=path.join(root,'frontend/dist-consultation');
 fs.mkdirSync(path.join(source,'assets'),{recursive:true});
 fs.writeFileSync(path.join(source,'consultation.html'),'<script type="module" src="/app/assets/current.js"></script><link href="/app/assets/current.css" rel="stylesheet">');
 fs.writeFileSync(path.join(source,'assets/current.js'),'actual-entry-module');fs.writeFileSync(path.join(source,'assets/current.css'),'current-style');
 // Only this explicitly created resolved temporary fixture root is deleted, never repository or external paths.
 t.after(()=>{if(fs.realpathSync(root)!==root)throw Error('Fixture root changed');fs.rmSync(root,{recursive:true,force:true});});return {root,source};
}
test('stages exact bytes and does not touch shared workbench output',t=>{
 const {root,source}=fixture(t);const shared=path.join(root,'backend/apps/platform-api/target/classes/static/app');fs.mkdirSync(shared,{recursive:true});fs.writeFileSync(path.join(shared,'index.html'),'keep');
 const manifest=stageConsultationAssets(root);assert.equal(manifest.files.length,3);assert.equal(fs.readFileSync(path.join(shared,'index.html'),'utf8'),'keep');
 const output=path.join(root,'backend/apps/platform-api/target/consultation-product-classes/static/app');assert.deepEqual(JSON.parse(fs.readFileSync(path.join(output,'consultation-product-assets.json'),'utf8')),manifest);
 assert.equal(fs.readFileSync(path.join(output,'assets/current.js'),'utf8'),fs.readFileSync(path.join(source,'assets/current.js'),'utf8'));
});
test('repeated build drops only old dedicated resources',t=>{const {root,source}=fixture(t);stageConsultationAssets(root);fs.renameSync(path.join(source,'assets/current.js'),path.join(source,'assets/new.js'));fs.writeFileSync(path.join(source,'consultation.html'),'<script src="/app/assets/new.js"></script>');stageConsultationAssets(root);assert.equal(fs.existsSync(path.join(root,'backend/apps/platform-api/target/consultation-product-classes/static/app/assets/current.js')),false);});
for(const [name,modify,message] of [
 ['missing consultation entry',s=>fs.unlinkSync(path.join(s,'consultation.html')),'CONSULTATION_ENTRY_MISSING'],
 ['legacy index contamination',s=>fs.writeFileSync(path.join(s,'index.html'),'old'),'OTHER_PRODUCT_ENTRY'],
 ['another business entry',s=>fs.writeFileSync(path.join(s,'adjudication.html'),'other'),'OTHER_PRODUCT_ENTRY'],
 ['missing referenced asset',s=>fs.unlinkSync(path.join(s,'assets/current.js')),'ENTRY_ASSET_MISSING'],
 ['external entry reference',s=>fs.writeFileSync(path.join(s,'consultation.html'),'<script src="https://unapproved.invalid/code.js"></script>'),'UNSAFE_ENTRY_REFERENCE'],
 ['traversal entry reference',s=>fs.writeFileSync(path.join(s,'consultation.html'),'<script src="/app/assets/../current.js"></script>'),'ENTRY_TRAVERSAL'],
 ['source is not built',s=>fs.writeFileSync(path.join(s,'consultation.html'),'<script src="/src/consultation.tsx"></script>'),'BUILT_MODULE_MISSING'],
 ['source manifest injected',s=>fs.writeFileSync(path.join(s,'consultation-product-assets.json'),'{}'),'SOURCE_MANIFEST_NOT_BUILD_INPUT']]) {
 test(name+' rejects before clearing output',t=>{const {root,source}=fixture(t);const output=path.join(root,'backend/apps/platform-api/target/consultation-product-classes/static/app');fs.mkdirSync(output,{recursive:true});fs.writeFileSync(path.join(output,'preserve.txt'),'prior');modify(source);assert.throws(()=>stageConsultationAssets(root),new RegExp(message));assert.equal(fs.readFileSync(path.join(output,'preserve.txt'),'utf8'),'prior');});
}
test('linked output ancestor is rejected without changing external files',t=>{const {root}=fixture(t);const external=path.join(root,'outside-owned-output');fs.mkdirSync(external);fs.writeFileSync(path.join(external,'keep'),'unchanged');const target=path.join(root,'backend/apps/platform-api/target');fs.mkdirSync(path.dirname(target),{recursive:true});fs.symlinkSync(external,target,'junction');assert.throws(()=>stageConsultationAssets(root),/LINKED_BUILD_PATH/);assert.equal(fs.readFileSync(path.join(external,'keep'),'utf8'),'unchanged');fs.unlinkSync(target);});
test('linked input asset rejected',t=>{const {root,source}=fixture(t);const outside=path.join(root,'original');fs.mkdirSync(outside);fs.symlinkSync(outside,path.join(source,'assets/linked'),'junction');assert.throws(()=>inspectConsultationAssets(source),/LINKED_ASSET/);fs.unlinkSync(path.join(source,'assets/linked'));});

test('controlled consultation deployment waits for the actual independent product application',()=>{
 const script=fs.readFileSync(new URL('../start-consultation-container.ps1',import.meta.url),'utf8');
 const readiness=[...script.matchAll(/\$ready -match '([^']+)'/g)].map(m=>m[1]);
 assert.deepEqual(readiness,['Started AgentPlatformApplication','Started AgentPlatformApplication']);
 for(const marker of readiness){const expression=new RegExp(marker);assert.equal(expression.test('Started AgentPlatformApplication in 12.5 seconds'),true);assert.equal(expression.test('Started ProductConsultationApplication in 12.5 seconds'),false);assert.equal(expression.test('APPLICATION FAILED TO START'),false);}
 assert.ok(script.includes('--product-consultation'));assert.ok(script.includes('Candidate exited during startup'));assert.ok(script.includes('Candidate startup deadline exceeded'));
});

test('controlled deployment checks actual mounted bytes before replacement and preserves a stopped original',()=>{
 const script=fs.readFileSync(new URL('../start-consultation-container.ps1',import.meta.url),'utf8');
 assert.ok(script.includes('test -f /release.jar; sha256sum /release.jar'));
 assert.ok(script.indexOf('Actual mounted release SHA mismatch')<script.indexOf("Invoke-DockerChecked @('rename',$staging,$config.appName)"));
 assert.ok(script.includes("if($oldWasRunning -eq 'true')"));assert.ok(!script.includes("if($oldStopped){Invoke-DockerChecked @('start',$config.appName)"));assert.ok(script.includes("Previous application remains stopped: old-JAR write compatibility requires separately verified approval."));
 assert.ok(script.includes('Runtime mount outside exact private root'));assert.ok(script.includes('Private runtime manifest SHA mismatch'));assert.ok(script.includes('if(@($runtimeProbe)[0] -notlike')); 
});

test('managed product networking binds both original database identities instead of remembered bridge IPs',()=>{
 const script=fs.readFileSync(new URL('../start-consultation-container.ps1',import.meta.url),'utf8');
 assert.ok(script.includes('Network ownership mismatch'));assert.ok(script.includes('Original database absent from managed network'));assert.ok(script.includes("$arguments+=@('--network',$config.network)"));
});

