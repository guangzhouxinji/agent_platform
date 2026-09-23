import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const reviewDir = join(root, "data", "enforcement", "source-review", "cnipa-official-v1");
const worksetPath = join(reviewDir, "record-review-workset.json");
const generate = process.argv.includes("--generate");
const approveDevelopment = process.argv.includes("--approve-development");
const selfTest = process.argv.includes("--self-test");
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);

const batchIds = [
  "CNIPA_2025_DRUG_ADJUDICATION_0077", "CNIPA_2025_DRUG_ADJUDICATION_0076",
  "CNIPA_2025_DRUG_ADJUDICATION_0075", "CNIPA_2025_DRUG_ADJUDICATION_0074",
  "CNIPA_2025_DRUG_ADJUDICATION_0073", "CNIPA_2025_DRUG_ADJUDICATION_0072",
  "CNIPA_2026_DRUG_ADJUDICATION_0062", "CNIPA_2026_DRUG_ADJUDICATION_0061",
  "CNIPA_2026_DRUG_ADJUDICATION_0021", "CNIPA_2025_DRUG_ADJUDICATION_0060",
  "CNIPA_2026_ADJUDICATION_DEADLINE_REPLY", "CNIPA_2026_ADJUDICATION_PUBLICATION_REPLY",
];
const decisionUses = ["ADJUDICATION_DOCUMENT_PARSING", "ADJUDICATION_DRAFT_STRUCTURE_TEST",
  "ENFORCEMENT_HISTORICAL_REFERENCE", "SOURCE_CITATION_TEST"];
const replyUses = ["RULE_SOURCE_DISPLAY", "WORKFLOW_BRANCH_TEST", "SOURCE_CITATION_TEST"];
const prohibitedClaims = ["NOT_CURRENT_CASE", "NOT_UNDERLYING_EVIDENCE", "NOT_ACCEPTED_EVIDENCE",
  "NOT_CURRENT_RIGHT_STATUS", "NOT_AUTOMATIC_LEGAL_CONCLUSION", "NOT_FORMAL_DECISION"];
const approvalEvidence = {
  responsibleParty: "PROJECT_OWNER_AND_DATA_LEGAL_RESPONSIBLE_PARTY",
  identityReference: "CURRENT_WORKSPACE_PROJECT_OWNER",
  evidenceSource: "docs/02-待确认事项清单.md#19-三域公开数据优先的开发功能覆盖",
  decidedAt: "2026-09-21",
  scope: "Internal-development pilot only: local DM8 storage/indexing and the record candidate domains/uses",
  conclusion: "Approved for internal development; not approved for external models, evidence acceptance, executable rules, legal conclusions, formal decisions, or formal business use.",
};

async function inputs() {
  const [catalogText, manifestText] = await Promise.all([
    readFile(join(reviewDir, "source-catalog.json"), "utf8"),
    readFile(join(reviewDir, "manifest.json"), "utf8"),
  ]);
  return { catalogText, catalog: JSON.parse(catalogText), manifest: JSON.parse(manifestText) };
}

function createWorkset(catalogText, catalog, manifest) {
  const snapshots = new Map(manifest.snapshots.map((item) => [item.id, item]));
  return {
    schemaVersion: "public-record-review-workset-v1",
    worksetId: "CNIPA-DECISION-CORPUS-REVIEW-2026-09-21-V2",
    sourceReviewId: manifest.reviewId,
    sourceCatalogId: catalog.catalogId,
    sourceCatalogVersion: catalog.catalogVersion,
    sourceCatalogSha256: sha256(catalogText),
    sourceBatchSha256: manifest.batchSha256,
    preparedBy: "ENGINEERING_AGENT",
    preparedAt: manifest.capturedAt,
    authorizationRef: "docs/02-待确认事项清单.md#19-三域公开数据优先的开发功能覆盖",
    boundaries: {
      reviewStatus: "REVIEW_REQUIRED",
      dm8ImportAllowed: false,
      externalModelAllowed: false,
      automaticEvidenceAcceptanceAllowed: false,
      automaticLegalDecisionAllowed: false,
      formalBusinessUseAllowed: false,
    },
    requiredProhibitedClaims: prohibitedClaims,
    records: batchIds.map((sourceSnapshotId) => {
      const snapshot = snapshots.get(sourceSnapshotId);
      const historical = snapshot.category === "OFFICIAL_ADJUDICATION_DECISION";
      return {
        sourceSnapshotId,
        sourceSha256: snapshot.sha256,
        sourceUrl: snapshot.requestedUrl,
        title: snapshot.title,
        publishedAt: snapshot.publishedAt,
        file: snapshot.file,
        bytes: snapshot.bytes,
        recordKind: historical ? "HISTORICAL_ADJUDICATION_DECISION" : "OFFICIAL_REPLY",
        technicalIntegrityStatus: "VERIFIED",
        candidateDomains: ["ENFORCEMENT", "ADJUDICATION"],
        candidateUses: historical ? decisionUses : replyUses,
        prohibitedClaims,
        developmentUseReview: {
          status: "PENDING_REVIEW", allowedDomains: [], allowedUses: [], responsibleParty: null,
          identityReference: null, evidenceSource: null, decidedAt: null, scope: null, conclusion: null,
        },
        professionalEffectivityReview: {
          status: "PENDING_PROFESSIONAL_REVIEW", reviewer: null, evidenceSource: null,
          reviewedAt: null, statedScope: null, conclusion: null,
        },
      };
    }),
    eligibleRecords: [],
  };
}

