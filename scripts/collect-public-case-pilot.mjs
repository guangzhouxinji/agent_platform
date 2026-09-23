import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDir = join(projectRoot, "data", "cases", "public-pilot-v1");
const rawDir = join(outputDir, "raw");
const recordsPath = join(outputDir, "records.json");
const manifestPath = join(outputDir, "manifest.json");
const verifyOnly = process.argv.includes("--verify-only");
const sourceBase = "https://ipc.court.gov.cn";
const categoryUrl = `${sourceBase}/zh-cn/news/more-5-25.html`;

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
const sleep = (milliseconds) => new Promise((resolveSleep) => setTimeout(resolveSleep, milliseconds));

function decodeHtml(value) {
  return value
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCodePoint(Number.parseInt(code, 16)))
    .replace(/&nbsp;/gi, " ")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&amp;/gi, "&");
}

function plainText(html) {
  return decodeHtml(html)
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, " ")
    .replace(/<br\s*\/?\s*>/gi, "\n")
    .replace(/<\/(p|div|li|h[1-6]|tr)>/gi, "\n")
    .replace(/<[^>]+>/g, " ")
    .replace(/[ \t]+/g, " ")
    .replace(/\n\s+/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

async function fetchText(url, attempts = 3) {
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(60_000), headers: { Accept: "text/html", "User-Agent": "agent-platform-public-pilot/1.0" } });
      const text = await response.text();
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
      return text;
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await sleep(attempt * 500);
    }
  }
  throw new Error(`Failed to fetch ${url}: ${lastError?.message}`);
}

function extractEntries(html) {
  const entries = [];
  const pattern = /<a\s+title="([^"]+)"\s+target="_blank"\s+href="(\/zh-cn\/news\/view-(\d+)\.html)"/gi;
  let match;
  while ((match = pattern.exec(html)) !== null) {
    entries.push({ listedTitle: decodeHtml(match[1]).trim(), path: match[2], sourceRecordId: match[3] });
  }
  return entries;
}

function extractFirst(html, pattern) {
  const match = pattern.exec(html);
  return match ? plainText(match[1]) : null;
}

function classifyDocumentType(text) {
  const heading = text.slice(0, 300).replace(/\s+/g, "");
  const candidates = ["民事判决书", "行政判决书", "民事裁定书", "行政裁定书", "决定书"]
    .map((value) => ({ value, index: heading.indexOf(value) }))
    .filter((candidate) => candidate.index >= 0)
    .sort((left, right) => left.index - right.index);
  return candidates[0]?.value ?? null;
}

function classifyIpType(text) {
  const types = [
    ["植物新品种", ["植物新品种"]],
    ["集成电路布图设计", ["集成电路布图设计"]],
    ["技术秘密", ["技术秘密", "商业秘密"]],
    ["垄断", ["垄断协议", "滥用市场支配地位", "反垄断"]],
    ["计算机软件", ["计算机软件"]],
    ["专利", ["发明专利", "实用新型专利", "外观设计专利", "专利权"]],
    ["商标", ["商标权"]],
    ["著作权", ["著作权"]],
  ];
  const opening = text.slice(0, 3_000);
  const candidates = types.flatMap(([type, keywords]) => keywords
    .map((keyword) => ({ type, keyword, index: opening.indexOf(keyword) }))
    .filter((candidate) => candidate.index >= 0));
  candidates.sort((left, right) => left.index - right.index);
  return candidates[0]?.type ?? null;
}

function normalizeDetail(entry, html, capturedAt) {
  const title = extractFirst(html, /<h2[^>]*>([\s\S]*?)<\/h2>/i) ?? entry.listedTitle;
  const publishedAt = extractFirst(html, /<span[^>]*>\s*发布时间[：:]\s*([\s\S]*?)<\/span>/i);
  const sourceName = extractFirst(html, /<span[^>]*>\s*来源[：:]\s*([\s\S]*?)<\/span>/i);
  const bodyHtml = /<div\s+class="txt"[^>]*>([\s\S]*?)<\/div>\s*<\/div>/i.exec(html)?.[1] ?? "";
  const fullText = plainText(bodyHtml);
  const documentType = classifyDocumentType(fullText);
  const intellectualPropertyType = classifyIpType(fullText);
  const sourceRef = `${sourceBase}${entry.path}`;
  return {
    sourceId: "SPC_IP_COURT_SELECTED_JUDGMENTS",
    sourceRecordId: entry.sourceRecordId,
    sourceVersion: `${entry.sourceRecordId}@${publishedAt ?? "UNKNOWN"}`,
    caseNumber: title,
    docketNumber: title,
    title,
    caseType: documentType,
    intellectualPropertyType,
    institution: sourceName,
    procedure: null,
    hearingDate: null,
    decisionDate: null,
    outcomeExcerpt: null,
    sourcePublishedAt: publishedAt,
    sourceUpdatedAt: publishedAt,
    capturedAt,
    sourceRef,
    dataMode: "PUBLIC_PILOT",
    documents: [{ versionId: `SOURCE-${entry.sourceRecordId}`, state: "SOURCE_CAPTURED", ocrState: "NOT_APPLICABLE", text: fullText, originalRef: sourceRef, sourceLocator: ".detail .txt" }],
    attachments: [],
    relations: [],
    annotations: [],
    classificationBasis: {
      caseType: documentType ? `Explicit document heading: ${documentType}` : null,
      intellectualPropertyType: intellectualPropertyType ? `Explicit keyword present in source text for ${intellectualPropertyType}` : null,
    },
    missingFields: ["procedure", "hearingDate", "decisionDate", "outcomeExcerpt", "reviewedAnnotations"],
    boundaries: { ...boundaries },
  };
}

