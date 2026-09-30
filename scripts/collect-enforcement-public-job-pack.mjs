import { createHash } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const root = resolve(dirname(scriptPath), "..");
const outputDir = join(root, "data", "enforcement", "public-job-pack-candidates-v1");
const catalogPath = join(outputDir, "source-catalog.json");
const manifestPath = join(outputDir, "manifest.json");
const candidatesPath = join(outputDir, "candidates.json");
const attemptsPath = join(outputDir, "collection-attempts.json");
const allowedOrigin = "https://www.cnipa.gov.cn";
const maxBytes = 5 * 1024 * 1024;
const verifyOnly = process.argv.includes("--verify-only");
const selfTest = process.argv.includes("--self-test");

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const delay = (milliseconds) => new Promise((done) => setTimeout(done, milliseconds));
const normalizeText = (value) => value.replace(/\s+/g, " ").trim();

function decodeHtml(value) {
  return value
    .replace(/&nbsp;|&#160;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, "\"")
    .replace(/&#39;|&apos;/gi, "'");
}

function requireSameOrigin(location, current) {
  const next = new URL(location, current);
  if (next.origin !== allowedOrigin || next.protocol !== "https:") {
    throw new Error("cross-origin or non-HTTPS redirect rejected");
  }
  return next;
}

function validateCatalog(catalog) {
  const errors = [];
  if (catalog?.schemaVersion !== "enforcement-public-job-source-catalog-v2") errors.push("schema mismatch");
  if (catalog?.origin !== allowedOrigin || catalog?.trustLevel !== "OFFICIAL_GOVERNMENT_PRIMARY") errors.push("source boundary mismatch");
  if (!catalog?.catalogId || !catalog?.catalogVersion || !catalog?.provider) errors.push("catalog identity missing");
  if (!(catalog?.queryStrategy?.queries?.length > 0) || !(catalog?.queryStrategy?.selectionRules?.length > 0)) {
    errors.push("query strategy missing");
  }
  const ids = new Set();
  const files = new Set();
  for (const source of catalog?.sources ?? []) {
    if (!source.id || ids.has(source.id)) errors.push(`${source.id ?? "-"}: duplicate or missing id`);
    ids.add(source.id);
    let url;
    try { url = new URL(source.url); } catch { errors.push(`${source.id}: invalid URL`); continue; }
    if (url.origin !== allowedOrigin || url.protocol !== "https:" || !url.pathname.startsWith("/art/")) {
      errors.push(`${source.id}: URL outside official allowlist`);
    }
    const resolvedFile = resolve(outputDir, source.file ?? "");
    if (!source.file?.startsWith("raw/") || !resolvedFile.startsWith(`${resolve(outputDir, "raw")}${sep}`)) {
      errors.push(`${source.id}: raw path invalid`);
    }
    if (files.has(source.file)) errors.push(`${source.id}: duplicate raw path`);
    files.add(source.file);
    if (!source.expectedTitleContains || !source.sourceType || !source.scenarioClass
      || !source.evidenceCompleteness || !(source.mappedRequirements?.length > 0)) errors.push(`${source.id}: mapping incomplete`);
    if (source.mappedRequirements?.some((id) => !/^ENF-(0[1-9]|1[0-7])$/.test(id))) errors.push(`${source.id}: invalid ENF mapping`);
    if (source.collectionPolicy !== "PUBLIC_ACCESS_PROJECT_COLLECTION_V1") errors.push(`${source.id}: collection policy missing`);
  }
  if (!(catalog?.sources?.length > 0)) errors.push("no sources");
  if (errors.length) throw new Error(`Public job source catalog invalid:\n- ${errors.join("\n- ")}`);
}

function validateResponse(source, response, bytes) {
  if (response.status < 200 || response.status >= 300) throw new Error(`HTTP ${response.status}`);
  const type = (response.headers.get("content-type") ?? "").toLowerCase();
  if (!type.startsWith("text/html")) throw new Error(`unexpected content type ${type || "missing"}`);
  if (bytes.length === 0 || bytes.length > maxBytes) throw new Error(`response size ${bytes.length} invalid`);
  const prefix = bytes.subarray(0, Math.min(bytes.length, 1024)).toString("utf8").toLowerCase();
  if (!prefix.includes("<html") && !prefix.includes("<!doctype html")) throw new Error("response is not HTML");
  if (prefix.includes("captcha") || prefix.includes("access denied")) throw new Error("challenge or error page rejected");
  if (!source.expectedTitleContains) throw new Error("expected title missing from catalog");
}

function metaContent(html, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const direct = html.match(new RegExp(`<meta\\s+name=["']${escaped}["']\\s+content=["']([^"']*)["']`, "i"));
  const reversed = html.match(new RegExp(`<meta\\s+content=["']([^"']*)["']\\s+name=["']${escaped}["']`, "i"));
  return decodeHtml(direct?.[1] ?? reversed?.[1] ?? "") || null;
}

function parseOfficialPage(source, bytes) {
  const html = bytes.toString("utf8");
  const title = metaContent(html, "ArticleTitle")
    ?? decodeHtml(html.match(/<title>([\s\S]*?)<\/title>/i)?.[1] ?? "");
  const publishedAt = metaContent(html, "pubdate")
    ?? html.match(/发布时间[：:]\s*(20\d{2}-\d{2}-\d{2})/)?.[1] ?? null;
  const content = html.match(/<meta\s+name=["']ContentStart["']\s*\/?>([\s\S]*?)<meta\s+name=["']ContentEnd["']\s*\/?>/i)?.[1] ?? "";
  const plainText = normalizeText(decodeHtml(content
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")));
  if (!title.includes(source.expectedTitleContains)) throw new Error(`${source.id}: expected official title not found`);
  if (!publishedAt || !/^20\d{2}-\d{2}-\d{2}/.test(publishedAt)) throw new Error(`${source.id}: publication date missing`);
  if (plainText.length < 5) throw new Error(`${source.id}: official content block is empty`);
  return {
    title: normalizeText(title),
    publishedAt,
    plainText,
    plainTextSha256: sha256(Buffer.from(plainText, "utf8")),
    locator: "meta[name=ContentStart]..meta[name=ContentEnd]",
    excerpt: plainText.slice(0, 240),
  };
}

async function fetchOfficial(source, attempts, batchId) {
  let lastError;
  for (let retry = 0; retry < 2; retry += 1) {
    let current = new URL(source.url);
    try {
      for (let redirects = 0; redirects <= 3; redirects += 1) {
        const startedAt = new Date().toISOString();
        const response = await fetch(current, {
          headers: { Accept: "text/html", "User-Agent": "agent-platform-enforcement-public-job-pack/1.0" },
          redirect: "manual",
          signal: AbortSignal.timeout(30_000),
        });
        if (response.status >= 300 && response.status < 400) {
          const location = response.headers.get("location");
          if (!location) throw new Error("redirect without location");
          current = requireSameOrigin(location, current);
          continue;
        }
        const bytes = Buffer.from(await response.arrayBuffer());
        validateResponse(source, response, bytes);
        attempts.push({ batchId, sourceId: source.id, retry, startedAt, finishedAt: new Date().toISOString(),
          requestedUrl: source.url, finalUrl: current.toString(), httpStatus: response.status, result: "SUCCEEDED" });
        return { response, bytes, finalUrl: current.toString() };
      }
      throw new Error("too many redirects");
    } catch (error) {
      lastError = error;
      attempts.push({ batchId, sourceId: source.id, retry, startedAt: new Date().toISOString(),
        finishedAt: new Date().toISOString(), requestedUrl: source.url, finalUrl: current.toString(),
        httpStatus: null, result: "FAILED", error: String(error?.message ?? error) });
      if (retry === 0) await delay(500);
    }
  }
  throw new Error(`${source.id}: ${lastError?.message ?? "collection failed"}`);
}

function assignDuplicates(snapshots) {
  const firstByDigest = new Map();
  return snapshots.map((snapshot) => {
    const duplicateOf = firstByDigest.get(snapshot.sha256) ?? null;
    if (!duplicateOf) firstByDigest.set(snapshot.sha256, snapshot.sourceId);
    return { ...snapshot, duplicateOf };
  });
}

function makeCandidate(source, snapshot, batchId) {
  const opinionSource = new Set(["OFFICIAL_GUIDANCE_EXPLANATION", "OFFICIAL_PROCEDURE_REPLY", "OFFICIAL_RULE_REFERENCE"])
    .has(source.sourceType);
  return {
    candidateId: `ENF-PUBLIC-${source.id}`,
    version: 2,
    collectionBatchId: batchId,
    state: "WAITING_REAL_BUSINESS_FACTS",
    publicDataState: "PUBLIC_DATA_READY",
    scenarioClass: source.scenarioClass,
    sourceType: source.sourceType,
    evidenceCompleteness: source.evidenceCompleteness,
    mappedRequirements: source.mappedRequirements,
    publicSourceFacts: {
      sourceId: source.id,
      sourceName: "国家知识产权局",
      originalUrl: source.url,
      title: snapshot.title,
      publishedAt: snapshot.publishedAt,
      capturedAt: snapshot.capturedAt,
      rawFile: snapshot.file,
      rawSha256: snapshot.sha256,
      contentLocator: snapshot.contentLocator,
      plainTextSha256: snapshot.plainTextSha256,
      trustLevel: "OFFICIAL_GOVERNMENT_PRIMARY",
      verificationStatus: "PUBLIC_SOURCE_FACT_VERIFIED",
    },
    collection: {
      decision: "COLLECTION_ALLOWED",
      allowed: true,
      policy: source.collectionPolicy,
      method: "HTTPS_GET_OFFICIAL_SAME_ORIGIN_ALLOWLIST",
      basis: {
        publicAccess: true,
        traceableOfficialUrl: true,
        successfulHttpResponse: snapshot.httpStatus === 200,
        rawContentSaved: true,
        sha256Verified: true,
        prohibitedConditionDetected: false,
      },
    },
    rights: {
      copyrightLicense: "NOT_ASSERTED",
      commercialLicense: "NOT_ASSERTED",
      legalRights: "NOT_ASSERTED",
      collectionAllowedDoesNotImplyLegalRights: true,
    },
    publicBusinessFacts: {
      realPublicTask: { status: "VERIFIED", value: snapshot.title, locator: "meta[name=ArticleTitle]" },
      publicOrganization: { status: "VERIFIED", value: "国家知识产权局", locator: "meta[name=SiteName]" },
      publicIdentityOrRole: { status: "NOT_EXTRACTED", value: null, locator: null },
      publicHandlingResult: { status: "LOCATABLE_IN_ORIGINAL", value: null, locator: snapshot.contentLocator },
      publicEvaluationOrOpinion: opinionSource
        ? { status: "LOCATABLE_IN_ORIGINAL", value: null, locator: snapshot.contentLocator }
        : { status: "NOT_PRESENT_OR_NOT_EXTRACTED", value: null, locator: null },
    },
    businessRealityFacts: {
      currentOperator: { status: "MISSING", value: null },
      currentRoleAuthorization: { status: "MISSING", value: null },
      actualHumanApproval: { status: "MISSING", value: null },
      formalHumanReceipt: { status: "MISSING", value: null },
      businessEvaluation: { status: "MISSING", value: null },
    },
    workflow: { taskPackPublicInputAllowed: true, readyAllowed: false, integrationTriggerAllowed: false },
    gaps: ["CURRENT_OPERATOR", "CURRENT_ROLE_AUTHORIZATION", "ACTUAL_HUMAN_APPROVAL", "FORMAL_HUMAN_RECEIPT", "BUSINESS_EVALUATION"],
  };
}

function validateCandidateSet(catalog, manifest, candidates) {
  const errors = [];
  if (manifest?.schemaVersion !== "enforcement-public-job-snapshot-v2") errors.push("manifest schema mismatch");
  if (candidates?.schemaVersion !== "enforcement-public-job-candidates-v2") errors.push("candidate schema mismatch");
  if (manifest.catalogId !== catalog.catalogId || manifest.catalogVersion !== catalog.catalogVersion) errors.push("catalog identity mismatch");
  if (manifest.catalogSha256 !== candidates.catalogSha256 || manifest.catalogSha256 !== catalog.__sha256) errors.push("catalog digest mismatch");
  if (manifest.collectionBatchId !== candidates.collectionBatchId) errors.push("batch identity mismatch");
  const sourceById = new Map(catalog.sources.map((source) => [source.id, source]));
  const snapshotById = new Map((manifest.snapshots ?? []).map((snapshot) => [snapshot.sourceId, snapshot]));
  if (snapshotById.size !== catalog.sources.length || candidates.candidates?.length !== catalog.sources.length) errors.push("source/candidate count mismatch");
  for (const candidate of candidates.candidates ?? []) {
    const source = sourceById.get(candidate.publicSourceFacts?.sourceId);
    const snapshot = snapshotById.get(candidate.publicSourceFacts?.sourceId);
    if (!source || !snapshot) { errors.push(`${candidate.candidateId}: source snapshot missing`); continue; }
    if (candidate.publicSourceFacts.rawFile !== snapshot.file || candidate.publicSourceFacts.rawSha256 !== snapshot.sha256
      || candidate.publicSourceFacts.originalUrl !== source.url || candidate.publicSourceFacts.title !== snapshot.title
      || candidate.publicSourceFacts.publishedAt !== snapshot.publishedAt) errors.push(`${candidate.candidateId}: public fact trace mismatch`);
    const basis = candidate.collection?.basis ?? {};
    if (candidate.collection?.decision !== "COLLECTION_ALLOWED" || candidate.collection?.allowed !== true
      || candidate.collection?.policy !== source.collectionPolicy || !basis.publicAccess || !basis.traceableOfficialUrl
      || !basis.successfulHttpResponse || !basis.rawContentSaved || !basis.sha256Verified || basis.prohibitedConditionDetected !== false) {
      errors.push(`${candidate.candidateId}: collection decision is unsupported`);
    }
    if (candidate.state !== "WAITING_REAL_BUSINESS_FACTS" || candidate.publicDataState !== "PUBLIC_DATA_READY"
      || candidate.workflow?.taskPackPublicInputAllowed !== true || candidate.workflow?.readyAllowed !== false
      || candidate.workflow?.integrationTriggerAllowed !== false) errors.push(`${candidate.candidateId}: public/internal state boundary violated`);
    if (candidate.rights?.copyrightLicense !== "NOT_ASSERTED" || candidate.rights?.commercialLicense !== "NOT_ASSERTED"
      || candidate.rights?.legalRights !== "NOT_ASSERTED" || candidate.rights?.collectionAllowedDoesNotImplyLegalRights !== true) {
      errors.push(`${candidate.candidateId}: collection decision was promoted to legal rights`);
    }
    for (const [name, fact] of Object.entries(candidate.businessRealityFacts ?? {})) {
      if (fact?.status !== "MISSING" || fact?.value !== null) errors.push(`${candidate.candidateId}: public source inferred ${name}`);
    }
  }
  if (manifest.snapshots?.some((snapshot) => !snapshot.file || !snapshot.sha256 || !snapshot.title || !snapshot.publishedAt
    || !snapshot.contentLocator || !snapshot.plainTextSha256)) errors.push("snapshot marked complete without evidence fields");
  if (errors.length) throw new Error(`Public job candidate verification failed:\n- ${errors.join("\n- ")}`);
}

async function collect() {
  const catalogText = await readFile(catalogPath, "utf8");
  const catalog = JSON.parse(catalogText);
  validateCatalog(catalog);
  const catalogDigest = sha256(Buffer.from(catalogText, "utf8"));
  let previous = null;
  try { previous = JSON.parse(await readFile(manifestPath, "utf8")); } catch (error) { if (error?.code !== "ENOENT") throw error; }
  const batchId = `ENF-PUBLIC-COLLECT-${new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14)}`;
  const capturedAt = new Date().toISOString();
  const attempts = [];
  const captures = [];
  for (const [index, source] of catalog.sources.entries()) {
    if (index > 0) await delay(250);
    const { response, bytes, finalUrl } = await fetchOfficial(source, attempts, batchId);
    const parsed = parseOfficialPage(source, bytes);
    const digest = sha256(bytes);
    const prior = previous?.snapshots?.find((item) => item.sourceId === source.id);
    if (prior && previous.catalogVersion === catalog.catalogVersion && prior.sha256 !== digest) {
      throw new Error(`${source.id}: SOURCE_CONTENT_DRIFT; bump and review catalogVersion`);
    }
    captures.push({ source, bytes, snapshot: {
      sourceId: source.id, requestedUrl: source.url, finalUrl, sourceName: catalog.provider,
      sourceType: source.sourceType, trustLevel: catalog.trustLevel, httpStatus: response.status,
      contentType: response.headers.get("content-type"), etag: response.headers.get("etag"),
      lastModified: response.headers.get("last-modified"), capturedAt, file: source.file,
      bytes: bytes.length, sha256: digest, title: parsed.title, publishedAt: parsed.publishedAt,
      contentLocator: parsed.locator, plainTextSha256: parsed.plainTextSha256, excerpt: parsed.excerpt,
    } });
  }
  await mkdir(join(outputDir, "raw"), { recursive: true });
  for (const capture of captures) {
    const destination = join(outputDir, capture.source.file);
    const temporary = `${destination}.tmp`;
    await writeFile(temporary, capture.bytes);
    await rename(temporary, destination);
  }
  const snapshots = assignDuplicates(captures.map((capture) => capture.snapshot));
  const manifest = {
    schemaVersion: "enforcement-public-job-snapshot-v2", collectionBatchId: batchId,
    catalogId: catalog.catalogId, catalogVersion: catalog.catalogVersion, catalogSha256: catalogDigest,
    capturedAt, queryStrategy: catalog.queryStrategy, sourceCount: snapshots.length,
    uniqueContentCount: new Set(snapshots.map((snapshot) => snapshot.sha256)).size,
    boundaries: { publicFactsOnly: true, historicalUpgradeAllowed: false, publicCandidateInputAllowed: true,
      productBusinessTableWriteAllowed: false, internalFactsMayBeInferred: false, automaticIntegrationTriggerAllowed: false,
      collectionAllowedDoesNotAssertCopyrightCommercialOrLegalRights: true },
    snapshots,
    batchSha256: sha256(snapshots.map((item) => `${item.sourceId}:${item.sha256}`).sort().join("\n")),
  };
  const candidates = {
    schemaVersion: "enforcement-public-job-candidates-v2", collectionBatchId: batchId,
    catalogSha256: catalogDigest, candidateCount: snapshots.length,
    statusCounts: { COLLECTION_ALLOWED: catalog.sources.length, PUBLIC_DATA_READY: catalog.sources.length,
      WAITING_REAL_BUSINESS_FACTS: catalog.sources.length, READY: 0 },
    candidates: catalog.sources.map((source) => makeCandidate(source, snapshots.find((item) => item.sourceId === source.id), batchId)),
  };
  const attemptsDocument = { schemaVersion: "enforcement-public-job-collection-attempts-v2", collectionBatchId: batchId,
    catalogSha256: catalogDigest, queryStrategy: catalog.queryStrategy, attempts };
  await Promise.all([
    writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8"),
    writeFile(candidatesPath, `${JSON.stringify(candidates, null, 2)}\n`, "utf8"),
    writeFile(attemptsPath, `${JSON.stringify(attemptsDocument, null, 2)}\n`, "utf8"),
  ]);
}

async function verify() {
  const [catalogText, manifestText, candidatesText, attemptsText] = await Promise.all([
    readFile(catalogPath, "utf8"), readFile(manifestPath, "utf8"), readFile(candidatesPath, "utf8"), readFile(attemptsPath, "utf8"),
  ]);
  const catalog = JSON.parse(catalogText);
  validateCatalog(catalog);
  catalog.__sha256 = sha256(Buffer.from(catalogText, "utf8"));
  const manifest = JSON.parse(manifestText);
  const candidates = JSON.parse(candidatesText);
  const attempts = JSON.parse(attemptsText);
  validateCandidateSet(catalog, manifest, candidates);
  const errors = [];
  for (const snapshot of manifest.snapshots) {
    try {
      const bytes = await readFile(join(outputDir, snapshot.file));
      if (bytes.length !== snapshot.bytes || sha256(bytes) !== snapshot.sha256) errors.push(`${snapshot.sourceId}: raw digest mismatch`);
      if (parseOfficialPage(catalog.sources.find((source) => source.id === snapshot.sourceId), bytes).plainTextSha256 !== snapshot.plainTextSha256) {
        errors.push(`${snapshot.sourceId}: parsed text drift`);
      }
    } catch { errors.push(`${snapshot.sourceId}: raw file missing or invalid`); }
  }
  const expectedBatch = sha256(manifest.snapshots.map((item) => `${item.sourceId}:${item.sha256}`).sort().join("\n"));
  if (manifest.batchSha256 !== expectedBatch) errors.push("batch digest mismatch");
  if (attempts.collectionBatchId !== manifest.collectionBatchId || attempts.catalogSha256 !== catalog.__sha256) errors.push("attempt evidence mismatch");
  for (const source of catalog.sources) {
    if (!attempts.attempts?.some((attempt) => attempt.sourceId === source.id && attempt.result === "SUCCEEDED" && attempt.httpStatus === 200)) {
      errors.push(`${source.id}: successful collection attempt missing`);
    }
  }
  if (errors.length) throw new Error(`Public job package file verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`Enforcement public job candidates verified: ${candidates.candidateCount} real official pages, ${manifest.uniqueContentCount} unique, ${candidates.statusCounts.COLLECTION_ALLOWED} COLLECTION_ALLOWED, ${candidates.statusCounts.WAITING_REAL_BUSINESS_FACTS} WAITING_REAL_BUSINESS_FACTS, 0 integration triggers`);
}

async function runSelfTest() {
  const source = { id: "A", url: `${allowedOrigin}/art/2026/1/1/art_1_1.html`, file: "raw/a.html",
    sourceType: "OFFICIAL_TEST", scenarioClass: "NORMAL", evidenceCompleteness: "PARTIAL",
    expectedTitleContains: "Official", mappedRequirements: ["ENF-01"], collectionPolicy: "PUBLIC_ACCESS_PROJECT_COLLECTION_V1" };
  const baseCatalog = { schemaVersion: "enforcement-public-job-source-catalog-v2", catalogId: "T", catalogVersion: "1",
    provider: "P", origin: allowedOrigin, trustLevel: "OFFICIAL_GOVERNMENT_PRIMARY",
    queryStrategy: { queries: ["q"], selectionRules: ["r"] }, sources: [source] };
  validateCatalog(baseCatalog);
  for (const mutation of [
    (value) => { value.sources[0].url = "https://example.com/art/1"; },
    (value) => { value.sources[0].file = "../escape.html"; },
    (value) => { value.sources[0].collectionPolicy = null; },
    (value) => { value.sources.push({ ...value.sources[0] }); },
  ]) {
    const changed = structuredClone(baseCatalog); mutation(changed);
    let rejected = false; try { validateCatalog(changed); } catch { rejected = true; }
    if (!rejected) throw new Error("self-test failed to reject unsafe catalog");
  }
  let redirectRejected = false;
  try { requireSameOrigin("https://example.com/a", source.url); } catch { redirectRejected = true; }
  if (!redirectRejected) throw new Error("self-test failed to reject cross-origin redirect");
  const headers = new Headers({ "content-type": "application/json" });
  let typeRejected = false;
  try { validateResponse(source, { status: 200, headers }, Buffer.from("{}")); } catch { typeRejected = true; }
  if (!typeRejected) throw new Error("self-test failed to reject false content type");
  const duplicateSnapshots = assignDuplicates([{ sourceId: "A", sha256: "x" }, { sourceId: "B", sha256: "x" }]);
  if (duplicateSnapshots[1].duplicateOf !== "A") throw new Error("self-test failed to identify duplicate bytes");
  const catalog = structuredClone(baseCatalog);
  catalog.__sha256 = "digest";
  const snapshot = { sourceId: "A", file: "raw/a.html", sha256: "sha", title: "Official", publishedAt: "2026-01-01", httpStatus: 200,
    capturedAt: "2026-01-01T00:00:00Z", contentLocator: "locator", plainTextSha256: "plain" };
  const manifest = { schemaVersion: "enforcement-public-job-snapshot-v2", collectionBatchId: "B", catalogId: "T",
    catalogVersion: "1", catalogSha256: "digest", snapshots: [snapshot] };
  const candidate = makeCandidate(source, snapshot, "B");
  const document = { schemaVersion: "enforcement-public-job-candidates-v2", collectionBatchId: "B", catalogSha256: "digest", candidates: [candidate] };
  validateCandidateSet(catalog, manifest, document);
  for (const mutation of [
    (value) => { value.candidates[0].publicSourceFacts.rawSha256 = "tampered"; },
    (value) => { value.candidates[0].rights.legalRights = "CONFIRMED"; },
    (value) => { value.candidates[0].collection.basis.sha256Verified = false; },
    (value) => { value.candidates[0].state = "READY"; },
    (value) => { value.candidates[0].businessRealityFacts.currentOperator = { status: "VERIFIED", value: "public person" }; },
    (value) => { value.candidates[0].publicSourceFacts.rawFile = null; },
  ]) {
    const changed = structuredClone(document); mutation(changed);
    let rejected = false; try { validateCandidateSet(catalog, manifest, changed); } catch { rejected = true; }
    if (!rejected) throw new Error("self-test failed to reject tamper, false rights, unsupported collection, premature READY, inferred identity, or missing raw trace");
  }
  const imports = [...(await readFile(scriptPath, "utf8")).matchAll(/from\s+"([^"]+)"/g)].map((match) => match[1]);
  if (imports.some((value) => !value.startsWith("node:"))) throw new Error("collector must use Node built-ins only");
  console.log("Enforcement public job collector self-test passed: URL, type, digest, duplicate, collection, rights, identity, READY and raw-evidence guards");
}

if (selfTest) await runSelfTest();
else {
  if (!verifyOnly) await collect();
  await verify();
}
