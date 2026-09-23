import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const reviewDir = join(root, "data", "enforcement", "source-review", "cnipa-official-v1");
const outputDir = join(root, "data", "shared", "domain-mapping", "cnipa-decision-corpus-v1");
const generate = process.argv.includes("--generate");
const selfTest = process.argv.includes("--self-test");
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const json = (value) => `${JSON.stringify(value, null, 2)}\n`;
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);

const requiredClaims = [
  "NOT_CURRENT_CASE", "NOT_UNDERLYING_EVIDENCE", "NOT_ACCEPTED_EVIDENCE",
  "NOT_CURRENT_RIGHT_STATUS", "NOT_AUTOMATIC_LEGAL_CONCLUSION", "NOT_FORMAL_DECISION",
];
const decisionUses = {
  ENFORCEMENT: ["ENFORCEMENT_HISTORICAL_REFERENCE", "SOURCE_CITATION_TEST"],
  ADJUDICATION: ["ADJUDICATION_DOCUMENT_PARSING", "ADJUDICATION_DRAFT_STRUCTURE_TEST", "SOURCE_CITATION_TEST"],
};
const approvedDecisionUses = [
  "ADJUDICATION_DOCUMENT_PARSING", "ADJUDICATION_DRAFT_STRUCTURE_TEST",
  "ENFORCEMENT_HISTORICAL_REFERENCE", "SOURCE_CITATION_TEST",
];
const replyUses = ["RULE_SOURCE_DISPLAY", "WORKFLOW_BRANCH_TEST", "SOURCE_CITATION_TEST"];

async function loadInputs() {
  const [worksetText, catalogText, sourceManifestText] = await Promise.all([
    readFile(join(reviewDir, "record-review-workset.json"), "utf8"),
    readFile(join(reviewDir, "source-catalog.json"), "utf8"),
    readFile(join(reviewDir, "manifest.json"), "utf8"),
  ]);
  return {
    worksetText,
    workset: JSON.parse(worksetText),
    catalogText,
    catalog: JSON.parse(catalogText),
    sourceManifest: JSON.parse(sourceManifestText),
  };
}

function projectionId(domain, record, uses) {
  const digest = sha256(`${domain}|${record.sourceSnapshotId}|${record.sourceSha256}|${uses.join(",")}`);
  return `${domain === "ENFORCEMENT" ? "ENF" : "ADJ"}-${digest.slice(0, 24).toUpperCase()}`;
}

function mappedUses(domain, record) {
  const requested = record.recordKind === "HISTORICAL_ADJUDICATION_DECISION"
    ? decisionUses[domain]
    : replyUses;
  return requested.filter((use) => record.developmentUseReview.allowedUses.includes(use));
}

function mapRecord(domain, record, workset) {
  const uses = mappedUses(domain, record);
  const isDecision = record.recordKind === "HISTORICAL_ADJUDICATION_DECISION";
  return {
    domainProjectionId: projectionId(domain, record, uses),
    domain,
    projectionKind: isDecision
      ? (domain === "ENFORCEMENT" ? "HISTORICAL_REFERENCE" : "DOCUMENT_PARSING_AND_DRAFT_STRUCTURE_INPUT")
      : "RULE_SOURCE_DISPLAY_AND_WORKFLOW_TEST_FIXTURE",
    sourceSnapshotId: record.sourceSnapshotId,
    sourceSha256: record.sourceSha256,
    sourceUrl: record.sourceUrl,
    sourceTitle: record.title,
    sourcePublishedAt: record.publishedAt,
    sourceFile: `data/enforcement/source-review/cnipa-official-v1/${record.file}`,
    sourceLocator: {
      granularity: "SOURCE_PAGE_ONLY",
      locator: record.sourceUrl,
      limitation: isDecision
        ? "The captured page only links to the decision PDF; the PDF body is not present in this batch."
        : "The captured HTML page is the finest verified locator in this batch.",
    },
    documentBodyAvailable: !isDecision,
    recordKind: record.recordKind,
    allowedUses: uses,
    developmentUseStatus: record.developmentUseReview.status,
    approvalReference: record.developmentUseReview.evidenceSource,
    approvalDecidedAt: record.developmentUseReview.decidedAt,
    professionalEffectivityStatus: record.professionalEffectivityReview.status,
    availabilityStatus: "AVAILABLE_FOR_INTERNAL_DEVELOPMENT",
    withdrawalKey: `${record.sourceSnapshotId}:${record.sourceSha256}`,
    prohibitedClaims: [...record.prohibitedClaims],
    boundaries: {
      directBusinessImportAllowed: false,
      executableRuleAllowed: false,
      evidenceAcceptanceAllowed: false,
      formalDecisionAllowed: false,
      externalModelAllowed: false,
    },
  };
}

