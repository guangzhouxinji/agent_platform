import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const sourceRelativeDir = "data/patents/cn-topic-public-pilot-v1";
const sourceDir = join(projectRoot, sourceRelativeDir);
const outputDir = join(projectRoot, "data", "enforcement", "public-reference-pilot-v1");
const sourceManifestPath = join(sourceDir, "manifest.json");
const sourceRecordsPath = join(sourceDir, "records.json");
const sourceReviewManifestPath = join(projectRoot, "data", "enforcement", "source-review", "surechembl-v1", "manifest.json");
const sourceApprovalPath = join(projectRoot, "data", "enforcement", "source-review", "surechembl-v1", "approval.json");
const manifestPath = join(outputDir, "manifest.json");
const recordsPath = join(outputDir, "records.json");
const verifyOnly = process.argv.includes("--verify-only");

const datasetId = "ENF-PUBLIC-REFERENCE-PILOT-V1";
const category = "PUBLIC_RIGHT_STATUS_SNAPSHOT";
const requiredMissingFields = ["authoritativeCurrentLegalStatus", "sourceStatusLabel", "sourceStatusDate"];
const forbiddenRecordKeys = new Set([
  "applicant",
  "applicants",
  "inventor",
  "inventors",
  "person",
  "persons",
  "contact",
  "contacts",
  "abstract",
  "abstractExcerpt",
  "claims",
  "claimsExcerpt",
  "clue",
  "clueId",
  "evidence",
  "evidenceId",
  "rule",
  "ruleId",
  "legalConclusion",
  "infringementConclusion",
  "enforcementStatus",
]);

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const stableJson = (value) => JSON.stringify(value);

async function loadSource() {
  const [manifestText, recordsText, reviewManifestText, approvalText] = await Promise.all([
    readFile(sourceManifestPath, "utf8"),
    readFile(sourceRecordsPath, "utf8"),
    readFile(sourceReviewManifestPath, "utf8"),
    readFile(sourceApprovalPath, "utf8"),
  ]);
  const manifest = JSON.parse(manifestText);
  const payload = JSON.parse(recordsText);
  const reviewManifest = JSON.parse(reviewManifestText);
  const approval = JSON.parse(approvalText);
  const errors = [];
  if (manifest.datasetId !== "CON-04-CN-TOPIC-PUBLIC-PILOT-V1") errors.push("unexpected source datasetId");
  if (manifest.recordsSha256 !== sha256(recordsText)) errors.push("source recordsSha256 mismatch");
  if (manifest.source?.licenseReviewStatus !== "REVIEW_REQUIRED") errors.push("source license review no longer REVIEW_REQUIRED");
  if (manifest.source?.usageDecision !== "INTERNAL_DEVELOPMENT_PILOT_ONLY") errors.push("source usage decision changed");
  if (manifest.boundaries?.externalModelUseAllowed !== false) errors.push("source external model boundary changed");
  if (reviewManifest.reviewStatus !== "REVIEW_REQUIRED" || reviewManifest.importDecision !== "BLOCKED_SOURCE_REVIEW" || reviewManifest.approvalStatus !== "NOT_APPROVED") errors.push("source review evidence no longer blocks import");
  if (approval.reviewId !== reviewManifest.reviewId || approval.decision !== "APPROVED" || approval.scope?.retentionPeriod !== "UNTIL_DEVELOPMENT_PILOT_END") errors.push("source approval is missing or outside the approved retention scope");
  if ((payload.records ?? []).length !== 400 || manifest.recordCount !== 400) errors.push("source must contain exactly 400 records");
  if (errors.length) throw new Error(`Enforcement pilot source validation failed:\n- ${errors.join("\n- ")}`);
  return { manifest, recordsText, records: payload.records, reviewManifestText, reviewManifest, approvalText, approval };
}

async function buildSourceSnapshots(sourceManifest, sourceRecords) {
  const snapshots = [];
  for (const raw of sourceManifest.rawResponses ?? []) {
    const content = await readFile(join(sourceDir, raw.file), "utf8");
    if (sha256(content) !== raw.sha256) throw new Error(`Source raw file hash mismatch: ${raw.file}`);
    snapshots.push({
      ref: `search:${raw.queryId}:${raw.page}`,
      kind: "SEARCH_RESPONSE",
      sourceFile: raw.file,
      fileSha256: raw.sha256,
      responseSha256: raw.responseSha256,
    });
  }
  for (const record of sourceRecords) {
    if (!record.detailRawFile) continue;
    const content = await readFile(join(sourceDir, record.detailRawFile), "utf8");
    snapshots.push({
      ref: `detail:${record.sourceRecordId}`,
      kind: "DETAIL_RESPONSE",
      sourceFile: record.detailRawFile,
      fileSha256: sha256(content),
      responseSha256: record.detailResponseSha256,
    });
  }
  return snapshots.sort((left, right) => left.ref.localeCompare(right.ref, "en"));
}

