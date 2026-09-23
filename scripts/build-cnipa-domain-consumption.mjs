import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const mappingDir = join(root, "data", "shared", "domain-mapping", "cnipa-decision-corpus-v1");
const outputDir = join(root, "data", "shared", "domain-consumption", "cnipa-decision-corpus-v1");
const generate = process.argv.includes("--generate");
const selfTest = process.argv.includes("--self-test");
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const json = (value) => `${JSON.stringify(value, null, 2)}\n`;
const stable = (value) => JSON.stringify(value);
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const retention = "UNTIL_DEVELOPMENT_PILOT_END";
const attribution = "国家知识产权局公开页面；仅限内部开发试点；https://www.cnipa.gov.cn";

async function inputs() {
  const texts = {};
  for (const file of ["manifest.json", "enforcement-records.json", "adjudication-records.json", "consultation-records.json"]) {
    texts[file] = await readFile(join(mappingDir, file), "utf8");
  }
  return {
    texts,
    manifest: JSON.parse(texts["manifest.json"]),
    enforcement: JSON.parse(texts["enforcement-records.json"]),
    adjudication: JSON.parse(texts["adjudication-records.json"]),
    consultation: JSON.parse(texts["consultation-records.json"]),
  };
}

function sourceTrace(record, mapping, recordsSha256) {
  return {
    sourceDatasetId: mapping.mappingId,
    sourceRecordsSha256: recordsSha256,
    sourceRecordSha256: sha256(stable(record)),
    sourceSnapshotRefs: [record.sourceSnapshotId],
    sourceSnapshots: [{ ref: record.sourceSnapshotId, rawResponseSha256: record.sourceSha256, sourceFile: record.sourceFile }],
  };
}

function common(record, input, recordsSha256) {
  return {
    sourceSnapshotId: record.domainProjectionId,
    sourceSystem: record.recordKind === "OFFICIAL_REPLY" ? "CNIPA_OFFICIAL_REPLIES_2026" : "CNIPA_DECISION_PAGES_2026",
    stableSourceId: record.sourceSnapshotId,
    sourceVersion: record.sourceSha256,
    sourceLocator: record.sourceLocator.locator,
    title: record.sourceTitle,
    publicationDate: record.sourcePublishedAt,
    sourceUrl: record.sourceUrl,
    capturedAt: input.manifest.generatedAt,
    availability: "AVAILABLE",
    dataMode: "PUBLIC_PILOT",
    authorityMarker: "NOT_CENTER_SOURCE",
    professionalUse: "NOT_PROFESSIONAL_EVIDENCE",
    externalModelAllowed: false,
    importDecision: "APPROVED_FOR_INTERNAL_DEVELOPMENT",
    approvalId: input.manifest.sourceWorksetId,
    approvalSha256: input.manifest.sourceWorksetSha256,
    retentionPeriod: retention,
    attribution,
    sourceTrace: sourceTrace(record, input.manifest, recordsSha256),
  };
}

function enforcementRecord(record, input, recordsSha256) {
  const decision = record.recordKind === "HISTORICAL_ADJUDICATION_DECISION";
  const base = {
    ...common(record, input, recordsSha256),
    category: decision ? "PUBLIC_HISTORICAL_CASE_SAMPLE" : "PUBLIC_LEGAL_REFERENCE",
    sourceStatusLabel: null,
    missingFields: decision
      ? ["DECISION_PDF_BODY", "CURRENT_CASE_STATUS", "UNDERLYING_CASE_EVIDENCE", "CURRENT_RIGHT_STATUS", "PROFESSIONAL_ACCEPTANCE"]
      : ["PROFESSIONAL_EFFECTIVITY_REVIEW", "EXECUTABLE_RULE", "CURRENT_CASE_APPLICABILITY"],
    usageLimitation: decision
      ? "历史页面参考；PDF 正文未采集；非当前案件、非证据、非执法工作流输入。"
      : "官方批复来源展示/流程测试；专业效力待复核；不得生成或执行规则。",
    businessCategory: decision ? "ADMINISTRATIVE_ADJUDICATION_DECISION_PAGE" : null,
    sourcePageStart: null,
    sourcePageEnd: null,
    historicalCaseSummary: decision ? `国家知识产权局公开历史裁决页面：${record.sourceTitle}；PDF 正文未采集。` : null,
    titleExtractionNote: "标题来自已验证 CNIPA 页面快照。",
    businessWorkflowInputAllowed: false,
  };
  return { ...base, sourcePayloadSha256: sha256(stable(base)) };
}

