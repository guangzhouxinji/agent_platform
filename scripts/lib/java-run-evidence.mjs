import fs from "node:fs";
import path from "node:path";
const keys=["tests","failures","errors","skipped"];
function requireValue(condition,message){if(!condition)throw new Error(`JAVA_EVIDENCE_INVALID: ${message}`);}
function containedFile(root,base,relative){
  requireValue(typeof relative==="string" && !path.isAbsolute(relative) && !/^[A-Za-z]+:/.test(relative),"relative evidence path required");
  const target=fs.realpathSync(path.resolve(base,relative.replaceAll("\\","/")));
  const rel=path.relative(fs.realpathSync(root),target);
  requireValue(rel!=="" && rel!==".." && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel),"evidence path escapes workspace");
  return target;
}
function link(markdown,label){
  const matches=[...markdown.matchAll(new RegExp(`^${label}：\\[[^\\]]+\\]\\(([^)]+)\\)\\s*$`,"gm"))];
  requireValue(matches.length===1,`exactly one ${label} reference required`);return matches[0][1];
}
export function readJavaRunEvidence(root,markdown){
  const reportFile=containedFile(root,path.join(root,"docs"),link(markdown,"最近未筛选Java回归报告"));
  const exitFile=containedFile(root,path.join(root,"docs"),link(markdown,"最近未筛选Java回归退出"));
  requireValue(path.dirname(reportFile)===path.dirname(exitFile),"report and exit must belong to same evidence directory");
  const report=JSON.parse(fs.readFileSync(reportFile,"utf8").replace(/^\uFEFF/,""));
  const exit=JSON.parse(fs.readFileSync(exitFile,"utf8").replace(/^\uFEFF/,""));
  requireValue(typeof report.scope==="string" && report.scope.startsWith("FULL_"),"focused results are not an unfiltered run");
  requireValue(Number.isInteger(exit.exit) && exit.exit>=0 && Number.isFinite(Date.parse(exit.finishedAt)),"actual exit/time required");
  requireValue(Array.isArray(report.reports) && report.reports.length>0,"suite evidence required");
  const totals={tests:0,failures:0,errors:0,skipped:0},names=new Set(),files=new Set();
  for(const item of report.reports){
    const file=containedFile(root,path.dirname(reportFile),item.file);
    requireValue(!names.has(item.name)&&!files.has(file),"duplicate suite or file");names.add(item.name);files.add(file);
    const suite=fs.readFileSync(file,"utf8").match(/<testsuite\b[^>]*>/)?.[0];
    requireValue(suite && suite.match(/\bname="([^"]+)"/)?.[1]===item.name,"suite name mismatch");
    for(const key of keys){
      requireValue(Number.isInteger(item[key]) && item[key]>=0,`invalid ${key}`);
      const actual=suite.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1];
      requireValue(actual!==undefined && Number(actual)===item[key],`XML ${key} mismatch`);totals[key]+=item[key];
    }
    requireValue(item.failures+item.errors+item.skipped<=item.tests,"suite counts inconsistent");
  }
  for(const key of keys)requireValue(report.totals?.[key]===totals[key],`total ${key} mismatch`);
  requireValue(exit.exit!==0 || totals.failures+totals.errors===0,"successful exit contradicts failed suites");
  return {...totals,reportCount:report.reports.length,latestAt:new Date(exit.finishedAt).toISOString(),exitCode:exit.exit,
    scope:report.scope,reportPath:path.relative(root,reportFile).replaceAll("\\","/"),exitPath:path.relative(root,exitFile).replaceAll("\\","/")};
}
