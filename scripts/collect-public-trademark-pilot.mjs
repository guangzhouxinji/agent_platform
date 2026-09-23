import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDir = join(projectRoot, "data", "trademarks", "public-pilot-v1");
const rawDir = join(outputDir, "raw");
const recordsPath = join(outputDir, "records.json");
const manifestPath = join(outputDir, "manifest.json");
const verifyOnly = process.argv.includes("--verify-only");
const csvUrl = "https://raw.githubusercontent.com/APVentureEngine/trademark-watch/main/data/latest.csv";
const sourceManifestUrl = "https://raw.githubusercontent.com/APVentureEngine/trademark-watch/main/data/manifest.json";
const sourceCommit = "85b6fa5e2d7fa666c4ba746f519020eb14a60835";
const selectedClasses = new Set(["9", "35", "42", "45"]);

const boundaries = {
  dataClass: "PUBLIC_PILOT",
  centerAuthoritative: false,
  expertLabeled: false,
  externalModelUseAllowed: false,
  formalDesignEvidence: false,
  formalVerificationEvidence: false,
  businessAcceptanceEvidence: false,
};

const sha256 = (value) => createHash("sha256").update(value).digest("hex");

function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        cell += '"';
        index += 1;
      } else if (char === '"') {
        quoted = false;
      } else {
        cell += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ",") {
      row.push(cell);
      cell = "";
    } else if (char === "\n") {
      row.push(cell.replace(/\r$/, ""));
      if (row.some((value) => value !== "")) rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }
  if (cell || row.length) {
    row.push(cell.replace(/\r$/, ""));
    rows.push(row);
  }
  const [headers, ...values] = rows;
  return values.map((fields) => Object.fromEntries(headers.map((header, index) => [header, fields[index] ?? ""])));
}