function domainSet(domain, items, workset) {
  return {
    schemaVersion: "public-domain-projection-set-v1",
    domain,
    mappingPolicyVersion: "CNIPA-DOMAIN-MAPPING-V1",
    sourceWorksetId: workset.worksetId,
    sourceCatalogVersion: workset.sourceCatalogVersion,
    sourceBatchSha256: workset.sourceBatchSha256,
    projectionStatus: "ISOLATED_INTERNAL_DEVELOPMENT_ONLY",
    records: items,
  };
}

function createOutputs(inputs) {
  const { workset } = inputs;
  const eligible = new Map(workset.eligibleRecords.map((entry) => [entry.sourceSnapshotId, entry]));
  const approved = workset.records.filter((record) => eligible.has(record.sourceSnapshotId));
  const enforcement = approved
    .filter((record) => record.developmentUseReview.allowedDomains.includes("ENFORCEMENT"))
    .map((record) => mapRecord("ENFORCEMENT", record, workset));
  const adjudication = approved
    .filter((record) => record.developmentUseReview.allowedDomains.includes("ADJUDICATION"))
    .map((record) => mapRecord("ADJUDICATION", record, workset));
  const consultation = domainSet("CONSULTATION", [], workset);
  consultation.emptyReason = "NO_APPROVED_CONSULTATION_DOMAIN_OR_USE_IN_SOURCE_WORKSET";
  consultation.boundaries = { crossDomainFallbackAllowed: false, externalModelAllowed: false };

  const sets = {
    enforcement: domainSet("ENFORCEMENT", enforcement, workset),
    adjudication: domainSet("ADJUDICATION", adjudication, workset),
    consultation,
  };
  const fileTexts = {
    "enforcement-records.json": json(sets.enforcement),
    "adjudication-records.json": json(sets.adjudication),
    "consultation-records.json": json(sets.consultation),
  };
  const manifest = {
    schemaVersion: "public-domain-mapping-manifest-v1",
    mappingId: "CNIPA-DECISION-CORPUS-DOMAIN-MAPPING-V1",
    mappingPolicyVersion: "CNIPA-DOMAIN-MAPPING-V1",
    sourceReviewId: workset.sourceReviewId,
    sourceWorksetId: workset.worksetId,
    sourceWorksetSha256: sha256(inputs.worksetText),
    sourceCatalogId: workset.sourceCatalogId,
    sourceCatalogVersion: workset.sourceCatalogVersion,
    sourceCatalogSha256: sha256(inputs.catalogText),
    sourceBatchSha256: workset.sourceBatchSha256,
    generatedAt: workset.preparedAt,
    authorizationRef: workset.authorizationRef,
    counts: {
      eligibleSourceRecords: approved.length,
      enforcementRecords: enforcement.length,
      adjudicationRecords: adjudication.length,
      consultationRecords: 0,
    },
    boundaries: {
      isolatedDevelopmentProjectionOnly: true,
      directDm8WriteAllowed: false,
      externalModelAllowed: false,
      executableRuleAllowed: false,
      evidenceAcceptanceAllowed: false,
      formalDecisionAllowed: false,
    },
    files: Object.entries(fileTexts).map(([file, text]) => ({ file, sha256: sha256(text) })),
  };
  return { manifest, fileTexts };
}

