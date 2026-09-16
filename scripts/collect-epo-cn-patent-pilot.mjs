// EPO Linked Open Data 公开端点 CN 专利样本采集
// 来源：https://data.epo.org/linked-data/data/publication/CN （无需凭证）
// 边界：仅公开 LOD 字段（出版号/日期/种类/申请号/申请日），不含标题/申请人/CPC/摘要/权利要求
// 用途：CON-04 内部公开数据可行性试点，与现有 public-pilot-v1 (SureChEMBL) 同边界同 schema
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDir = join(projectRoot, "data", "patents", "epo-cn-pilot-v1");
const rawDir = join(outputDir, "raw");
const recordsPath = join(outputDir, "records.json");
const manifestPath = join(outputDir, "manifest.json");

const source = {
  name: "EPO Linked Open Data",
  websiteUrl: "https://data.epo.org/linked-data/",
  apiBaseUrl: "https://data.epo.org/linked-data/data",
  accessMode: "PUBLIC_LINKED_DATA_NO_AUTH",
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
    id: "EPO_LOD_CN_LIST",
    label: "CN publications list (EPO LOD public endpoint)",
    query: "GET /data/publication/CN (pages 0-2, 10 items per page)",
  },
];

const TARGET_PAGES = 3; // 30 条样本
const ITEMS_PER_PAGE = 10;

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function normalizeEpoDate(value) {
  // EPO LOD 返回 "Wed, 06 Nov 1996" 格式
  const text = String(value ?? "").trim();
  if (!text) return null;
  const match = text.match(/\w{3},\s+(\d{2})\s+(\w{3})\s+(\d{4})/);
  if (!match) return null;
  const [, day, mon, year] = match;
  const monthMap = {
    Jan: "01", Feb: "02", Mar: "03", Apr: "04", May: "05", Jun: "06",
    Jul: "07", Aug: "08", Sep: "09", Oct: "10", Nov: "11", Dec: "12",
  };
  const month = monthMap[mon];
  return month ? `${year}-${month}-${day}` : null;
}

function normalizeKind(value) {
  // publicationKind 可能是字符串 URL、对象 {label} 或字面值
  if (!value) return null;
  if (typeof value === "string") {
    const match = value.match(/publicationKind_([A-Z0-9]+)/);
    return match ? match[1] : value;
  }
  if (value.label) return String(value.label);
  return null;
}

function extractApplicationNumber(app) {
  if (!app) return null;
  return app.applicationNumber ? String(app.applicationNumber) : null;
}

function extractFilingDate(app) {
  if (!app?.filingDate) return null;
  return normalizeEpoDate(app.filingDate);
}

function extractApplicationKind(app) {
  if (!app?.applicationKind) return null;
  const kind = app.applicationKind;
  return kind.label ? String(kind.label) : null;
}

