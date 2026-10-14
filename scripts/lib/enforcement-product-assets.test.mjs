import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { stageEnforcementAssets, inspectEnforcementAssets } from './enforcement-product-assets.mjs';
const output='backend/apps/platform-api/target/enforcement-product-classes/static/app';
function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'adj-entry-'));
 // This exact OS-created temporary root is owned by this test only.
 t.after(()=>{if(!path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep))throw Error('UNSAFE_TEST_CLEANUP');fs.rmSync(root,{recursive:true,force:true});});
 const source=path.join(root,'frontend/dist-enforcement');fs.mkdirSync(path.join(source,'assets'),{recursive:true});
 fs.writeFileSync(path.join(source,'enforcement.html'),'<script type="module" src="/app/assets/entry.js"></script><link href="/app/assets/entry.css"/>');
 fs.writeFileSync(path.join(source,'assets/entry.js'),'export const approvedWorkspace=true;');fs.writeFileSync(path.join(source,'assets/entry.css'),'body{}');
 fs.mkdirSync(path.join(root,'frontend/target'));fs.writeFileSync(path.join(root,'frontend/target/enforcement-build-binding.json'),JSON.stringify({enabledPages:['job-pack'],clientBindings:{VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID:'unit-workspace-client',VITE_ENFORCEMENT_PRODUCT_CLIENT_ID:'unit-public-client'},...inspectEnforcementAssets(source)}));return {root,source};
}
test('stages exact bytes into only the fresh independent output',t=>{const {root,source}=fixture(t);const manifest=stageEnforcementAssets(root);assert.equal(manifest.entry,'enforcement.html');assert.equal(manifest.files.length,3);for(const f of manifest.files)assert.deepEqual(fs.readFileSync(path.join(root,output,f.path)),fs.readFileSync(path.join(source,f.path)));assert.equal(fs.existsSync(path.join(root,'backend/apps/platform-api/target/classes')),false);});
for(const [name,change,code] of [
 ['missing entry',s=>fs.unlinkSync(path.join(s,'enforcement.html')),'ENFORCEMENT_ENTRY_MISSING'],
 ['generic bundle',s=>fs.writeFileSync(path.join(s,'index.html'),'old'),'OTHER_PRODUCT_ENTRY'],
 ['consultation bundle',s=>fs.writeFileSync(path.join(s,'consultation.html'),'other'),'OTHER_PRODUCT_ENTRY'],
 ['missing module',s=>fs.unlinkSync(path.join(s,'assets/entry.js')),'ENTRY_ASSET_MISSING'],
 ['external module',s=>fs.writeFileSync(path.join(s,'enforcement.html'),'<script src="https://external.invalid/x.js"></script>'),'UNSAFE_ENTRY_REFERENCE'],
 ['traversal',s=>fs.writeFileSync(path.join(s,'enforcement.html'),'<script src="/app/assets/../x.js"></script>'),'ENTRY_TRAVERSAL'],
 ['encoded traversal',s=>fs.writeFileSync(path.join(s,'enforcement.html'),'<script src="/app/assets/%2e%2e/x.js"></script>'),'UNSAFE_ENTRY_REFERENCE'],
 ['source entry',s=>fs.writeFileSync(path.join(s,'enforcement.html'),'<script src="/src/enforcement.tsx"></script>'),'BUILT_MODULE_MISSING'],
 ['injected manifest',s=>fs.writeFileSync(path.join(s,'enforcement-product-assets.json'),'{}'),'SOURCE_MANIFEST_NOT_BUILD_INPUT']
])test(name+' refuses before creating output',t=>{const {root,source}=fixture(t);change(source);assert.throws(()=>stageEnforcementAssets(root),new RegExp(code));assert.equal(fs.existsSync(path.join(root,output)),false);});
test('prior output is preserved instead of overwritten',t=>{const {root}=fixture(t);const dest=path.join(root,output);fs.mkdirSync(dest,{recursive:true});fs.writeFileSync(path.join(dest,'prior'),'unchanged');assert.throws(()=>stageEnforcementAssets(root),/OWNED_OUTPUT_ALREADY_EXISTS/);assert.equal(fs.readFileSync(path.join(dest,'prior'),'utf8'),'unchanged');});
test('linked input is rejected',t=>{const {source}=fixture(t);const external=path.join(path.dirname(source),'external');fs.mkdirSync(external);const link=path.join(source,'assets/linked');fs.symlinkSync(external,link,'junction');t.after(()=>{if(fs.existsSync(link))fs.unlinkSync(link);});assert.throws(()=>inspectEnforcementAssets(source),/LINKED_ASSET/);});
test('linked output ancestor is rejected and external bytes preserved',t=>{const {root}=fixture(t);const external=path.join(root,'external');fs.mkdirSync(external);fs.writeFileSync(path.join(external,'keep'),'unchanged');const link=path.join(root,'backend');fs.symlinkSync(external,link,'junction');t.after(()=>{if(fs.existsSync(link))fs.unlinkSync(link);});assert.throws(()=>stageEnforcementAssets(root),/LINKED_BUILD_PATH/);assert.equal(fs.readFileSync(path.join(external,'keep'),'utf8'),'unchanged');});

