import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const directory = resolve(root, "data/adjudication/dev-synthetic-case-v1");
const raw = readFileSync(resolve(directory, "records.json"));
const records = JSON.parse(raw.toString("utf8"));
const manifest = JSON.parse(readFileSync(resolve(directory, "manifest.json"), "utf8"));
const sha256 = value => createHash("sha256").update(value).digest("hex");
const entries = data => [
  ...data.cases.map(value => ({ kind: "case", id: value.id, version: value.sourceVersion, value })),
  ...data.materials.map(value => ({ kind: "material", id: value.id, version: value.sourceVersion, value })),
  ...data.issueRevisions.map(value => ({ kind: "issueRevision", id: value.id, version: String(value.version), value }))
];
const digests = entries(records).map(({ kind, id, version, value }) => ({ kind, id, version, sha256: sha256(JSON.stringify(value)) }));

if (process.argv.includes("--print-hashes")) {
  console.log(JSON.stringify({ batchSha256: sha256(raw), recordDigests: digests }, null, 2));
  process.exit(0);
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function verify(data, policy, bytesHash) {
  assert(data.schemaVersion === 1 && policy.schemaVersion === 1, "schema version");
  assert(policy.manifestId === "DEV-ADJ-SYNTH-INPUT-01" && policy.datasetId === data.datasetId
    && data.datasetId === "DEV-ADJ-CASE-WORK-01", "dataset identity");
  assert(policy.origin === "PROJECT_AUTHORED_SYNTHETIC_TEXT" && policy.status === "SYNTHETIC_DEV_ONLY", "synthetic scope");
  assert(policy.recordsFile === "records.json" && policy.batchSha256 === bytesHash, "batch digest");
  assert(policy.organizationRef === "dev-adj-isolated"
    && JSON.stringify(policy.testPrincipalRefs) === JSON.stringify(["dev-adj-author", "dev-adj-reviewer"]), "test identity references");
  const allowed = ["SAVE_DEV_WORK", "READ_DEV_WORK", "DISPLAY_DEV_WORK", "CONTRACT_TEST"];
  const fields = {
    cases: ["id", "sourceVersion", "kind", "title", "applicantRef", "respondentRef", "rightRef", "rightGap", "locator", "available"],
    materials: ["id", "sourceVersion", "caseId", "providedBy", "text", "locator", "submissionKind", "available", "unavailableReason"],
    issueRevisions: ["id", "version", "caseId", "nature", "statements", "gaps", "locator"]
  };
  for (const kind of Object.keys(fields)) {
    assert(JSON.stringify(policy.allowedFieldUses?.[kind]?.fields) === JSON.stringify(fields[kind]), `${kind} field list`);
    assert(JSON.stringify(policy.allowedFieldUses?.[kind]?.uses) === JSON.stringify(allowed), `${kind} allowed uses`);
    assert(data[kind].every(value => Object.keys(value).every(field => fields[kind].includes(field))), `${kind} undeclared field`);
  }
  assert(Object.keys(policy.allowedFieldUses).length === 3, "unknown field use");
  const forbidden = ["FULLTEXT_INDEX", "FILE_DOWNLOAD", "EXTERNAL_MODEL", "CROSS_DOMAIN_SHARE", "FORMAL_CASE", "FORMAL_MATERIAL", "LEGAL_APPLICABILITY", "EVIDENCE_ACCEPTANCE", "REPORT_DRAFT", "ADJUDICATION_WORKFLOW", "FORMAL_DECISION"];
  assert(JSON.stringify(policy.forbiddenUses) === JSON.stringify(forbidden), "forbidden uses");
  assert(data.cases.length === 2 && data.materials.length === 4 && data.issueRevisions.length === 2, "record count");
  const actual = entries(data);
  assert(actual.length === policy.recordDigests.length, "record digest count");
  actual.forEach(({ kind, id, version, value }, index) => {
    const expected = policy.recordDigests[index];
    assert(expected?.kind === kind && expected.id === id && expected.version === version, "record identity/order");
    assert(expected.sha256 === sha256(JSON.stringify(value)), `record digest ${id}@${version}`);
    assert(value.locator?.startsWith("synthetic:"), `synthetic locator ${id}`);
  });
  const cases = new Set(data.cases.map(value => value.id));
  assert(cases.size === 2 && cases.has("DEV-ADJ-CASE-001") && cases.has("DEV-ADJ-CASE-002"), "case set");
  assert(data.cases.every(value => value.rightRef === null && value.rightGap && value.available === true), "rights gap");
  const materials = new Map(data.materials.map(value => [value.id, value]));
  assert(materials.size === 4 && [...materials.values()].every(value => cases.has(value.caseId)
    && ["APPLICANT", "RESPONDENT"].includes(value.providedBy)
    && value.submissionKind === "PROJECT_AUTHORED_TEXT" && value.text), "material relationship");
  assert(materials.get("DEV-ADJ-MAT-001")?.caseId === "DEV-ADJ-CASE-001"
    && materials.get("DEV-ADJ-MAT-001")?.providedBy === "APPLICANT"
    && materials.get("DEV-ADJ-MAT-002")?.providedBy === "RESPONDENT"
    && materials.get("DEV-ADJ-MAT-003")?.available === false
    && materials.get("DEV-ADJ-MAT-003")?.unavailableReason === "SYNTHETIC_SOURCE_WITHDRAWN"
    && materials.get("DEV-ADJ-MAT-004")?.caseId === "DEV-ADJ-CASE-002", "negative examples");
  assert(data.issueRevisions.every((value, index) => value.id === "DEV-ADJ-ISSUE-001"
    && value.caseId === "DEV-ADJ-CASE-001" && value.version === index + 1
    && value.nature.endsWith("NOT_CONFIRMED_FACT") && value.gaps.includes("权利要求原文")
    && value.statements.every(item => materials.get(item.materialId)?.caseId === value.caseId
      && materials.get(item.materialId)?.providedBy === item.providedBy)), "issue provenance");
  assert(data.issueRevisions[0].statements.length === 1 && data.issueRevisions[1].statements.length === 2
    && new Set(data.issueRevisions[1].statements.map(item => item.providedBy)).size === 2, "opposing claims");
}

verify(records, manifest, sha256(raw));
if (process.argv.includes("--self-test")) {
  const rejects = (mutate, expected) => {
    const data = structuredClone(records);
    const policy = structuredClone(manifest);
    mutate(data, policy);
    try { verify(data, policy, sha256(raw)); } catch (error) {
      assert(error.message.includes(expected), `wrong rejection: ${error.message}`);
      return;
    }
    throw new Error(`accepted ${expected}`);
  };
  rejects(data => { data.materials[0].text = "tampered"; }, "record digest");
  rejects(data => { data.materials.pop(); }, "record count");
  rejects((_, policy) => { policy.allowedFieldUses.materials.uses.push("EXTERNAL_MODEL"); }, "allowed uses");
  rejects((_, policy) => { policy.allowedFieldUses.materials.fields.pop(); }, "field list");
  rejects((_, policy) => { policy.forbiddenUses.pop(); }, "forbidden uses");
  rejects((_, policy) => { policy.batchSha256 = "0".repeat(64); }, "batch digest");
  rejects((data, policy) => {
    data.materials[1].caseId = "DEV-ADJ-CASE-002";
    policy.recordDigests[3].sha256 = sha256(JSON.stringify(data.materials[1]));
  }, "issue provenance");
  console.log("Adjudication synthetic input self-test passed: tamper, missing, excessive use and cross-case guards");
}
console.log("Adjudication synthetic input verified: 2 cases, 4 materials, 2 issue revisions; development-only uses");
