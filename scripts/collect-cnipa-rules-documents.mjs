import { createHash } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const directory = join(root, "data", "shared", "source-review", "cnipa-rules-documents-v1");
const catalogPath = join(directory, "catalog.json");
const manifestPath = join(directory, "manifest.json");
const maxBytes = 12 * 1024 * 1024;
const origin = "https://www.cnipa.gov.cn";
const verifyOnly = process.argv.includes("--verify-only");
const selfTest = process.argv.includes("--self-test");
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
const forbiddenUses = ["businessRuleImportAllowed", "formalTemplateImportAllowed",
  "caseEvidenceAllowed", "externalModelAllowed", "formalDecisionAllowed"];

function requireNoDrift(previous, catalogVersion, id, digest) {
  const prior = previous?.snapshots?.find((item) => item.id === id);
  if (prior && previous.catalogVersion === catalogVersion && prior.sha256 !== digest) {
    throw new Error(`${id}: SOURCE_CONTENT_DRIFT; review a new catalog version`);
  }
}

function sameOriginRedirect(location, current, id) {
  const next = new URL(location, current);
  if (next.origin !== origin) throw new Error(`${id}: cross-origin redirect`);
  return next;
}

function validateCatalog(catalog) {
  if (catalog.schemaVersion !== "cnipa-rules-documents-source-catalog-v1"
      || catalog.origin !== origin || catalog.reviewStatus !== "REVIEW_REQUIRED"
      || !catalog.catalogId || !catalog.catalogVersion || !catalog.authorizationRef
      || !Array.isArray(catalog.targets) || catalog.targets.length !== 4) {
    throw new Error("catalog identity or review boundary invalid");
  }
  const ids = new Set();
  const files = new Set();
  for (const target of catalog.targets) {
    const url = new URL(target.url);
    const path = resolve(directory, target.file ?? "");
    if (!target.id || ids.has(target.id) || files.has(target.file)
        || url.origin !== origin || url.protocol !== "https:"
        || (!url.pathname.startsWith("/art/") && url.pathname !== "/module/download/downfile.jsp")
        || !path.startsWith(resolve(directory, "raw") + sep)
        || !["text/html", "application/pdf"].includes(target.contentType)
        || (target.contentType === "text/html" && !target.marker)) {
      throw new Error(`invalid target: ${target.id ?? "missing"}`);
    }
    ids.add(target.id);
    files.add(target.file);
    if (target.parentUrl && new URL(target.parentUrl).origin !== origin) {
      throw new Error(`invalid parent URL: ${target.id}`);
    }
  }
  for (const target of catalog.targets) {
    if (target.parentId && !ids.has(target.parentId)) throw new Error(`missing parent: ${target.id}`);
  }
}

function validateBytes(target, contentType, bytes) {
  if (!bytes.length || bytes.length > maxBytes || !contentType.toLowerCase().startsWith(target.contentType)) {
    throw new Error(`${target.id}: invalid size or content type`);
  }
  if (target.contentType === "application/pdf" && bytes.subarray(0, 5).toString("ascii") !== "%PDF-") {
    throw new Error(`${target.id}: PDF signature missing`);
  }
  if (target.marker && !bytes.toString("utf8").includes(target.marker)) {
    throw new Error(`${target.id}: expected source marker missing`);
  }
}

async function fetchTarget(target) {
  let url = new URL(target.url);
  for (let redirects = 0; redirects <= 3; redirects += 1) {
    const response = await fetch(url, {
      headers: { Accept: target.contentType, "User-Agent": "agent-platform-cnipa-source-review/1.0" },
      redirect: "manual",
      signal: AbortSignal.timeout(30_000),
    });
    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get("location");
      if (!location) throw new Error(`${target.id}: redirect without location`);
      url = sameOriginRedirect(location, url, target.id);
      continue;
    }
    if (!response.ok || Number(response.headers.get("content-length") ?? 0) > maxBytes) {
      throw new Error(`${target.id}: HTTP ${response.status} or oversized response`);
    }
    const bytes = Buffer.from(await response.arrayBuffer());
    validateBytes(target, response.headers.get("content-type") ?? "", bytes);
    return { bytes, finalUrl: url.href, contentType: response.headers.get("content-type"),
      etag: response.headers.get("etag"), lastModified: response.headers.get("last-modified") };
  }
  throw new Error(`${target.id}: too many redirects`);
}

