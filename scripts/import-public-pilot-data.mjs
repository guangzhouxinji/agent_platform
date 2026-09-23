import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const resourceDir = join(projectRoot, "backend", "apps", "platform-api", "src", "main", "resources", "public-pilot");
const sources = {
  patents: [
    join(projectRoot, "data", "patents", "public-pilot-v1"),
    join(projectRoot, "data", "patents", "epo-cn-pilot-v1"),
    join(projectRoot, "data", "patents", "cn-topic-public-pilot-v1"),
  ],
  trademarks: join(projectRoot, "data", "trademarks", "public-pilot-v1"),
  cases: join(projectRoot, "data", "cases", "public-pilot-v1"),
};

const sha256 = (value) => createHash("sha256").update(value).digest("hex");

async function loadVerified(directory, expectedDatasetId, expectedCount) {
  const [recordsText, manifestText] = await Promise.all([
    readFile(join(directory, "records.json"), "utf8"),
    readFile(join(directory, "manifest.json"), "utf8"),
  ]);
  const payload = JSON.parse(recordsText);
  const manifest = JSON.parse(manifestText);
  if (manifest.datasetId !== expectedDatasetId) throw new Error(`${expectedDatasetId}: unexpected datasetId ${manifest.datasetId}`);
  if (manifest.recordsSha256 !== sha256(recordsText)) throw new Error(`${expectedDatasetId}: source records hash mismatch`);
  if (manifest.recordCount !== expectedCount || payload.records?.length !== expectedCount) throw new Error(`${expectedDatasetId}: expected exactly ${expectedCount} records`);
  if (manifest.boundaries?.dataClass !== "PUBLIC_PILOT") throw new Error(`${expectedDatasetId}: expected PUBLIC_PILOT boundary`);
  return { records: payload.records, sourceSha256: manifest.recordsSha256, datasetId: manifest.datasetId, source: manifest.source };
}

function patentResource(datasets) {
  const records = datasets.flatMap((dataset) => dataset.records.map((record) => ({
    sourceId: record.sourceId ?? (dataset.datasetId.includes("EPO") ? "EPO_LINKED_OPEN_DATA" : "SURECHEMBL_PUBLIC_SEARCH"),
    sourceRecordId: record.sourceRecordId,
    sourceVersion: record.sourceVersion,
    sourceDatasetId: dataset.datasetId,
    applicationNumber: record.applicationNumber ?? record.applicationNumbers?.[0] ?? null,
    publicationNumber: record.publicationNumber,
    title: record.titleZh ?? record.title ?? record.publicationNumber,
    titles: record.titles ?? [],
    applicants: record.applicants?.length ? record.applicants : (record.applicant ? [record.applicant] : []),
    inventors: record.inventors ?? [],
    applicationDate: record.applicationDate ?? record.filingDate ?? null,
    publicationDate: record.publicationDate,
    ipc: record.ipcClassifications ?? [],
    cpc: record.cpcClassifications ?? [],
    priorityClaims: record.priorityClaims ?? [],
    citationReferences: record.citationReferences ?? [],
    familyRecordIds: record.familyRecordIds ?? [],
    sourceLegalEvents: record.sourceLegalEvents ?? [],
    sourcePdfUrl: record.sourcePdfUrl ?? null,
    sourceUrl: record.sourceRef ?? record.detailUrl,
    abstract: record.abstractExcerpt ?? null,
    abstractState: record.abstractExcerpt ? "PARTIAL" : "SOURCE_NOT_PROVIDED",
    claims: record.claimsExcerpt ?? null,
    claimsState: record.claimsExcerpt ? "PARTIAL" : "SOURCE_NOT_PROVIDED",
    topic: record.topicLabelZh ?? null,
    capturedAt: record.capturedAt,
    missingFields: record.missingFields ?? [],
    rawRecordPath: `${dataset.datasetId}/records.json`,
  })));
  const unique = new Map();
  for (const record of records) {
    const key = record.publicationNumber.replace(/[^A-Za-z0-9]/g, "").toUpperCase();
    if (!unique.has(key)) unique.set(key, record);
  }
  return [...unique.values()];
}

