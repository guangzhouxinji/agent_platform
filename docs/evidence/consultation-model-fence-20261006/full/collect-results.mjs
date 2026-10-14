import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const root=process.cwd(),base=path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/,'$1'));
const started=Date.parse(JSON.parse(fs.readFileSync(path.join(base,'full-round02-input-check.json'),'utf8').replace(/^\uFEFF/,'' )).startedAt);
const exit=JSON.parse(fs.readFileSync(path.join(base,'full-round02-exit.json'),'utf8').replace(/^\uFEFF/,''));
const runLog=fs.readFileSync(path.join(base,'full-round02-unfiltered.log'),'utf8');
const runNames=new Set([...runLog.matchAll(/\[INFO\] Running ([A-Za-z0-9_.$]+)/g)].map(m=>m[1]));
const excludedInventory=[];
const reports=[],skips=[],totals={tests:0,failures:0,errors:0,skipped:0},seen=new Set();
function visit(dir) {
 for(const entry of fs.readdirSync(dir,{withFileTypes:true})) {
  const file=path.join(dir,entry.name);
  if(entry.isDirectory()){if(entry.name!=='node_modules')visit(file);continue;}
  if(!entry.name.startsWith('TEST-') || !entry.name.endsWith('.xml') || path.basename(dir)!=='surefire-reports')continue;
  const text=fs.readFileSync(file,'utf8'),tag=text.match(/<testsuite\b[^>]*>/)?.[0];if(!tag)throw Error('Missing suite: '+file);
  const name=tag.match(/\bname="([^"]+)"/)?.[1];
  if(entry.name!=='TEST-'+name+'.xml')continue; // Focused/suffixed inventory is NOT this run.
  if(!runNames.has(name)){excludedInventory.push({name,file:path.relative(root,file).replaceAll('\\','/'),reason:'not executed by this Maven command'});continue;}
  if(fs.statSync(file).mtimeMs<started-1000)throw Error('Stale executed suite: '+file);
  if(seen.has(name))throw Error('Duplicate suite: '+name);seen.add(name);
  const item={name};for(const key of Object.keys(totals)){item[key]=Number(tag.match(new RegExp('\\b'+key+'="(\\d+)"'))?.[1]);if(!Number.isInteger(item[key]))throw Error('Missing count');totals[key]+=item[key];}
  const relative=path.relative(path.join(root,'backend'),file);item.file=path.join('full-round02-reports',relative).replaceAll('\\','/');
  const dest=path.join(base,item.file);fs.mkdirSync(path.dirname(dest),{recursive:true});if(fs.existsSync(dest)){if(!fs.readFileSync(dest).equals(fs.readFileSync(file)))throw Error('Never overwrite different suite evidence');}else fs.copyFileSync(file,dest);
  item.sha256=crypto.createHash('sha256').update(text).digest('hex');reports.push(item);
  for(const match of text.matchAll(/<testcase\b[^>]*>[\s\S]*?<\/testcase>/g))if(match[0].includes('<skipped'))skips.push({suite:name,name:match[0].match(/\bname="([^"]+)"/)?.[1],reason:match[0].match(/<skipped\b[^>]*message="([^"]*)"/)?.[1]??'conditional skip'});
 }
}
visit(path.join(root,'backend'));reports.sort((a,b)=>a.name.localeCompare(b.name));
for(const name of runNames)if(!seen.has(name))throw Error('Executed suite missing original: '+name);
if(exit.exit===0 && totals.failures+totals.errors!==0)throw Error('Exit contradicts reports');
fs.writeFileSync(path.join(base,'full-round02-result.json'),JSON.stringify({scope:'FULL_BACKEND_UNFILTERED_ALL_REACTOR_MODULES_CON_MODEL_FENCE_20261006',totals,reports,skips,excludedInventory},null,2));
console.log(JSON.stringify({exit:exit.exit,reportCount:reports.length,totals}));
