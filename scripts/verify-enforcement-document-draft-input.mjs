import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const manifestPath = "data/enforcement/document-draft-v2-d-input-v1/manifest.json";
const input = JSON.parse(readFileSync(manifestPath, "utf8"));
const sha256 = path => createHash("sha256").update(readFileSync(path)).digest("hex");
const sha256Text = value => createHash("sha256").update(value, "utf8").digest("hex");
const fail = code => { throw new Error(code); };

const requiredDeniedUses = [
  "FORMAL_DOCUMENT", "OFFICIAL_NUMBER", "SIGNATURE", "ISSUANCE", "SERVICE",
  "FORMAL_PENALTY_DECISION", "ASSERT_LEGAL_APPLICABILITY", "BUSINESS_ACCEPTANCE"
];
const requiredInvariants = [
  "PREPARE_VALIDATES_EXACT_TEMPLATE_RULE_LEGAL_AND_REQUIRED_UPSTREAM_VERSIONS",
  "REGENERATION_CREATES_CANDIDATE_AND_NEVER_OVERWRITES_HUMAN_REVISION",
  "ADOPTION_IS_EXPLICIT_AND_CREATES_NEW_DOCUMENT_REVISION",
  "REVIEW_BINDS_EXACT_DOCUMENT_REVISION",
  "EXPORT_BINDS_THE_SAME_REVIEWED_DOCUMENT_REVISION_AND_CONTENT_HASH",
  "UNUSED_ENF_11_OR_ENF_16_DOES_NOT_BLOCK_A_DOCUMENT_TYPE",
  "REQUIRED_ENF_11_OR_ENF_16_MISSING_OR_UNREVIEWED_FAILS_CLOSED",
  "NO_FORMAL_NUMBER_SIGNATURE_ISSUANCE_OR_SERVICE_STATE"
];

function verifyScope(value = input) {
  if (value.schemaVersion !== "enf-document-draft-v2-d-input-v1" ||
      value.status !== "INPUT_ONLY_NOT_GENERATED" ||
      value.origin !== "PROJECT_AUTHORED_FICTIONAL" ||
      value.commandEnvelope.identitySource !== "TRUSTED_JWT_CONTEXT" ||
      value.commandEnvelope.callerIdentityFieldsIgnored !== true ||
      !requiredDeniedUses.every(item => value.deniedUses.includes(item))) fail("SCOPE");
}

function verifyActorsAndWorkbench(value = input) {
  if (value.testActors.length !== 2 || value.testActors[0].subject === value.testActors[1].subject ||
      value.testActors.some(actor => actor.kind !== "CONTROLLED_TEST_SUBJECT" ||
        actor.organization !== "ORG-ENF-PROJECT") ||
      !value.testActors[0].actions.includes("SUBMIT_REVIEW") ||
      value.testActors[0].actions.includes("REVIEW") ||
      !value.testActors[1].actions.includes("REVIEW")) fail("TEST_ACTORS");
  const workbench = value.workbenchContract;
  if (workbench.sameServiceApiAsDirectCalls !== true || workbench.staticOrMockResultAllowed !== false ||
      workbench.requestIdVisible !== true ||
      !["STRUCTURED_DRAFT_EDITOR", "FIELD_AND_PARAGRAPH_PROVENANCE", "VERSION_HISTORY_AND_DIFF",
        "REGENERATION_CANDIDATE_AND_ADOPTION", "REVIEW", "SAME_REVISION_DOCX_EXPORT"]
        .every(view => workbench.views.includes(view)) ||
      !["LOADING", "EMPTY", "FORBIDDEN", "CONFLICT", "UPSTREAM_UNAVAILABLE", "FAILED"]
        .every(state => workbench.states.includes(state)) ||
      workbench.readApis.length !== 6 || workbench.readApis.some(api => !api.startsWith("GET /v2/enforcement/")))
    fail("WORKBENCH");
}

