import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const oldDir = resolve(root, "data/adjudication/dev-synthetic-case-v1");
const newDir = resolve(root, "data/adjudication/dev-case-work-input-v2");
const oldManifestBytes = readFileSync(resolve(oldDir, "manifest.json"));
const oldRecordsBytes = readFileSync(resolve(oldDir, "records.json"));
const inputBytes = readFileSync(resolve(newDir, "input.json"));
const oldManifest = JSON.parse(oldManifestBytes);
const oldRecords = JSON.parse(oldRecordsBytes);
const manifest = JSON.parse(readFileSync(resolve(newDir, "manifest.json")));
const input = JSON.parse(inputBytes);
const hash = value => createHash("sha256").update(value).digest("hex");
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const check = (condition, message) => {
  if (!condition) throw new Error("Adjudication case-work input v2: " + message);
};
const pinned = {
  oldManifest: "8b4f8b40691a91515b375157dab4f75da354b432eb284abc62eabbaca75bbfec",
  oldRecords: "6bf1cefad74cd0a05e8a017f3a4593e89a4e8825f07921522d6bef190e25ebc4",
  input: "4e3ad8bd1b1407e3359eb4196aa0c71bfa8e9cf9a5edcd0045a4ea2cd9d82885"
};

const basic = ["QUERY_DEV_WORK", "SAVE_DEV_WORK", "DISPLAY_DEV_WORK"];
const sideBySide = [...basic, "CLAIM_SIDE_BY_SIDE_INPUT"];
const fieldUses = {
  cases: [{ fields: ["id", "sourceVersion", "kind", "title", "applicantRef", "respondentRef",
    "rightRef", "rightGap", "locator", "available"], uses: basic }],
  materials: [
    { fields: ["id", "sourceVersion", "caseId", "submissionKind", "available", "unavailableReason"], uses: basic },
    { fields: ["providedBy", "text", "locator"], uses: sideBySide }
  ],
  issueRevisions: [
    { fields: ["id", "version", "caseId", "nature", "locator"], uses: basic },
    { fields: ["statements", "gaps"], uses: sideBySide }
  ]
};
const denied = ["FULLTEXT_INDEX", "FILE_DOWNLOAD", "EXTERNAL_MODEL", "CROSS_DOMAIN_SHARE",
  "REPORT_DRAFT", "PROFESSIONAL_COMPARE", "EVIDENCE_ACCEPTANCE", "LEGAL_APPLICABILITY",
  "FORMAL_CASE", "FORMAL_MATERIAL", "ADJUDICATION_WORKFLOW", "FORMAL_DECISION", "AUTO_CONFIRM_FACT"];
const oldUses = ["SAVE_DEV_WORK", "READ_DEV_WORK", "DISPLAY_DEV_WORK", "CONTRACT_TEST"];

