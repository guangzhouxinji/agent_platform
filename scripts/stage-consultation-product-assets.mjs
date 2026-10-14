import { fileURLToPath } from 'node:url';
import { stageConsultationAssets } from './lib/consultation-product-assets.mjs';
const manifest=stageConsultationAssets(fileURLToPath(new URL('../',import.meta.url)));
console.log(JSON.stringify({contract:manifest.contract,entry:manifest.entry,files:manifest.files.length}));
