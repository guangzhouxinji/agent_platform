import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const reviewDir = join(root, "data", "enforcement", "source-review", "cnipa-official-v1");
const decisionPath = join(reviewDir, "historical-case-conversion-decision.json");
const sourceManifestPath = join(reviewDir, "manifest.json");
const outputDir = join(root, "data", "enforcement", "public-historical-cases-cnipa-v1");
const manifestPath = join(outputDir, "manifest.json");
const recordsPath = join(outputDir, "records.json");
const verifyOnly = process.argv.includes("--verify-only");
const selfTest = process.argv.includes("--self-test");

const DATASET_ID = "ENF-CNIPA-PUBLIC-HISTORICAL-CASES-V1";
const CATEGORY = "PUBLIC_HISTORICAL_CASE_SAMPLE";
const SOURCE_SYSTEM = "CNIPA_OFFICIAL_PUBLIC_CASES";
const DECISION_SHA256 = "3a3170999aea58365a1f0dce641afbf2195dd4ffff55b9e80d89456abe32c4ed";
const REQUIRED_MISSING = [
  "CURRENT_CASE_STATUS",
  "UNDERLYING_CASE_EVIDENCE",
  "CURRENT_RIGHT_STATUS",
  "PROFESSIONAL_ACCEPTANCE",
];
const forbiddenKeys = new Set([
  "clue", "clueId", "evidence", "evidenceId", "rule", "ruleId", "rightStatus",
  "legalConclusion", "infringementConclusion", "penaltyRecommendation", "acceptedEvidence",
]);

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const stableJson = (value) => JSON.stringify(value);

async function inputs() {
  const [decisionText, sourceManifestText] = await Promise.all([
    readFile(decisionPath, "utf8"),
    readFile(sourceManifestPath, "utf8"),
  ]);
  const decision = JSON.parse(decisionText);
  const sourceManifest = JSON.parse(sourceManifestText);
  const errors = [];
  if (sha256(decisionText) !== DECISION_SHA256) errors.push("conversion decision digest mismatch");
  if (decision.sourceReviewId !== sourceManifest.reviewId) errors.push("source review identity mismatch");
  if (!/^[0-9a-f]{64}$/.test(decision.sourceBatchSha256 ?? "")) errors.push("approved source batch digest missing");
  if (decision.externalModelAllowed !== false || decision.businessWorkflowInputAllowed !== false) errors.push("decision model/workflow boundary changed");
  if (decision.retentionPeriod !== "UNTIL_DEVELOPMENT_PILOT_END") errors.push("decision retention changed");
  if (decision.cases?.length !== 33) errors.push("conversion decision must contain exactly 33 cases");
  const snapshots = new Map((sourceManifest.snapshots ?? []).map((item) => [item.id, item]));
  for (const source of decision.sources ?? []) {
    const snapshot = snapshots.get(source.snapshotId);
    if (!snapshot || snapshot.sha256 !== source.sha256) errors.push(`source declaration mismatch: ${source.snapshotId}`);
    if (snapshot) {
      const bytes = await readFile(join(reviewDir, snapshot.file));
      if (sha256(bytes) !== snapshot.sha256) errors.push(`source attachment digest mismatch: ${source.snapshotId}`);
    }
  }
  if (errors.length) throw new Error(`CNIPA historical case input validation failed:\n- ${errors.join("\n- ")}`);
  return { decisionText, decision, sourceManifest, snapshots };
}

function recordFor(item, decision, source) {
  const pages = item.pageStart === item.pageEnd ? `page:${item.pageStart}` : `pages:${item.pageStart}-${item.pageEnd}`;
  const base = {
    sourceSnapshotId: `ENF-HIST-${item.caseId}`,
    category: CATEGORY,
    sourceSystem: SOURCE_SYSTEM,
    stableSourceId: item.caseId,
    sourceVersion: `${decision.decisionVersion}@${source.sha256}`,
    title: item.title,
    publicationDate: source.publicationDate,
    sourceStatusLabel: null,
    businessCategory: item.businessCategory,
    sourcePageStart: item.pageStart,
    sourcePageEnd: item.pageEnd,
    sourceUrl: source.sourceUrl,
    sourceLocator: `${source.snapshotId}#${pages}`,
    capturedAt: "2026-09-20T01:57:59.337Z",
    historicalCaseSummary: `国家知识产权局公开历史案例记载：${item.title}。本记录只保存公开案例定位和历史上下文，不代表平台对当前事实、证据或权利状态作出认定。`,
    titleExtractionNote: item.titleExtractionNote ?? null,
    sourceDocumentSha256: source.sha256,
    missingFields: [...REQUIRED_MISSING],
    availability: "AVAILABLE",
    usageLimitation: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
    dataMode: "PUBLIC_PILOT",
    authorityMarker: "NOT_CENTER_SOURCE",
    professionalUse: "NOT_PROFESSIONAL_EVIDENCE",
    externalModelAllowed: false,
    businessWorkflowInputAllowed: false,
    importDecision: "APPROVED_FOR_INTERNAL_DEVELOPMENT",
    sourceTrace: {
      sourceDatasetId: decision.sourceReviewId,
      sourceRecordsSha256: decision.sourceBatchSha256,
      sourceRecordSha256: sha256(stableJson(item)),
      sourceSnapshotRefs: [`attachment:${source.snapshotId}:${pages}`],
      sourceSnapshots: [{
        ref: `attachment:${source.snapshotId}:${pages}`,
        rawResponseSha256: source.sha256,
        sourceFile: `data/enforcement/source-review/cnipa-official-v1/${source.snapshotId}`,
      }],
    },
    approvalId: decision.decisionId,
    approvalSha256: DECISION_SHA256,
    retentionPeriod: decision.retentionPeriod,
    attribution: "国家知识产权局公开行政保护案例；内部开发期历史参考；https://www.cnipa.gov.cn",
  };
  return { ...base, payloadSha256: sha256(stableJson(base)) };
}

