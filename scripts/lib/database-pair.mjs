import fs from "node:fs";
import path from "node:path";
import { createHash } from "node:crypto";
export const sha256 = bytes => createHash("sha256").update(bytes).digest("hex");
const businessFiles = ["db/dm8/V032__product_identity.sql", "db/dm8/V033__product_oauth2.sql", "db/dm8/V046__product_workload_subject.sql", "db/dm8/V057__product_oauth_client_control.sql", "db/dm8/V096__agent_runtime_checkpoint.sql"];
const authorityFiles = {
 consultation: ["db/identity-authority/V003__consultation_source_watermark.sql", "db/identity-authority/V004__consultation_source_recovery.sql"],
 enforcement: ["db/identity-authority/V002__enforcement_source_watermark.sql", "db/identity-authority/V005__enforcement_delete_watermark.sql"],
 adjudication: ["db/dm8/adjudication_source_authority.sql"]
};
const commonAuthority = "db/identity-authority/V001__grant_watermark.sql";
function requireValue(ok, reason) { if (!ok) throw new Error(reason); }
function exactKeys(object, keys) {
 requireValue(object && typeof object === "object" && !Array.isArray(object), "OBJECT_REQUIRED");
 requireValue(Object.keys(object).sort().join() === [...keys].sort().join(), "EXACT_CONFIG_KEYS_REQUIRED");
}
export function databasePairPlan(config, workspace) {
 exactKeys(config, ["version", "pairName", "imageId", "businessPort", "authorityPort", "domains"]);
 requireValue(config.version === 1, "CONFIG_VERSION_UNSUPPORTED");
 requireValue(/^agent-platform-db-pair(?:-[a-z0-9]+(?:-[a-z0-9]+)*)?$/.test(config.pairName) && config.pairName.length <= 48, "OWNED_PAIR_NAME_REQUIRED");
 requireValue(/^sha256:[a-f0-9]{64}$/.test(config.imageId), "PINNED_LOCAL_IMAGE_REQUIRED");
 const protectedPorts = [5236, 5237, 5301, 5302, 5311, 5312, 5319, 5367, 5370, 5443, 5444, 5445];
 for (const port of [config.businessPort, config.authorityPort]) requireValue(Number.isInteger(port) && port >= 1024 && port <= 65535 && !protectedPorts.includes(port), "NEW_UNPROTECTED_PORT_REQUIRED");
 requireValue(config.businessPort !== config.authorityPort, "TWO_INDEPENDENT_ENDPOINTS_REQUIRED");
 requireValue(Array.isArray(config.domains) && config.domains.length === 3, "THREE_INDEPENDENT_DOMAINS_REQUIRED");
 const domainNames = Object.keys(authorityFiles);
 requireValue(config.domains.map(d => d.domain).sort().join() === domainNames.sort().join(), "DOMAIN_SET_INVALID");
 const names = [];
 const migrations = new Map();
 function migration(file) {
  if (!migrations.has(file)) {
   const bytes = fs.readFileSync(path.join(workspace, file));
   const tables = [...bytes.toString("utf8").matchAll(/^CREATE TABLE\s+([a-z][a-z0-9_]*)/gim)].map(m => m[1].toUpperCase());
   requireValue(tables.length > 0 || file.includes("V046"), "MIGRATION_TABLES_REQUIRED");
   migrations.set(file, { file, sha256: sha256(bytes), tables });
  }
  return migrations.get(file);
 }
 const domains = config.domains.map(d => {
  exactKeys(d, ["domain", "businessSchema", "businessApp", "authoritySchema", "authorityApp"]);
  for (const key of ["businessSchema", "businessApp", "authoritySchema", "authorityApp"]) {
   requireValue(typeof d[key] === "string" && /^[A-Z][A-Z0-9_]{2,29}$/.test(d[key]) && !/^(SYS|PUBLIC|DBA)/.test(d[key]), "SAFE_DISTINCT_ACCOUNTS_REQUIRED");
   names.push(d[key]);
  }
  return { ...d, businessMigrations: businessFiles.map(migration), authorityMigrations: [commonAuthority, ...authorityFiles[d.domain]].map(migration) };
 });
 requireValue(new Set(names).size === names.length, "ACCOUNT_OR_SCHEMA_COLLISION");
 const instances = ["business", "authority"].map(kind => ({ kind, container: `${config.pairName}-${kind}`, volume: `${config.pairName}-${kind}-data`, port: config[`${kind}Port`], jdbcUrl: `jdbc:dm://127.0.0.1:${config[`${kind}Port`]}` }));
 const proof = { version: 1, pairName: config.pairName, imageId: config.imageId, domains, instances };
 return { ...proof, contractSha: sha256(JSON.stringify(proof)), scope: "EMPTY_BASELINES_NO_PRODUCT_ACTIVATION_OR_DATA_MIGRATION", migrations: [...migrations.values()] };
}
export function verifyMigrationHashes(plan, workspace) {
 for (const migration of plan.migrations) requireValue(sha256(fs.readFileSync(path.join(workspace, migration.file))) === migration.sha256, "MIGRATION_SHA_CHANGED");
}
export function verifyOwnedPair(plan, containers, volumes, imageId) {
 requireValue(imageId === plan.imageId && containers.length === 2 && volumes.length === 2, "EXACT_PAIR_AND_IMAGE_REQUIRED");
 for (const instance of plan.instances) {
  const c = containers.find(c => c.Name === `/${instance.container}`);
  const v = volumes.find(v => v.Name === instance.volume);
  requireValue(c && v && c.State?.Running === true && c.Image === plan.imageId, "OWNED_RUNNING_INSTANCE_REQUIRED");
  for (const labels of [c.Config?.Labels, v.Labels]) requireValue(labels?.["agent-platform.role"] === `database-pair-${instance.kind}` && labels?.["agent-platform.pair"] === plan.pairName && labels?.["agent-platform.contract-sha"] === plan.contractSha, "RESOURCE_OWNERSHIP_MISMATCH");
  requireValue(c.Mounts?.length === 1 && c.Mounts[0].Type === "volume" && c.Mounts[0].Name === instance.volume && c.Mounts[0].Destination === "/opt/dmdbms/data" && c.Mounts[0].RW === true, "EXACT_OWNED_DATA_VOLUME_REQUIRED");
  const ports = c.HostConfig?.PortBindings;
  requireValue(ports && Object.keys(ports).length === 1 && ports["5236/tcp"]?.length === 1 && ports["5236/tcp"][0].HostIp === "127.0.0.1" && ports["5236/tcp"][0].HostPort === String(instance.port), "EXACT_LOOPBACK_BINDING_REQUIRED");
 }
}
