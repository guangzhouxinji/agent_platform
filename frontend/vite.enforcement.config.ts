import {defineConfig,loadEnv} from 'vite';import react from '@vitejs/plugin-react';import {resolve} from 'node:path';import {mkdirSync,writeFileSync} from 'node:fs';import {requireEnforcementBuildBinding} from '../scripts/lib/enforcement-product-build.mjs';import {inspectEnforcementAssets} from '../scripts/lib/enforcement-product-assets.mjs';
export default defineConfig(({mode})=>{const admission=requireEnforcementBuildBinding({...loadEnv(mode,import.meta.dirname,'VITE_'),...process.env});return {
 base:'/app/',cacheDir:'target/vite-enforcement-cache',plugins:[react(),{name:'enforcement-build-evidence',writeBundle(){
  const modules=[...this.getModuleIds()].filter(id=>!id.includes('/node_modules/')).map(id=>id.replaceAll('\\','/').replace(import.meta.dirname.replaceAll('\\','/')+'/','')).sort();mkdirSync(resolve(import.meta.dirname,'target'),{recursive:true});
  writeFileSync(resolve(import.meta.dirname,'target/enforcement-build-modules.json'),JSON.stringify(modules,null,2));
  const manifest=inspectEnforcementAssets(resolve(import.meta.dirname,'dist-enforcement'));
  writeFileSync(resolve(import.meta.dirname,'target/enforcement-build-binding.json'),JSON.stringify({...manifest,...admission},null,2));
 }}],build:{outDir:'dist-enforcement',emptyOutDir:true,sourcemap:true,rollupOptions:{input:resolve(import.meta.dirname,'enforcement.html')}}};});