function adjudicationRecord(record, input, recordsSha256) {
  const decision = record.recordKind === "HISTORICAL_ADJUDICATION_DECISION";
  const base = {
    ...common(record, input, recordsSha256),
    category: decision ? "PUBLIC_JUDGMENT_REFERENCE" : "PUBLIC_LEGAL_REFERENCE",
    institution: "国家知识产权局",
    documentType: decision ? "行政裁决决定页面（PDF 正文未采集）" : "官方批复（专业效力待复核）",
    missingFields: decision
      ? ["DECISION_PDF_BODY", "PARTIES", "FACTS", "REASONING", "OUTCOME", "PROFESSIONAL_ACCEPTANCE"]
      : ["PROFESSIONAL_EFFECTIVITY_REVIEW", "EXECUTABLE_RULE", "CURRENT_CASE_APPLICABILITY"],
    usageLimitation: decision
      ? "仅展示来源；PDF 正文未采集，不得解析字段或生成草稿。"
      : "仅作来源展示/流程测试；不得生成规则、计算期限或改变案件状态。",
  };
  return { ...base, sourcePayloadSha256: sha256(stable(base)) };
}

function create(input) {
  const enforcementSourceSha = sha256(input.texts["enforcement-records.json"]);
  const adjudicationSourceSha = sha256(input.texts["adjudication-records.json"]);
  const enforcement = input.enforcement.records.map((record) => enforcementRecord(record, input, enforcementSourceSha))
    .sort((a, b) => `${a.stableSourceId}\0${a.sourceVersion}`.localeCompare(`${b.stableSourceId}\0${b.sourceVersion}`, "en"));
  const adjudication = input.adjudication.records.map((record) => adjudicationRecord(record, input, adjudicationSourceSha))
    .sort((a, b) => `${a.stableSourceId}\0${a.sourceVersion}`.localeCompare(`${b.stableSourceId}\0${b.sourceVersion}`, "en"));
  const payloads = {
    "enforcement-records.json": json({ schemaVersion: "cnipa-domain-consumption-v1", domain: "ENFORCEMENT", records: enforcement }),
    "adjudication-records.json": json({ schemaVersion: "cnipa-domain-consumption-v1", domain: "ADJUDICATION", records: adjudication }),
  };
  const manifest = {
    schemaVersion: "cnipa-domain-consumption-manifest-v1",
    datasetId: "CNIPA-DECISION-CORPUS-DOMAIN-CONSUMPTION-V1",
    generatedAt: input.manifest.generatedAt,
    sourceMappingId: input.manifest.mappingId,
    sourceMappingManifestSha256: sha256(input.texts["manifest.json"]),
    approvalId: input.manifest.sourceWorksetId,
    approvalSha256: input.manifest.sourceWorksetSha256,
    boundaries: {
      dm8Tables: ["enf_public_source_registration", "enf_public_reference_batch", "enf_public_reference_snapshot",
        "adj_public_source_registration", "adj_public_reference_batch", "adj_public_reference_snapshot"],
      businessWorkflowInputAllowed: false,
      externalModelAllowed: false,
      executableRuleAllowed: false,
      documentParsingAllowed: false,
      draftGenerationAllowed: false,
    },
    counts: { enforcement: enforcement.length, adjudication: adjudication.length, consultation: 0 },
    files: Object.entries(payloads).map(([file, text]) => ({ file, sha256: sha256(text) })),
  };
  return { manifest, payloads };
}

