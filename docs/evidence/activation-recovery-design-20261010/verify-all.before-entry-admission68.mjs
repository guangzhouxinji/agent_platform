import { spawnSync } from "node:child_process";
import { runVerificationStages, verificationExitCode } from "./lib/verification-stages.mjs";

const args = process.argv.slice(2);
if (args.length > 1 || (args.length === 1 && args[0] !== '--test-health-exit-code')) {
  console.error('Unsupported verification option'); process.exit(1);
}
const stages = [
  ["--test", "--test-concurrency=2", ...(process.platform === "win32" ? ["scripts/lib/single-database-owned-image-admission.test.mjs", "scripts/lib/single-database-adjudication-tls-publish.test.mjs", "scripts/lib/single-database-adjudication-tls-publish41.test.mjs", "scripts/lib/single-database-adjudication-tls-publish42.test.mjs", "scripts/lib/single-database-enforcement-tls-publish44.test.mjs"] : []), "scripts/lib/single-database-grant-session.test.mjs", "scripts/lib/single-database-grant-session-port.test.mjs", "scripts/lib/single-database-object-grants-session.test.mjs", "scripts/lib/single-database-installation.test.mjs", "scripts/lib/single-database-final-installation.test.mjs", "scripts/lib/single-database-object-grants.test.mjs", "scripts/lib/single-database-jdbc-container.test.mjs", "scripts/lib/single-database-jdbc-termination.test.mjs", "scripts/lib/single-database-regression-launch.test.mjs", "scripts/lib/single-database-native-fence-container.test.mjs", "scripts/lib/single-database-bootstrap.test.mjs", "scripts/lib/single-database-bootstrap-fence.test.mjs", "scripts/lib/single-database-structure-checks.test.mjs", "scripts/lib/single-database-dm8-startup-observation.test.mjs", "scripts/lib/single-database-owned-restore-entry.test.mjs", "scripts/lib/single-database-continuous-window-admission.test.mjs", "scripts/lib/single-database-restore-native-preparation.test.mjs", "scripts/lib/single-database-restore-catalog.test.mjs", "scripts/lib/single-database-restore-comparison.test.mjs", "scripts/lib/single-database-restore-evidence.test.mjs", "scripts/lib/single-database-restore-comparison-cli.test.mjs", "scripts/lib/single-database-restore-profile-admission.test.mjs", "scripts/lib/single-database-recovery-origin.test.mjs", "scripts/lib/single-database-recovery-seal.test.mjs", "scripts/lib/single-database-source-catalog.test.mjs", "scripts/lib/single-database-lifecycle.test.mjs", "scripts/lib/single-database-lifecycle-native.test.mjs", "scripts/lib/single-database-native-termination.test.mjs", "scripts/lib/single-database-lifecycle-restore.test.mjs", "scripts/lib/single-database-lifecycle-native-restore.test.mjs", "scripts/lib/single-database-lifecycle-runtime.test.mjs", "scripts/lib/single-database-snapshot-capacity.test.mjs", "scripts/lib/single-database-migration-plan.test.mjs", "scripts/lib/consultation-activation-recovery-migration.test.mjs", "scripts/lib/single-database-deployment.test.mjs", "scripts/lib/single-database-windows-reference.test.mjs", "scripts/lib/single-database-failed-recovery.test.mjs", "scripts/lib/single-database-current-failed-resume.test.mjs", "scripts/lib/single-database-local-tls-publish.test.mjs", "scripts/lib/consultation-product-assets.test.mjs", "scripts/lib/enforcement-product-assets.test.mjs", "scripts/lib/enforcement-product-entry-source.test.mjs", "scripts/lib/consultation-comparison-deployment.test.mjs", "scripts/lib/consultation-comparison-release.test.mjs", "scripts/lib/plan-dependencies.test.mjs", "scripts/lib/verification-stages.test.mjs", "scripts/lib/quality-gate-order.test.mjs", "scripts/lib/java-run-evidence.test.mjs", "scripts/lib/agent-boundaries.test.mjs", "scripts/lib/patent-archive.test.mjs", "scripts/verify-hupd-capture.test.mjs"],
  ["scripts/verify-governance.mjs"],
  ["scripts/verify-governance.mjs", "--self-test"],
  ["scripts/generate-project-progress.mjs"],
  ["scripts/verify-project-plan.mjs"],
  ["scripts/verify-structure.mjs"],
  ["--test", "scripts/verify-consultation-task-quality.test.mjs", "scripts/evaluate-consultation-task-quality.test.mjs", "scripts/consultation-professional-review.test.mjs"],
  ["scripts/evaluate-consultation-task-quality.mjs", "--verify"],
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
].map(args => ({ args, deferTestHealth: args[0] === "scripts/verify-project-plan.mjs" }));
const result = runVerificationStages(stages, args => spawnSync(process.execPath, args, { stdio: "inherit" }).status, console.error);
const exitCode = verificationExitCode(result, args[0] === '--test-health-exit-code');
if (exitCode !== 0) process.exit(exitCode);

console.log("Requirement baseline, project plan health, architecture structure, three-domain synthetic input boundaries, approval/import data, 41-item ledger, contracts, and evidence gates passed.");



