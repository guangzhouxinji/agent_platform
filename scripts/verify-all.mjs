import { spawnSync } from "node:child_process";

for (const args of [
  ["scripts/verify-governance.mjs"],
  ["scripts/verify-governance.mjs", "--self-test"],
  ["scripts/generate-project-progress.mjs"],
  ["scripts/verify-project-plan.mjs"],
]) {
  const result = spawnSync(process.execPath, args, { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

console.log("Requirement baseline, project plan health, 41-item ledger, contracts, and evidence gates passed.");