function verify(policy, data, bytes, source, options = {}) {
  const now = options.now ?? "2026-09-25T02:00:00Z";
  const withdrawalVersion = options.withdrawalVersion ?? 0;
  const principal = options.principal ?? "dev-adj-author";
  const caseId = options.caseId ?? "DEV-ADJ-CASE-001";
  const subject = options.subject ?? { issuer: "LOCAL_DEV", kind: "HUMAN",
    organizationRef: "dev-adj-isolated", currentCredential: true, exactCaseGrant: true };
  const requestedField = options.field ?? "materials.text";
  const use = options.use ?? "CLAIM_SIDE_BY_SIDE_INPUT";
  check(policy.schemaVersion === 1 && data.schemaVersion === 1
    && policy.manifestId === "DEV-ADJ-CASE-WORK-INPUT-02"
    && policy.datasetId === data.datasetId && data.datasetId === policy.manifestId
    && policy.origin === data.origin && data.origin === "PROJECT_AUTHORED_SYNTHETIC_TEXT"
    && policy.environment === "isolated-development-only" && policy.status === "SYNTHETIC_DEV_ONLY"
    && policy.approvalRef === "docs/02-待确认事项清单.md#32-2026-09-25-项目数据处理与功能体验优先决定"
    && policy.decisionVersion === "2026-09-25"
    && policy.responsibleParty === "PROJECT_OWNER_FOR_PROJECT_AUTHORED_DATA"
    && policy.payloadFile === "input.json", "identity or approval");
  check(policy.withdrawalVersion === 0 && withdrawalVersion === 0 && policy.withdrawnAt === null
    && policy.validFrom === "2026-09-25T00:00:00+08:00"
    && policy.validUntil === "2027-03-25T00:00:00+08:00"
    && Number.isFinite(Date.parse(now)) && Number.isFinite(Date.parse(policy.validFrom))
    && Number.isFinite(Date.parse(policy.validUntil))
    && Date.parse(policy.validFrom) < Date.parse(policy.validUntil)
    && Date.parse(policy.validFrom) <= Date.parse(now) && Date.parse(now) < Date.parse(policy.validUntil),
  "withdrawal or validity");
  check(policy.organizationRef === "dev-adj-isolated"
    && equal(policy.allowedPrincipalRefs, ["dev-adj-author", "dev-adj-reviewer"])
    && equal(policy.trustedSubjectRequirement, { issuer: "LOCAL_DEV", kind: "HUMAN",
      currentCredential: true, exactCaseGrant: true })
    && subject.issuer === policy.trustedSubjectRequirement.issuer
    && subject.kind === policy.trustedSubjectRequirement.kind
    && subject.organizationRef === policy.organizationRef
    && subject.currentCredential === true && subject.exactCaseGrant === true
    && policy.identityLimitation.includes("separate trusted credential")
    && policy.allowedPrincipalRefs.includes(principal)
    && equal(policy.allowedCaseIds, ["DEV-ADJ-CASE-001"])
    && equal(policy.negativeCaseIds, ["DEV-ADJ-CASE-002"])
    && policy.allowedCaseIds.includes(caseId), "subject or case scope");
  check(equal(policy.fieldUses, fieldUses) && equal(policy.deniedUses, denied)
    && policy.semanticBoundary.includes("unconfirmed")
    && policy.retentionBoundary.includes("six-calendar-month"), "field policy");
  const [kind, field] = requestedField.split(".");
  check(requestedField.split(".").length === 2
    && policy.fieldUses[kind]?.some(group => group.fields.includes(field) && group.uses.includes(use))
    && !policy.deniedUses.includes(use), "unapproved field or use");
  check(hash(bytes) === policy.payloadSha256, "payload digest");
  check(equal(policy.sourceManifests, [{ manifestId: "DEV-ADJ-SYNTH-INPUT-01", sha256: pinned.oldManifest }])
    && hash(source.manifestBytes) === pinned.oldManifest
    && policy.sourceRecordsSha256 === pinned.oldRecords
    && hash(source.recordsBytes) === pinned.oldRecords
    && source.manifest.batchSha256 === hash(source.recordsBytes), "source digest");
  check(source.manifest.manifestId === "DEV-ADJ-SYNTH-INPUT-01"
    && source.manifest.origin === "PROJECT_AUTHORED_SYNTHETIC_TEXT"
    && equal(Object.keys(source.manifest.allowedFieldUses), ["cases", "materials", "issueRevisions"])
    && Object.values(source.manifest.allowedFieldUses).every(group => equal(group.uses, oldUses))
    && source.manifest.forbiddenUses.includes("EXTERNAL_MODEL")
    && source.manifest.forbiddenUses.includes("REPORT_DRAFT")
    && source.manifest.forbiddenUses.includes("FULLTEXT_INDEX"), "old permission must remain limited");
  check(Object.keys(fieldUses).every(group => Array.isArray(data[group])
    && data[group].every(item => Object.keys(item).every(key =>
      policy.fieldUses[group].some(entry => entry.fields.includes(key))))),
  "undeclared payload field");
  check(equal(Object.keys(data), ["schemaVersion", "datasetId", "origin", "cases", "materials", "issueRevisions"])
    && source.records.datasetId === "DEV-ADJ-CASE-WORK-01"
    && source.records.cases.some(item => item.id === "DEV-ADJ-CASE-002")
    && source.records.materials.some(item => item.id === "DEV-ADJ-MAT-003" && item.available === false)
    && source.records.materials.some(item => item.id === "DEV-ADJ-MAT-004" && item.caseId === "DEV-ADJ-CASE-002")
    && equal(data.cases, source.records.cases.filter(item => item.id === "DEV-ADJ-CASE-001"))
    && equal(data.materials, source.records.materials.filter(item =>
      ["DEV-ADJ-MAT-001", "DEV-ADJ-MAT-002"].includes(item.id) && item.available === true))
    && equal(data.issueRevisions, source.records.issueRevisions.filter(item => item.caseId === "DEV-ADJ-CASE-001")),
  "authorized record projection");
  check(data.cases.length === 1 && data.materials.length === 2 && data.issueRevisions.length === 2
    && data.cases.every(item => item.rightRef === null)
    && data.materials[0].providedBy === "APPLICANT"
    && data.materials[1].providedBy === "RESPONDENT"
    && data.materials.every(item => item.caseId === "DEV-ADJ-CASE-001" && item.available === true)
    && data.issueRevisions.every(item => item.nature.endsWith("NOT_CONFIRMED_FACT")
      && item.gaps.includes("权利要求原文")), "claims and negative examples");
  check(policy.payloadSha256 === pinned.input, "payload digest");
}

const source = { manifestBytes: oldManifestBytes, recordsBytes: oldRecordsBytes,
  manifest: oldManifest, records: oldRecords };
