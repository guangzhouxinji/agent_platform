import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const paths = [
  "data/enforcement/procedure-v2-d-input-v1/manifest.json",
  "data/enforcement/conduct-v2-d-input-v1/manifest.json",
];
const inputs = paths.map((path) => JSON.parse(readFileSync(path, "utf8")));
const hash = (path) => createHash("sha256").update(readFileSync(path)).digest("hex");
const unique = (values) => new Set(values).size === values.length;
const fail = (code) => { throw new Error(code); };

function common(input, prefix, scenarios) {
  if (input.schemaVersion !== `enf-${prefix}-v2-d-input-v1` ||
      input.status !== "INPUT_ONLY_NOT_PUBLISHED" ||
      input.origin !== "PROJECT_AUTHORED_FICTIONAL" ||
      !input.rightsBasis.includes("Project owner instruction, 2026-09-28") ||
      !(/no real|not a real/.test(input.scope)) ||
      JSON.stringify(input.allowedUses) !== JSON.stringify(["D_REVIEW", "TEST_INPUT"]) ||
      !input.deniedUses.includes("PUBLISH_LEGAL_RULE") ||
      input.expectedOnlyNotAProductResult !== true) fail("SCOPE_OR_STATUS");
  if (input.caseInput.authority !== "PROJECT_TEST_NOT_CENTER_CASE" ||
      !input.caseInput.caseRef.startsWith("PROJECT-CASE-") ||
      input.caseInput.caseVersion !== "1" ||
      !Array.isArray(input.unresolvedProductD) || input.unresolvedProductD.length !== 4)
    fail("CASE_OR_D_GAPS");
  if (JSON.stringify(input.scenarios.map((item) => item.id)) !== JSON.stringify(scenarios) ||
      !input.scenarios.every((item) => item.input && item.expected &&
        Array.isArray(item.forbidden) && item.forbidden.length > 0)) fail("SCENARIOS");
}

function procedure(input) {
  common(input, "procedure", ["P01", "P02", "P03", "P04", "P05", "P06"]);
  const rule = input.ruleCandidate;
  const calendar = input.calendar;
  const events = input.caseInput.events;
  if (rule.status !== "DRAFT_INPUT_NOT_PUBLISHED" ||
      rule.basisRef !== "docs/57#6.26:PROJECT_METHOD_ONLY" ||
      rule.applicability.procedure !== input.caseInput.procedure ||
      rule.applicability.stage !== input.caseInput.stage ||
      rule.calendarRef !== `${calendar.ref}@${calendar.version}` ||
      calendar.status !== "PROJECT_TEST_ONLY_NOT_OFFICIAL" ||
      calendar.timezone !== "Asia/Shanghai" ||
      !calendar.note.includes("not an official holiday")) fail("PROCEDURE_BASIS");
  if (rule.period.value !== 2 || rule.period.unit !== "PROJECT_WORKDAY" ||
      rule.period.includeStartDay !== false ||
      !rule.period.startEvent || !rule.period.deadlineAdjustment ||
      !rule.pausePolicy || !rule.extensionPolicy ||
      rule.interruptionPolicy !== "UNCONFIRMED; interruption cannot be calculated or published" ||
      !calendar.nonWorkingDates.includes("2026-09-16")) fail("CLOCK_INPUT");
  if (!unique(events.map((event) => event.ref)) ||
      !events.every((event) => event.source === "PROJECT_AUTHORED" &&
        !Number.isNaN(Date.parse(event.occurredAt))) ||
      !rule.requiredEventRefs.every((kind) => events.some((event) => event.kind === kind)) ||
      !rule.requiredMaterialRefs.every((ref) => input.caseInput.materials.includes(ref)) ||
      !["PAUSE_START", "RESUME", "EXTENSION_APPROVED", "INTERRUPTION_CANDIDATE"].every((kind) =>
        events.some((event) => event.kind === kind))) fail("EVENT_OR_MATERIAL");
  if (JSON.stringify(input.trialInputs.map((trial) => [trial.id, trial.expected])) !== JSON.stringify([
    ["BASE", "2026-09-17"], ["PAUSE_EXTEND", "2026-09-22"],
    ["INTERRUPTION_UNKNOWN", "UNDETERMINED_NO_INTERRUPTION_POLICY"],
    ["NONWORKING_DEADLINE", "2026-09-21"]
  ]) || !input.trialInputs.every((trial) => trial.trace.length > 0 &&
      trial.eventRefs.every((ref) => events.some((event) => event.ref === ref))) ||
      !input.trialInputs[1].trace.some((step) => step.includes("Sep 21 day 2")) ||
      !input.trialInputs[3].trace.some((step) => step.includes("Sep 19 day 2 nonworking")))
    fail("TRIAL_TRACE");
  if (!input.scenarios[1].expected.includes("Sep 21") ||
      !input.scenarios[1].expected.includes("Sep 22") ||
      !input.scenarios[3].expected.includes("UNDETERMINED_VS_EXECUTION_FAILED"))
    fail("PROCEDURE_EXPECTATION");
}