async function main() {
  const catalogBytes = await readFile(catalogPath);
  const catalog = JSON.parse(catalogBytes.toString("utf8"));
  validateCatalog(catalog);
  if (selfTest) {
    const rejects = (operation) => {
      try { operation(); } catch { return; }
      throw new Error("negative source boundary did not reject");
    };
    rejects(() => validateCatalog({ ...catalog, targets: catalog.targets.map((target, index) =>
      index === 0 ? { ...target, url: "https://example.com/foreign" } : target) }));
    rejects(() => validateCatalog({ ...catalog, targets: catalog.targets.map((target, index) =>
      index === 0 ? { ...target, file: "../escape.html" } : target) }));
    rejects(() => validateCatalog({ ...catalog, targets: catalog.targets.map((target, index) =>
      index === 0 ? { ...target, id: catalog.targets[1].id } : target) }));
    rejects(() => validateBytes(catalog.targets[1], "application/pdf", Buffer.from("<html>error</html>")));
    rejects(() => validateBytes(catalog.targets[0], "text/html", Buffer.from("<html>unrelated</html>")));
    rejects(() => requireNoDrift({ catalogVersion: catalog.catalogVersion,
      snapshots: [{ id: catalog.targets[0].id, sha256: "old" }] },
      catalog.catalogVersion, catalog.targets[0].id, "changed"));
    rejects(() => sameOriginRedirect("https://example.com/redirect", origin, "test"));
    console.log("CNIPA source collector boundary self-test passed");
    return;
  }
  const previous = await readFile(manifestPath, "utf8").then(JSON.parse, () => null);
  if (verifyOnly) {
    if (!previous || previous.catalogSha256 !== sha256(catalogBytes)
        || previous.snapshots.length !== catalog.targets.length
        || previous.reviewStatus !== "REVIEW_REQUIRED"
        || forbiddenUses.some((key) => previous.boundaries?.[key] !== false)) {
      throw new Error("manifest/catalog or prohibited-use boundary mismatch");
    }
    for (const target of catalog.targets) {
      const snapshot = previous.snapshots.find((item) => item.id === target.id);
      if (!snapshot || snapshot.file !== target.file || snapshot.requestedUrl !== target.url
          || snapshot.reviewStatus !== "REVIEW_REQUIRED"
          || new URL(snapshot.finalUrl).origin !== origin
          || snapshot.sha256 !== sha256(await readFile(join(directory, target.file)))) {
        throw new Error(`${target.id}: snapshot missing or changed`);
      }
    }
    console.log(`Verified ${catalog.targets.length} REVIEW_REQUIRED CNIPA source snapshots`);
    return;
  }
  const snapshots = [];
  const downloaded = [];
  for (const target of catalog.targets) {
    const result = await fetchTarget(target);
    const digest = sha256(result.bytes);
    requireNoDrift(previous, catalog.catalogVersion, target.id, digest);
    downloaded.push({ target, bytes: result.bytes });
    snapshots.push({ id: target.id, category: target.category, requestedUrl: target.url,
      finalUrl: result.finalUrl, parentId: target.parentId ?? null, parentUrl: target.parentUrl ?? null,
      file: target.file, contentType: result.contentType, etag: result.etag,
      lastModified: result.lastModified, bytes: result.bytes.length, sha256: digest,
      reviewStatus: "REVIEW_REQUIRED" });
  }
  await mkdir(join(directory, "raw"), { recursive: true });
  for (const { target, bytes } of downloaded) {
    const path = join(directory, target.file);
    await writeFile(path + ".tmp", bytes);
    await rename(path + ".tmp", path);
  }
  const manifest = { schemaVersion: "cnipa-rules-documents-source-review-v1",
    catalogId: catalog.catalogId, catalogVersion: catalog.catalogVersion,
    catalogSha256: sha256(catalogBytes), capturedAt: new Date().toISOString(),
    provider: catalog.provider, origin, authorizationRef: catalog.authorizationRef,
    reviewStatus: "REVIEW_REQUIRED",
    boundaries: { businessRuleImportAllowed: false, formalTemplateImportAllowed: false,
      caseEvidenceAllowed: false, externalModelAllowed: false, formalDecisionAllowed: false },
    snapshots };
  await writeFile(manifestPath + ".tmp", JSON.stringify(manifest, null, 2) + "\n");
  await rename(manifestPath + ".tmp", manifestPath);
  console.log(`Collected ${snapshots.length} REVIEW_REQUIRED CNIPA source snapshots`);
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
