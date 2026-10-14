import { spawnSync } from "node:child_process";

for (const args of [
  ["--test", "scripts/lib/agent-boundaries.test.mjs", "scripts/lib/patent-archive.test.mjs", "scripts/verify-hupd-capture.test.mjs"],
  ["scripts/verify-governance.mjs"],
  ["scripts/verify-governance.mjs", "--self-test"],
  ["scripts/generate-project-progress.mjs"],
  ["scripts/verify-project-plan.mjs"],
  ["scripts/verify-structure.mjs"],
  ["scripts/verify-consultation-feature-candidates.mjs", "--self-test"],
  ["scripts/verify-consultation-comparison-input.mjs", "--self-test"],
  ["scripts/verify-consultation-correction-input.mjs", "--self-test"],
  ["scripts/verify-consultation-human-gap.mjs", "--self-test"],
  ["scripts/verify-enforcement-dev-synthetic-input.mjs", "--self-test"],
  ["scripts/verify-enforcement-dev-synthetic-v2.mjs", "--self-test"],
  ["scripts/verify-enforcement-rule-draft.mjs", "--self-test"],
  ["scripts/verify-enforcement-material-dev-input.mjs", "--self-test"],
  ["scripts/verify-enforcement-dev-discovery-rule.mjs", "--self-test"],
  ["scripts/verify-enforcement-dev-rule-release.mjs", "--self-test"],
  ["scripts/verify-enforcement-material-dev-preflight.mjs", "--self-test"],
  ["scripts/collect-enforcement-source-review.mjs", "--verify-only"],
  ["scripts/build-enforcement-public-reference-pilot.mjs", "--verify-only"],
  ["scripts/build-cnipa-public-historical-cases.mjs", "--verify-only", "--self-test"],
  ["scripts/collect-enforcement-official-sources.mjs", "--verify-only"],
  ["scripts/collect-enforcement-official-sources.mjs", "--self-test"],
  ["scripts/collect-enforcement-public-job-pack.mjs", "--verify-only"],
  ["scripts/collect-enforcement-public-job-pack.mjs", "--self-test"],
  ["scripts/collect-cnipa-rules-documents.mjs", "--verify-only"],
  ["scripts/collect-cnipa-rules-documents.mjs", "--self-test"],
  ["scripts/verify-cnipa-record-review.mjs", "--self-test"],
  ["scripts/build-cnipa-domain-mapping.mjs", "--self-test"],
  ["scripts/build-cnipa-domain-consumption.mjs", "--self-test"],
  ["scripts/build-cnipa-authorized-enforcement-package.mjs", "--verify-only"],
  ["scripts/build-cnipa-authorized-enforcement-package.mjs", "--self-test"],
  ["scripts/collect-adjudication-source-review.mjs", "--verify-only"],
  ["scripts/build-adjudication-public-reference-pilot.mjs", "--verify-only"],
  ["scripts/verify-adjudication-dev-synthetic-case.mjs", "--self-test"],
  ["scripts/verify-adjudication-case-work-input-v2.mjs", "--self-test"],
]) {
  const result = spawnSync(process.execPath, args, { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

console.log("Requirement baseline, project plan health, architecture structure, three-domain synthetic input boundaries, approval/import data, 41-item ledger, contracts, and evidence gates passed.");
