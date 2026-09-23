import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const sourceDir = join(root, "data", "enforcement", "source-review", "cnipa-official-v1");
const outputDir = join(root, "data", "enforcement", "authorized", "cnipa-official-v1");
const dataFiles = ["clues.json", "evidence.json", "rules.json", "right-statuses.json"];
const expectedBatchSha256 = "859e0304542fd0ee729c1a0fc442ffc911846207f1fb7536ddad0e26b010cf35";
const expectedCatalogSha256 = "6e0abc08e38c8f3cd3c981455e9872dff5c7aea4ecf38bb69748c35180fd8145";
const expectedCatalogVersion = "2026-09-20.2";
const approvedCaptureTime = "2026-09-20T01:57:59.337Z";
const expectedAllowedUse = "INTERNAL_DEVELOPMENT_ENFORCEMENT_ASSISTANCE_UNTIL_PILOT_END";
const buildMode = process.argv.includes("--build");
const verifyOnly = process.argv.includes("--verify-only");
const selfTest = process.argv.includes("--self-test");
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const jsonBytes = (value) => Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");

function requireValue(condition, code) {
  if (!condition) throw new Error(code);
}

function source(id, version, location) {
  return { sourceSystem: "CNIPA_OFFICIAL_PUBLIC", sourceId: id, version, location };
}

function validateInputs(manifest, catalog, decision) {
  requireValue(manifest.reviewId === decision.sourceReviewId, "SOURCE_REVIEW_ID_MISMATCH");
  requireValue(manifest.catalogId === decision.sourceCatalogId, "SOURCE_CATALOG_ID_MISMATCH");
  requireValue(decision.sourceCatalogVersion === expectedCatalogVersion, "SOURCE_CATALOG_VERSION_MISMATCH");
  requireValue(decision.sourceBatchSha256 === expectedBatchSha256, "SOURCE_BATCH_DIGEST_MISMATCH");
  requireValue(decision.sourceCatalogSha256 === expectedCatalogSha256, "SOURCE_CATALOG_DIGEST_MISMATCH");
  requireValue(catalog.catalogId === decision.sourceCatalogId
    && catalog.catalogVersion === manifest.catalogVersion, "SOURCE_CATALOG_BINDING_MISMATCH");
  requireValue(decision.retentionPolicy === "UNTIL_DEVELOPMENT_PILOT_END", "RETENTION_POLICY_INVALID");
  requireValue(decision.allowedUse === expectedAllowedUse, "ALLOWED_USE_INVALID");
  requireValue(decision.externalModelAllowed === false
    && decision.automaticEvidenceAcceptanceAllowed === false
    && decision.automaticLegalDecisionAllowed === false, "AUTOMATION_BOUNDARY_INVALID");
  const snapshots = new Map(manifest.snapshots.map((item) => [item.id, item]));
  requireValue(snapshots.size === manifest.snapshots.length, "SOURCE_SNAPSHOT_DUPLICATE");
  const currentBatchSha256 = sha256(manifest.snapshots
    .map((snapshot) => `${snapshot.id}:${snapshot.sha256}`).sort().join("\n"));
  requireValue(manifest.batchSha256 === currentBatchSha256, "SOURCE_BATCH_DIGEST_MISMATCH");
  const decisions = new Map();
  const approvedSnapshots = new Map();
  for (const record of decision.records) {
    requireValue(!decisions.has(record.snapshotId), "REVIEW_RECORD_DUPLICATE");
    const snapshot = snapshots.get(record.snapshotId);
    requireValue(snapshot, "REVIEW_SOURCE_MISSING");
    requireValue(record.sourceSha256 === snapshot.sha256, "REVIEW_SOURCE_DIGEST_MISMATCH");
    requireValue(["APPROVED_FOR_INTERNAL_DEVELOPMENT_PACKAGE", "EXCLUDED_FROM_FIRST_PACKAGE",
      "HISTORICAL_REFERENCE_ONLY"].includes(record.conversionDecision), "REVIEW_DECISION_INVALID");
    decisions.set(record.snapshotId, record);
    approvedSnapshots.set(record.snapshotId, snapshot);
  }
  for (const required of ["CNIPA_GUIDANCE_CASE_1_EXPLANATION", "CNIPA_2026_ADJUDICATION_DEADLINE_REPLY"]) {
    requireValue(decisions.get(required)?.conversionDecision === "APPROVED_FOR_INTERNAL_DEVELOPMENT_PACKAGE",
      "REQUIRED_RECORD_NOT_APPROVED");
  }
  const caseDecision = decisions.get("CNIPA_GUIDANCE_CASE_1_EXPLANATION");
  requireValue(JSON.stringify(caseDecision.allowedMappings) === JSON.stringify([
    "HISTORICAL_PUBLIC_SAMPLE_CLUE", "PUBLIC_OFFICIAL_DOCUMENT_MATERIAL"]), "CASE_MAPPING_SCOPE_INVALID");
  requireValue(["CURRENT_VIOLATION_CLUE", "UNDERLYING_CASE_EVIDENCE", "ACCEPTED_EVIDENCE", "CURRENT_RIGHT_STATUS"]
    .every((claim) => caseDecision.prohibitedClaims?.includes(claim)), "CASE_PROHIBITED_CLAIMS_INCOMPLETE");
  const replyDecision = decisions.get("CNIPA_2026_ADJUDICATION_DEADLINE_REPLY");
  requireValue(JSON.stringify(replyDecision.allowedMappings) === JSON.stringify(["NARROW_OFFICIAL_INTERPRETIVE_REPLY"])
    && replyDecision.effectivityStatus === "NO_EXPLICIT_EFFECTIVE_DATE", "RULE_MAPPING_SCOPE_INVALID");
  requireValue(decisions.get("CNIPA_MAJOR_PATENT_DISPUTE_ADJUDICATION_MEASURES")?.conversionDecision
    === "HISTORICAL_REFERENCE_ONLY", "HISTORICAL_RULE_NOT_ISOLATED");
  return { snapshots: approvedSnapshots, decisions };
}

