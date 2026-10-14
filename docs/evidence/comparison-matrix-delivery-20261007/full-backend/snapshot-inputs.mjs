import fs from 'node:fs';import path from 'node:path';import crypto from 'node:crypto';
const base='docs/evidence/comparison-matrix-delivery-20261007/full-backend';
function walk(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(d=>d.isDirectory()?( ['target','node_modules','.git'].includes(d.name)?[]:walk(path.join(dir,d.name))):[path.join(dir,d.name)]);}
const files=['backend','db','scripts'].flatMap(walk).filter(f=>/\.(java|xml|yml|yaml|json|sql|py|ps1|mjs)$/.test(f)).sort();
const records=files.map(f=>({path:f.replaceAll('\\','/'),sha256:crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex')}));
fs.writeFileSync(path.join(base,process.argv[2]),JSON.stringify(records,null,2)+'\n');console.log(records.length);
