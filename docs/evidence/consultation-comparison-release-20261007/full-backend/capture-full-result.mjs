import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
const root=process.cwd(), base=path.join(root,'docs/evidence/consultation-comparison-release-20261007/full-backend');
const read=f=>JSON.parse(fs.readFileSync(path.join(base,f),'utf8').replace(/^\uFEFF/,''));
const start=read('full-start.json'),exit=read('full-backend-round1-exit.json');
const before=read('full-inputs-before.json'),after=read('full-inputs-after.json');
if(JSON.stringify(before.map(x=>[x.path,x.sha256]))!==JSON.stringify(after.map(x=>[x.path,x.sha256])))throw Error('Input hash drift');
function walk(dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(d=>d.isDirectory()?walk(path.join(dir,d.name)):[path.join(dir,d.name)]);}
const files=walk(path.join(root,'backend')).filter(f=>/[\\/]target[\\/]surefire-reports[\\/]TEST-[^\\/]+\.xml$/.test(f)&&fs.statSync(f).mtimeMs>=Date.parse(start.startedAt));
const out=path.join(base,'reports');fs.mkdirSync(out,{recursive:true});
const reports=[],names=new Set(),totals={tests:0,failures:0,errors:0,skipped:0};
for(const file of files){
  const bytes=fs.readFileSync(file),tag=bytes.toString('utf8').match(/<testsuite\b[^>]*>/)?.[0];
  if(!tag)throw Error('Missing suite '+file);
  const name=tag.match(/\bname="([^"]+)"/)?.[1];if(!name||names.has(name))throw Error('Duplicate or missing suite '+name);names.add(name);
  const record={name};for(const k of Object.keys(totals)){const n=Number(tag.match(new RegExp(`\\b${k}="(\\d+)"`))?.[1]);if(!Number.isInteger(n))throw Error('Invalid count');record[k]=n;totals[k]+=n;}
  const key=crypto.createHash('sha256').update(name).digest('hex').slice(0,24)+'.xml';
  record.file='reports/'+key;record.sha256=crypto.createHash('sha256').update(bytes).digest('hex');
  record.originalPath=path.relative(root,file).replaceAll('\\','/');fs.writeFileSync(path.join(out,key),bytes);reports.push(record);
}
if(!reports.length)throw Error('No fresh suites');
const log=fs.readFileSync(path.join(base,'full-backend-round1.log'),'utf8');
if(exit.exit===0&&(!log.includes('BUILD SUCCESS')||totals.failures+totals.errors!==0))throw Error('Exit/result mismatch');
const report={scope:'FULL_BACKEND_UNFILTERED_ALL_REACTOR_ATTEMPT_COMPARISON_RELEASE_20261007',reactorCompleted:exit.exit===0,command:start.command,
  startedAt:start.startedAt,finishedAt:exit.finishedAt,totals,reports:reports.sort((a,b)=>a.name.localeCompare(b.name)),
  inputSnapshotBefore:'full-inputs-before.json',inputSnapshotAfter:'full-inputs-after.json',inputsUnchanged:true,inputCount:before.length,
  limitations:['Conditional skips are not passes or product admission','No formal deployment/database migration/business model calls were performed']};
fs.writeFileSync(path.join(base,'full-backend-round1-result.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({exit:exit.exit,totals,reportCount:reports.length,inputCount:before.length}));