async function loadInputs(directory = sourceDir) {
  const manifestBytes = await readFile(join(directory, "manifest.json"));
  const catalogBytes = await readFile(join(directory, "source-catalog.json"));
  const decisionBytes = await readFile(join(directory, "conversion-decision.json"));
  const manifest = JSON.parse(manifestBytes);
  const catalog = JSON.parse(catalogBytes);
  const decision = JSON.parse(decisionBytes);
  const { snapshots, decisions } = validateInputs(manifest, catalog, decision);
  requireValue(sha256(catalogBytes) === manifest.catalogSha256, "CATALOG_FILE_DIGEST_MISMATCH");
  for (const snapshot of snapshots.values()) {
    const path = resolve(directory, snapshot.file);
    requireValue(path.startsWith(`${resolve(directory, "raw")}${sep}`), "SOURCE_PATH_UNCONTROLLED");
    const bytes = await readFile(path);
    requireValue(sha256(bytes) === snapshot.sha256, `SOURCE_FILE_DIGEST_MISMATCH:${snapshot.id}`);
    const recordDecision = decisions.get(snapshot.id);
    if (recordDecision.requiredSourceText) {
      const text = bytes.toString("utf8");
      for (const requiredText of recordDecision.requiredSourceText) {
        requireValue(text.includes(requiredText), `SOURCE_TEXT_MISSING:${snapshot.id}`);
      }
    }
  }
  return { manifest, decision, decisionSha256: sha256(decisionBytes), snapshots };
}