function verifyUpstream(value = input) {
  const expected = {
    template: ["data/enforcement/document-template-v2-d-input-v1/manifest.json", "PUBLISHED"],
    rule: ["data/enforcement/document-rule-v2-d-input-v1/manifest.json", "PUBLISHED"],
    legalReference: ["data/enforcement/legal-reference-v2-d-input-v1/manifest.json", "CONFIRMED"]
  };
  for (const [name, [path, state]] of Object.entries(expected)) {
    const ref = value.upstreamContracts[name];
    if (!ref || ref.manifest !== path || ref.requiredProductState !== state || sha256(path) !== ref.sha256)
      fail("UPSTREAM_CONTRACT");
  }
  if (value.upstreamContracts.template.inputStatus !== "PROJECT_INPUT_ONLY" ||
      value.upstreamContracts.rule.inputStatus !== "DRAFT_INPUT_NOT_PUBLISHED" ||
      value.upstreamContracts.legalReference.inputStatus !== "PROJECT_TEST_REVIEWED_NOT_LEGAL_APPLICABILITY")
    fail("UPSTREAM_SCOPE");
}

function verifyDocumentTypes(value = input) {
  const types = new Map(value.documentTypes.map(item => [item.id, item]));
  if (types.size !== 3 || value.documentTypes.some(item => item.projectInputOnly !== true)) fail("DOCUMENT_TYPES");
  const fact = types.get("PROJECT_FACT_RECORD");
  const conduct = types.get("PROJECT_CONDUCT_REVIEW_DRAFT");
  const penalty = types.get("PROJECT_PENALTY_REVIEW_DRAFT");
  if (!fact || fact.usesEnf11 || fact.usesEnf16 || fact.requires.some(item => /^ENF_1[16]_/.test(item)) ||
      !conduct || !conduct.usesEnf11 || conduct.usesEnf16 || !conduct.requires.includes("ENF_11_CONDUCT_ANALYSIS") ||
      !penalty || !penalty.usesEnf11 || !penalty.usesEnf16 ||
      !penalty.requires.includes("ENF_11_CONDUCT_ANALYSIS") ||
      !penalty.requires.includes("ENF_16_PENALTY_SUGGESTION")) fail("DOCUMENT_TYPES");
  if (value.fixtures.complete.documentType !== fact.id ||
      value.fixtures.complete.optionalUpstream.enf11 !== null ||
      value.fixtures.complete.optionalUpstream.enf16 !== null ||
      value.fixtures.complete.expectedPrepare.canGenerate !== true ||
      sha256Text(value.fixtures.complete.legalReference.verbatim) !== value.fixtures.complete.legalReference.sourceSha256 ||
      value.fixtures.complete.legalReference.legalApplicability !== "NOT_ASSERTED" ||
      value.fixtures.conduct.documentType !== conduct.id ||
      value.fixtures.conduct.enf11.reviewState !== "REVIEWED" ||
      value.fixtures.conduct.enf16 !== null ||
      value.fixtures.penaltyIncomplete.documentType !== penalty.id ||
      value.fixtures.penaltyIncomplete.enf16.reviewState !== "PENDING_REVIEW" ||
      value.fixtures.penaltyIncomplete.enf16.amount !== null ||
      value.fixtures.penaltyIncomplete.expectedReviewState !== "INCOMPLETE_DRAFT") fail("TYPE_FIXTURES");
}

function verifyDraftContract(value = input) {
  const fields = new Set(value.structuredDraftContract.requiredFields);
  for (const field of ["draftId", "revision", "contentHash", "templateRevision", "ruleRevision",
    "inputManifestHash", "sections", "fieldProvenance", "missingItems", "reviewState", "requestId"])
    if (!fields.has(field)) fail("DRAFT_FIELDS");
  if (value.structuredDraftContract.systemDraftIdIsOfficialNumber !== false ||
      value.structuredDraftContract.sourceFieldsMayBeModelGenerated !== false ||
      value.structuredDraftContract.missingRequiredFieldsKeepVisiblePlaceholder !== true ||
      value.structuredDraftContract.renderFailureKeepsStructuredDraft !== true) fail("DRAFT_BOUNDARY");
}

