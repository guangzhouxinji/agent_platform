import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDir = join(projectRoot, "data", "patents", "cn-topic-public-pilot-v1");
const rawDir = join(outputDir, "raw");
const recordsPath = join(outputDir, "records.json");
const manifestPath = join(outputDir, "manifest.json");
const verifyOnly = process.argv.includes("--verify-only");
const apiBaseUrl = "https://surechembl.org/api";
const quotaPerTopic = 100;

const topics = [
  { id: "AI_ML", labelZh: "人工智能与机器学习", query: '(pnctry:CN AND (ttl:"人工智能" OR ttl:"机器学习"))' },
  { id: "BATTERY", labelZh: "锂离子与固态电池", query: '(pnctry:CN AND (ttl:"锂离子电池" OR ttl:"固态电池"))' },
  { id: "SEMICONDUCTOR", labelZh: "半导体与集成电路", query: '(pnctry:CN AND (ttl:"半导体" OR ttl:"集成电路"))' },
  { id: "BIOTECH", labelZh: "基因与生物技术", query: '(pnctry:CN AND (ttl:"基因" OR ttl:"生物"))' },
];

const source = {
  name: "SureChEMBL",
  websiteUrl: "https://www.surechembl.org/",
  apiBaseUrl,
  accessMode: "PUBLIC_WEB_API",
  authorityStatus: "NON_CENTER_PUBLIC_SOURCE",
  licenseReviewStatus: "REVIEW_REQUIRED",
  usageDecision: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
};

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