function buildRecords(inputs) {
  const caseSnapshot = inputs.snapshots.get("CNIPA_GUIDANCE_CASE_1_EXPLANATION");
  const replySnapshot = inputs.snapshots.get("CNIPA_2026_ADJUDICATION_DEADLINE_REPLY");
  const caseLocation = `${caseSnapshot.finalUrl}#ContentStart`;
  const clueId = "CNIPA-HISTORICAL-GUIDANCE-CASE-1";
  const clueVersion = `2021-04-01@${caseSnapshot.sha256}`;
  const clueSource = source(clueId, clueVersion, caseLocation);
  const materialSource = source("CNIPA-PUBLIC-DOC-GUIDANCE-CASE-1",
    `2021-04-01@${caseSnapshot.sha256}`, caseLocation);
  const clues = [{
    source: clueSource,
    subjects: [{ roleAsStated: "历史案件当事人（官方公开文档所述）", name: "上海章元信息技术有限公司",
      creditCode: null, source: clueSource }],
    facts: [{ value: "国家知识产权局公开说明记载：办案机关在该历史案件中认定相关行为侵犯注册商标专用权并作出行政处罚。",
      normalizedValue: "HISTORICAL_OFFICIAL_CASE_DESCRIPTION", timeKind: null, source: clueSource }],
    times: [{ value: "2019年11月15日", normalizedValue: "2019-11-15",
      timeKind: "CASE_COMPLETED", source: clueSource }],
    places: [{ value: "上海市崇明区", normalizedValue: "上海市崇明区", timeKind: null, source: clueSource }],
    evidenceMentions: [{ value: "国家知识产权局公开的指导案例1号理解与适用页面",
      normalizedValue: "PUBLIC_OFFICIAL_DOCUMENT", timeKind: null, source: materialSource }],
    missingItems: ["CURRENT_CASE_STATUS", "ORIGINAL_CASE_FILE", "UNDERLYING_CASE_EVIDENCE",
      "CURRENT_RIGHT_STATUS", "PENALTY_DECISION_FULL_TEXT"],
    conflicts: []
  }];
  const evidence = [{
    source: materialSource,
    controlledResourceRef: `controlled://cnipa-official-v1/${caseSnapshot.id}/${caseSnapshot.sha256}`,
    relatedClueId: clueId,
    collector: "CNIPA_OFFICIAL_SOURCE_COLLECTOR",
    collectionMissingReason: null,
    occurrenceTime: "2021-04-01T16:33:00+08:00",
    collectionTime: approvedCaptureTime,
    uploadTime: approvedCaptureTime,
    availability: "AVAILABLE",
    issue: "PUBLIC_OFFICIAL_DOCUMENT_NOT_UNDERLYING_CASE_EVIDENCE",
    relationType: "MISSING",
    relationReason: "公开官方说明仅支持历史样例上下文，不是案件原始证据，也未被自动采信。",
    originalRef: null,
    derivedKind: "OFFICIAL_PUBLIC_EXPLANATORY_DOCUMENT"
  }];
  const ruleContent = "《专利纠纷行政裁决和调解办法》第三十七条第二款规定的案件处理期限计算中，追加被请求人不属于不计入期限的所列情形，对其送达及等待答辩的时间应计入案件处理期限；新追加的被请求人申请延长举证期限或者请求和解时，申请延长的举证期限以及和解期间不计入案件处理期限。";
  const rules = [{
    source: source("CNIPA-2026-REPLY-ADDED-PARTY-DEADLINE", `2026-07-27@${replySnapshot.sha256}`,
      `${replySnapshot.finalUrl}#ContentStart`),
    status: "PUBLISHED",
    approvedBy: inputs.decision.authorizedBy,
    approvedAt: inputs.decision.decidedAt,
    effectiveFrom: null,
    effectivityStatus: "NO_EXPLICIT_EFFECTIVE_DATE",
    effectivityBasis: "国家知识产权局批复原文记载落款日期为2026年7月27日、页面发布日期为2026年8月18日，但未明示生效日期。",
    applicability: "仅适用于专利侵权纠纷行政裁决案件追加被请求人后的办案期限计算及批复明确列出的延长举证期限、和解期间情形。",
    ruleType: "OFFICIAL_INTERPRETIVE_REPLY",
    content: ruleContent,
    contentSha256: sha256(Buffer.from(ruleContent, "utf8"))
  }];
  return { clues, evidence, rules, "right-statuses": [] };
}

async function packageBytes(inputs) {
  const records = buildRecords(inputs);
  const bytes = new Map();
  for (const file of dataFiles) bytes.set(file, jsonBytes(records[file.replace(".json", "")]));
  const files = Object.fromEntries(dataFiles.map((file) => [file, {
    sha256: sha256(bytes.get(file)), recordCount: JSON.parse(bytes.get(file)).length
  }]));
  const manifest = {
    schemaVersion: "enforcement-authorized-package-v1",
    batchId: "ENF-CNIPA-OFFICIAL-AUTHORIZED-V1",
    datasetId: "ENF-CNIPA-OFFICIAL-INTERNAL-DEVELOPMENT",
    datasetVersion: "2026-09-20.1",
    sourceName: "国家知识产权局公开执法指导资料",
    sourceType: "OFFICIAL_PUBLIC_DOCUMENTS",
    approvalDecisionId: inputs.decision.decisionId,
    approvedBy: inputs.decision.authorizedBy,
    approvedAt: inputs.decision.decidedAt,
    allowedUse: inputs.decision.allowedUse,
    recordScope: `1 historical public sample clue, 1 public official document material, 1 narrow official reply, 0 right statuses; reviewDecisionSha256=${inputs.decisionSha256}`,
    retentionPolicy: inputs.decision.retentionPolicy,
    retentionUntil: null,
    withdrawalContact: inputs.decision.authorizedBy,
    withdrawalProcess: inputs.decision.withdrawalProcess,
    externalModelAllowed: false,
    files
  };
  bytes.set("manifest.json", jsonBytes(manifest));
  return bytes;
}