async function fetchText(url) {
  const response = await fetch(url, {
    signal: AbortSignal.timeout(60_000),
    headers: { Accept: "text/csv,application/json;q=0.9,*/*;q=0.8", "User-Agent": "agent-platform-public-pilot/1.0" },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${response.status} ${response.statusText} for ${url}`);
  return text;
}

function normalizeRecord(row, capturedAt, sourceIssue) {
  const classes = row.classes.split(/\s+/).filter(Boolean);
  return {
    sourceId: "USPTO_TMOG_DERIVED_SNAPSHOT",
    sourceRecordId: row.serial,
    sourceVersion: `${sourceIssue}:${row.transaction_date}:${row.event}`,
    serialNumber: row.serial,
    registrationNumber: null,
    name: row.mark,
    imageRef: null,
    applicants: row.owner ? [row.owner] : [],
    applicantAddress: null,
    classes: classes.map((code) => ({ code, name: null, goodsServices: [], similarGroups: [] })),
    filingDate: row.filing_date || null,
    registrationDate: null,
    validUntil: null,
    gazetteEvent: row.event || null,
    sourceStatusCode: row.status || null,
    publicationDate: row.pub_date || null,
    transactionDate: row.transaction_date || null,
    sourceUpdatedAt: row.transaction_date || null,
    capturedAt,
    sourceRef: `${csvUrl}#serial=${row.serial}`,
    officialStatusRef: `https://tsdr.uspto.gov/#caseNumber=${row.serial}&caseSearchType=US_APPLICATION&caseType=DEFAULT&searchType=statusSearch`,
    dataMode: "PUBLIC_PILOT",
    missingFields: ["registrationNumber", "imageRef", "applicantAddress", "classNames", "goodsServices", "similarGroups", "registrationDate", "validUntil", "authoritativeCurrentLegalStatus"],
    boundaries: { ...boundaries },
  };
}

async function collect() {
  await mkdir(rawDir, { recursive: true });
  const capturedAt = new Date().toISOString();
  const [csvText, sourceManifestText] = await Promise.all([fetchText(csvUrl), fetchText(sourceManifestUrl)]);
  const sourceManifest = JSON.parse(sourceManifestText);
  const rows = parseCsv(csvText);
  const chosen = rows
    .filter((row) => row.serial && row.mark && row.classes.split(/\s+/).some((code) => selectedClasses.has(code)))
    .filter((row, index, all) => all.findIndex((candidate) => candidate.serial === row.serial) === index)
    .slice(0, 100);
  if (chosen.length !== 100) throw new Error(`Expected 100 qualifying trademark rows, found ${chosen.length}`);

  const rawCsvFile = "raw/latest.csv";
  const rawManifestFile = "raw/source-manifest.json";
  await writeFile(join(outputDir, rawCsvFile), csvText, "utf8");
  await writeFile(join(outputDir, rawManifestFile), `${JSON.stringify(sourceManifest, null, 2)}\n`, "utf8");

  const recordsDocument = {
    schemaVersion: "1.0",
    datasetId: "CON-01-TRADEMARK-PUBLIC-PILOT-V1",
    generatedAt: capturedAt,
    records: chosen.map((row) => normalizeRecord(row, capturedAt, sourceManifest.latest_issue)),
  };
  const recordsText = `${JSON.stringify(recordsDocument, null, 2)}\n`;
  await writeFile(recordsPath, recordsText, "utf8");

  const manifest = {
    schemaVersion: "1.0",
    datasetId: recordsDocument.datasetId,
    generatedAt: capturedAt,
    recordCount: recordsDocument.records.length,
    recordsSha256: sha256(recordsText),
    source: {
      name: "USPTO Trademark Official Gazette word marks via trademark-watch snapshot",
      upstreamAuthority: "United States Patent and Trademark Office",
      upstreamDataset: sourceManifest.dataset,
      upstreamIssue: sourceManifest.latest_issue,
      csvUrl,
      sourceManifestUrl,
      repositoryUrl: "https://github.com/APVentureEngine/trademark-watch",
      snapshotCommit: sourceCommit,
      snapshotCommitUrl: `https://github.com/APVentureEngine/trademark-watch/commit/${sourceCommit}`,
      accessMode: "PUBLIC_STATIC_DOWNLOAD",
      authorityStatus: "NON_CENTER_PUBLIC_DERIVED_SOURCE",
      declaredDatasetLicense: sourceManifest.license,
      repositoryLicenseStatus: "NOT_DECLARED",
      licenseReviewStatus: "REVIEW_REQUIRED",
      usageDecision: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
    },
    selection: { count: 100, niceClasses: [...selectedClasses], rule: "First 100 unique serials containing Nice class 9, 35, 42, or 45 in source order" },
    boundaries,
    rawResponses: [
      { file: rawCsvFile, url: csvUrl, sha256: sha256(csvText), bytes: Buffer.byteLength(csvText) },
      { file: rawManifestFile, url: sourceManifestUrl, sha256: sha256(`${JSON.stringify(sourceManifest, null, 2)}\n`) },
    ],
    fieldCoverage: {
      available: ["serialNumber", "name", "applicants", "classes.code", "filingDate", "gazetteEvent", "sourceStatusCode", "publicationDate", "transactionDate"],
      unavailable: ["registrationNumber", "imageRef", "applicantAddress", "classNames", "goodsServices", "similarGroups", "registrationDate", "validUntil", "authoritativeCurrentLegalStatus"],
    },
    limitations: [
      "The snapshot contains USPTO Gazette word marks, not China National Intellectual Property Administration records.",
      "The source repository declares its dataset CC0, but the repository itself has no detected license; reuse beyond this internal pilot requires review.",
      "Gazette event and status code are preserved as source fields and are not interpreted as a current authoritative legal status.",
      "Live status must be checked in USPTO TSDR using officialStatusRef.",
    ],
  };
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

async function verify() {
  const [recordsText, manifestText] = await Promise.all([readFile(recordsPath, "utf8"), readFile(manifestPath, "utf8")]);
  const payload = JSON.parse(recordsText);
  const manifest = JSON.parse(manifestText);
  const records = payload.records ?? [];
  const errors = [];
  if (records.length !== 100 || manifest.recordCount !== 100) errors.push(`expected exactly 100 records, found ${records.length}`);
  if (manifest.recordsSha256 !== sha256(recordsText)) errors.push("recordsSha256 mismatch");
  if (manifest.boundaries?.dataClass !== "PUBLIC_PILOT") errors.push("manifest dataClass must be PUBLIC_PILOT");
  const seen = new Set();
  for (const [index, record] of records.entries()) {
    if (!record.sourceRecordId || !record.name || !record.sourceRef || !record.capturedAt) errors.push(`record ${index} missing required source fields`);
    if (record.dataMode !== "PUBLIC_PILOT" || record.boundaries?.centerAuthoritative !== false) errors.push(`record ${index} violates PUBLIC_PILOT boundary`);
    if (seen.has(record.sourceRecordId)) errors.push(`duplicate sourceRecordId ${record.sourceRecordId}`);
    seen.add(record.sourceRecordId);
  }
  if (manifest.source?.licenseReviewStatus !== "REVIEW_REQUIRED") errors.push("license review must remain REVIEW_REQUIRED");
  for (const raw of manifest.rawResponses ?? []) {
    try {
      const content = await readFile(join(outputDir, raw.file), "utf8");
      if (sha256(content) !== raw.sha256) errors.push(`raw file hash mismatch: ${raw.file}`);
    } catch {
      errors.push(`raw file missing: ${raw.file}`);
    }
  }
  if (errors.length) throw new Error(`Trademark pilot verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`Trademark public pilot verified: ${records.length} unique records`);
}

if (!verifyOnly) await collect();
await verify();