async function collect() {
  await mkdir(rawDir, { recursive: true });
  const capturedAt = new Date().toISOString();
  const entries = [];
  const rawResponses = [];
  for (let page = 1; page <= 20; page += 1) {
    const url = page === 1 ? categoryUrl : `${categoryUrl}?page=${page}`;
    const html = await fetchText(url);
    const file = `raw/list-page-${String(page).padStart(2, "0")}.html`;
    await writeFile(join(outputDir, file), html, "utf8");
    rawResponses.push({ type: "LIST", page, url, file, sha256: sha256(html), itemCount: extractEntries(html).length });
    entries.push(...extractEntries(html));
  }
  const uniqueEntries = entries.filter((entry, index, all) => all.findIndex((candidate) => candidate.sourceRecordId === entry.sourceRecordId) === index).slice(0, 100);
  if (uniqueEntries.length !== 100) throw new Error(`Expected 100 unique case entries, found ${uniqueEntries.length}`);

  const records = [];
  for (const [index, entry] of uniqueEntries.entries()) {
    const url = `${sourceBase}${entry.path}`;
    const html = await fetchText(url);
    const file = `raw/detail-${entry.sourceRecordId}.html`;
    await writeFile(join(outputDir, file), html, "utf8");
    rawResponses.push({ type: "DETAIL", sourceRecordId: entry.sourceRecordId, url, file, sha256: sha256(html), bytes: Buffer.byteLength(html) });
    records.push(normalizeDetail(entry, html, capturedAt));
    if ((index + 1) % 20 === 0) console.log(`Captured case details: ${index + 1}/100`);
    await sleep(100);
  }

  const recordsDocument = { schemaVersion: "1.0", datasetId: "CON-03-CASE-PUBLIC-PILOT-V1", generatedAt: capturedAt, records };
  const recordsText = `${JSON.stringify(recordsDocument, null, 2)}\n`;
  await writeFile(recordsPath, recordsText, "utf8");
  const manifest = {
    schemaVersion: "1.0",
    datasetId: recordsDocument.datasetId,
    generatedAt: capturedAt,
    recordCount: records.length,
    recordsSha256: sha256(recordsText),
    source: {
      name: "最高人民法院知识产权法庭 - 精品裁判",
      categoryUrl,
      accessMode: "PUBLIC_WEB_PAGES",
      authorityStatus: "PUBLIC_JUDICIAL_WEBSITE_NON_CENTER_SOURCE",
      licenseReviewStatus: "REVIEW_REQUIRED",
      usageDecision: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
    },
    selection: { count: 100, pages: "1-20", rule: "First 100 unique detail links in category page order", capturedDetailPages: 100 },
    boundaries,
    rawResponses,
    fieldCoverage: {
      available: ["docketNumber", "title", "caseType when explicit", "intellectualPropertyType when explicit", "institution", "sourcePublishedAt", "source full text", "sourceRef"],
      unavailableUnlessExplicitlyParsed: ["procedure", "hearingDate", "decisionDate", "outcomeExcerpt", "reviewedAnnotations"],
    },
    limitations: [
      "Website publication time is preserved as sourcePublishedAt and is not treated as the hearing or decision date.",
      "Document and IP type are populated only when explicit source-text keywords are present.",
      "No facts, issues, holdings, legal citations, outcomes, or expert annotations are generated.",
      "Public pages do not establish permission for production redistribution, indexing, or external-model processing; legal/data-use review remains required.",
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
  const seen = new Set();
  for (const [index, record] of records.entries()) {
    if (!record.sourceRecordId || !record.docketNumber || !record.sourceRef || !record.capturedAt || !record.documents?.[0]?.text) errors.push(`record ${index} missing required source fields`);
    if (record.decisionDate !== null || record.hearingDate !== null) errors.push(`record ${index} must not infer decision/hearing date`);
    if (record.annotations?.length) errors.push(`record ${index} must not contain generated annotations`);
    if (record.dataMode !== "PUBLIC_PILOT" || record.boundaries?.centerAuthoritative !== false) errors.push(`record ${index} violates PUBLIC_PILOT boundary`);
    if (seen.has(record.sourceRecordId)) errors.push(`duplicate sourceRecordId ${record.sourceRecordId}`);
    seen.add(record.sourceRecordId);
  }
  const details = manifest.rawResponses?.filter((item) => item.type === "DETAIL") ?? [];
  if (details.length !== 100) errors.push(`expected 100 raw detail pages, found ${details.length}`);
  if (manifest.source?.licenseReviewStatus !== "REVIEW_REQUIRED") errors.push("license review must remain REVIEW_REQUIRED");
  for (const raw of manifest.rawResponses ?? []) {
    try {
      const content = await readFile(join(outputDir, raw.file), "utf8");
      if (sha256(content) !== raw.sha256) errors.push(`raw file hash mismatch: ${raw.file}`);
    } catch {
      errors.push(`raw file missing: ${raw.file}`);
    }
  }
  if (errors.length) throw new Error(`Case pilot verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`Case public pilot verified: ${records.length} unique records and ${details.length} raw details`);
}

if (!verifyOnly) await collect();
await verify();