async function build() {
  const source = await inputs();
  const sources = new Map(source.decision.sources.map((item) => [item.snapshotId, item]));
  const records = source.decision.cases
    .map((item) => recordFor(item, source.decision, sources.get(item.sourceSnapshotId)))
    .sort((left, right) => left.stableSourceId.localeCompare(right.stableSourceId, "en"));
  const recordsPayload = { schemaVersion: "1.0", datasetId: DATASET_ID, records };
  const recordsText = `${JSON.stringify(recordsPayload, null, 2)}\n`;
  const counts = Object.fromEntries([...new Set(records.map((item) => item.businessCategory))]
    .sort().map((category) => [category, records.filter((item) => item.businessCategory === category).length]));
  const manifest = {
    schemaVersion: "1.0",
    datasetId: DATASET_ID,
    generatedAt: source.decision.decidedAt,
    purpose: "Approved CNIPA public historical case samples for isolated internal-development reference; never an enforcement workflow input",
    sourceReview: {
      sourceReviewId: source.decision.sourceReviewId,
      originalReviewStatus: source.sourceManifest.boundaries.reviewStatus,
      conversionDecisionId: source.decision.decisionId,
      conversionDecisionSha256: DECISION_SHA256,
    },
    boundaries: {
      category: CATEGORY,
      dataMode: "PUBLIC_PILOT",
      authorityMarker: "NOT_CENTER_SOURCE",
      professionalUse: "NOT_PROFESSIONAL_EVIDENCE",
      externalModelAllowed: false,
      businessWorkflowInputAllowed: false,
      dm8ImportAllowed: true,
      retentionPeriod: source.decision.retentionPeriod,
    },
    recordCount: records.length,
    categoryCounts: counts,
    recordsFile: "records.json",
    recordsSha256: sha256(recordsText),
    batchSha256: sha256(records.map((item) => item.payloadSha256).join("\n")),
    stableSort: ["stableSourceId"],
    sourceAttachments: source.decision.sources,
    limitations: [
      "Historical public cases are not current violation clues or underlying case evidence.",
      "No record asserts accepted evidence, current right status, an executable rule, professional acceptance, or a platform legal conclusion.",
      "Records are isolated from enforcement workflow tables and are unavailable to external models.",
    ],
  };
  await mkdir(outputDir, { recursive: true });
  await Promise.all([
    writeFile(recordsPath, recordsText),
    writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`),
  ]);
}

function collectForbidden(value, path = "root", found = []) {
  if (!value || typeof value !== "object") return found;
  if (Array.isArray(value)) value.forEach((item, index) => collectForbidden(item, `${path}[${index}]`, found));
  else for (const [key, item] of Object.entries(value)) {
    if (forbiddenKeys.has(key)) found.push(`${path}.${key}`);
    collectForbidden(item, `${path}.${key}`, found);
  }
  return found;
}

function validationErrors(manifest, payload, source) {
  const records = payload.records ?? [];
  const errors = [];
  if (manifest.datasetId !== DATASET_ID || payload.datasetId !== DATASET_ID) errors.push("dataset id mismatch");
  if (manifest.recordsSha256 !== sha256(`${JSON.stringify(payload, null, 2)}\n`)) errors.push("records digest mismatch");
  if (manifest.recordCount !== 33 || records.length !== 33) errors.push("expected exactly 33 records");
  const expectedCounts = {
    ADMINISTRATIVE_PROTECTION_GUIDANCE: 3,
    GEOGRAPHICAL_OFFICIAL_SPECIAL_MARK_PROTECTION: 10,
    PATENT_ADMINISTRATIVE_PROTECTION: 10,
    TRADEMARK_ADMINISTRATIVE_PROTECTION: 10,
  };
  if (stableJson(manifest.categoryCounts) !== stableJson(expectedCounts)) errors.push("category counts mismatch");
  if (manifest.boundaries?.businessWorkflowInputAllowed !== false || manifest.boundaries?.externalModelAllowed !== false) errors.push("manifest workflow/model boundary changed");
  if (manifest.sourceReview?.originalReviewStatus !== "REVIEW_REQUIRED") errors.push("original source review status changed");
  if (manifest.sourceReview?.conversionDecisionSha256 !== DECISION_SHA256) errors.push("decision trace mismatch");
  const sourceCases = new Map(source.decision.cases.map((item) => [item.caseId, item]));
  const keys = records.map((item) => item.stableSourceId);
  const sorted = [...keys].sort((left, right) => left.localeCompare(right, "en"));
  if (new Set(keys).size !== 33) errors.push("case ids are not unique");
  if (keys.some((key, index) => key !== sorted[index])) errors.push("records are not stably sorted");
  for (const [index, record] of records.entries()) {
    const decisionCase = sourceCases.get(record.stableSourceId);
    if (!decisionCase) { errors.push(`record ${index} has no approved case decision`); continue; }
    if (record.category !== CATEGORY || record.sourceSystem !== SOURCE_SYSTEM) errors.push(`record ${index} category/source mismatch`);
    if (record.title !== decisionCase.title || record.businessCategory !== decisionCase.businessCategory) errors.push(`record ${index} approved mapping mismatch`);
    if (record.sourcePageStart !== decisionCase.pageStart || record.sourcePageEnd !== decisionCase.pageEnd) errors.push(`record ${index} page anchor mismatch`);
    if (record.sourceStatusLabel !== null || !REQUIRED_MISSING.every((field) => record.missingFields?.includes(field))) errors.push(`record ${index} asserts or omits status boundaries`);
    if (record.externalModelAllowed !== false || record.businessWorkflowInputAllowed !== false || record.professionalUse !== "NOT_PROFESSIONAL_EVIDENCE") errors.push(`record ${index} usage boundary changed`);
    if (record.approvalId !== source.decision.decisionId || record.approvalSha256 !== DECISION_SHA256 || record.retentionPeriod !== "UNTIL_DEVELOPMENT_PILOT_END") errors.push(`record ${index} approval trace mismatch`);
    if (!record.historicalCaseSummary?.startsWith("国家知识产权局公开历史案例记载：")) errors.push(`record ${index} historical framing missing`);
    if (record.sourceTrace?.sourceRecordSha256 !== sha256(stableJson(decisionCase))) errors.push(`record ${index} case decision digest mismatch`);
    if (record.sourceTrace?.sourceSnapshots?.length !== 1 || record.sourceTrace.sourceSnapshots[0].rawResponseSha256 !== record.sourceDocumentSha256) errors.push(`record ${index} attachment trace mismatch`);
    const { payloadSha256, ...base } = record;
    if (payloadSha256 !== sha256(stableJson(base))) errors.push(`record ${index} payload digest mismatch`);
    const forbidden = collectForbidden(record, `record[${index}]`);
    if (forbidden.length) errors.push(`record ${index} contains forbidden fields: ${forbidden.join(",")}`);
  }
  if (manifest.batchSha256 !== sha256(records.map((item) => item.payloadSha256).join("\n"))) errors.push("batch digest mismatch");
  return errors;
}

async function verify() {
  const source = await inputs();
  const [manifestText, recordsText] = await Promise.all([readFile(manifestPath, "utf8"), readFile(recordsPath, "utf8")]);
  const manifest = JSON.parse(manifestText);
  const payload = JSON.parse(recordsText);
  const errors = validationErrors(manifest, payload, source);
  if (errors.length) throw new Error(`CNIPA historical case batch verification failed:\n- ${errors.join("\n- ")}`);
  return { source, manifest, payload };
}

function expectFailure(label, manifest, payload, source) {
  if (!validationErrors(manifest, payload, source).length) throw new Error(`self-test did not reject ${label}`);
}

async function runSelfTest(verified) {
  const copy = (value) => structuredClone(value);
  const tampered = copy(verified.payload); tampered.records[0].title += " tampered";
  expectFailure("tampered case", verified.manifest, tampered, verified.source);
  const duplicate = copy(verified.payload); duplicate.records[1].stableSourceId = duplicate.records[0].stableSourceId;
  expectFailure("duplicate id", verified.manifest, duplicate, verified.source);
  const workflow = copy(verified.payload); workflow.records[0].businessWorkflowInputAllowed = true;
  expectFailure("workflow input", verified.manifest, workflow, verified.source);
  const status = copy(verified.payload); status.records[0].sourceStatusLabel = "VALID";
  expectFailure("invented current status", verified.manifest, status, verified.source);
  console.log("CNIPA historical case self-test passed: tamper, duplicate, workflow and status guards");
}

if (!verifyOnly) await build();
const verified = await verify();
if (selfTest) await runSelfTest(verified);
console.log(`CNIPA public historical case batch verified: ${verified.payload.records.length} isolated records`);
