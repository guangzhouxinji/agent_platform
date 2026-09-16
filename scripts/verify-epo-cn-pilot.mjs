// 校验 EPO CN pilot 数据集的 schema 和完整性
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const datasetDir = join(projectRoot, "data", "patents", "epo-cn-pilot-v1");
const recordsPath = join(datasetDir, "records.json");
const manifestPath = join(datasetDir, "manifest.json");

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const errors = [];

// 1. 读 manifest
const manifestText = await readFile(manifestPath, "utf8");
const manifest = JSON.parse(manifestText);

// 2. 读 records
const recordsText = await readFile(recordsPath, "utf8");
const recordsPayload = JSON.parse(recordsText);
const records = recordsPayload.records ?? [];

// 3. 校验 manifest 字段
if (manifest.datasetId !== "CON-04-EPO-CN-PILOT-V1") errors.push("unexpected datasetId");
if (manifest.source?.accessMode !== "PUBLIC_LINKED_DATA_NO_AUTH") errors.push("unexpected accessMode");
if (manifest.source?.authorityStatus !== "NON_CENTER_PUBLIC_SOURCE") errors.push("unexpected authorityStatus");
if (manifest.source?.usageDecision !== "INTERNAL_DEVELOPMENT_PILOT_ONLY") errors.push("unexpected usageDecision");
if (manifest.boundaries?.centerAuthoritative !== false) errors.push("must not be center authoritative");
if (manifest.boundaries?.expertLabeled !== false) errors.push("must not be expert labeled");
if (manifest.boundaries?.formalDesignEvidence !== false) errors.push("cannot be formal design evidence");
if (manifest.boundaries?.formalVerificationEvidence !== false) errors.push("cannot be formal verification evidence");
if (manifest.boundaries?.businessAcceptanceEvidence !== false) errors.push("cannot be business acceptance evidence");

// 4. 校验 recordsSha256
if (manifest.recordsSha256 !== sha256(recordsText)) errors.push("recordsSha256 mismatch");

// 5. 校验记录数
if (manifest.recordCount !== records.length) errors.push(`recordCount mismatch: manifest=${manifest.recordCount}, actual=${records.length}`);

// 6. 校验每条记录
const requiredFields = ["sourceRecordId", "publicationNumber", "publicationDate", "sourceVersion", "title", "matchedQueryIds", "capturedAt", "boundaries"];
const seen = new Set();
for (const [index, record] of records.entries()) {
  const prefix = `records[${index}]`;
  for (const field of requiredFields) {
    if (!(field in record)) errors.push(`${prefix} missing field: ${field}`);
  }
  if (record.sourceRecordId && !record.sourceRecordId.startsWith("CN-")) {
    errors.push(`${prefix} sourceRecordId must start with CN-: ${record.sourceRecordId}`);
  }
  if (record.boundaries?.dataClass !== "PUBLIC_PILOT") errors.push(`${prefix} wrong dataClass`);
  if (record.boundaries?.centerAuthoritative !== false) errors.push(`${prefix} must not be center authoritative`);
  if (seen.has(record.sourceRecordId)) errors.push(`${prefix} duplicate sourceRecordId: ${record.sourceRecordId}`);
  seen.add(record.sourceRecordId);
}

// 7. 校验字段覆盖说明
if (!manifest.fieldCoverage?.available?.length) errors.push("fieldCoverage.available missing");
if (!manifest.fieldCoverage?.unavailable?.includes("title")) errors.push("fieldCoverage must declare title unavailable");
if (!manifest.fieldCoverage?.unavailable?.includes("applicant")) errors.push("fieldCoverage must declare applicant unavailable");
if (!manifest.fieldCoverage?.unavailable?.includes("cpcClassifications")) errors.push("fieldCoverage must declare cpcClassifications unavailable");
if (!manifest.limitations?.some((l) => l.includes("OPS API"))) errors.push("limitations must mention OPS API requirement");

// 8. 校验 raw 文件存在
const rawFiles = manifest.rawResponses ?? [];
if (rawFiles.length < 3) errors.push(`expected >=3 raw responses, found ${rawFiles.length}`);

if (errors.length) {
  console.error("EPO CN pilot verification FAILED:");
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}

// 摘要
const withFilingDate = records.filter((r) => r.filingDate).length;
const withAppNumber = records.filter((r) => r.applicationNumber).length;
const withDetail = records.filter((r) => r.detailResponseSha256).length;
const dateRange = records.reduce(
  (acc, r) => {
    if (r.publicationDate) {
      if (!acc.min || r.publicationDate < acc.min) acc.min = r.publicationDate;
      if (!acc.max || r.publicationDate > acc.max) acc.max = r.publicationDate;
    }
    return acc;
  },
  { min: null, max: null }
);

console.log(`EPO CN pilot verified: ${records.length} records`);
console.log(`  Publication date range: ${dateRange.min} ~ ${dateRange.max}`);
console.log(`  With filingDate: ${withFilingDate}/${records.length}`);
console.log(`  With applicationNumber: ${withAppNumber}/${records.length}`);
console.log(`  With detail response: ${withDetail}/${records.length}`);
console.log(`  Raw files: ${rawFiles.length}`);
console.log(`  Records SHA256: ${manifest.recordsSha256.slice(0, 16)}...`);
