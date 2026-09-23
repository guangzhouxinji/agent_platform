import { spawnSync } from "node:child_process";

for (const args of [
  ["scripts/verify-governance.mjs"],
  ["scripts/verify-governance.mjs", "--self-test"],
  ["scripts/generate-project-progress.mjs"],
  ["scripts/verify-project-plan.mjs"],
  ["scripts/verify-structure.mjs"],
  ["scripts/collect-enforcement-source-review.mjs", "--verify-only"],
  ["scripts/build-enforcement-public-reference-pilot.mjs", "--verify-only"],
  ["scripts/build-cnipa-public-historical-cases.mjs", "--verify-only", "--self-test"],
  ["scripts/collect-enforcement-official-sources.mjs", "--verify-only"],
  ["scripts/collect-enforcement-official-sources.mjs", "--self-test"],
  ["scripts/verify-cnipa-record-review.mjs", "--self-test"],
  ["scripts/build-cnipa-domain-mapping.mjs", "--self-test"],
  ["scripts/build-cnipa-domain-consumption.mjs", "--self-test"],
  ["scripts/build-cnipa-authorized-enforcement-package.mjs", "--verify-only"],
  ["scripts/build-cnipa-authorized-enforcement-package.mjs", "--self-test"],
  ["scripts/collect-adjudication-source-review.mjs", "--verify-only"],
  ["scripts/build-adjudication-public-reference-pilot.mjs", "--verify-only"],
]) {
  const result = spawnSync(process.execPath, args, { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

console.log("Requirement baseline, project plan health, architecture structure, enforcement and adjudication approval/import data, 41-item ledger, contracts, and evidence gates passed.");
