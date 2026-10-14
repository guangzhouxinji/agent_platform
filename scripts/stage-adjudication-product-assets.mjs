import { fileURLToPath } from 'node:url';
import { stageAdjudicationAssets } from './lib/adjudication-product-assets.mjs';
console.log(JSON.stringify(stageAdjudicationAssets(fileURLToPath(new URL('../',import.meta.url))),null,2));
