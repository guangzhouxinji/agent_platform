import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const sourceRelativeDir = "data/cases/public-pilot-v1";
const sourceDir = join(root, sourceRelativeDir);
const outputDir = join(root, "data", "adjudication", "public-reference-pilot-v1");
const sourceManifestPath = join(sourceDir, "manifest.json");
const sourceRecordsPath = join(sourceDir, "records.json");
const sourceReviewRelativePath = "data/adjudication/source-review/spc-ip-court-v1/manifest.json";
const sourceApprovalRelativePath = "data/adjudication/source-review/spc-ip-court-v1/approval.json";
const sourceReviewPath = join(root, sourceReviewRelativePath);
const sourceApprovalPath = join(root, sourceApprovalRelativePath);
const manifestPath = join(outputDir, "manifest.json");
const recordsPath = join(outputDir, "records.json");
const verifyOnly = process.argv.includes("--verify-only");
const datasetId = "ADJ-PUBLIC-REFERENCE-PILOT-V1";
const category = "PUBLIC_JUDGMENT_REFERENCE";
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const stableJson = (value) => JSON.stringify(value);
const forbiddenKeys = new Set([
  "documents", "text", "fullText", "party", "parties", "appellant", "appellee", "person", "persons",
  "facts", "evidence", "holding", "reasoning", "outcome", "outcomeExcerpt", "legalCitations", "annotations",
  "similarity", "relevance", "legalConclusion", "draft", "caseId", "materialId",
]);

async function loadSource() {
  const [manifestText, recordsText, reviewText, approvalText] = await Promise.all([
    readFile(sourceManifestPath, "utf8"), readFile(sourceRecordsPath, "utf8"),
    readFile(sourceReviewPath, "utf8"), readFile(sourceApprovalPath, "utf8"),
  ]);
  const manifest = JSON.parse(manifestText);
  const records = JSON.parse(recordsText).records ?? [];
  const review = JSON.parse(reviewText);
  const approval = JSON.parse(approvalText);
  const errors = [];
  if (manifest.datasetId !== "CON-03-CASE-PUBLIC-PILOT-V1") errors.push("unexpected source datasetId");
  if (manifest.recordsSha256 !== sha256(recordsText)) errors.push("source recordsSha256 mismatch");
  if (manifest.source?.licenseReviewStatus !== "REVIEW_REQUIRED") errors.push("source review must remain REVIEW_REQUIRED");
  if (manifest.source?.usageDecision !== "INTERNAL_DEVELOPMENT_PILOT_ONLY") errors.push("source usage decision changed");
  if (manifest.boundaries?.externalModelUseAllowed !== false) errors.push("source model boundary changed");
  if (records.length !== 100 || manifest.recordCount !== 100) errors.push("source must contain exactly 100 records");
  if (review.reviewId !== "ADJ-SOURCE-REVIEW-SPC-IP-COURT-V1" || approval.reviewId !== review.reviewId
      || approval.decision !== "APPROVED") errors.push("source approval evidence mismatch");
  if (approval.scope?.sourceDatasetId !== datasetId || approval.scope?.recordCount !== 100
      || approval.scope?.retentionPeriod !== "UNTIL_DEVELOPMENT_PILOT_END") errors.push("source approval scope mismatch");
  if (errors.length) throw new Error(`Adjudication pilot source validation failed:\n- ${errors.join("\n- ")}`);
  return { manifest, recordsText, records, reviewText, review, approvalText, approval };
}

async function verifyRawSnapshots(sourceManifest) {
  const details = new Map();
  for (const raw of sourceManifest.rawResponses ?? []) {
    const content = await readFile(join(sourceDir, raw.file));
    if (sha256(content) !== raw.sha256) throw new Error(`Source raw file hash mismatch: ${raw.file}`);
    if (raw.type === "DETAIL") details.set(raw.sourceRecordId, raw);
  }
  return details;
}

