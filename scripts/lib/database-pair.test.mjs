import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { databasePairPlan, verifyOwnedPair, verifyMigrationHashes } from "./database-pair.mjs";
const root = process.cwd();
const config = () => JSON.parse(fs.readFileSync("deployment/database-pair/config.json", "utf8"));
function inspection(plan) {
 return {
 containers: plan.instances.map(i => ({ Name: `/${i.container}`, Image: plan.imageId, State: { Running: true }, Config: { Labels: { "agent-platform.role": `database-pair-${i.kind}`, "agent-platform.pair": plan.pairName, "agent-platform.contract-sha": plan.contractSha } }, Mounts: [{ Type: "volume", Name: i.volume, Destination: "/opt/dmdbms/data", RW: true }], HostConfig: { PortBindings: { "5236/tcp": [{ HostIp: "127.0.0.1", HostPort: String(i.port) }] } } })),
 volumes: plan.instances.map(i => ({ Name: i.volume, Labels: { "agent-platform.role": `database-pair-${i.kind}`, "agent-platform.pair": plan.pairName, "agent-platform.contract-sha": plan.contractSha } })),
 };
}
test("two persisted instances, six schemas and twelve accounts; no product activation", () => {
 const p = databasePairPlan(config(), root);
 assert.equal(p.instances.length, 2); assert.equal(p.domains.length, 3);
 assert.equal(new Set(p.domains.flatMap(d => [d.businessSchema, d.businessApp, d.authoritySchema, d.authorityApp])).size, 12);
 assert.match(p.scope, /NO_PRODUCT_ACTIVATION/); assert.ok(p.migrations.every(m => /^[a-f0-9]{64}$/.test(m.sha256)));
 assert.ok(p.domains.every(d => d.authorityMigrations.some(m => m.file.includes("V001__grant"))));
 assert.ok(!p.migrations.some(m => /bootstrap|candidate|fixture/.test(m.file)));
 const x=inspection(p); verifyOwnedPair(p,x.containers,x.volumes,p.imageId);
});
test("protected or equal ports, remote endpoints and mutable image tags rejected", () => {
 for (const patch of [{businessPort:5301},{authorityPort:5312},{businessPort:5432},{businessPort:65536},{authorityPort:5432.5},{imageId:"cnxc/dm8:latest"},{pairName:"agent-platform-dm8"},{jdbcUrl:"jdbc:dm://remote"}]) assert.throws(() => databasePairPlan({...config(),...patch},root));
});
test("domain set and schema/owner/app boundaries cannot collapse", () => {
 for (const mutate of [c=>c.domains.pop(),c=>c.domains[1].domain="consultation",c=>c.domains[1].businessSchema=c.domains[0].businessSchema,c=>c.domains[0].businessApp=c.domains[0].businessSchema,c=>c.domains[0].authoritySchema="SYSDBA",c=>c.domains[0].authoritySchema='X; DROP USER X',c=>c.domains[0].grantAll=true]) { const c=config();mutate(c);assert.throws(()=>databasePairPlan(c,root)); }
});
test("six schemas can use configured names without changing source migrations", () => {
 const c=config();c.domains[0].businessSchema="CON_PROPOSED";
 const p=databasePairPlan(c,root);assert.notEqual(p.contractSha,databasePairPlan(config(),root).contractSha);
 verifyMigrationHashes(p,root);
});
test("foreign, stale, unmounted and shared-like Docker resources fail ownership validation", () => {
 const p=databasePairPlan(config(),root);
 for(const mutate of [x=>x.containers.pop(),x=>x.volumes.pop(),x=>x.containers[0].Image="sha256:"+"0".repeat(64),x=>x.containers[0].State.Running=false,x=>x.containers[0].Config.Labels["agent-platform.pair"]="foreign",x=>x.volumes[0].Labels["agent-platform.contract-sha"]="stale",x=>x.containers[0].Mounts.push({...x.containers[0].Mounts[0]}),x=>x.containers[0].Mounts[0].Name="original-product",x=>x.containers[0].Mounts[0].RW=false,x=>x.containers[0].HostConfig.PortBindings["5236/tcp"][0].HostIp="0.0.0.0",x=>x.containers[0].HostConfig.PortBindings["5236/tcp"][0].HostPort="5301"]) { const x=inspection(p);mutate(x);assert.throws(()=>verifyOwnedPair(p,x.containers,x.volumes,p.imageId)); }
});
test("real migration byte drift rejected rather than silently reusing initialized resources", () => {
 const p=databasePairPlan(config(),root),temp=fs.mkdtempSync(path.join(os.tmpdir(),"db-pair-hash-"));
 try {
  for(const m of p.migrations) { const f=path.join(temp,m.file);fs.mkdirSync(path.dirname(f),{recursive:true});fs.copyFileSync(path.join(root,m.file),f); }
  verifyMigrationHashes(p,temp);fs.appendFileSync(path.join(temp,p.migrations[0].file),"\n-- changed\n");assert.throws(()=>verifyMigrationHashes(p,temp),/MIGRATION_SHA_CHANGED/);
 } finally { fs.rmSync(temp,{recursive:true}); }
});