function applyDevelopmentApproval(workset) {
  for (const record of workset.records ?? []) {
    record.developmentUseReview = {
      status: "APPROVED_FOR_INTERNAL_DEVELOPMENT",
      allowedDomains: [...record.candidateDomains],
      allowedUses: [...record.candidateUses],
      ...approvalEvidence,
    };
  }
  workset.eligibleRecords = workset.records.map((record) => ({
    sourceSnapshotId: record.sourceSnapshotId,
    sourceSha256: record.sourceSha256,
    allowedDomains: [...record.developmentUseReview.allowedDomains],
    allowedUses: [...record.developmentUseReview.allowedUses],
    approvalReference: record.developmentUseReview.evidenceSource,
  }));
  return workset;
}

function validationErrors(workset, catalogText, catalog, manifest) {
  const errors = [];
  if (workset.schemaVersion !== "public-record-review-workset-v1") errors.push("schema mismatch");
  if (workset.sourceReviewId !== manifest.reviewId || workset.sourceCatalogId !== catalog.catalogId
      || workset.sourceCatalogVersion !== manifest.catalogVersion
      || workset.sourceCatalogSha256 !== sha256(catalogText)
      || workset.sourceBatchSha256 !== manifest.batchSha256) errors.push("source identity mismatch");
  if (!workset.preparedBy || !workset.preparedAt || !workset.authorizationRef) errors.push("preparation evidence missing");
  const boundaries = workset.boundaries ?? {};
  if (boundaries.reviewStatus !== "REVIEW_REQUIRED" || boundaries.dm8ImportAllowed !== false
      || boundaries.externalModelAllowed !== false || boundaries.automaticEvidenceAcceptanceAllowed !== false
      || boundaries.automaticLegalDecisionAllowed !== false || boundaries.formalBusinessUseAllowed !== false) {
    errors.push("review boundary changed");
  }
  if (!same(workset.requiredProhibitedClaims, prohibitedClaims)) errors.push("prohibited claims incomplete");
  const records = workset.records ?? [];
  if (!same(records.map((item) => item.sourceSnapshotId).sort(), [...batchIds].sort())) {
    errors.push("workset must contain exactly the 12 batch pages");
  }
  const snapshots = new Map(manifest.snapshots.map((item) => [item.id, item]));
  const expectedEligible = [];
  for (const record of records) {
    const snapshot = snapshots.get(record.sourceSnapshotId);
    if (!snapshot || record.sourceSha256 !== snapshot.sha256 || record.sourceUrl !== snapshot.requestedUrl
        || record.title !== snapshot.title || record.publishedAt !== snapshot.publishedAt
        || record.file !== snapshot.file || record.bytes !== snapshot.bytes) {
      errors.push(`${record.sourceSnapshotId}: source binding mismatch`);
      continue;
    }
    const historical = snapshot.category === "OFFICIAL_ADJUDICATION_DECISION";
    if (record.recordKind !== (historical ? "HISTORICAL_ADJUDICATION_DECISION" : "OFFICIAL_REPLY")) {
      errors.push(`${record.sourceSnapshotId}: record kind invalid`);
    }
    if (record.technicalIntegrityStatus !== "VERIFIED"
        || !same(record.candidateDomains, ["ENFORCEMENT", "ADJUDICATION"])
        || !same(record.candidateUses, historical ? decisionUses : replyUses)
        || !same(record.prohibitedClaims, prohibitedClaims)) {
      errors.push(`${record.sourceSnapshotId}: review boundary invalid`);
    }
    const use = record.developmentUseReview ?? {};
    if (use.status === "PENDING_REVIEW") {
      if ((use.allowedDomains ?? []).length || (use.allowedUses ?? []).length || use.responsibleParty
          || use.identityReference || use.evidenceSource || use.decidedAt || use.scope || use.conclusion) {
        errors.push(`${record.sourceSnapshotId}: pending development decision contains approval evidence`);
      }
    } else if (use.status === "APPROVED_FOR_INTERNAL_DEVELOPMENT") {
      if (!same(use.allowedDomains, record.candidateDomains) || !same(use.allowedUses, record.candidateUses)
          || Object.entries(approvalEvidence).some(([key, value]) => use[key] !== value)) {
        errors.push(`${record.sourceSnapshotId}: development approval evidence or scope invalid`);
      } else expectedEligible.push({
        sourceSnapshotId: record.sourceSnapshotId,
        sourceSha256: record.sourceSha256,
        allowedDomains: [...use.allowedDomains],
        allowedUses: [...use.allowedUses],
        approvalReference: use.evidenceSource,
      });
    } else {
      errors.push(`${record.sourceSnapshotId}: development decision status invalid`);
    }
    const effect = record.professionalEffectivityReview ?? {};
    if (effect.status !== "PENDING_PROFESSIONAL_REVIEW" || effect.reviewer || effect.evidenceSource
        || effect.reviewedAt || effect.statedScope || effect.conclusion) {
      errors.push(`${record.sourceSnapshotId}: machine-generated effectivity review is not pending`);
    }
  }
  if (!same(workset.eligibleRecords, expectedEligible)) errors.push("eligibility projection does not match approved records");
  return errors;
}

