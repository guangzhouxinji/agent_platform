import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDir = process.env.PATENT_CAPTURE_OUTPUT_DIR
  ? resolve(process.env.PATENT_CAPTURE_OUTPUT_DIR)
  : join(projectRoot, "data", "patents", "public-pilot-v1");
const rawDir = join(outputDir, "raw");
const recordsPath = join(outputDir, "records.json");
const manifestPath = join(outputDir, "manifest.json");
const verifyOnly = process.argv.includes("--verify-only");

const source = {
  name: "SureChEMBL",
  websiteUrl: "https://www.surechembl.org/",
  apiBaseUrl: "https://surechembl.org/api",
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

const queries = [
  {
    id: "Q1_AQUEOUS_BINDER_COATING_DRYING",
    label: "Water-based binder coating and drying",
    query: '(coating AND "water-based binder" AND drying)',
  },
  {
    id: "Q2_TWO_STAGE_DRYING",
    label: "Binder coating with partial and full drying",
    query: '(binder AND coating AND "partially drying" AND "fully drying")',
  },
  {
    id: "Q3_CN_AQUEOUS_BINDER",
    label: "Chinese patent titles for aqueous binders",
    query: '((ttl:"水性粘结剂" OR ttl:"水系粘结剂") AND pnctry:CN)',
  },
];

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

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
  return /^\d{8}$/.test(text)
    ? `${text.slice(0, 4)}-${text.slice(4, 6)}-${text.slice(6, 8)}`
    : null;
}

function collectSectionContent(value, result = []) {
  if (Array.isArray(value)) {
    for (const item of value) collectSectionContent(item, result);
    return result;
  }
  if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (key === "content" && typeof item === "string") {
        const cleaned = cleanText(item);
        if (cleaned) result.push(cleaned);
      } else if (key !== "annotations") {
        collectSectionContent(item, result);
      }
    }
  }
  return result;
}

function collectClassifications(value, result = new Set()) {
  if (Array.isArray(value)) {
    for (const item of value) collectClassifications(item, result);
  } else if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (key === "classification" && typeof item === "string") {
        const normalized = item.replace(/\s+/g, " ").trim();
        if (normalized) result.add(normalized);
      } else {
        collectClassifications(item, result);
      }
    }
  }
  return [...result];
}

function asArray(value) {
  return Array.isArray(value) ? value : value ? [value] : [];
}

function normalizedDocumentId(value) {
  if (!value || typeof value !== "object") return null;
  const country = cleanText(value.country?.content ?? value.country);
  const number = cleanText(value.docNumber);
  const kind = cleanText(value.kind);
  const date = normalizeDate(value.date);
  if (!country && !number) return null;
  return { country: country || null, number: number || null, kind: kind || null, date };
}

function collectDocumentIds(value, result = []) {
  if (Array.isArray(value)) {
    for (const item of value) collectDocumentIds(item, result);
  } else if (value && typeof value === "object") {
    const identifier = normalizedDocumentId(value);
    if (identifier) result.push(identifier);
    for (const item of Object.values(value)) collectDocumentIds(item, result);
  }
  return result.filter((item, index, all) => all.findIndex((candidate) => JSON.stringify(candidate) === JSON.stringify(item)) === index);
}

function collectPartyNames(parties, partyType) {
  const values = [];
  for (const group of asArray(parties)) {
    for (const entry of asArray(group?.[partyType]?.[partyType.slice(0, -1)])) {
      const name = cleanText(entry?.addressbook?.name ?? entry?.addressbook?.lastName);
      if (name) values.push(name);
    }
  }
  return [...new Set(values)];
}

function collectLegalEvents(value) {
  const events = [];
  for (const group of asArray(value)) {
    for (const event of asArray(group?.["legal-event"])) {
      const code = cleanText(event?.["@code"]);
      const date = normalizeDate(event?.["@date"]);
      const title = cleanText(event?.["legal-event-body"]?.["event-title"]?.$);
      if (code || date || title) events.push({ code: code || null, date, title: title || null });
    }
  }
  return events;
}