function createRecord(sourceRecord, sourceManifest, sourceRecordsSha256, snapshotsByRef) {
  const searchRefs = (sourceManifest.rawResponses ?? [])
    .filter((raw) => raw.queryId === sourceRecord.topicId)
    .map((raw) => `search:${raw.queryId}:${raw.page}`)
    .sort((left, right) => left.localeCompare(right, "en"));
  const sourceSnapshotRefs = sourceRecord.detailRawFile
    ? [...searchRefs, `detail:${sourceRecord.sourceRecordId}`]
    : searchRefs;
  const sourceSnapshots = sourceSnapshotRefs.map((ref) => {
    const snapshot = snapshotsByRef.get(ref);
    if (!snapshot) throw new Error(`Missing source snapshot trace: ${ref}`);
    return { ref, rawResponseSha256: snapshot.responseSha256, sourceFile: snapshot.sourceFile };
  });
  const base = {
    sourceSnapshotId: `ENF-PRS-${sourceRecord.sourceRecordId}-${sha256(sourceRecord.sourceVersion).slice(0, 12)}`,
    category,
    sourceSystem: sourceRecord.sourceId,
    stableSourceId: sourceRecord.sourceRecordId,
    sourceVersion: sourceRecord.sourceVersion,
    publicationNumber: sourceRecord.publicationNumber,
    publicationDate: sourceRecord.publicationDate,
    title: sourceRecord.titleZh ?? sourceRecord.title,
    sourceStatusLabel: null,
    sourceStatusDate: null,
    sourceUrl: sourceRecord.sourceRef,
    sourceLocator: sourceRecord.sourceLocator,
    capturedAt: sourceRecord.capturedAt,
    missingFields: [...requiredMissingFields],
    availability: "AVAILABLE",
    dataMode: "PUBLIC_PILOT",
    authorityMarker: "NOT_CENTER_SOURCE",
    usageLimitation: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
    professionalUse: "NOT_PROFESSIONAL_EVIDENCE",
    externalModelAllowed: false,
    importDecision: "APPROVED_FOR_INTERNAL_DEVELOPMENT",
    sourceTrace: {
      sourceDatasetId: sourceManifest.datasetId,
      sourceRecordsSha256,
      sourceRecordSha256: sha256(stableJson(sourceRecord)),
      sourceSnapshotRefs,
      sourceSnapshots,
    },
  };
  return { ...base, payloadSha256: sha256(stableJson(base)) };
}