function conduct(input) {
  common(input, "conduct", ["C01", "C02", "C03", "C04", "C05", "C06"]);
  const facts = input.caseInput.facts;
  const evidence = input.caseInput.evidence;
  const rights = input.caseInput.historicalRight;
  const rules = input.ruleCandidates;
  if (Number.isNaN(Date.parse(input.caseInput.behaviorAt)) ||
      rights.ref !== null || rights.status !== "UNKNOWN_AT_BEHAVIOR_TIME" ||
      rights.currentStatusMaySubstitute !== false) fail("RIGHT_AT_TIME");
  if (!unique(facts.map((fact) => fact.ref)) ||
      !facts.every((fact) => fact.sourceRef && fact.locator &&
        ["MACHINE_CANDIDATE", "PARTY_STATEMENT", "CONFIRMED_FACT"].includes(fact.kind)) ||
      !evidence.every((item) => facts.some((fact) => fact.ref === item.factRef) &&
        ["SUPPORT", "CONTRADICT"].includes(item.relation) && item.locator) ||
      !evidence.some((item) => item.relation === "SUPPORT") ||
      !evidence.some((item) => item.relation === "CONTRADICT")) fail("FACT_RELATIONS");
  if (JSON.stringify(rules.map((rule) => rule.behaviorType)) !== JSON.stringify([
    "PATENT_INFRINGEMENT_CANDIDATE", "COUNTERFEIT_PATENT_CANDIDATE", "TRADEMARK_CANDIDATE"
  ]) || !rules.every((rule) => rule.status === "DRAFT_INPUT_NOT_PUBLISHED" &&
      rule.basis === "NO_LEGAL_EFFECT_INPUT" && rule.elements.length > 0 &&
      rule.defenses.includes("UNASSESSED_NOT_NONE")) ||
      rules[0].procedure === rules[1].procedure) fail("CONDUCT_RULES");
  if (!input.scenarios[0].expected.includes("INSUFFICIENT") ||
      !input.scenarios[2].forbidden.includes("CONTRADICTION_DROPPED") ||
      !input.scenarios[3].forbidden.includes("SEVERITY_GRADE")) fail("CONDUCT_EXPECTATION");
}

procedure(inputs[0]);
conduct(inputs[1]);
if (process.argv.includes("--self-test")) {
  let rejected = 0;
  const rejects = (check, source, change, code) => {
    const copy = structuredClone(source);
    change(copy);
    try { check(copy); } catch (error) { if (error.message === code) { rejected++; return; } }
    fail(`SELF_TEST_${code}`);
  };
  rejects(procedure, inputs[0], (item) => { item.status = "PUBLISHED"; }, "SCOPE_OR_STATUS");
  rejects(procedure, inputs[0], (item) => { item.calendar.status = "OFFICIAL"; }, "PROCEDURE_BASIS");
  rejects(procedure, inputs[0], (item) => { item.ruleCandidate.period.startEvent = null; }, "CLOCK_INPUT");
  rejects(procedure, inputs[0], (item) => { item.caseInput.events.pop(); }, "EVENT_OR_MATERIAL");
  rejects(procedure, inputs[0], (item) => { item.trialInputs[2].expected = "2026-09-21"; }, "TRIAL_TRACE");
  rejects(procedure, inputs[0], (item) => { item.scenarios[3].forbidden = []; }, "SCENARIOS");
  rejects(conduct, inputs[1], (item) => { item.caseInput.historicalRight.currentStatusMaySubstitute = true; }, "RIGHT_AT_TIME");
  rejects(conduct, inputs[1], (item) => { item.caseInput.evidence.pop(); }, "FACT_RELATIONS");
  rejects(conduct, inputs[1], (item) => { item.ruleCandidates[0].status = "PUBLISHED"; }, "CONDUCT_RULES");
  rejects(conduct, inputs[1], (item) => { item.scenarios[3].forbidden = []; }, "SCENARIOS");
  console.log(`ENF-08/11 D inputs: 2 intact, ${rejected} rejected mutations; no product execution`);
} else {
  console.log("ENF-08/11 D inputs verified; draft expectations only, no product execution");
}
paths.forEach((path) => console.log(`${path} sha256=${hash(path)}`));
