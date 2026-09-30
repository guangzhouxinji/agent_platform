import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = "data/enforcement/material-dev-product-contract-v1";
const read = (name) => JSON.parse(readFileSync(join(root, name), "utf8"));
const fail = (code) => { throw new Error(code); };

function verify(plan, recovery, manifest, observations, scenarios, collection, rule) {
  const batch = manifest.batchId;
  if (plan.batchId !== batch || recovery.batchId !== batch ||
      plan.status !== "PREPARED_NOT_RUN" || plan.formalBusinessAcceptance !== "NOT_RUN" ||
      plan.grantIntent !== manifest.authorizationIntent.file ||
      plan.ruleDraft !== "discovery-rule-draft.json" ||
      plan.recoveryProtocol !== "recovery-protocol.json") fail("PLAN_BOUNDARY");
  const caseIds = ["M01", "M02", "M03", "M04", "M05", "M06"];
  if (plan.cases.length !== 6 || plan.cases.some((item, index) =>
      item.id !== caseIds[index] || !item.contract || !item.forbidden.length ||
      [item.c, item.i, item.internalEvaluation].some((value) => value !== "NOT_RUN"))) fail("CASE_STATUS");
  const validInputs = new Set([
    ...observations.records.map((record) => `${record.recordId}@${record.version}`),
    ...scenarios.scenarios.map((scenario) => scenario.id),
    ...recovery.scenarios.map((scenario) => scenario.id),
    ...rule.examples.map((example) => example.observation),
    `${rule.ruleId}@${rule.revision}`,
    manifest.material.file, manifest.collectionInput.file, manifest.authorizationIntent.file
  ]);
  for (const item of plan.cases) {
    if (!item.inputs.length || item.inputs.some((input) => !validInputs.has(input))) fail("INPUT_REFERENCE");
  }
  if (collection.target.verificationResult !== null || rule.status !== "DRAFT") fail("PREMATURE_ACTION");
  if (recovery.status !== "NOT_RUN" || recovery.result !== "NOT_RUN" ||
      recovery.environment.instances !== 2 ||
      ["observedEndpoints", "observedBackupId", "observedCheckpoint", "observedAuthorityEpoch"]
        .some((key) => recovery.environment[key] !== null)) fail("RECOVERY_STATUS");
  const recoveryIds = ["COMMON_CHECKPOINT", "OLD_BACKUP_EPOCH", "MISSING_AUTHORITY",
    "LOST_RESPONSE", "ORPHAN_CLEANUP", "WITHDRAWAL_INTERLEAVING"];
  if (recovery.scenarios.length !== recoveryIds.length || recovery.scenarios.some((item, index) =>
      item.id !== recoveryIds[index] || item.run !== "NOT_RUN" ||
      !item.setup || !item.action || !item.expected)) fail("RECOVERY_CASES");
  if (!recovery.preflight.length || !recovery.evidenceRequired.length ||
      !plan.executionEvidenceRequired.length) fail("EVIDENCE_CONTRACT");
}

const args = [read("material-test-plan.json"), read("recovery-protocol.json"),
  read("manifest.json"), read("observations.json"), read("collection-scenarios.json"),
  read("collection-input.json"), read("discovery-rule-draft.json")];
verify(...args);

if (process.argv.includes("--self-test")) {
  function rejects(code, index, mutate) {
    const changed = structuredClone(args);
    mutate(changed[index]);
    try { verify(...changed); } catch (error) { if (error.message === code) return; }
    fail(`SELF_TEST_${code}`);
  }
  rejects("CASE_STATUS", 0, (plan) => { plan.cases[0].c = "PASSED"; });
  rejects("INPUT_REFERENCE", 0, (plan) => { plan.cases[2].inputs.push("OLD-SYNTHETIC@1"); });
  rejects("PLAN_BOUNDARY", 0, (plan) => { plan.formalBusinessAcceptance = "PASSED"; });
  rejects("RECOVERY_STATUS", 1, (recovery) => { recovery.environment.observedBackupId = "invented"; });
  rejects("RECOVERY_CASES", 1, (recovery) => { recovery.scenarios[1].run = "PASSED"; });
  console.log("Enforcement material preflight: 1 positive, 5 negative checks; all execution NOT_RUN");
} else {
  console.log("Enforcement material preflight verified: M01-M06 and six recovery scenarios, all NOT_RUN");
}