async function build() {
  const source = await loadSource();
  const sourceSnapshots = await buildSourceSnapshots(source.manifest, source.records);
  const snapshotsByRef = new Map(sourceSnapshots.map((snapshot) => [snapshot.ref, snapshot]));
  const records = source.records
    .map((record) => createRecord(record, source.manifest, source.manifest.recordsSha256, snapshotsByRef))
    .sort((left, right) => `${left.stableSourceId}\u0000${left.sourceVersion}`.localeCompare(`${right.stableSourceId}\u0000${right.sourceVersion}`, "en"));
  const recordsPayload = {
    schemaVersion: "1.0",
    datasetId,
    generatedAt: source.manifest.generatedAt,
    records,
  };
  const recordsText = `${JSON.stringify(recordsPayload, null, 2)}\n`;
  const manifest = {
    schemaVersion: "1.0",
    datasetId,
    generatedAt: source.manifest.generatedAt,
    purpose: "Approved enforcement-domain public reference import set for internal development; not business input",
    sourceReview: {
      sourceName: source.manifest.source.name,
      licenseReviewStatus: "APPROVED",
      importDecision: "APPROVED_FOR_INTERNAL_DEVELOPMENT",
      approvalStatus: "APPROVED",
    },
    origin: {
      sourceDatasetId: source.manifest.datasetId,
      sourceDatasetRelativePath: sourceRelativeDir.replaceAll("\\", "/"),
      sourceRecordsSha256: source.manifest.recordsSha256,
      sourceReviewId: source.reviewManifest.reviewId,
      sourceReviewManifest: "data/enforcement/source-review/surechembl-v1/manifest.json",
      sourceReviewManifestSha256: sha256(source.reviewManifestText),
      sourceApproval: "data/enforcement/source-review/surechembl-v1/approval.json",
      sourceApprovalSha256: sha256(source.approvalText),
    },
    boundaries: {
      category,
      dataMode: "PUBLIC_PILOT",
      authorityMarker: "NOT_CENTER_SOURCE",
      usageLimitation: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
      professionalUse: "NOT_PROFESSIONAL_EVIDENCE",
      externalModelAllowed: false,
      dm8ImportAllowed: true,
      retentionPeriod: "UNTIL_DEVELOPMENT_PILOT_END",
      businessWorkflowInputAllowed: false,
    },
    recordCount: records.length,
    recordsFile: "records.json",
    recordsSha256: sha256(recordsText),
    batchSha256: sha256(records.map((record) => record.payloadSha256).join("\n")),
    stableSort: ["stableSourceId", "sourceVersion"],
    sourceSnapshots,
    omittedSourceFields: ["applicant", "titles", "titleEn", "abstractExcerpt", "claimsExcerpt", "cpcClassifications", "expertLabel", "topicId", "topicLabelZh", "matchedQueryIds"],
    limitations: [
      "DM8 import is approved only for this isolated internal-development public-reference catalog until the development pilot ends.",
      "Publication metadata is not an authoritative current or historical right-status determination.",
      "Records are public development references, not enforcement clues, evidence, rules, findings, or workflow inputs.",
      "No center source, external model, non-public case material, personal information, or applicant field is used.",
    ],
  };
  await mkdir(outputDir, { recursive: true });
  await Promise.all([
    writeFile(recordsPath, recordsText, "utf8"),
    writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8"),
  ]);
}

function collectForbiddenKeys(value, path = "record", matches = []) {
  if (!value || typeof value !== "object") return matches;
  if (Array.isArray(value)) {
    value.forEach((entry, index) => collectForbiddenKeys(entry, `${path}[${index}]`, matches));
    return matches;
  }
  for (const [key, child] of Object.entries(value)) {
    if (forbiddenRecordKeys.has(key)) matches.push(`${path}.${key}`);
    collectForbiddenKeys(child, `${path}.${key}`, matches);
  }
  return matches;
}