function trademarkResource(records) {
  return records.map((record) => ({
    sourceId: record.sourceId,
    sourceRecordId: record.sourceRecordId,
    sourceVersion: record.sourceVersion,
    serialNumber: record.serialNumber,
    registrationNumber: record.registrationNumber,
    name: record.name,
    imageRef: record.imageRef,
    applicants: record.applicants,
    applicantAddress: record.applicantAddress,
    classes: record.classes,
    filingDate: record.filingDate,
    registrationDate: record.registrationDate,
    validUntil: record.validUntil,
    gazetteEvent: record.gazetteEvent,
    sourceStatusCode: record.sourceStatusCode,
    publicationDate: record.publicationDate,
    transactionDate: record.transactionDate,
    sourceUpdatedAt: record.sourceUpdatedAt,
    capturedAt: record.capturedAt,
    sourceRef: record.sourceRef,
    officialStatusRef: record.officialStatusRef,
    missingFields: record.missingFields ?? [],
    rawRecordPath: "data/trademarks/public-pilot-v1/records.json",
  }));
}

function caseResource(records) {
  return records.map((record) => ({
    sourceId: record.sourceId,
    sourceRecordId: record.sourceRecordId,
    sourceVersion: record.sourceVersion,
    caseNumber: record.caseNumber,
    docketNumber: record.docketNumber,
    title: record.title,
    caseType: record.caseType,
    intellectualPropertyType: record.intellectualPropertyType,
    institution: record.institution,
    procedure: record.procedure,
    hearingDate: record.hearingDate,
    decisionDate: record.decisionDate,
    outcomeExcerpt: record.outcomeExcerpt,
    sourcePublishedAt: record.sourcePublishedAt,
    sourceUpdatedAt: record.sourceUpdatedAt,
    capturedAt: record.capturedAt,
    sourceRef: record.sourceRef,
    documents: (record.documents ?? []).map((document) => ({
      versionId: document.versionId,
      state: document.state,
      ocrState: document.ocrState,
      text: document.text,
      originalRef: document.originalRef,
      sourceLocator: document.sourceLocator,
    })),
    attachments: record.attachments ?? [],
    relations: record.relations ?? [],
    annotations: record.annotations ?? [],
    classificationBasis: record.classificationBasis ?? null,
    missingFields: record.missingFields ?? [],
    rawRecordPath: "data/cases/public-pilot-v1/records.json",
  }));
}

async function writeResource(file, value) {
  const text = `${JSON.stringify(value, null, 2)}\n`;
  await writeFile(join(resourceDir, file), text, "utf8");
  return { file, recordCount: value.length, sha256: sha256(text) };
}

await mkdir(resourceDir, { recursive: true });
const patents = await Promise.all([
  loadVerified(sources.patents[0], "CON-04-PUBLIC-PILOT-V1", 44),
  loadVerified(sources.patents[1], "CON-04-EPO-CN-PILOT-V1", 30),
  loadVerified(sources.patents[2], "CON-04-CN-TOPIC-PUBLIC-PILOT-V1", 400),
]);
const trademarks = await loadVerified(sources.trademarks, "CON-01-TRADEMARK-PUBLIC-PILOT-V1", 100);
const cases = await loadVerified(sources.cases, "CON-03-CASE-PUBLIC-PILOT-V1", 100);

const generated = [
  await writeResource("public-patents-local-v1.json", patentResource(patents)),
  await writeResource("public-trademarks-v1.json", trademarkResource(trademarks.records)),
  await writeResource("public-cases-v1.json", caseResource(cases.records)),
];
const importManifest = {
  schemaVersion: "1.0",
  importId: "CON-PUBLIC-DATA-IMPORT-V1",
  generatedAt: new Date().toISOString(),
  mode: "PUBLIC_PILOT",
  sources: [...patents, trademarks, cases].map(({ datasetId, sourceSha256, source }) => ({ datasetId, sourceSha256, source })),
  generated,
  boundaries: { centerAuthoritative: false, formalVerificationEvidence: false, businessAcceptanceEvidence: false },
};
await writeFile(join(resourceDir, "public-data-import-v1.manifest.json"), `${JSON.stringify(importManifest, null, 2)}\n`, "utf8");
console.log(`Imported public pilot resources: ${generated.map((item) => `${item.recordCount} ${item.file}`).join(", ")}`);
