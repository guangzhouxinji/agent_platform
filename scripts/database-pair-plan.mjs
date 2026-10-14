import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { databasePairPlan, verifyMigrationHashes, verifyOwnedPair } from "./lib/database-pair.mjs";
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const [configPath, inspectionPath] = process.argv.slice(2);
if (!configPath) throw new Error("CONFIG_PATH_REQUIRED");
const plan = databasePairPlan(JSON.parse(fs.readFileSync(configPath, "utf8").replace(/^\uFEFF/, "")), root);
verifyMigrationHashes(plan, root);
if (inspectionPath) {
 const inspection = JSON.parse(fs.readFileSync(inspectionPath, "utf8").replace(/^\uFEFF/, ""));
 verifyOwnedPair(plan, inspection.containers, inspection.volumes, inspection.imageId);
}
console.log(JSON.stringify(plan, null, 2));
