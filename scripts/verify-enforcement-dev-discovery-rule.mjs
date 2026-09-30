import { readFileSync } from "node:fs";
import { join } from "node:path";
import { validateDraft } from "./verify-enforcement-rule-draft.mjs";

const root = "data/enforcement/material-dev-product-contract-v1";
const fail = (code) => { throw new Error(code); };

function verify(rule, observations, manifest) {
  if (rule.status !== "DRAFT" || rule.family !== "DISCOVERY" ||
      rule.scope.sourceId !== "PROJECT-DEV-OBSERVATION" ||
      rule.scope.legalApplicability !== "NOT_ASSESSED" ||
      rule.output.category !== "MATERIAL_REVIEW_CANDIDATE" ||
      rule.output.officialEffect !== "NONE" || rule.reviewer !== null ||
      rule.basisRefs.length !== 1 || rule.basisRefs[0].kind !== "PROJECT_METHOD" ||
      rule.condition.operator !== "CONTAINS_LITERAL" ||
      rule.condition.field !== "sourceText" ||
      rule.inputs.length !== 1 || rule.inputs[0].field !== "sourceText" ||
      !manifest.observations.allowedUses.includes(rule.inputs[0].sourceUse)) fail("RULE_BOUNDARY");
  const expected = new Map([["OBS-001@1", "CANDIDATE"], ["OBS-001@2", "NO_MATCH_WITHIN_SCOPE"],
    ["MISSING_SOURCE_TEXT", "UNKNOWN"], ["SOURCE_UNAVAILABLE", "FAILED_NOT_NO_MATCH"]]);
  if (rule.examples.length !== expected.size) fail("EXAMPLE_SET");
  for (const example of rule.examples) {
    if (expected.get(example.observation) !== example.expected) fail("EXAMPLE_SET");
    const record = observations.records.find((value) => `${value.recordId}@${value.version}` === example.observation);
    if (record) {
      const contains = record.sourceText.includes(rule.condition.value);
      if (contains !== (example.expected === "CANDIDATE")) fail("EXAMPLE_TEXT");
    }
  }
  if (rule.missingPolicy.missingField !== "UNKNOWN" ||
      rule.missingPolicy.sourceUnavailable !== "FAILED_NOT_NO_MATCH" ||
      rule.missingPolicy.scopeMismatch !== "NOT_APPLICABLE") fail("MISSING_POLICY");
}

const bytes = readFileSync(join(root, "discovery-rule-draft.json"));
validateDraft(bytes);
const rule = JSON.parse(bytes);
const observations = JSON.parse(readFileSync(join(root, "observations.json")));
const manifest = JSON.parse(readFileSync(join(root, "manifest.json")));
verify(rule, observations, manifest);

if (process.argv.includes("--self-test")) {
  const rejection = (code, changed) => {
    try { verify(changed, observations, manifest); } catch (error) { if (error.message === code) return; }
    fail(`SELF_TEST_${code}`);
  };
  const clone = () => structuredClone(rule);
  const official = clone(); official.output.officialEffect = "FINDING";
  rejection("RULE_BOUNDARY", official);
  const premature = clone(); premature.status = "PUBLISHED";
  rejection("RULE_BOUNDARY", premature);
  const mislabeled = clone(); mislabeled.examples[1].expected = "CANDIDATE";
  rejection("EXAMPLE_SET", mislabeled);
  const missing = clone(); missing.missingPolicy.sourceUnavailable = "NO_MATCH_WITHIN_SCOPE";
  rejection("MISSING_POLICY", missing);
  console.log("Enforcement development rule draft: 1 positive, 4 negative checks passed; no rule published or business side effect");
} else {
  console.log("Enforcement development discovery draft and 4 expected cases verified; no rule published");
}