async function verify(workset, catalogText, catalog, manifest) {
  const errors = validationErrors(workset, catalogText, catalog, manifest);
  for (const record of workset.records ?? []) {
    const path = resolve(reviewDir, record.file ?? "");
    if (!path.startsWith(`${resolve(reviewDir, "raw")}${sep}`)) errors.push(`${record.sourceSnapshotId}: raw path invalid`);
    else try {
      const bytes = await readFile(path);
      if (sha256(bytes) !== record.sourceSha256) errors.push(`${record.sourceSnapshotId}: raw digest mismatch`);
    } catch { errors.push(`${record.sourceSnapshotId}: raw source missing`); }
  }
  if (errors.length) throw new Error(`CNIPA record review verification failed:\n- ${errors.join("\n- ")}`);
}

const loaded = await inputs();
if (generate) await writeFile(worksetPath,
  `${JSON.stringify(createWorkset(loaded.catalogText, loaded.catalog, loaded.manifest), null, 2)}\n`, "utf8");
let workset = JSON.parse(await readFile(worksetPath, "utf8"));
if (approveDevelopment) {
  workset = applyDevelopmentApproval(workset);
  await writeFile(worksetPath, `${JSON.stringify(workset, null, 2)}\n`, "utf8");
}
await verify(workset, loaded.catalogText, loaded.catalog, loaded.manifest);
if (selfTest) {
  for (const mutate of [
    (value) => { value.records.pop(); },
    (value) => { value.records[0].sourceSha256 = "0".repeat(64); },
    (value) => { value.records[0].sourceUrl = "https://example.com/tampered"; },
    (value) => { value.records[0].developmentUseReview.evidenceSource = null; },
    (value) => { value.records[0].developmentUseReview.allowedUses.push("EXECUTABLE_RULE"); },
    (value) => { value.records.at(-1).professionalEffectivityReview.status = "CONFIRMED_FOR_STATED_SCOPE"; },
    (value) => { value.eligibleRecords.pop(); },
  ]) {
    const changed = structuredClone(workset); mutate(changed);
    if (!validationErrors(changed, loaded.catalogText, loaded.catalog, loaded.manifest).length) {
      throw new Error("CNIPA record review self-test failed to reject invalid workset");
    }
  }
  console.log("CNIPA record review self-test passed: coverage, binding, approval-evidence, effectivity and eligibility guards");
}
console.log(`CNIPA record review workset verified: ${workset.records.length} records, ${workset.eligibleRecords.length} eligible for approved domain mapping`);