function validationErrors(output, input) {
  const errors = [];
  const expected = create(input);
  if (input.manifest.boundaries?.directDm8WriteAllowed !== false || input.manifest.boundaries?.externalModelAllowed !== false) {
    errors.push("mapping boundary changed");
  }
  if ((input.consultation.records ?? []).length) errors.push("consultation mapping must remain empty");
  if (!same(output.manifest, expected.manifest)) errors.push("consumption manifest mismatch");
  for (const [file, text] of Object.entries(expected.payloads)) if (output.payloads[file] !== text) errors.push(`${file}: content mismatch`);
  for (const [file, text] of Object.entries(output.payloads)) {
    const records = JSON.parse(text).records ?? [];
    if (records.length !== 12) errors.push(`${file}: expected 12 records`);
    for (const record of records) {
      if (record.externalModelAllowed !== false || record.importDecision !== "APPROVED_FOR_INTERNAL_DEVELOPMENT"
          || record.professionalUse !== "NOT_PROFESSIONAL_EVIDENCE" || !record.sourceTrace?.sourceSnapshots?.length
          || !record.sourcePayloadSha256 || !record.approvalId || !record.approvalSha256) errors.push(`${record.stableSourceId}: trace boundary incomplete`);
      if (file.startsWith("enforcement") && record.businessWorkflowInputAllowed !== false) errors.push(`${record.stableSourceId}: workflow input enabled`);
      if (record.documentType?.includes("PDF 正文未采集") && !record.missingFields.includes("DECISION_PDF_BODY")) errors.push(`${record.stableSourceId}: missing body guard absent`);
      const { sourcePayloadSha256, ...base } = record;
      if (sourcePayloadSha256 !== sha256(stable(base))) errors.push(`${record.stableSourceId}: payload digest mismatch`);
    }
  }
  return errors;
}

async function readOutputs() {
  const manifest = JSON.parse(await readFile(join(outputDir, "manifest.json"), "utf8"));
  const payloads = {};
  for (const file of ["enforcement-records.json", "adjudication-records.json"]) payloads[file] = await readFile(join(outputDir, file), "utf8");
  return { manifest, payloads };
}

const input = await inputs();
const built = create(input);
if (generate) {
  await mkdir(outputDir, { recursive: true });
  await Promise.all([
    writeFile(join(outputDir, "manifest.json"), json(built.manifest), "utf8"),
    ...Object.entries(built.payloads).map(([file, text]) => writeFile(join(outputDir, file), text, "utf8")),
  ]);
}
const output = await readOutputs();
const errors = validationErrors(output, input);
if (errors.length) throw new Error(`CNIPA domain consumption verification failed:\n- ${errors.join("\n- ")}`);
if (selfTest) {
  for (const mutate of [
    (value) => { value.payloads["enforcement-records.json"] = value.payloads["enforcement-records.json"].replace("false", "true"); },
    (value) => { value.payloads["adjudication-records.json"] = value.payloads["adjudication-records.json"].replace("DECISION_PDF_BODY", "BODY_AVAILABLE"); },
    (value) => { const data = JSON.parse(value.payloads["enforcement-records.json"]); data.records[0].sourcePayloadSha256 = "0".repeat(64); value.payloads["enforcement-records.json"] = json(data); },
  ]) {
    const changed = structuredClone(output); mutate(changed);
    if (!validationErrors(changed, input).length) throw new Error("CNIPA domain consumption self-test failed");
  }
  console.log("CNIPA domain consumption self-test passed: workflow, body and digest guards");
}
console.log(`CNIPA domain consumption verified: ${built.manifest.counts.enforcement} enforcement, ${built.manifest.counts.adjudication} adjudication records`);