function truncate(value, maxLength) {
  const text = cleanText(value);
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}...`;
}

async function fetchText(url, options = {}) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(url, {
        ...options,
        signal: AbortSignal.timeout(60_000),
        headers: {
          Accept: "application/json",
          Origin: "https://www.surechembl.org",
          ...options.headers,
        },
      });
      const text = await response.text();
      if (!response.ok) {
        throw new Error(`${response.status} ${response.statusText}: ${truncate(text, 300)}`);
      }
      return text;
    } catch (error) {
      lastError = error;
      if (attempt < 3) await new Promise((resolveDelay) => setTimeout(resolveDelay, attempt * 1_000));
    }
  }
  throw lastError;
}

async function fetchSearch(query) {
  const url = new URL(`${source.apiBaseUrl}/search/content`);
  url.searchParams.set("query", query.query);
  url.searchParams.set("page", "1");
  url.searchParams.set("itemsPerPage", "15");
  const text = await fetchText(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
  });
  const payload = JSON.parse(text);
  if (payload.status !== "OK") {
    throw new Error(`Search ${query.id} returned status ${payload.status}`);
  }
  return { text, payload, requestUrl: url.toString() };
}

function parseDetailResponse(text, publicationNumber = "unknown") {
  const payload = JSON.parse(text);
  if (payload.status !== "OK") {
    throw new Error(`Detail ${publicationNumber} returned status ${payload.status}`);
  }
  const patentDocument = payload?.data?.contents?.patentDocument ?? {};
  const bibliographicData = patentDocument.bibliographicData ?? {};
  const technicalData = bibliographicData.technicalData ?? {};
  const abstracts = collectSectionContent(patentDocument.abstracts).join(" ");
  const claims = collectSectionContent(patentDocument.claimResponses).join(" ");
  const cpc = collectClassifications(technicalData.classificationsCpc);
  const ipc = collectClassifications(technicalData.classificationsIpcr);
  const applicationIds = collectDocumentIds(bibliographicData.applicationReference);
  const priorityIds = collectDocumentIds(bibliographicData.priorityClaims);
  const citationIds = collectDocumentIds(technicalData.citations);
  const publicationIds = collectDocumentIds(bibliographicData.publicationReference);
  return {
    rawResponse: text,
    responseSha256: sha256(text),
    abstractExcerpt: truncate(abstracts, 1_500) || null,
    claimsExcerpt: truncate(claims, 5_000) || null,
    cpcClassifications: cpc.slice(0, 30),
    ipcClassifications: ipc,
    applicationNumbers: applicationIds.map((item) => item.number).filter(Boolean),
    applicationDate: applicationIds.map((item) => item.date).find(Boolean) ?? null,
    priorityClaims: priorityIds,
    applicants: collectPartyNames(bibliographicData.parties, "applicants"),
    inventors: collectPartyNames(bibliographicData.parties, "inventors"),
    publicationReferences: publicationIds,
    citationReferences: citationIds,
    familyRecordIds: asArray(patentDocument.family).map((item) => cleanText(item)).filter(Boolean),
    sourceLegalEvents: collectLegalEvents(patentDocument.legalStatus),
    sourcePdfUrl: cleanText(patentDocument.pdfUrl) || null,
    sourceLocators: [
      ...(abstracts ? ["abstract"] : []),
      ...(claims ? ["claims"] : []),
    ],
  };
}

async function fetchDetail(publicationNumber) {
  const url = `${source.apiBaseUrl}/document/${encodeURIComponent(publicationNumber)}/contents`;
  return parseDetailResponse(await fetchText(url), publicationNumber);
}

function normalizeSearchRecord(document, queryId, capturedAt) {
  const metadata = document.metadata ?? {};
  const titles = (metadata.titles ?? []).flatMap((entry) =>
    (entry.titles ?? []).map((title) => ({
      language: String(entry.lang ?? "und").toLowerCase(),
      text: cleanText(title),
    })),
  );
  const preferredTitle =
    titles.find((title) => title.language === "zh") ??
    titles.find((title) => title.language === "en") ??
    titles[0];
  const publicationNumber = cleanText(metadata.pn || document.docId);
  return {
    sourceRecordId: cleanText(document.docId),
    publicationNumber,
    publicationDate: normalizeDate(metadata.pd),
    sourceVersion: `${publicationNumber}@${metadata.pd || "UNKNOWN"}`,
    title: preferredTitle?.text ?? publicationNumber,
    titles,
    applicant: cleanText(document.pa) || null,
    detailUrl: `https://www.surechembl.org/patent/${encodeURIComponent(document.docId)}`,
    matchedQueryIds: [queryId],
    capturedAt,
    abstractExcerpt: null,
    claimsExcerpt: null,
    cpcClassifications: [],
    ipcClassifications: [],
    applicationNumbers: [],
    applicationDate: null,
    priorityClaims: [],
    applicants: [],
    inventors: [],
    publicationReferences: [],
    citationReferences: [],
    familyRecordIds: [],
    sourceLegalEvents: [],
    sourcePdfUrl: null,
    sourceLocators: ["document-root"],
    detailResponseSha256: null,
    expertLabel: null,
    boundaries: { ...boundaries },
  };
}

