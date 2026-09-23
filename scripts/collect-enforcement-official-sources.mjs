import { createHash } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const root = resolve(dirname(scriptPath), "..");
const outputDir = join(root, "data", "enforcement", "source-review", "cnipa-official-v1");
const catalogPath = join(outputDir, "source-catalog.json");
const manifestPath = join(outputDir, "manifest.json");
const allowedOrigin = "https://www.cnipa.gov.cn";
const maxBytes = 25 * 1024 * 1024;
const verifyOnly = process.argv.includes("--verify-only");
const selfTest = process.argv.includes("--self-test");

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const delay = (milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));

function requireAllowedRedirect(location, current, origin = allowedOrigin) {
  const next = new URL(location, current);
  if (next.origin !== origin) throw new Error("cross-origin redirect rejected");
  return next;
}

function validateResponseBytes(target, contentType, contentDisposition, bytes) {
  if (bytes.length === 0 || bytes.length > maxBytes) throw new Error(`${target.id}: response size invalid`);
  const normalizedType = (contentType ?? "").toLowerCase();
  if (!target.expectedContentTypes.some((value) => normalizedType.startsWith(value))) {
    throw new Error(`${target.id}: unexpected content type ${normalizedType || "missing"}`);
  }
  if (target.expectedMagicHex && !bytes.subarray(0, target.expectedMagicHex.length / 2)
    .equals(Buffer.from(target.expectedMagicHex, "hex"))) {
    throw new Error(`${target.id}: attachment file signature mismatch`);
  }
  if (target.expectedMagicHex && !contentDisposition) throw new Error(`${target.id}: attachment disposition missing`);
}

function requireSuccessfulStatus(targetId, status) {
  if (status < 200 || status >= 300) throw new Error(`${targetId}: HTTP ${status}`);
}

function requireSameSnapshotIds(targets, snapshots) {
  const expected = [...targets.map((target) => target.id)].sort();
  const actual = [...snapshots.map((snapshot) => snapshot.id)].sort();
  if (JSON.stringify(expected) !== JSON.stringify(actual)) throw new Error("current source snapshot set mismatch");
}

function requireNoContentDrift(previous, catalogVersion, targetId, digest) {
  const prior = previous?.snapshots?.find((snapshot) => snapshot.id === targetId);
  if (prior && previous.catalogVersion === catalogVersion && prior.sha256 !== digest) {
    throw new Error(`${targetId}: SOURCE_CONTENT_DRIFT; create and review a new catalog version`);
  }
}

function validateCatalog(catalog) {
  const errors = [];
  if (catalog?.schemaVersion !== "enforcement-official-source-catalog-v1") errors.push("catalog schema mismatch");
  if (catalog?.origin !== allowedOrigin || catalog?.externalModelAllowed !== false) errors.push("catalog boundary mismatch");
  if (!catalog?.catalogId || !catalog?.catalogVersion || !catalog?.responsibleParty || !catalog?.authorizationRef) {
    errors.push("catalog identity or authorization missing");
  }
  const ids = new Set();
  const files = new Set();
  for (const target of catalog?.targets ?? []) {
    if (!target.id || ids.has(target.id)) errors.push(`duplicate or missing target id: ${target.id ?? "-"}`);
    ids.add(target.id);
    let url;
    try { url = new URL(target.url); } catch { errors.push(`${target.id}: invalid URL`); continue; }
    if (url.origin !== allowedOrigin || url.protocol !== "https:") errors.push(`${target.id}: URL is outside the official origin`);
    if (!url.pathname.startsWith("/art/") && url.pathname !== "/module/download/downfile.jsp") {
      errors.push(`${target.id}: URL path is not allowlisted`);
    }
    const resolvedFile = resolve(outputDir, target.file ?? "");
    if (!target.file?.startsWith("raw/") || !resolvedFile.startsWith(`${resolve(outputDir, "raw")}${sep}`)) {
      errors.push(`${target.id}: output path escapes the raw directory`);
    }
    if (files.has(target.file)) errors.push(`${target.id}: duplicate output file`);
    files.add(target.file);
    if (!Array.isArray(target.expectedContentTypes) || target.expectedContentTypes.length === 0) {
      errors.push(`${target.id}: expected content type missing`);
    }
    if (target.expectedMagicHex && !/^[0-9a-f]+$/i.test(target.expectedMagicHex)) {
      errors.push(`${target.id}: invalid expected file signature`);
    }
  }
  for (const target of catalog?.targets ?? []) {
    if (target.parentId && !ids.has(target.parentId)) errors.push(`${target.id}: parent target missing`);
  }
  if (!(catalog?.targets?.length > 0)) errors.push("catalog has no targets");
  if (errors.length) throw new Error(`Official source catalog invalid:\n- ${errors.join("\n- ")}`);
}