async function fetchJson(url) {
  const response = await fetch(url, {
    headers: { Accept: "application/json" },
    redirect: "follow",
    signal: AbortSignal.timeout(30_000),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 300)}`);
  }
  return { text, payload: JSON.parse(text), requestUrl: url, status: response.status };
}

function buildLabel(pubNumber, kind) {
  return kind ? `CN ${pubNumber} ${kind}` : `CN ${pubNumber}`;
}

function buildSourceRecordId(pubNumber, kind) {
  // 与 public-pilot-v1 保持一致：CN-104900909-A
  return kind ? `CN-${pubNumber}-${kind}` : `CN-${pubNumber}`;
}

async function fetchListPage(pageIndex) {
  const url = new URL("https://data.epo.org/linked-data/data/publication/CN");
  if (pageIndex > 0) url.searchParams.set("_page", String(pageIndex));
  return fetchJson(url.toString());
}

async function fetchDetail(aboutUrl) {
  // aboutUrl 形如 http://data.epo.org/linked-data/data/publication/CN/1134880/A/-
  return fetchJson(aboutUrl);
}

function normalizeListRecord(item, capturedAt) {
  const pubNumber = item.publicationNumber
    ? String(item.publicationNumber)
    : (item.label ? item.label.replace(/^CN\s+/, "").replace(/\s+[A-Z]$/, "") : null);
  if (!pubNumber) return null;
  const kind = normalizeKind(item.publicationKind);
  const label = item.label ? String(item.label) : buildLabel(pubNumber, kind);
  const pubDate = normalizeEpoDate(item.publicationDate);
  const sourceRecordId = buildSourceRecordId(pubNumber, kind);
  return {
    sourceRecordId,
    publicationNumber: label,
    publicationDate: pubDate,
    sourceVersion: `${sourceRecordId}@${pubDate || "UNKNOWN"}`,
    title: label, // LOD 不含标题，用 label 作占位
    titles: [], // LOD 不含多语言标题
    applicant: null, // LOD 不含申请人
    detailUrl: item._about,
    matchedQueryIds: ["EPO_LOD_CN_LIST"],
    capturedAt,
    abstractExcerpt: null,
    claimsExcerpt: null,
    cpcClassifications: [], // LOD 不含 CPC
    applicationNumber: extractApplicationNumber(item.application),
    filingDate: null, // 列表页不含 filingDate，详情页才有
    applicationKind: null,
    sourceLocators: ["document-root"],
    detailResponseSha256: null,
    expertLabel: null,
    boundaries: { ...boundaries },
  };
}

async function enrichWithDetail(record) {
  if (!record.detailUrl) return record;
  try {
    const { text, payload } = await fetchDetail(record.detailUrl);
    const topic = payload?.result?.primaryTopic ?? {};
    const app = topic.application ?? {};
    record.filingDate = extractFilingDate(app);
    record.applicationKind = extractApplicationKind(app);
    record.detailResponseSha256 = sha256(text);
    record.sourceLocators = ["document-root", "application"];
    return record;
  } catch (err) {
    // 详情失败不阻塞采集，保留列表数据
    return record;
  }
}

async function collect() {
  await mkdir(rawDir, { recursive: true });
  const capturedAt = new Date().toISOString();
  const records = [];
  const rawResponses = [];

  for (let page = 0; page < TARGET_PAGES; page++) {
    const { text, payload, requestUrl } = await fetchListPage(page);
    const rawFile = `raw/list-page-${page}.json`;
    await writeFile(join(outputDir, rawFile), `${JSON.stringify(payload, null, 2)}\n`, "utf8");
    rawResponses.push({
      queryId: "EPO_LOD_CN_LIST",
      page,
      requestUrl,
      file: rawFile,
      sha256: sha256(text),
      responseSha256: sha256(JSON.stringify(payload)),
      itemCount: payload?.result?.items?.length ?? 0,
    });

    const items = payload?.result?.items ?? [];
    for (const item of items) {
      const record = normalizeListRecord(item, capturedAt);
      if (record) records.push(record);
    }
  }

  // 详情增强：限速 1 秒 1 个，遵守 fair-use
  for (const record of records) {
    await enrichWithDetail(record);
    await new Promise((r) => setTimeout(r, 1000));
  }

  // 去重（同 sourceRecordId 保留首次出现）
  const seen = new Set();
  const dedupedRecords = records.filter((r) => {
    if (seen.has(r.sourceRecordId)) return false;
    seen.add(r.sourceRecordId);
    return true;
  });

  const recordsPayload = {
    schemaVersion: "1.0",
    datasetId: "CON-04-EPO-CN-PILOT-V1",
    generatedAt: capturedAt,
    source: { ...source },
    records: dedupedRecords,
  };
  const recordsText = `${JSON.stringify(recordsPayload, null, 2)}\n`;
  await writeFile(recordsPath, recordsText, "utf8");

  const manifest = {
    schemaVersion: "1.0",
    datasetId: "CON-04-EPO-CN-PILOT-V1",
    generatedAt: capturedAt,
    purpose: "CON-04 EPO public LOD feasibility pilot for CN publications",
    source: { ...source },
    boundaries: { ...boundaries },
    queries,
    recordCount: dedupedRecords.length,
    detailedRecordCount: dedupedRecords.filter((r) => r.detailResponseSha256).length,
    recordsFile: "records.json",
    recordsSha256: sha256(recordsText),
    rawResponses,
    fieldCoverage: {
      // LOD 公开端点可用字段
      available: ["publicationNumber", "publicationDate", "publicationKind", "applicationNumber", "filingDate", "applicationKind"],
      // 完整著录数据需要 OPS API（需注册凭证）
      unavailable: ["title", "applicant", "inventor", "cpcClassifications", "abstractExcerpt", "claimsExcerpt"],
      unavailableReason: "EPO LOD public endpoint exposes only publication-level metadata. Full bibliographic data (title, applicant, CPC, abstract, claims) requires Open Patent Services (OPS) API with OAuth credentials.",
    },
    limitations: [
      "LOD public endpoint provides publication-level metadata only; not full bibliographic records.",
      "Sample is the first N publications returned by the CN list endpoint, not a curated or relevance-ranked set.",
      "Titles are placeholder labels (e.g., 'CN 1134880 A'), not actual invention titles.",
      "Public accessibility does not establish permission for production indexing, retention, redistribution, or external model processing.",
      "This source is not the project's center authoritative patent system.",
      "Full bibliographic data and CPC classification require OPS API registration (consumer key/secret, 4 GB/week free tier).",
      "No PDF or full patent description is stored in this pilot dataset.",
    ],
  };
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");

  console.log(`EPO CN pilot collected: ${dedupedRecords.length} records (${manifest.detailedRecordCount} with detail).`);
  console.log(`Output: ${outputDir}`);
}

collect().catch((err) => {
  console.error("Collection failed:", err);
  process.exit(1);
});