function rankForDetail(record) {
  const title = record.title.toLowerCase();
  return ["water-based", "aqueous", "binder", "coating", "drying", "粘结剂", "涂层"]
    .reduce((score, term) => score + (title.includes(term) ? 1 : 0), 0);
}

async function collect() {
  await mkdir(rawDir, { recursive: true });
  const capturedAt = new Date().toISOString();
  const recordsById = new Map();
  const rawResponses = [];

  for (const query of queries) {
    const { text, payload, requestUrl } = await fetchSearch(query);
    const rawFile = `raw/search-${query.id.toLowerCase()}.json`;
    await writeFile(join(outputDir, rawFile), `${JSON.stringify(payload, null, 2)}\n`, "utf8");
    rawResponses.push({
      queryId: query.id,
      requestUrl,
      file: rawFile.replaceAll("\\", "/"),
      sha256: sha256(`${JSON.stringify(payload, null, 2)}\n`),
      responseSha256: sha256(text),
      totalHits: payload?.data?.results?.total_hits ?? 0,
    });

    for (const document of payload?.data?.results?.documents ?? []) {
      const normalized = normalizeSearchRecord(document, query.id, capturedAt);
      const existing = recordsById.get(normalized.publicationNumber);
      if (existing) {
        existing.matchedQueryIds = [...new Set([...existing.matchedQueryIds, query.id])];
      } else {
        recordsById.set(normalized.publicationNumber, normalized);
      }
    }
  }

  const records = [...recordsById.values()].sort((left, right) =>
    left.publicationNumber.localeCompare(right.publicationNumber),
  );
  // Preserve a raw source response for every collected candidate. The normalized
  // record remains deliberately small; raw responses are trace evidence, not an
  // implicit authorization to use full text in a model or production index.
  for (const record of records) {
    const rawFile = `raw/detail-${record.sourceRecordId}.json`;
    let detail;
    try {
      try {
        detail = parseDetailResponse(await readFile(join(outputDir, rawFile), "utf8"), record.sourceRecordId);
      } catch (error) {
        if (error.code !== "ENOENT") throw error;
        detail = await fetchDetail(record.sourceRecordId);
        await writeFile(join(outputDir, rawFile), detail.rawResponse, "utf8");
      }
    } catch (error) {
      record.detailCaptureFailure = String(error?.message ?? error);
      record.sourceLocators = [...new Set([...record.sourceLocators, "document-root"] )];
      continue;
    }
    record.abstractExcerpt = detail.abstractExcerpt;
    record.claimsExcerpt = detail.claimsExcerpt;
    record.cpcClassifications = detail.cpcClassifications;
    record.ipcClassifications = detail.ipcClassifications;
    record.applicationNumbers = detail.applicationNumbers;
    record.applicationDate = detail.applicationDate;
    record.priorityClaims = detail.priorityClaims;
    record.applicants = detail.applicants;
    record.inventors = detail.inventors;
    record.publicationReferences = detail.publicationReferences;
    record.citationReferences = detail.citationReferences;
    record.familyRecordIds = detail.familyRecordIds;
    record.sourceLegalEvents = detail.sourceLegalEvents;
    record.sourcePdfUrl = detail.sourcePdfUrl;
    record.sourceLocators = [...new Set([...record.sourceLocators, ...detail.sourceLocators])];
    record.detailResponseSha256 = detail.responseSha256;
    record.detailRawFile = rawFile;
    rawResponses.push({
      type: "DETAIL",
      sourceRecordId: record.sourceRecordId,
      requestUrl: `${source.apiBaseUrl}/document/${encodeURIComponent(record.sourceRecordId)}/contents`,
      file: rawFile,
      sha256: sha256(detail.rawResponse),
      responseSha256: detail.responseSha256,
    });
  }

  const recordsDocument = {
    schemaVersion: "1.0",
    datasetId: "CON-04-PUBLIC-PILOT-V1",
    generatedAt: capturedAt,
    source,
    records,
  };
  const recordsText = `${JSON.stringify(recordsDocument, null, 2)}\n`;
  await writeFile(recordsPath, recordsText, "utf8");

  const manifest = {
    schemaVersion: "1.0",
    datasetId: recordsDocument.datasetId,
    generatedAt: capturedAt,
    purpose: "CON-04 internal public-data feasibility pilot",
    source,
    boundaries,
    queries,
    recordCount: records.length,
    detailedRecordCount: records.filter((record) => record.detailResponseSha256).length,
    detailCaptureFailures: records.filter((record) => record.detailCaptureFailure).length,
    recordsFile: "records.json",
    recordsSha256: sha256(recordsText),
    rawResponses,
    limitations: [
      "Search matches are unreviewed candidates, not expert relevance labels.",
      "Public accessibility does not establish permission for production indexing, retention, redistribution, or external model processing.",
      "This source is not the project's center authoritative patent system.",
      "No PDF or full patent description is stored in this pilot dataset.",
    ],
  };
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

async function verify() {
  const manifestText = await readFile(manifestPath, "utf8");
  const recordsText = await readFile(recordsPath, "utf8");
  const manifest = JSON.parse(manifestText);
  const recordsDocument = JSON.parse(recordsText);
  const errors = [];
  const records = recordsDocument.records ?? [];

  if (manifest.datasetId !== "CON-04-PUBLIC-PILOT-V1") errors.push("unexpected datasetId");
  if (manifest.recordCount !== records.length) errors.push("manifest recordCount mismatch");
  if (records.length < 10) errors.push(`expected at least 10 records, found ${records.length}`);
  const failedDetails = records.filter((record) => record.detailCaptureFailure).length;
  if (manifest.detailCaptureFailures !== failedDetails) {
    errors.push("detailCaptureFailures mismatch");
  }
  if (sha256(recordsText) !== manifest.recordsSha256) errors.push("records digest mismatch");

  const uniqueIds = new Set();
  for (const [index, record] of records.entries()) {
    const prefix = `record[${index}]`;
    for (const field of ["sourceRecordId", "publicationNumber", "sourceVersion", "title", "detailUrl", "capturedAt"]) {
      if (!record[field]) errors.push(`${prefix} missing ${field}`);
    }
    if (uniqueIds.has(record.publicationNumber)) errors.push(`${prefix} duplicate publicationNumber`);
    uniqueIds.add(record.publicationNumber);
    if (!Array.isArray(record.matchedQueryIds) || record.matchedQueryIds.length === 0) {
      errors.push(`${prefix} missing matchedQueryIds`);
    }
    if (JSON.stringify(record.boundaries) !== JSON.stringify(boundaries)) {
      errors.push(`${prefix} boundary flags changed`);
    }
    if (record.expertLabel !== null) errors.push(`${prefix} must not contain an expert label`);
    if (!record.detailResponseSha256 && !record.detailCaptureFailure) {
      errors.push(`${prefix} missing detailed source capture`);
    }
  }

  for (const raw of manifest.rawResponses ?? []) {
    const rawText = await readFile(join(outputDir, raw.file), "utf8");
    if (sha256(rawText) !== raw.sha256) errors.push(`${raw.file} digest mismatch`);
  }

  if (errors.length) {
    throw new Error(`Public patent pilot verification failed:\n- ${errors.join("\n- ")}`);
  }
  console.log(
    `Public patent pilot verified: ${records.length} unique records, ` +
      `${manifest.detailedRecordCount} with abstract/claim detail, ${manifest.rawResponses.length} traced queries.`,
  );
}

if (!verifyOnly) await collect();
await verify();