async function fetchAllowlisted(target) {
  let current = new URL(target.url);
  for (let redirects = 0; redirects <= 3; redirects += 1) {
    const response = await fetch(current, {
      headers: {
        Accept: target.expectedContentTypes.join(","),
        "User-Agent": "agent-platform-cnipa-source-review/1.0",
      },
      redirect: "manual",
      signal: AbortSignal.timeout(60_000),
    });
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get("location");
      if (!location) throw new Error(`${target.id}: redirect without location`);
      current = requireAllowedRedirect(location, current);
      continue;
    }
    requireSuccessfulStatus(target.id, response.status);
    const declaredLength = Number(response.headers.get("content-length") ?? 0);
    if (declaredLength > maxBytes) throw new Error(`${target.id}: response exceeds size limit`);
    const bytes = Buffer.from(await response.arrayBuffer());
    validateResponseBytes(target, response.headers.get("content-type"),
      response.headers.get("content-disposition"), bytes);
    return { response, bytes, finalUrl: current.toString() };
  }
  throw new Error(`${target.id}: too many redirects`);
}

function extractPageMetadata(target, bytes) {
  if (!target.expectedTitleContains) return { title: null, publishedAt: null };
  const html = bytes.toString("utf8");
  const title = html.match(/<title>([\s\S]*?)<\/title>/i)?.[1]?.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim() ?? null;
  const publishedAt = html.match(/<meta\s+name=['"]pubdate['"]\s+content=['"]([^'"]+)['"]/i)?.[1]
    ?? html.match(/<meta\s+name=['"]Maketime['"]\s+content=['"]([^'"]+)['"]/i)?.[1] ?? null;
  if (!title || !title.includes(target.expectedTitleContains)) throw new Error(`${target.id}: expected title not found`);
  return { title, publishedAt };
}

async function collect() {
  const catalogText = await readFile(catalogPath, "utf8");
  const catalog = JSON.parse(catalogText);
  validateCatalog(catalog);
  let previous = null;
  try { previous = JSON.parse(await readFile(manifestPath, "utf8")); } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
  const capturedAt = new Date().toISOString();
  const captures = [];
  for (const [index, target] of catalog.targets.entries()) {
    if (index > 0) await delay(1_000);
    const { response, bytes, finalUrl } = await fetchAllowlisted(target);
    const metadata = extractPageMetadata(target, bytes);
    const digest = sha256(bytes);
    requireNoContentDrift(previous, catalog.catalogVersion, target.id, digest);
    captures.push({
      target,
      bytes,
      snapshot: {
        id: target.id,
        category: target.category,
        requestedUrl: target.url,
        finalUrl,
        httpStatus: response.status,
        contentType: response.headers.get("content-type"),
        contentDisposition: response.headers.get("content-disposition"),
        etag: response.headers.get("etag"),
        lastModified: response.headers.get("last-modified"),
        capturedAt,
        file: target.file,
        bytes: bytes.length,
        sha256: digest,
        title: metadata.title,
        publishedAt: metadata.publishedAt,
        publicationStatus: target.publicationStatus,
        parentId: target.parentId,
      },
    });
  }
  await mkdir(join(outputDir, "raw"), { recursive: true });
  for (const capture of captures) {
    const destination = join(outputDir, capture.target.file);
    const temporary = `${destination}.tmp`;
    await writeFile(temporary, capture.bytes);
    await rename(temporary, destination);
  }
  const snapshots = captures.map((capture) => capture.snapshot);
  const manifest = {
    schemaVersion: "enforcement-official-source-review-v1",
    reviewId: "ENF-SOURCE-REVIEW-CNIPA-OFFICIAL-V1",
    catalogId: catalog.catalogId,
    catalogVersion: catalog.catalogVersion,
    catalogSha256: sha256(catalogText),
    capturedAt,
    source: { provider: catalog.provider, origin: catalog.origin, discoveryPage: catalog.discoveryPage },
    authorization: {
      responsibleParty: catalog.responsibleParty,
      authorizationRef: catalog.authorizationRef,
      allowedUse: catalog.allowedUse,
      withdrawalProcess: catalog.withdrawalProcess,
    },
    boundaries: {
      reviewStatus: "REVIEW_REQUIRED",
      approvalStatus: "NOT_RECORD_REVIEWED",
      dm8ImportAllowed: false,
      formalPackageConversionAllowed: false,
      externalModelAllowed: false,
      automaticEvidenceAcceptanceAllowed: false,
      automaticLegalDecisionAllowed: false,
    },
    snapshots,
    batchSha256: sha256(snapshots.map((snapshot) => `${snapshot.id}:${snapshot.sha256}`).sort().join("\n")),
  };
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

async function verify() {
  const [catalogText, manifestText] = await Promise.all([readFile(catalogPath, "utf8"), readFile(manifestPath, "utf8")]);
  const catalog = JSON.parse(catalogText);
  const manifest = JSON.parse(manifestText);
  validateCatalog(catalog);
  const errors = [];
  if (manifest.catalogId !== catalog.catalogId || manifest.catalogVersion !== catalog.catalogVersion) errors.push("catalog identity mismatch");
  if (manifest.catalogSha256 !== sha256(catalogText)) errors.push("catalog digest mismatch");
  const boundaries = manifest.boundaries ?? {};
  if (boundaries.reviewStatus !== "REVIEW_REQUIRED" || boundaries.approvalStatus !== "NOT_RECORD_REVIEWED"
      || boundaries.dm8ImportAllowed !== false || boundaries.formalPackageConversionAllowed !== false
      || boundaries.externalModelAllowed !== false || boundaries.automaticEvidenceAcceptanceAllowed !== false
      || boundaries.automaticLegalDecisionAllowed !== false) errors.push("review boundary changed");
  const byId = new Map((manifest.snapshots ?? []).map((snapshot) => [snapshot.id, snapshot]));
  try { requireSameSnapshotIds(catalog.targets, manifest.snapshots ?? []); }
  catch { errors.push("current source snapshot set mismatch"); }
  for (const target of catalog.targets) {
    const snapshot = byId.get(target.id);
    if (!snapshot || snapshot.requestedUrl !== target.url || snapshot.file !== target.file
        || snapshot.category !== target.category || snapshot.publicationStatus !== target.publicationStatus
        || snapshot.parentId !== target.parentId || snapshot.httpStatus !== 200) {
      errors.push(`${target.id}: snapshot trace mismatch`);
      continue;
    }
    try {
      const bytes = await readFile(join(outputDir, target.file));
      if (bytes.length !== snapshot.bytes || sha256(bytes) !== snapshot.sha256) errors.push(`${target.id}: file digest mismatch`);
      if (target.expectedTitleContains && !snapshot.title?.includes(target.expectedTitleContains)) errors.push(`${target.id}: title trace mismatch`);
      if (target.expectedMagicHex && !bytes.subarray(0, target.expectedMagicHex.length / 2)
        .equals(Buffer.from(target.expectedMagicHex, "hex"))) errors.push(`${target.id}: attachment signature mismatch`);
      if (target.expectedMagicHex && !snapshot.contentDisposition) errors.push(`${target.id}: attachment disposition trace missing`);
    } catch { errors.push(`${target.id}: captured file missing`); }
  }
  if (byId.size !== catalog.targets.length) errors.push("snapshot count mismatch");
  const batchSha256 = sha256((manifest.snapshots ?? []).map((snapshot) => `${snapshot.id}:${snapshot.sha256}`).sort().join("\n"));
  if (manifest.batchSha256 !== batchSha256) errors.push("batch digest mismatch");
  if (errors.length) throw new Error(`Official source snapshot verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`CNIPA official enforcement source snapshots verified: ${catalog.targets.length} files, REVIEW_REQUIRED, batch ${manifest.batchSha256}`);
}

async function runSelfTest() {
  const base = {
    schemaVersion: "enforcement-official-source-catalog-v1", catalogId: "T", catalogVersion: "1",
    origin: allowedOrigin, responsibleParty: "R", authorizationRef: "A", externalModelAllowed: false,
    targets: [{ id: "A", url: `${allowedOrigin}/art/2024/1/1/art_1_1.html`, file: "raw/a.html",
      expectedContentTypes: ["text/html"], parentId: null }],
  };
  validateCatalog(base);
  for (const mutation of [
    (value) => { value.targets[0].url = "https://example.com/art/1"; },
    (value) => { value.targets[0].file = "../escape.html"; },
    (value) => { value.targets.push({ ...value.targets[0] }); },
    (value) => { value.targets[0].parentId = "MISSING"; },
  ]) {
    const changed = structuredClone(base); mutation(changed);
    let rejected = false;
    try { validateCatalog(changed); } catch { rejected = true; }
    if (!rejected) throw new Error("Official source collector self-test failed to reject an unsafe catalog");
  }
  const attachment = { id: "ATTACHMENT", expectedContentTypes: ["text/html"], expectedMagicHex: "25504446" };
  validateResponseBytes(attachment, "text/html;charset=utf-8", "attachment; filename=a.pdf", Buffer.from("%PDF-test"));
  for (const action of [
    () => requireAllowedRedirect("https://example.com/file", `${allowedOrigin}/art/1`),
    () => validateResponseBytes(attachment, "text/html", "attachment; filename=a.pdf", Buffer.from("<html>error")),
    () => validateResponseBytes({ ...attachment, expectedContentTypes: ["application/pdf"] }, "text/html", "attachment", Buffer.from("%PDF-test")),
    () => validateResponseBytes(attachment, "text/html", "attachment", Buffer.alloc(0)),
    () => validateResponseBytes(attachment, "text/html", "attachment", { length: maxBytes + 1 }),
    () => requireSuccessfulStatus("A", 404),
    () => requireSuccessfulStatus("A", 429),
    () => requireSameSnapshotIds([{ id: "A" }], []),
    () => requireNoContentDrift({ catalogVersion: "1", snapshots: [{ id: "A", sha256: "old" }] }, "1", "A", "new"),
  ]) {
    let rejected = false;
    try { action(); } catch { rejected = true; }
    if (!rejected) throw new Error("Official source collector self-test failed to reject an unsafe response or drift");
  }
  const source = await readFile(scriptPath, "utf8");
  const imports = [...source.matchAll(/from\s+"([^"]+)"/g)].map((match) => match[1]);
  if (imports.some((value) => !value.startsWith("node:"))) {
    throw new Error("Official source collector must depend only on Node built-ins");
  }
  console.log("CNIPA official source collector self-test passed: catalog, redirect, type, signature and drift guards");
}

if (selfTest) await runSelfTest();
else {
  if (!verifyOnly) await collect();
  await verify();
}