async function verify() {
  const source = await loadSource();
  const [manifestText, recordsText] = await Promise.all([readFile(manifestPath, "utf8"), readFile(recordsPath, "utf8")]);
  const manifest = JSON.parse(manifestText);
  const payload = JSON.parse(recordsText);
  const records = payload.records ?? [];
  const errors = [];
  if (manifest.datasetId !== datasetId || payload.datasetId !== datasetId) errors.push("datasetId mismatch");
  if (manifest.recordsSha256 !== sha256(recordsText)) errors.push("recordsSha256 mismatch");
  if (manifest.recordCount !== 400 || records.length !== 400) errors.push(`expected exactly 400 records, found ${records.length}`);
  if (manifest.sourceReview?.licenseReviewStatus !== "APPROVED" || manifest.sourceReview?.approvalStatus !== "APPROVED") errors.push("source approval mismatch");
  if (manifest.sourceReview?.importDecision !== "APPROVED_FOR_INTERNAL_DEVELOPMENT" || manifest.boundaries?.dm8ImportAllowed !== true) errors.push("internal-development DM8 import approval mismatch");
  if (manifest.boundaries?.externalModelAllowed !== false || manifest.boundaries?.businessWorkflowInputAllowed !== false) errors.push("model/workflow boundary changed");
  if (manifest.origin?.sourceRecordsSha256 !== source.manifest.recordsSha256) errors.push("origin source records digest mismatch");
  if (manifest.origin?.sourceReviewId !== source.reviewManifest.reviewId || manifest.origin?.sourceReviewManifestSha256 !== sha256(source.reviewManifestText)) errors.push("source review evidence trace mismatch");
  if (manifest.origin?.sourceApprovalSha256 !== sha256(source.approvalText)) errors.push("source approval trace mismatch");

  const sourceByKey = new Map(source.records.map((record) => [`${record.sourceRecordId}\u0000${record.sourceVersion}`, record]));
  const snapshots = new Map((manifest.sourceSnapshots ?? []).map((snapshot) => [snapshot.ref, snapshot]));
  const keys = records.map((record) => `${record.stableSourceId}\u0000${record.sourceVersion}`);
  const sortedKeys = [...keys].sort((left, right) => left.localeCompare(right, "en"));
  if (new Set(keys).size !== 400) errors.push("source/version keys are not unique");
  if (keys.some((key, index) => key !== sortedKeys[index])) errors.push("records are not in stable source/version order");

  for (const [index, record] of records.entries()) {
    const key = `${record.stableSourceId}\u0000${record.sourceVersion}`;
    const sourceRecord = sourceByKey.get(key);
    if (!sourceRecord) {
      errors.push(`record ${index} has no matching source record`);
      continue;
    }
    if (record.category !== category) errors.push(`record ${index} category mismatch`);
    if (record.sourceStatusLabel !== null || record.sourceStatusDate !== null) errors.push(`record ${index} asserts a right status`);
    if (!requiredMissingFields.every((field) => record.missingFields?.includes(field))) errors.push(`record ${index} does not declare missing authoritative status fields`);
    if (record.availability !== "AVAILABLE" || record.dataMode !== "PUBLIC_PILOT" || record.authorityMarker !== "NOT_CENTER_SOURCE") errors.push(`record ${index} boundary mismatch`);
    if (record.usageLimitation !== "INTERNAL_DEVELOPMENT_PILOT_ONLY" || record.importDecision !== "APPROVED_FOR_INTERNAL_DEVELOPMENT" || record.externalModelAllowed !== false) errors.push(`record ${index} usage/import boundary mismatch`);
    if (record.professionalUse !== "NOT_PROFESSIONAL_EVIDENCE") errors.push(`record ${index} professional-use boundary mismatch`);
    if (record.sourceTrace?.sourceDatasetId !== source.manifest.datasetId || record.sourceTrace?.sourceRecordsSha256 !== source.manifest.recordsSha256) errors.push(`record ${index} source dataset trace mismatch`);
    if (record.sourceTrace?.sourceRecordSha256 !== sha256(stableJson(sourceRecord))) errors.push(`record ${index} source record digest mismatch`);
    if (!record.sourceTrace?.sourceSnapshotRefs?.length || record.sourceTrace.sourceSnapshotRefs.some((ref) => !snapshots.has(ref))) errors.push(`record ${index} has broken source snapshot references`);
    if (record.sourceTrace?.sourceSnapshots?.length !== record.sourceTrace?.sourceSnapshotRefs?.length) errors.push(`record ${index} source snapshot digest count mismatch`);
    for (const trace of record.sourceTrace?.sourceSnapshots ?? []) {
      const snapshot = snapshots.get(trace.ref);
      if (!snapshot || trace.rawResponseSha256 !== snapshot.responseSha256 || trace.sourceFile !== snapshot.sourceFile) errors.push(`record ${index} source snapshot digest mismatch: ${trace.ref}`);
    }
    const searchPrefix = `search:${sourceRecord.topicId}:`;
    if (!record.sourceTrace?.sourceSnapshotRefs?.some((ref) => ref.startsWith(searchPrefix))) errors.push(`record ${index} lacks a search response trace`);
    if (sourceRecord.detailRawFile && !record.sourceTrace?.sourceSnapshotRefs?.includes(`detail:${sourceRecord.sourceRecordId}`)) errors.push(`record ${index} lacks its detail response trace`);
    const { payloadSha256, ...base } = record;
    if (payloadSha256 !== sha256(stableJson(base))) errors.push(`record ${index} payload digest mismatch`);
    const forbidden = collectForbiddenKeys(record, `record[${index}]`);
    if (forbidden.length) errors.push(`record ${index} contains forbidden fields: ${forbidden.join(", ")}`);
  }

  for (const snapshot of snapshots.values()) {
    try {
      const content = await readFile(join(sourceDir, snapshot.sourceFile), "utf8");
      if (sha256(content) !== snapshot.fileSha256) errors.push(`source snapshot file hash mismatch: ${snapshot.sourceFile}`);
    } catch {
      errors.push(`source snapshot file missing: ${snapshot.sourceFile}`);
    }
  }
  const expectedBatchSha256 = sha256(records.map((record) => record.payloadSha256).join("\n"));
  if (manifest.batchSha256 !== expectedBatchSha256) errors.push("batchSha256 mismatch");
  if (errors.length) throw new Error(`Enforcement public reference pilot verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`Enforcement public reference import set verified: ${records.length} records, approved for isolated internal-development DM8 only`);
}

if (!verifyOnly) await build();
await verify();