function cleanText(value) {
  return String(value ?? "")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeDate(value) {
  const text = String(value ?? "");
  return /^\d{8}$/.test(text) ? `${text.slice(0, 4)}-${text.slice(4, 6)}-${text.slice(6, 8)}` : null;
}

async function fetchSearch(topic, page) {
  const url = new URL(`${apiBaseUrl}/search/content`);
  url.searchParams.set("query", topic.query);
  url.searchParams.set("page", String(page));
  url.searchParams.set("itemsPerPage", "25");
  const response = await fetch(url, {
    method: "POST",
    signal: AbortSignal.timeout(60_000),
    headers: { Accept: "application/json", Origin: "https://www.surechembl.org", "User-Agent": "agent-platform-public-pilot/1.0" },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${response.status} ${response.statusText} for ${topic.id} page ${page}`);
  const payload = JSON.parse(text);
  if (payload.status !== "OK") throw new Error(`Source returned ${payload.status} for ${topic.id} page ${page}`);
  return { url: url.toString(), text, payload };
}

function normalizeRecord(document, topic, capturedAt) {
  const metadata = document.metadata ?? {};
  const titles = (metadata.titles ?? []).flatMap((entry) => (entry.titles ?? []).map((title) => ({
    language: String(entry.lang ?? "und").toLowerCase(),
    text: cleanText(title),
  }))).filter((title) => title.text);
  const titleZh = titles.find((title) => title.language === "zh")?.text ?? titles.find((title) => /[\u3400-\u9fff]/u.test(title.text))?.text ?? null;
  const titleEn = titles.find((title) => title.language === "en")?.text ?? null;
  const publicationNumber = cleanText(metadata.pn || document.docId);
  const publicationDate = normalizeDate(metadata.pd);
  return {
    sourceId: "SURECHEMBL_PUBLIC_SEARCH",
    sourceRecordId: cleanText(document.docId),
    sourceVersion: `${publicationNumber}@${metadata.pd || "UNKNOWN"}`,
    publicationNumber,
    publicationDate,
    title: titleZh ?? titleEn ?? titles[0]?.text ?? publicationNumber,
    titleZh,
    titleEn,
    titles,
    applicant: cleanText(document.pa) || null,
    topicId: topic.id,
    topicLabelZh: topic.labelZh,
    matchedQueryIds: [topic.id],
    capturedAt,
    sourceRef: `https://www.surechembl.org/patent/${encodeURIComponent(document.docId)}`,
    sourceLocator: "search-result.document",
    abstractExcerpt: null,
    claimsExcerpt: null,
    cpcClassifications: [],
    expertLabel: null,
    dataMode: "PUBLIC_PILOT",
    missingFields: ["filingDate", "inventor", "abstractExcerpt", "claimsExcerpt", "cpcClassifications", "authoritativeCurrentLegalStatus"],
    boundaries: { ...boundaries },
  };
}

async function loadExistingIds() {
  const paths = [
    join(projectRoot, "data", "patents", "public-pilot-v1", "records.json"),
    join(projectRoot, "data", "patents", "epo-cn-pilot-v1", "records.json"),
  ];
  const ids = new Set();
  for (const path of paths) {
    try {
      const payload = JSON.parse(await readFile(path, "utf8"));
      for (const record of payload.records ?? []) {
        if (record.sourceRecordId) ids.add(record.sourceRecordId);
        if (record.publicationNumber) ids.add(String(record.publicationNumber).replaceAll(" ", "-").toUpperCase());
      }
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
  }
  return ids;
}

async function collect() {
  await mkdir(rawDir, { recursive: true });
  const capturedAt = new Date().toISOString();
  const excludedIds = await loadExistingIds();
  const selectedIds = new Set();
  const records = [];
  const rawResponses = [];

  for (const topic of topics) {
    let selectedForTopic = 0;
    for (let page = 1; page <= 10 && selectedForTopic < quotaPerTopic; page += 1) {
      const { url, text, payload } = await fetchSearch(topic, page);
      const formatted = `${JSON.stringify(payload, null, 2)}\n`;
      const rawFile = `raw/search-${topic.id.toLowerCase()}-page-${String(page).padStart(2, "0")}.json`;
      await writeFile(join(outputDir, rawFile), formatted, "utf8");
      const documents = payload?.data?.results?.documents ?? [];
      rawResponses.push({
        queryId: topic.id,
        page,
        requestUrl: url,
        file: rawFile,
        sha256: sha256(formatted),
        responseSha256: sha256(text),
        sourceTotalHits: payload?.data?.results?.total_hits ?? 0,
        returnedCount: documents.length,
      });

      for (const document of documents) {
        const record = normalizeRecord(document, topic, capturedAt);
        const normalizedId = record.sourceRecordId.toUpperCase();
        if (!normalizedId.startsWith("CN-") || excludedIds.has(normalizedId) || selectedIds.has(normalizedId)) continue;
        if (!record.titleZh) continue;
        records.push(record);
        selectedIds.add(normalizedId);
        selectedForTopic += 1;
        if (selectedForTopic === quotaPerTopic) break;
      }
    }
    if (selectedForTopic !== quotaPerTopic) throw new Error(`Expected ${quotaPerTopic} records for ${topic.id}, found ${selectedForTopic}`);
  }

  const recordsPayload = {
    schemaVersion: "1.0",
    datasetId: "CON-04-CN-TOPIC-PUBLIC-PILOT-V1",
    generatedAt: capturedAt,
    source,
    records,
  };
  const recordsText = `${JSON.stringify(recordsPayload, null, 2)}\n`;
  await writeFile(recordsPath, recordsText, "utf8");

  const manifest = {
    schemaVersion: "1.0",
    datasetId: recordsPayload.datasetId,
    generatedAt: capturedAt,
    purpose: "CON-04 Chinese topic patent public-data development pilot",
    source,
    boundaries,
    topics: topics.map((topic) => ({ ...topic, quota: quotaPerTopic })),
    selection: { total: 400, quotaPerTopic, requiresChineseTitle: true, excludesExistingPilotRecords: true, maximumPagesPerTopic: 10 },
    recordCount: records.length,
    recordsFile: "records.json",
    recordsSha256: sha256(recordsText),
    rawResponses,
    fieldCoverage: {
      available: ["publicationNumber", "publicationDate", "titleZh", "titleEn when supplied", "applicant", "topicId", "sourceRef"],
      unavailable: ["filingDate", "inventor", "abstractExcerpt", "claimsExcerpt", "cpcClassifications", "authoritativeCurrentLegalStatus"],
    },
    limitations: [
      "Search matches are deterministic topic candidates, not expert relevance labels or legal conclusions.",
      "Topic assignment records the query that selected the result; it is not an authoritative patent classification.",
      "Public accessibility does not establish permission for production indexing, retention, redistribution, or external model processing.",
      "This source is not the project's center authoritative patent system.",
      "No PDF, full description, abstract, claims, or current legal status is stored in this dataset.",
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
  if (records.length !== 400 || manifest.recordCount !== 400) errors.push(`expected exactly 400 records, found ${records.length}`);
  if (manifest.recordsSha256 !== sha256(recordsText)) errors.push("recordsSha256 mismatch");
  const seen = new Set();
  for (const [index, record] of records.entries()) {
    if (!record.sourceRecordId?.startsWith("CN-") || !record.publicationNumber || !record.titleZh || !record.sourceRef || !record.capturedAt) errors.push(`record ${index} missing required CN source fields`);
    if (record.dataMode !== "PUBLIC_PILOT" || record.boundaries?.centerAuthoritative !== false || record.boundaries?.expertLabeled !== false) errors.push(`record ${index} violates PUBLIC_PILOT boundary`);
    if (seen.has(record.sourceRecordId)) errors.push(`duplicate sourceRecordId ${record.sourceRecordId}`);
    seen.add(record.sourceRecordId);
  }
  for (const topic of topics) {
    const count = records.filter((record) => record.topicId === topic.id).length;
    if (count !== quotaPerTopic) errors.push(`${topic.id} expected ${quotaPerTopic}, found ${count}`);
  }
  const existingIds = await loadExistingIds();
  for (const record of records) if (existingIds.has(record.sourceRecordId.toUpperCase())) errors.push(`overlaps existing pilot: ${record.sourceRecordId}`);
  for (const raw of manifest.rawResponses ?? []) {
    try {
      const content = await readFile(join(outputDir, raw.file), "utf8");
      if (sha256(content) !== raw.sha256) errors.push(`raw file hash mismatch: ${raw.file}`);
    } catch {
      errors.push(`raw file missing: ${raw.file}`);
    }
  }
  if (manifest.source?.licenseReviewStatus !== "REVIEW_REQUIRED") errors.push("license review must remain REVIEW_REQUIRED");
  if (errors.length) throw new Error(`CN topic patent pilot verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`CN topic patent public pilot verified: ${records.length} unique records (${quotaPerTopic} per topic)`);
}

if (!verifyOnly) await collect();
await verify();
