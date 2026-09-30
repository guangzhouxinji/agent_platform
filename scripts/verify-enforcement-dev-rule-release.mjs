import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { validateDraft } from "./verify-enforcement-rule-draft.mjs";

const root = "data/enforcement/material-dev-product-contract-v1";
const read = (name) => JSON.parse(readFileSync(join(root, name), "utf8"));
const fail = (code) => { throw new Error(code); };

function evaluate(rule, input) {
  if (input.unavailable) return "FAILED_NOT_NO_MATCH";
  if (input.sourceId !== rule.scope.sourceId) return "NOT_APPLICABLE";
  if (typeof input.sourceText !== "string") return "UNKNOWN";
  return input.sourceText.includes(rule.condition.value) ? "CANDIDATE" : "NO_MATCH_WITHIN_SCOPE";
}

function verify(release, rule, manifest, observations, draftBytes) {
  if (release.status !== "PUBLISHED_DEVELOPMENT" ||
      release.result !== "PUBLISHED_CONFIG_NOT_PRODUCT_EXECUTION" ||
      release.releaseId !== `${rule.ruleId}@${rule.revision}` ||
      release.ruleDraft !== "discovery-rule-draft.json" ||
      release.ruleDraftSha256 !== createHash("sha256").update(draftBytes).digest("hex") ||
      release.batchId !== manifest.batchId || release.scope.batchId !== manifest.batchId ||
      release.scope.sourceId !== rule.scope.sourceId ||
      release.scope.inputField !== rule.condition.field ||
      release.scope.sourceUse !== rule.inputs[0].sourceUse ||
      !manifest.observations.allowedUses.includes(release.scope.sourceUse) ||
      !release.approvalBasis.includes("docs/02 section 40") ||
      !release.publishedBy || !Number.isFinite(Date.parse(release.publishedAt)) ||
      release.officialEffect !== "NONE" || release.effect !== "MATERIAL_REVIEW_CANDIDATE_ONLY" ||
      rule.status !== "DRAFT" || rule.output.officialEffect !== "NONE" ||
      rule.condition.operator !== "CONTAINS_LITERAL" || rule.exceptions.length !== 0) fail("RELEASE_BOUNDARY");
  const actual = observations.records.map((record) => ({
    observation: `${record.recordId}@${record.version}`,
    sourceVersion: record.version,
    result: evaluate(rule, record),
    explanation: record.sourceText.includes(rule.condition.value)
      ? `Literal at UTF-8 byte ${Buffer.byteLength(record.sourceText.slice(0, record.sourceText.indexOf(rule.condition.value)))}`
      : "Literal absent in this exact source version"
  }));
  const [first, second] = actual;
  if (actual.length !== 2 || first.result !== "CANDIDATE" ||
      second.result !== "NO_MATCH_WITHIN_SCOPE" || first.observation !== "OBS-001@1" ||
      second.observation !== "OBS-001@2" ||
      evaluate(rule, { sourceId: rule.scope.sourceId }) !== "UNKNOWN" ||
      evaluate(rule, { sourceId: rule.scope.sourceId, unavailable: true }) !== "FAILED_NOT_NO_MATCH" ||
      evaluate(rule, { sourceId: "OTHER", sourceText: rule.condition.value }) !== "NOT_APPLICABLE")
    fail("RULE_RESULT");
  return actual;
}

const bytes = readFileSync(join(root, "discovery-rule-draft.json"));
validateDraft(bytes);
const args = [read("discovery-rule-dev-release.json"), JSON.parse(bytes), read("manifest.json"),
  read("observations.json"), bytes];
const actual = verify(...args);
if (process.argv.includes("--self-test")) {
  function rejects(code, index, mutate) {
    const changed = [...args];
    changed[index] = structuredClone(args[index]);
    mutate(changed[index]);
    try { verify(...changed); } catch (error) { if (error.message === code) return; }
    fail(`SELF_TEST_${code}`);
  }
  rejects("RELEASE_BOUNDARY", 0, (release) => { release.ruleDraftSha256 = "0".repeat(64); });
  rejects("RELEASE_BOUNDARY", 0, (release) => { release.officialEffect = "FINDING"; });
  rejects("RELEASE_BOUNDARY", 0, (release) => { release.scope.sourceUse = "MODEL"; });
  rejects("RULE_RESULT", 3, (input) => { input.records[0].sourceText = "updated text"; });
  console.log("Enforcement development release: 1 positive, 4 negative checks; no business side effect");
} else {
  console.log(JSON.stringify({ releaseId: args[0].releaseId, status: args[0].status,
    batchId: args[0].batchId, actual, missing: "UNKNOWN", unavailable: "FAILED_NOT_NO_MATCH",
    crossScope: "NOT_APPLICABLE", businessWrites: 0 }));
}