function createRecord(source, sourceManifest, raw, approval, approvalSha256) {
  const trace = {
    sourceDatasetId: sourceManifest.datasetId,
    sourceRecordsSha256: sourceManifest.recordsSha256,
    sourceRecordSha256: sha256(stableJson(source)),
    sourceSnapshotRefs: [`detail:${source.sourceRecordId}`],
    sourceSnapshots: [{
      ref: `detail:${source.sourceRecordId}`,
      rawResponseSha256: raw.sha256,
      sourceFile: `${sourceRelativeDir}/${raw.file}`,
    }],
  };
  const base = {
    sourceSnapshotId: `ADJ-PJR-${source.sourceRecordId}-${sha256(source.sourceVersion).slice(0, 12)}`,
    category,
    sourceSystem: source.sourceId,
    stableSourceId: source.sourceRecordId,
    sourceVersion: source.sourceVersion,
    sourceLocator: source.documents?.[0]?.sourceLocator ?? ".detail .txt",
    title: source.docketNumber,
    publicationDate: source.sourcePublishedAt,
    institution: source.institution,
    documentType: source.caseType,
    missingFields: ["decisionDate", "authoritativeStatus", "legalEffect", "facts", "evidence", "holdings", "legalCitations"],
    availability: "AVAILABLE",
    usageLimitation: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
    sourceUrl: source.sourceRef,
    capturedAt: source.capturedAt,
    dataMode: "PUBLIC_PILOT",
    authorityMarker: "NOT_CENTER_SOURCE",
    professionalUse: "NOT_PROFESSIONAL_EVIDENCE",
    externalModelAllowed: false,
    importDecision: "APPROVED_FOR_INTERNAL_DEVELOPMENT",
    sourceTrace: trace,
    approvalId: approval.approvalId,
    approvalSha256,
    retentionPeriod: approval.scope.retentionPeriod,
    attribution: approval.scope.attribution,
  };
  return { ...base, sourcePayloadSha256: sha256(stableJson(base)) };
}

async function build() {
  const source = await loadSource();
  const details = await verifyRawSnapshots(source.manifest);
  const approvalSha256 = sha256(source.approvalText);
  const records = source.records.map((record) => {
    const raw = details.get(record.sourceRecordId);
    if (!raw) throw new Error(`Missing raw detail response: ${record.sourceRecordId}`);
    return createRecord(record, source.manifest, raw, source.approval, approvalSha256);
  }).sort((left, right) => `${left.stableSourceId}\u0000${left.sourceVersion}`.localeCompare(
    `${right.stableSourceId}\u0000${right.sourceVersion}`, "en"));
  const recordsText = `${JSON.stringify({ schemaVersion: "1.0", datasetId, generatedAt: source.manifest.generatedAt, records }, null, 2)}\n`;
  const manifest = {
    schemaVersion: "1.0",
    datasetId,
    generatedAt: source.manifest.generatedAt,
    purpose: "Approved adjudication-domain judgment locator import set for isolated internal development; not business input",
    origin: {
      sourceDatasetId: source.manifest.datasetId,
      sourceDatasetRelativePath: sourceRelativeDir,
      sourceRecordsSha256: source.manifest.recordsSha256,
      sourceReviewId: source.review.reviewId,
      sourceReviewManifest: sourceReviewRelativePath,
      sourceReviewManifestSha256: sha256(source.reviewText),
      sourceApproval: sourceApprovalRelativePath,
      sourceApprovalSha256: approvalSha256,
    },
    sourceReview: {
      sourceName: source.manifest.source.name,
      licenseReviewStatus: "APPROVED_BY_PROJECT_RESPONSIBLE_PARTY",
      approvalStatus: "APPROVED",
      importDecision: "APPROVED_FOR_INTERNAL_DEVELOPMENT",
    },
    boundaries: {
      category,
      dataMode: "PUBLIC_PILOT",
      authorityMarker: "NOT_CENTER_SOURCE",
      professionalUse: "NOT_PROFESSIONAL_EVIDENCE",
      externalModelAllowed: false,
      dm8ImportAllowed: true,
      retentionPeriod: source.approval.scope.retentionPeriod,
      businessWorkflowInputAllowed: false,
    },
    recordCount: records.length,
    recordsFile: "records.json",
    recordsSha256: sha256(recordsText),
    batchSha256: sha256(records.map((record) => record.sourcePayloadSha256).join("\n")),
    omittedSourceFields: ["documents", "fullText", "parties", "facts", "evidence", "holdings", "reasoning", "outcome", "legalCitations", "annotations"],
    limitations: [
      "DM8 import is approved only for this isolated internal-development public-reference catalog until the development pilot ends.",
      "Source withdrawal or authorization revocation makes records unavailable and removes them from local indexes; only non-content audit digests remain until pilot end.",
      "Records are public document locators, not current-case facts, evidence, legal rules, precedent determinations, or draft inputs.",
      "No source full text, party data, factual findings, evidence, reasoning, outcome, or generated summary is copied.",
    ],
  };
  await mkdir(outputDir, { recursive: true });
  await Promise.all([
    writeFile(recordsPath, recordsText, "utf8"),
    writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8"),
  ]);
}

