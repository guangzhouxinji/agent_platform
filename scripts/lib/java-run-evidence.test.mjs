import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { readJavaRunEvidence } from "./java-run-evidence.mjs";
function fixture(t){
 const root=fs.mkdtempSync(path.join(os.tmpdir(),"agent-evidence-"));t.after(()=>{const resolved=fs.realpathSync(root);assert.equal(path.dirname(resolved),fs.realpathSync(os.tmpdir()));assert.ok(path.basename(resolved).startsWith("agent-evidence-"));fs.rmSync(resolved,{recursive:true,force:true});});
 fs.mkdirSync(path.join(root,"docs/run"),{recursive:true});
 const report={scope:"FULL_COMPONENT_TEST",totals:{tests:2,failures:0,errors:1,skipped:0},reports:[{name:"Suite",file:"suite.xml",tests:2,failures:0,errors:1,skipped:0}]};
 const exit={exit:1,finishedAt:"2026-10-05T21:40:39+08:00"};
 const write=()=>{fs.writeFileSync(path.join(root,"docs/run/result.json"),JSON.stringify(report));fs.writeFileSync(path.join(root,"docs/run/exit.json"),JSON.stringify(exit));};
 fs.writeFileSync(path.join(root,"docs/run/suite.xml"),'<testsuite name="Suite" tests="2" failures="0" errors="1" skipped="0"></testsuite>');write();
 const markdown="最近未筛选Java回归报告：[原件](run/result.json)\n最近未筛选Java回归退出：[退出](run/exit.json)\n";
 return {root,report,exit,write,markdown,read:()=>readJavaRunEvidence(root,markdown)};
}
test("reads exact retained run rather than newer unrelated red/green inventory",t=>{const f=fixture(t);fs.writeFileSync(path.join(f.root,"docs/run/unrelated.xml"),'<testsuite tests="9999" errors="100"/>');const value=f.read();assert.equal(value.tests,2);assert.equal(value.errors,1);assert.equal(value.exitCode,1);assert.equal(value.reportCount,1);});
test("rejects focused report promoted to full evidence",t=>{const f=fixture(t);f.report.scope="SELECTED_AGENT_TESTS";f.write();assert.throws(f.read,/focused/);});
test("rejects duplicate suite without silently double counting",t=>{const f=fixture(t);f.report.reports.push(f.report.reports[0]);f.write();assert.throws(f.read,/duplicate/);});
test("rechecks XML rather than trusting summary totals",t=>{const f=fixture(t);f.report.reports[0].errors=0;f.report.totals.errors=0;f.write();assert.throws(f.read,/XML errors mismatch/);});
test("summary total or successful exit cannot hide errors",t=>{const f=fixture(t);f.report.totals.errors=0;f.write();assert.throws(f.read,/total errors/);f.report.totals.errors=1;f.exit.exit=0;f.write();assert.throws(f.read,/contradicts/);});
test("requires a single master-plan link and real exit time",t=>{const f=fixture(t);assert.throws(()=>readJavaRunEvidence(f.root,f.markdown+f.markdown),/exactly one/);f.exit.finishedAt="invalid";f.write();assert.throws(f.read,/actual exit/);});
test("missing XML fails closed without falling back to local reports",t=>{const f=fixture(t);fs.unlinkSync(path.join(f.root,"docs/run/suite.xml"));assert.throws(f.read);});
test("absolute evidence paths are rejected",t=>{const f=fixture(t);f.report.reports[0].file=path.join(f.root,"docs/run/suite.xml");f.write();assert.throws(f.read,/relative evidence path/);});

test("relative traversal cannot read evidence outside the workspace",t=>{
 const outer=fixture(t),inner=fixture(t);
 inner.report.reports[0].file=path.relative(path.join(inner.root,"docs/run"),path.join(outer.root,"docs/run/suite.xml"));inner.write();
 assert.throws(inner.read,/escapes workspace/);
});
test("report and exit from different directories cannot be combined",t=>{
 const f=fixture(t);fs.mkdirSync(path.join(f.root,"docs/other"));fs.copyFileSync(path.join(f.root,"docs/run/exit.json"),path.join(f.root,"docs/other/exit.json"));
 assert.throws(()=>readJavaRunEvidence(f.root,f.markdown.replace("run/exit.json","other/exit.json")),/same evidence directory/);
});