function inputErrors(inputs) {
  const { workset, catalog, sourceManifest } = inputs;
  const errors = [];
  if (workset.schemaVersion !== "public-record-review-workset-v1") errors.push("workset schema mismatch");
  if (workset.sourceCatalogId !== catalog.catalogId || workset.sourceCatalogVersion !== catalog.catalogVersion
      || workset.sourceBatchSha256 !== sourceManifest.batchSha256) errors.push("source catalog or batch identity mismatch");
  if (workset.boundaries?.externalModelAllowed !== false || workset.boundaries?.dm8ImportAllowed !== false) {
    errors.push("source workset boundary changed");
  }
  const records = new Map(workset.records.map((record) => [record.sourceSnapshotId, record]));
  const snapshots = new Map(sourceManifest.snapshots.map((snapshot) => [snapshot.id, snapshot]));
  const seen = new Set();
  for (const eligible of workset.eligibleRecords) {
    const record = records.get(eligible.sourceSnapshotId);
    const snapshot = snapshots.get(eligible.sourceSnapshotId);
    if (seen.has(eligible.sourceSnapshotId)) errors.push(`${eligible.sourceSnapshotId}: duplicate eligibility`);
    seen.add(eligible.sourceSnapshotId);
    if (!record || !snapshot || eligible.sourceSha256 !== record?.sourceSha256
        || record?.sourceSha256 !== snapshot?.sha256 || record?.sourceUrl !== snapshot?.requestedUrl) {
      errors.push(`${eligible.sourceSnapshotId}: eligible source binding mismatch`);
      continue;
    }
    const review = record.developmentUseReview;
    if (record.technicalIntegrityStatus !== "VERIFIED" || review.status !== "APPROVED_FOR_INTERNAL_DEVELOPMENT"
        || !same(eligible.allowedDomains, review.allowedDomains) || !same(eligible.allowedUses, review.allowedUses)
        || eligible.approvalReference !== review.evidenceSource || !review.decidedAt || !review.responsibleParty) {
      errors.push(`${eligible.sourceSnapshotId}: approval evidence mismatch`);
    }
    if (!same(record.prohibitedClaims, requiredClaims)) errors.push(`${eligible.sourceSnapshotId}: prohibited claims incomplete`);
    if (record.professionalEffectivityReview?.status !== "PENDING_PROFESSIONAL_REVIEW") {
      errors.push(`${eligible.sourceSnapshotId}: professional effectivity was not kept pending`);
    }
    if (review.allowedDomains.includes("CONSULTATION")) errors.push(`${eligible.sourceSnapshotId}: consultation domain not approved`);
    const expectedUses = record.recordKind === "HISTORICAL_ADJUDICATION_DECISION"
      ? approvedDecisionUses
      : replyUses;
    if (!same(review.allowedUses, expectedUses)) errors.push(`${eligible.sourceSnapshotId}: approved use set changed`);
  }
  return errors;
}

function outputErrors(outputs, inputs) {
  const errors = inputErrors(inputs);
  const expected = createOutputs(inputs);
  if (!same(outputs.manifest, expected.manifest)) errors.push("mapping manifest does not match approved inputs");
  for (const [file, text] of Object.entries(expected.fileTexts)) {
    if (outputs.fileTexts[file] !== text) errors.push(`${file}: projection content mismatch`);
  }
  const parsed = Object.fromEntries(Object.entries(outputs.fileTexts).map(([file, text]) => [file, JSON.parse(text)]));
  const enforcement = parsed["enforcement-records.json"]?.records ?? [];
  const adjudication = parsed["adjudication-records.json"]?.records ?? [];
  const consultation = parsed["consultation-records.json"]?.records ?? [];
  if (inputs.workset.eligibleRecords.length === 12
      && (enforcement.length !== 12 || adjudication.length !== 12 || consultation.length !== 0)) {
    errors.push("current approved batch must map to 12 enforcement, 12 adjudication and 0 consultation records");
  }
  for (const record of [...enforcement, ...adjudication]) {
    if (!record.domainProjectionId || !record.sourceSnapshotId || !record.sourceSha256 || !record.sourceUrl
        || !record.sourceFile || !record.withdrawalKey || !record.approvalReference
        || !same(record.prohibitedClaims, requiredClaims)) errors.push(`${record.sourceSnapshotId}: trace fields incomplete`);
    if (record.boundaries?.directBusinessImportAllowed !== false || record.boundaries?.externalModelAllowed !== false
        || record.boundaries?.executableRuleAllowed !== false) errors.push(`${record.sourceSnapshotId}: output boundary expanded`);
    if (record.recordKind === "OFFICIAL_REPLY" && record.professionalEffectivityStatus === "PENDING_PROFESSIONAL_REVIEW"
        && record.projectionKind !== "RULE_SOURCE_DISPLAY_AND_WORKFLOW_TEST_FIXTURE") {
      errors.push(`${record.sourceSnapshotId}: pending reply became executable`);
    }
  }
  return errors;
}