verify(manifest, input, inputBytes, source);
if (process.argv.includes("--self-test")) {
  function rejects(label, mutate, reason, options = {}) {
    const policy = structuredClone(manifest);
    const data = structuredClone(input);
    const old = { manifestBytes: oldManifestBytes, recordsBytes: oldRecordsBytes,
      manifest: structuredClone(oldManifest), records: structuredClone(oldRecords) };
    mutate(policy, data, old);
    const bytes = equal(data, input) ? inputBytes : Buffer.from(JSON.stringify(data));
    policy.payloadSha256 = hash(bytes);
    try { verify(policy, data, bytes, old, options); }
    catch (error) {
      check(error.message.includes(reason), `wrong rejection for ${label}: ${error.message}`);
      return;
    }
    throw new Error("Adjudication case-work input v2 accepted " + label);
  }
  rejects("material tamper", (_, data) => { data.materials[0].text += "篡改"; }, "authorized record projection");
  rejects("injected decision", (_, data) => { data.formalDecision = "APPROVED"; }, "authorized record projection");
  rejects("cross-case payload", (_, data, old) => {
    data.cases.push(structuredClone(old.records.cases.find(item => item.id === "DEV-ADJ-CASE-002")));
  }, "authorized record projection");
  rejects("cross-case material payload", (_, data, old) => {
    data.materials.push(structuredClone(old.records.materials.find(item => item.id === "DEV-ADJ-MAT-004")));
  }, "authorized record projection");
  rejects("unavailable material payload", (_, data, old) => {
    data.materials.push(structuredClone(old.records.materials.find(item => item.id === "DEV-ADJ-MAT-003")));
  }, "authorized record projection");
  rejects("source manifest drift", (policy) => { policy.sourceManifests[0].sha256 = "0".repeat(64); }, "source digest");
  rejects("old permission expansion", (_, __, old) => {
    old.manifest.allowedFieldUses.materials.uses.push("CLAIM_SIDE_BY_SIDE_INPUT");
  }, "old permission must remain limited");
  rejects("undeclared case field while requesting material", (_, data) => {
    data.cases[0].formalState = "APPROVED";
  }, "undeclared payload field");
  rejects("undeclared issue field while requesting material", (_, data) => {
    data.issueRevisions[0].professionalFinding = "MATCH";
  }, "undeclared payload field");
  rejects("v2 permission expansion", (policy) => {
    policy.fieldUses.materials[1].uses.push("EXTERNAL_MODEL");
  }, "field policy");
  let allowedChecks = 0;
  let deniedChecks = 0;
  for (const [group, entries] of Object.entries(fieldUses)) {
    for (const entry of entries) {
      for (const field of entry.fields) {
        for (const use of entry.uses) {
          verify(manifest, input, inputBytes, source, { field: `${group}.${field}`, use });
          allowedChecks++;
        }
        if (!entry.uses.includes("CLAIM_SIDE_BY_SIDE_INPUT")) {
          rejects(`unapproved comparison ${group}.${field}`, () => {}, "unapproved field or use",
            { field: `${group}.${field}`, use: "CLAIM_SIDE_BY_SIDE_INPUT" });
          deniedChecks++;
        }
        for (const use of denied) {
          rejects(`denied ${use} ${group}.${field}`, () => {}, "unapproved field or use",
            { field: `${group}.${field}`, use });
          deniedChecks++;
        }
      }
    }
  }
  rejects("unknown field", () => {}, "unapproved field or use", { field: "cases.unknown" });
  rejects("unknown group", () => {}, "unapproved field or use", { field: "unknown.text" });
  rejects("malformed field", () => {}, "unapproved field or use", { field: "materials.text.extra" });
  rejects("cross case", () => {}, "subject or case scope", { caseId: "DEV-ADJ-CASE-002" });
  rejects("unbound principal", () => {}, "subject or case scope", { principal: "untrusted" });
  rejects("agent subject", () => {}, "subject or case scope", { subject: { issuer: "LOCAL_DEV",
    kind: "AGENT", organizationRef: "dev-adj-isolated", currentCredential: true, exactCaseGrant: true } });
  rejects("missing grant", () => {}, "subject or case scope", { subject: { issuer: "LOCAL_DEV",
    kind: "HUMAN", organizationRef: "dev-adj-isolated", currentCredential: true, exactCaseGrant: false } });
  rejects("withdrawal", () => {}, "withdrawal or validity", { withdrawalVersion: 1 });
  rejects("source expiry", () => {}, "withdrawal or validity", { now: "2027-03-24T16:00:00Z" });
  console.log(`Adjudication case-work v2 self-test passed: ${allowedChecks} allowed and ${deniedChecks} denied field uses, authorized projection, old-use, tamper, scope, withdrawal and expiry`);
}
console.log("Adjudication case-work v2 input verified: project-authored claims for bounded development use only");