function verifyOperations(value = input) {
  const expected = ["PREPARE", "GENERATE", "REVISE", "REGENERATE", "ADOPT", "SUBMIT_REVIEW", "REVIEW", "EXPORT_DOCX"];
  if (JSON.stringify(value.operations.map(item => item.name)) !== JSON.stringify(expected) ||
      value.operations.some(item => !item.api.startsWith("POST /v2/enforcement/documents") ||
        !item.input.length || !item.output.includes("requestId"))) fail("OPERATIONS");
  if (value.operations.find(item => item.name === "PREPARE").writesDocument !== false ||
      value.operations.find(item => item.name === "REGENERATE").writesDocument !== false ||
      value.operations.find(item => item.name === "ADOPT").writesDocument !== true ||
      !value.operations.find(item => item.name === "EXPORT_DOCX").input.includes("reviewEventRevision"))
    fail("OPERATION_SEMANTICS");
}

function verifyStatesAndInvariants(value = input) {
  const expectedReviews = ["INCOMPLETE_DRAFT", "DRAFT", "PENDING_REVIEW", "RETURNED", "REVIEWED"];
  if (JSON.stringify(value.states.documentReview) !== JSON.stringify(expectedReviews) ||
      !["SIGNED", "ISSUED", "SERVED", "DELIVERED", "OFFICIALLY_EFFECTIVE"]
        .every(item => value.states.forbiddenProductStates.includes(item)) ||
      !requiredInvariants.every(item => value.invariants.includes(item))) fail("STATE_INVARIANTS");
}

function verifyPermissions(value = input) {
  const expected = ["PREPARE", "GENERATE", "READ", "REVISE", "REGENERATE", "ADOPT", "SUBMIT_REVIEW", "REVIEW", "EXPORT_DOCX"];
  if (JSON.stringify(value.permissions.map(item => item.operation)) !== JSON.stringify(expected) ||
      value.permissions.some(item => item.resource !== "DOCUMENT" || !item.action || !item.use)) fail("PERMISSIONS");
}

function verifyFailuresAndRecovery(value = input) {
  const conditions = new Set(value.failureContract.map(item => item.condition));
  for (const condition of ["REQUIRED_INPUT_MISSING", "UNCONFIRMED_PENALTY", "REFERENCE_BINDING_FAILURE",
    "SOURCE_OR_UPSTREAM_VERSION_CHANGED", "RENDERER_OR_FONT_FAILURE", "VERSION_OR_IDEMPOTENCY_CONFLICT",
    "UNAUTHORIZED_OR_REVOKED", "DB_OR_FILE_UNAVAILABLE"])
    if (!conditions.has(condition)) fail("FAILURES");
  if (value.failureContract.some(item => !item.result || !item.forbiddenSideEffects.length) ||
      !value.recoveryContract.commonSnapshot.includes("DOCX_ARTIFACTS") ||
      !value.recoveryContract.commonSnapshot.includes("RECEIPTS") ||
      !value.recoveryContract.notRestoredFromBackup.includes("CURRENT_GRANT_EPOCH") ||
      value.recoveryContract.sameKeySamePayload !== "SAME_RECEIPT_AND_RESULT" ||
      value.recoveryContract.sameKeyDifferentPayload !== "IDEMPOTENCY_CONFLICT" ||
      value.recoveryContract.oldReviewedRevisionAfterNewRevision !== "HISTORY_ONLY_NOT_EXPORTABLE_AS_NEW_REVIEWED_REVISION")
    fail("RECOVERY");
}

function verifyScenarios(value = input) {
  if (JSON.stringify(value.scenarios.map(item => item.id)) !==
      JSON.stringify(["D01", "D02", "D03", "D04", "D05", "D06", "D07", "D08", "D09"]) ||
      value.scenarios.some(item => !item.input || !item.expected || !item.forbidden.length)) fail("SCENARIOS");
  const forbidden = new Set(value.scenarios.flatMap(item => item.forbidden));
  for (const item of ["OFFICIAL_NUMBER", "SIGNATURE", "ISSUANCE", "SERVICE", "DEFAULT_ZERO",
    "HUMAN_REVISION_OVERWRITTEN", "OLD_REVIEW_APPLIED_TO_NEW_REVISION", "MIXED_REVISION_EXPORT",
    "AGENT_REVIEW", "STALE_GRANT_SUCCESS", "HALF_RECEIPT"])
    if (!forbidden.has(item)) fail("SCENARIO_BOUNDARIES");
}

