import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { stageAdjudicationAssets, inspectAdjudicationAssets } from './adjudication-product-assets.mjs';
const output='backend/apps/platform-api/target/adjudication-product-classes/static/app';
function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'adj-entry-'));
 // This exact OS-created temporary root is owned by this test only.
 t.after(()=>{if(!path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep))throw Error('UNSAFE_TEST_CLEANUP');fs.rmSync(root,{recursive:true,force:true});});
 const source=path.join(root,'frontend/dist-adjudication');fs.mkdirSync(path.join(source,'assets'),{recursive:true});
 fs.writeFileSync(path.join(source,'adjudication.html'),'<script type="module" src="/app/assets/adj.js"></script><link href="/app/assets/adj.css"/>');
 fs.writeFileSync(path.join(source,'assets/adj.js'),'export const approvedWorkspace=true;');fs.writeFileSync(path.join(source,'assets/adj.css'),'body{}');
 fs.mkdirSync(path.join(root,'frontend/target'));fs.writeFileSync(path.join(root,'frontend/target/adjudication-build-binding.json'),JSON.stringify({clientId:'approved-client-reference',...inspectAdjudicationAssets(source)}));return {root,source};
}
test('approved route forwards the independent entry, not absent generic index',()=>{
 const route=fs.readFileSync(new URL('../../backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/adjudication/casework/CaseWorkspacePageController.java',import.meta.url),'utf8');
 assert.match(route,/@GetMapping\("\/app\/adjudication"\)/);assert.match(route,/forward:\/app\/adjudication\.html/);assert.doesNotMatch(route,/index\.html/);
});
test('stages exact bytes into only the fresh independent output',t=>{const {root,source}=fixture(t);const manifest=stageAdjudicationAssets(root);assert.equal(manifest.entry,'adjudication.html');assert.equal(manifest.files.length,3);for(const f of manifest.files)assert.deepEqual(fs.readFileSync(path.join(root,output,f.path)),fs.readFileSync(path.join(source,f.path)));assert.equal(fs.existsSync(path.join(root,'backend/apps/platform-api/target/classes')),false);});
for(const [name,change,code] of [
 ['missing entry',s=>fs.unlinkSync(path.join(s,'adjudication.html')),'ADJUDICATION_ENTRY_MISSING'],
 ['generic bundle',s=>fs.writeFileSync(path.join(s,'index.html'),'old'),'OTHER_PRODUCT_ENTRY'],
 ['consultation bundle',s=>fs.writeFileSync(path.join(s,'consultation.html'),'other'),'OTHER_PRODUCT_ENTRY'],
 ['missing module',s=>fs.unlinkSync(path.join(s,'assets/adj.js')),'ENTRY_ASSET_MISSING'],
 ['external module',s=>fs.writeFileSync(path.join(s,'adjudication.html'),'<script src="https://external.invalid/x.js"></script>'),'UNSAFE_ENTRY_REFERENCE'],
 ['traversal',s=>fs.writeFileSync(path.join(s,'adjudication.html'),'<script src="/app/assets/../x.js"></script>'),'ENTRY_TRAVERSAL'],
 ['encoded traversal',s=>fs.writeFileSync(path.join(s,'adjudication.html'),'<script src="/app/assets/%2e%2e/x.js"></script>'),'UNSAFE_ENTRY_REFERENCE'],
 ['source entry',s=>fs.writeFileSync(path.join(s,'adjudication.html'),'<script src="/src/adjudication.tsx"></script>'),'BUILT_MODULE_MISSING'],
 ['injected manifest',s=>fs.writeFileSync(path.join(s,'adjudication-product-assets.json'),'{}'),'SOURCE_MANIFEST_NOT_BUILD_INPUT']
])test(name+' refuses before creating output',t=>{const {root,source}=fixture(t);change(source);assert.throws(()=>stageAdjudicationAssets(root),new RegExp(code));assert.equal(fs.existsSync(path.join(root,output)),false);});
test('prior output is preserved instead of overwritten',t=>{const {root}=fixture(t);const dest=path.join(root,output);fs.mkdirSync(dest,{recursive:true});fs.writeFileSync(path.join(dest,'prior'),'unchanged');assert.throws(()=>stageAdjudicationAssets(root),/OWNED_OUTPUT_ALREADY_EXISTS/);assert.equal(fs.readFileSync(path.join(dest,'prior'),'utf8'),'unchanged');});
test('linked input is rejected',t=>{const {source}=fixture(t);const external=path.join(path.dirname(source),'external');fs.mkdirSync(external);const link=path.join(source,'assets/linked');fs.symlinkSync(external,link,'junction');t.after(()=>{if(fs.existsSync(link))fs.unlinkSync(link);});assert.throws(()=>inspectAdjudicationAssets(source),/LINKED_ASSET/);});
test('linked output ancestor is rejected and external bytes preserved',t=>{const {root}=fixture(t);const external=path.join(root,'external');fs.mkdirSync(external);fs.writeFileSync(path.join(external,'keep'),'unchanged');const link=path.join(root,'backend');fs.symlinkSync(external,link,'junction');t.after(()=>{if(fs.existsSync(link))fs.unlinkSync(link);});assert.throws(()=>stageAdjudicationAssets(root),/LINKED_BUILD_PATH/);assert.equal(fs.readFileSync(path.join(external,'keep'),'utf8'),'unchanged');});
test('entry directly reuses approved workspace, not cross-domain router',()=>{const entry=fs.readFileSync(new URL('../../frontend/src/adjudication.tsx',import.meta.url),'utf8');assert.match(entry,/features\/adjudication\/AdjudicationWorkspace/);assert.doesNotMatch(entry,/AppRoutes|AppShell|features\/consultation|features\/workflows/);});

import { requireAdjudicationClientId } from './adjudication-product-build.mjs';
test('build requires explicit public client binding, never defaults a stale identity',()=>{for(const value of [undefined,'','not a client','x'.repeat(101)])assert.throws(()=>requireAdjudicationClientId({VITE_ADJUDICATION_CLIENT_ID:value}),/CURRENT_ADJUDICATION_CLIENT_BINDING_REQUIRED/);assert.equal(requireAdjudicationClientId({VITE_ADJUDICATION_CLIENT_ID:'approved-client-reference'}),'approved-client-reference');});
test('independent Maven profile refuses legacy, consultation mixing and wrong output',()=>{const pom=fs.readFileSync(new URL('../../backend/apps/platform-api/pom.xml',import.meta.url),'utf8');assert.match(pom,/<id>bundle-adjudication<\/id>/);assert.match(pom,/product.assets.adjudication>true/);assert.match(pom,/product.assets.consultation>true/);assert.match(pom,/reject-mixed-adjudication-assets/);assert.match(pom,/<not><equals arg1="\$\{platform.api.outputDirectory\}"/);assert.match(pom,/build:adjudication/);assert.match(pom,/stage-adjudication-product-assets\.mjs/);});

test('unbound prior mechanical build is never staged',t=>{const {root}=fixture(t);fs.unlinkSync(path.join(root,'frontend/target/adjudication-build-binding.json'));assert.throws(()=>stageAdjudicationAssets(root),/ADJUDICATION_BUILD_BINDING_REQUIRED/);assert.equal(fs.existsSync(path.join(root,output)),false);});
test('post-build asset changes are refused before output',t=>{const {root,source}=fixture(t);fs.appendFileSync(path.join(source,'assets/adj.js'),'changed');assert.throws(()=>stageAdjudicationAssets(root),/ADJUDICATION_BUILD_BINDING_DRIFT/);assert.equal(fs.existsSync(path.join(root,output)),false);});