function forbiddenPaths(value, path = "record", matches = []) {
  if (!value || typeof value !== "object") return matches;
  if (Array.isArray(value)) {
    value.forEach((entry, index) => forbiddenPaths(entry, `${path}[${index}]`, matches));
    return matches;
  }
  for (const [key, child] of Object.entries(value)) {
    if (forbiddenKeys.has(key)) matches.push(`${path}.${key}`);
    forbiddenPaths(child, `${path}.${key}`, matches);
  }
  return matches;
}

async function verify() {
  const source = await loadSource();
  const details = await verifyRawSnapshots(source.manifest);
  const [manifestText, recordsText] = await Promise.all([readFile(manifestPath, "utf8"), readFile(recordsPath, "utf8")]);
  const manifest = JSON.parse(manifestText);
  const records = JSON.parse(recordsText).records ?? [];
  const sourceById = new Map(source.records.map((record) => [record.sourceRecordId, record]));
  const errors = [];
  if (manifest.datasetId !== datasetId || manifest.recordsSha256 !== sha256(recordsText)) errors.push("dataset or records digest mismatch");
  if (manifest.recordCount !== 100 || records.length !== 100) errors.push(`expected 100 records, found ${records.length}`);
  if (manifest.sourceReview?.approvalStatus !== "APPROVED" || manifest.sourceReview?.importDecision !== "APPROVED_FOR_INTERNAL_DEVELOPMENT") errors.push("approval boundary mismatch");
  if (manifest.boundaries?.dm8ImportAllowed !== true || manifest.boundaries?.externalModelAllowed !== false
      || manifest.boundaries?.businessWorkflowInputAllowed !== false) errors.push("approved use boundary changed");
  if (manifest.origin?.sourceReviewManifestSha256 !== sha256(source.reviewText)
      || manifest.origin?.sourceApprovalSha256 !== sha256(source.approvalText)) errors.push("approval evidence trace mismatch");
  const keys = records.map((record) => `${record.stableSourceId}\u0000${record.sourceVersion}`);
  const sorted = [...keys].sort((a, b) => a.localeCompare(b, "en"));
  if (new Set(keys).size !== 100 || keys.some((key, index) => key !== sorted[index])) errors.push("record keys are not unique and stably sorted");
  for (const [index, record] of records.entries()) {
    const origin = sourceById.get(record.stableSourceId);
    const raw = details.get(record.stableSourceId);
    if (!origin || !raw) { errors.push(`record ${index} has no source trace`); continue; }
    if (record.category !== category || record.importDecision !== "APPROVED_FOR_INTERNAL_DEVELOPMENT") errors.push(`record ${index} category/import decision mismatch`);
    if (record.externalModelAllowed !== false || record.professionalUse !== "NOT_PROFESSIONAL_EVIDENCE") errors.push(`record ${index} use boundary mismatch`);
    if (record.approvalId !== source.approval.approvalId || record.approvalSha256 !== sha256(source.approvalText)
        || record.retentionPeriod !== source.approval.scope.retentionPeriod
        || record.attribution !== source.approval.scope.attribution) errors.push(`record ${index} approval trace mismatch`);
    if (record.sourceTrace?.sourceRecordSha256 !== sha256(stableJson(origin))) errors.push(`record ${index} source digest mismatch`);
    const trace = record.sourceTrace?.sourceSnapshots?.[0];
    if (trace?.rawResponseSha256 !== raw.sha256 || !trace?.sourceFile?.endsWith(raw.file)) errors.push(`record ${index} raw trace mismatch`);
    const { sourcePayloadSha256, ...base } = record;
    if (sourcePayloadSha256 !== sha256(stableJson(base))) errors.push(`record ${index} payload digest mismatch`);
    const forbidden = forbiddenPaths(record, `record[${index}]`);
    if (forbidden.length) errors.push(`record ${index} contains forbidden fields: ${forbidden.join(", ")}`);
  }
  if (manifest.batchSha256 !== sha256(records.map((record) => record.sourcePayloadSha256).join("\n"))) errors.push("batchSha256 mismatch");
  if (errors.length) throw new Error(`Adjudication public reference import verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`Adjudication public reference import set verified: ${records.length} records, approved for isolated internal-development DM8 only`);
}

if (!verifyOnly) await build();
await verify();