function verifyBusinessBoundary(value = input) {
  const pending = new Set(value.businessAcceptancePending);
  for (const item of ["CENTER_APPROVED_DOCUMENT_TYPES_AND_REQUIRED_FIELDS",
    "CENTER_APPROVED_TEMPLATE_AND_FIELD_MAPPING", "REAL_CASE_EVIDENCE_ANALYSIS_AND_PENALTY_INPUTS",
    "LEGAL_PROFESSIONAL_APPLICABILITY", "REAL_AUTHOR_AND_REVIEW_ROLES",
    "TARGET_RENDERER_FONTS_AND_LAYOUT_ACCEPTANCE", "NAMED_BUSINESS_REVIEW_OF_FIELDS_AND_LAYOUT"])
    if (!pending.has(item)) fail("BUSINESS_BOUNDARY");
}

function verify(value = input) {
  verifyScope(value);
  verifyActorsAndWorkbench(value);
  verifyUpstream(value);
  verifyDocumentTypes(value);
  verifyDraftContract(value);
  verifyOperations(value);
  verifyStatesAndInvariants(value);
  verifyPermissions(value);
  verifyFailuresAndRecovery(value);
  verifyScenarios(value);
  verifyBusinessBoundary(value);
}

verify();

if (process.argv.includes("--self-test")) {
  let rejected = 0;
  const rejects = (mutate, code) => {
    const copy = structuredClone(input);
    mutate(copy);
    try { verify(copy); } catch (error) { if (error.message === code) { rejected++; return; } }
    fail(`SELF_TEST_${code}`);
  };
  rejects(item => { item.status = "BUSINESS_ACCEPTED"; }, "SCOPE");
  rejects(item => { item.testActors[0].subject = item.testActors[1].subject; }, "TEST_ACTORS");
  rejects(item => { item.workbenchContract.staticOrMockResultAllowed = true; }, "WORKBENCH");
  rejects(item => { item.upstreamContracts.template.sha256 = "0".repeat(64); }, "UPSTREAM_CONTRACT");
  rejects(item => { item.upstreamContracts.legalReference.inputStatus = "LEGAL_APPLICABILITY_CONFIRMED"; }, "UPSTREAM_SCOPE");
  rejects(item => { item.documentTypes[0].usesEnf16 = true; }, "DOCUMENT_TYPES");
  rejects(item => { item.fixtures.penaltyIncomplete.enf16.reviewState = "REVIEWED"; }, "TYPE_FIXTURES");
  rejects(item => { item.structuredDraftContract.systemDraftIdIsOfficialNumber = true; }, "DRAFT_BOUNDARY");
  rejects(item => { item.operations.find(operation => operation.name === "REGENERATE").writesDocument = true; }, "OPERATION_SEMANTICS");
  rejects(item => { item.states.documentReview.push("ISSUED"); }, "STATE_INVARIANTS");
  rejects(item => { item.permissions.find(permission => permission.operation === "REVIEW").resource = "AGENT"; }, "PERMISSIONS");
  rejects(item => { item.failureContract.find(failure => failure.condition === "REFERENCE_BINDING_FAILURE").forbiddenSideEffects = []; }, "RECOVERY");
  rejects(item => { item.recoveryContract.sameKeyDifferentPayload = "SECOND_SUCCESS"; }, "RECOVERY");
  rejects(item => { item.scenarios.find(scenario => scenario.id === "D05").forbidden = []; }, "SCENARIOS");
  rejects(item => { item.scenarios.find(scenario => scenario.id === "D08").forbidden = ["SECOND_RECEIPT"]; }, "SCENARIO_BOUNDARIES");
  rejects(item => { item.businessAcceptancePending = []; }, "BUSINESS_BOUNDARY");
  assert.equal(rejected, 16);
  console.log(`ENF-14 D input: 1 intact, ${rejected} rejected mutations; no product execution`);
} else {
  console.log("ENF-14 D input verified; input contract only, no product execution");
}
console.log(`${manifestPath} sha256=${sha256(manifestPath)}`);