import {requireEnforcementBuildBinding} from './enforcement-product-build.mjs';
test('no default pages/client; unknown, duplicate or unmet dependencies refuse',()=>{for(const value of [undefined,'','material','job-pack,job-pack','job-pack,document-rules'])assert.throws(()=>requireEnforcementBuildBinding({VITE_ENFORCEMENT_ENABLED_PAGES:value}),/EXPLICIT_ENFORCEMENT_PAGE_ADMISSION_REQUIRED/);assert.throws(()=>requireEnforcementBuildBinding({VITE_ENFORCEMENT_ENABLED_PAGES:'job-pack'}),/CURRENT_ENFORCEMENT_CLIENT_BINDING_REQUIRED/);assert.deepEqual(requireEnforcementBuildBinding({VITE_ENFORCEMENT_ENABLED_PAGES:'job-pack',VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID:'unit-workspace-reference',VITE_ENFORCEMENT_PRODUCT_CLIENT_ID:'unit-reference'}).enabledPages,['job-pack']);});
test('admin pages require their exact dedicated client bindings',()=>{assert.throws(()=>requireEnforcementBuildBinding({VITE_ENFORCEMENT_ENABLED_PAGES:'job-pack,document-templates',VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID:'unit-workspace-reference',VITE_ENFORCEMENT_PRODUCT_CLIENT_ID:'unit-reference'}),/CURRENT_ENFORCEMENT_CLIENT_BINDING_REQUIRED/);});
test('unbound prior bundle cannot be staged',t=>{const {root}=fixture(t);fs.unlinkSync(path.join(root,'frontend/target/enforcement-build-binding.json'));assert.throws(()=>stageEnforcementAssets(root),/ENFORCEMENT_BUILD_BINDING_REQUIRED/);assert.equal(fs.existsSync(path.join(root,output)),false);});
test('post-build asset changes refuse before output',t=>{const {root,source}=fixture(t);fs.appendFileSync(path.join(source,'assets/entry.js'),'changed');assert.throws(()=>stageEnforcementAssets(root),/ENFORCEMENT_BUILD_BINDING_DRIFT/);assert.equal(fs.existsSync(path.join(root,output)),false);});

test('one-redirect registry cannot alias workspace and slice callbacks to one client',()=>{assert.throws(()=>requireEnforcementBuildBinding({VITE_ENFORCEMENT_ENABLED_PAGES:'job-pack',VITE_ENFORCEMENT_WORKSPACE_CLIENT_ID:'same',VITE_ENFORCEMENT_PRODUCT_CLIENT_ID:'same'}),/DISTINCT_ENFORCEMENT_CLIENT_BINDINGS_REQUIRED/);});