async function writePackage(directory, bytes) {
  await mkdir(directory, { recursive: true });
  for (const [name, value] of bytes) await writeFile(join(directory, name), value);
}

async function verifyPackage(directory, expectedBytes) {
  const names = ["manifest.json", ...dataFiles];
  for (const name of names) {
    const actual = await readFile(join(directory, name));
    requireValue(actual.equals(expectedBytes.get(name)), `PACKAGE_DRIFT:${name}`);
  }
  const manifest = JSON.parse(expectedBytes.get("manifest.json"));
  requireValue(manifest.retentionPolicy === "UNTIL_DEVELOPMENT_PILOT_END"
    && manifest.retentionUntil === null, "PACKAGE_RETENTION_INVALID");
  requireValue(manifest.externalModelAllowed === false, "EXTERNAL_MODEL_NOT_ALLOWED");
  requireValue(JSON.parse(expectedBytes.get("right-statuses.json")).length === 0, "RIGHT_STATUS_MUST_BE_EMPTY");
  const rules = JSON.parse(expectedBytes.get("rules.json"));
  requireValue(rules.length === 1 && rules[0].effectiveFrom === null
    && rules[0].effectivityStatus === "NO_EXPLICIT_EFFECTIVE_DATE", "RULE_EFFECTIVITY_INVALID");
}

async function runSelfTest() {
  const { manifest, decision } = await loadInputs();
  const badBatch = structuredClone(manifest);
  badBatch.batchSha256 = "0".repeat(64);
  let rejectedBatch = false;
  try { validateInputs(badBatch, JSON.parse(await readFile(join(sourceDir, "source-catalog.json"))), decision); }
  catch (error) { rejectedBatch = error.message === "SOURCE_BATCH_DIGEST_MISMATCH"; }
  requireValue(rejectedBatch, "SELF_TEST_BATCH_TAMPER_NOT_REJECTED");
  const badDecision = structuredClone(decision);
  badDecision.records.find((item) => item.snapshotId === "CNIPA_GUIDANCE_CASE_1_EXPLANATION")
    .conversionDecision = "EXCLUDED_FROM_FIRST_PACKAGE";
  let rejectedReview = false;
  try { validateInputs(manifest, JSON.parse(await readFile(join(sourceDir, "source-catalog.json"))), badDecision); }
  catch (error) { rejectedReview = error.message === "REQUIRED_RECORD_NOT_APPROVED"; }
  requireValue(rejectedReview, "SELF_TEST_UNAPPROVED_RECORD_NOT_REJECTED");
  const badUse = structuredClone(decision);
  badUse.allowedUse = "PRODUCTION_ENFORCEMENT";
  let rejectedUse = false;
  try { validateInputs(manifest, JSON.parse(await readFile(join(sourceDir, "source-catalog.json"))), badUse); }
  catch (error) { rejectedUse = error.message === "ALLOWED_USE_INVALID"; }
  requireValue(rejectedUse, "SELF_TEST_USAGE_EXPANSION_NOT_REJECTED");
  const temporary = await mkdtemp(join(tmpdir(), "enf-cnipa-package-"));
  try {
    const inputs = await loadInputs();
    const bytes = await packageBytes(inputs);
    await writePackage(temporary, bytes);
    await verifyPackage(temporary, bytes);
    await writeFile(join(temporary, "right-statuses.json"), jsonBytes([{ invented: true }]));
    let rejectedRight = false;
    try { await verifyPackage(temporary, bytes); } catch (error) { rejectedRight = error.message.includes("PACKAGE_DRIFT"); }
    requireValue(rejectedRight, "SELF_TEST_INVENTED_RIGHT_STATUS_NOT_REJECTED");
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
  console.log("CNIPA authorized package self-test passed: batch, review and empty-right-status guards");
}

if (selfTest) {
  await runSelfTest();
} else {
  const inputs = await loadInputs();
  const bytes = await packageBytes(inputs);
  if (buildMode) await writePackage(outputDir, bytes);
  if (verifyOnly || buildMode) await verifyPackage(outputDir, bytes);
  console.log(`CNIPA authorized enforcement package ${buildMode ? "built and " : ""}verified: 1 clue, 1 material, 1 rule, 0 right statuses`);
}