async function rawFileErrors(inputs) {
  const errors = [];
  const eligibleIds = new Set(inputs.workset.eligibleRecords.map((entry) => entry.sourceSnapshotId));
  for (const record of inputs.workset.records.filter((item) => eligibleIds.has(item.sourceSnapshotId))) {
    const path = resolve(reviewDir, record.file);
    if (!path.startsWith(`${resolve(reviewDir, "raw")}${sep}`)) errors.push(`${record.sourceSnapshotId}: source path invalid`);
    else try {
      const bytes = await readFile(path);
      if (sha256(bytes) !== record.sourceSha256) errors.push(`${record.sourceSnapshotId}: raw source digest mismatch`);
    } catch { errors.push(`${record.sourceSnapshotId}: raw source missing`); }
  }
  return errors;
}

async function readOutputs() {
  const manifestText = await readFile(join(outputDir, "manifest.json"), "utf8");
  const manifest = JSON.parse(manifestText);
  const fileTexts = {};
  for (const file of ["enforcement-records.json", "adjudication-records.json", "consultation-records.json"]) {
    fileTexts[file] = await readFile(join(outputDir, file), "utf8");
  }
  return { manifest, fileTexts };
}

const inputs = await loadInputs();
const built = createOutputs(inputs);
const preflight = [...inputErrors(inputs), ...await rawFileErrors(inputs)];
if (preflight.length) throw new Error(`CNIPA domain mapping input verification failed:\n- ${preflight.join("\n- ")}`);
if (generate) {
  await mkdir(outputDir, { recursive: true });
  await Promise.all([
    writeFile(join(outputDir, "manifest.json"), json(built.manifest), "utf8"),
    ...Object.entries(built.fileTexts).map(([file, text]) => writeFile(join(outputDir, file), text, "utf8")),
  ]);
}
const outputs = await readOutputs();
const errors = outputErrors(outputs, inputs);
if (errors.length) throw new Error(`CNIPA domain mapping verification failed:\n- ${errors.join("\n- ")}`);

if (selfTest) {
  const mutations = [
    (value) => { value.fileTexts["enforcement-records.json"] = value.fileTexts["enforcement-records.json"].replace(/[a-f0-9]{64}/, "0".repeat(64)); },
    (value) => { const set = JSON.parse(value.fileTexts["consultation-records.json"]); set.records.push(JSON.parse(value.fileTexts["enforcement-records.json"]).records[0]); value.fileTexts["consultation-records.json"] = json(set); },
    (value) => { const set = JSON.parse(value.fileTexts["adjudication-records.json"]); set.records.at(-1).projectionKind = "EXECUTABLE_RULE"; value.fileTexts["adjudication-records.json"] = json(set); },
    (value) => { const set = JSON.parse(value.fileTexts["enforcement-records.json"]); set.records[0].prohibitedClaims.pop(); value.fileTexts["enforcement-records.json"] = json(set); },
  ];
  for (const mutate of mutations) {
    const changed = structuredClone(outputs);
    mutate(changed);
    if (!outputErrors(changed, inputs).length) throw new Error("CNIPA domain mapping self-test failed to reject tampered output");
  }
  const emptyInputs = structuredClone(inputs);
  emptyInputs.worksetText = json({ ...emptyInputs.workset, eligibleRecords: [] });
  emptyInputs.workset.eligibleRecords = [];
  const empty = createOutputs(emptyInputs);
  if (JSON.parse(empty.fileTexts["enforcement-records.json"]).records.length
      || JSON.parse(empty.fileTexts["adjudication-records.json"]).records.length
      || JSON.parse(empty.fileTexts["consultation-records.json"]).records.length) {
    throw new Error("CNIPA domain mapping self-test failed empty eligibility behavior");
  }
  console.log("CNIPA domain mapping self-test passed: drift, consultation, rule, prohibition and empty-set guards");
}
console.log(`CNIPA domain mapping verified: ${built.manifest.counts.enforcementRecords} enforcement, ${built.manifest.counts.adjudicationRecords} adjudication, ${built.manifest.counts.consultationRecords} consultation records`);
