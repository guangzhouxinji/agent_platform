import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDir = join(projectRoot, "data", "enforcement", "source-review", "surechembl-v1");
const rawDir = join(outputDir, "raw");
const manifestPath = join(outputDir, "manifest.json");
const approvalPath = join(outputDir, "approval.json");
const verifyOnly = process.argv.includes("--verify-only");

const targets = [
  {
    id: "SURECHEMBL_LICENSE",
    url: "https://ftp.ebi.ac.uk/pub/databases/chembl/SureChEMBL/bulk_data/LICENCE",
    file: "raw/surechembl-licence.txt",
    expectedStatus: 200,
  },
  {
    id: "SURECHEMBL_BULK_README",
    url: "https://ftp.ebi.ac.uk/pub/databases/chembl/SureChEMBL/bulk_data/README.txt",
    file: "raw/surechembl-bulk-readme.txt",
    expectedStatus: 200,
  },
  {
    id: "SURECHEMBL_API_DOC",
    url: "https://chembl.gitbook.io/surechembl/api.md",
    file: "raw/surechembl-api.md",
    expectedStatus: 200,
  },
  {
    id: "SURECHEMBL_DOWNLOADS_DOC",
    url: "https://chembl.gitbook.io/surechembl/downloads.md",
    file: "raw/surechembl-downloads.md",
    expectedStatus: 200,
  },
  {
    id: "SURECHEMBL_BULK_DOC",
    url: "https://chembl.gitbook.io/surechembl/downloads/bulk-data.md",
    file: "raw/surechembl-bulk-data.md",
    expectedStatus: 200,
  },
  {
    id: "SURECHEMBL_DOCS_ROBOTS",
    url: "https://chembl.gitbook.io/surechembl/robots.txt",
    file: "raw/surechembl-docs-robots.txt",
    expectedStatus: 200,
  },
  {
    id: "SURECHEMBL_DATA_HOST_ROBOTS_PROBE",
    url: "https://www.surechembl.org/robots.txt",
    file: "raw/surechembl-data-host-robots-probe.json",
    expectedStatus: 404,
  },
  {
    id: "EMBL_EBI_TERMS",
    url: "https://www.ebi.ac.uk/about/terms-of-use/",
    file: "raw/embl-ebi-terms.html",
    expectedStatus: 200,
  },
];

const sha256 = (value) => createHash("sha256").update(value).digest("hex");

async function collect() {
  await mkdir(rawDir, { recursive: true });
  const capturedAt = new Date().toISOString();
  const snapshots = [];
  for (const target of targets) {
    const response = await fetch(target.url, {
      headers: { Accept: "text/html,text/markdown,text/plain,application/json", "User-Agent": "agent-platform-source-review/1.0" },
      redirect: "follow",
      signal: AbortSignal.timeout(60_000),
    });
    const content = Buffer.from(await response.arrayBuffer());
    if (response.status !== target.expectedStatus) {
      throw new Error(`${target.id}: expected HTTP ${target.expectedStatus}, received ${response.status}`);
    }
    await writeFile(join(outputDir, target.file), content);
    snapshots.push({
      id: target.id,
      requestedUrl: target.url,
      finalUrl: response.url,
      httpStatus: response.status,
      contentType: response.headers.get("content-type"),
      etag: response.headers.get("etag"),
      lastModified: response.headers.get("last-modified"),
      capturedAt,
      file: target.file,
      bytes: content.length,
      sha256: sha256(content),
    });
  }

  const manifest = {
    schemaVersion: "1.0",
    reviewId: "ENF-SOURCE-REVIEW-SURECHEMBL-V1",
    capturedAt,
    source: {
      sourceSystem: "SURECHEMBL_PUBLIC_SEARCH",
      provider: "EMBL-EBI SureChEMBL",
      websiteUrl: "https://www.surechembl.org/",
      apiBaseUrl: "https://surechembl.org/api",
      supportContact: "surechembl-help@ebi.ac.uk",
    },
    evidenceAssessment: {
      dataLicenseIdentified: "CC-BY-4.0",
      licenseLocationScope: "SURECHEMBL_BULK_DATA_DIRECTORY",
      apiProgrammaticAccessDocumented: true,
      apiDataLicenseApplicability: "PENDING_RESPONSIBLE_PARTY_CONFIRMATION",
      attributionRequired: true,
      thirdPartyRightsRemainUserResponsibility: true,
      dataHostRobotsPolicy: "NOT_PUBLISHED_AT_PROBED_PATH",
      documentationHostRobotsPolicy: "ALLOW_ALL_WITH_PUBLISHED_CONTENT_SIGNALS",
      termsLastRevised: "2024-02-05",
    },
    intendedUse: {
      dataMode: "PUBLIC_PILOT",
      usageLimitation: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
      categories: ["PUBLIC_RIGHT_STATUS_SNAPSHOT"],
      fields: ["publicationNumber", "publicationDate", "title", "sourceUrl", "capturedAt"],
      excludes: ["applicant", "inventor", "abstract", "claims", "personalInformation", "currentLegalStatusInference"],
      externalModelAllowed: false,
      productionUseAllowed: false,
      redistributionAllowedByProject: false,
    },
    unresolvedApproval: {
      responsibleParty: null,
      decisionDate: null,
      retentionPeriod: null,
      indexingPermission: null,
      apiLicenseScopeConfirmed: false,
      attributionImplementationConfirmed: false,
    },
    reviewStatus: "REVIEW_REQUIRED",
    importDecision: "BLOCKED_SOURCE_REVIEW",
    approvalStatus: "NOT_APPROVED",
    snapshots,
    conclusion: "Public documentation supports programmatic access and identifies CC BY 4.0 for SureChEMBL bulk data, but it does not by itself approve this project's API-derived staging data. Responsible-party confirmation of API license scope, retention, indexing, and attribution remains required.",
  };
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

async function verify() {
  const [manifestText, approvalText] = await Promise.all([readFile(manifestPath, "utf8"), readFile(approvalPath, "utf8")]);
  const manifest = JSON.parse(manifestText);
  const approval = JSON.parse(approvalText);
  const errors = [];
  if (manifest.reviewId !== "ENF-SOURCE-REVIEW-SURECHEMBL-V1") errors.push("reviewId mismatch");
  if (manifest.reviewStatus !== "REVIEW_REQUIRED" || manifest.importDecision !== "BLOCKED_SOURCE_REVIEW" || manifest.approvalStatus !== "NOT_APPROVED") errors.push("captured evidence manifest must preserve its pre-decision state");
  if (manifest.unresolvedApproval?.responsibleParty !== null || manifest.unresolvedApproval?.decisionDate !== null) errors.push("responsible-party decision was asserted without evidence");
  if (manifest.unresolvedApproval?.retentionPeriod !== null || manifest.unresolvedApproval?.indexingPermission !== null) errors.push("retention/indexing decision was asserted without evidence");
  if (manifest.unresolvedApproval?.apiLicenseScopeConfirmed !== false || manifest.unresolvedApproval?.attributionImplementationConfirmed !== false) errors.push("license scope or attribution was asserted as confirmed");
  if (manifest.intendedUse?.externalModelAllowed !== false || manifest.intendedUse?.productionUseAllowed !== false || manifest.intendedUse?.redistributionAllowedByProject !== false) errors.push("intended-use boundary changed");
  if (manifest.evidenceAssessment?.dataLicenseIdentified !== "CC-BY-4.0" || manifest.evidenceAssessment?.apiDataLicenseApplicability !== "PENDING_RESPONSIBLE_PARTY_CONFIRMATION") errors.push("license evidence assessment changed");
  if (approval.reviewId !== manifest.reviewId || approval.decision !== "APPROVED") errors.push("approval does not resolve this review");
  if (approval.responsibleParty?.role !== "PROJECT_OWNER_AND_DATA_LEGAL_RESPONSIBLE_PARTY" || approval.decisionDate !== "2026-09-19") errors.push("approval responsibility/date evidence is incomplete");
  if (approval.scope?.sourceDatasetId !== "CON-04-CN-TOPIC-PUBLIC-PILOT-V1" || approval.scope?.recordCount !== 400) errors.push("approval dataset scope mismatch");
  if (approval.scope?.retentionPeriod !== "UNTIL_DEVELOPMENT_PILOT_END") errors.push("approval retention mismatch");
  for (const use of ["INTERNAL_DEVELOPMENT_PILOT", "LOCAL_DM8_STORAGE", "LOCAL_DM8_INDEXING"]) if (!approval.scope?.allowedUses?.includes(use)) errors.push(`missing approved use ${use}`);
  for (const use of ["EXTERNAL_MODEL_PROCESSING", "PRODUCTION_USE", "EXTERNAL_REDISTRIBUTION", "FORMAL_ENFORCEMENT_EVIDENCE", "ENFORCEMENT_WORKFLOW_INPUT"]) if (!approval.scope?.forbiddenUses?.includes(use)) errors.push(`missing forbidden use ${use}`);
  if (!approval.scope?.attribution?.includes("CC BY 4.0") || !approval.scope?.attribution?.includes("SureChEMBL")) errors.push("attribution decision incomplete");
  const snapshotById = new Map((manifest.snapshots ?? []).map((snapshot) => [snapshot.id, snapshot]));
  const capturedTextById = new Map();
  for (const target of targets) {
    const snapshot = snapshotById.get(target.id);
    if (!snapshot) {
      errors.push(`missing snapshot ${target.id}`);
      continue;
    }
    if (snapshot.requestedUrl !== target.url || snapshot.httpStatus !== target.expectedStatus) errors.push(`${target.id}: request/status trace mismatch`);
    try {
      const content = await readFile(join(outputDir, snapshot.file));
      if (content.length !== snapshot.bytes || sha256(content) !== snapshot.sha256) errors.push(`${target.id}: file digest mismatch`);
      capturedTextById.set(target.id, content.toString("utf8"));
    } catch {
      errors.push(`${target.id}: captured file missing`);
    }
  }
  if (snapshotById.size !== targets.length) errors.push(`expected ${targets.length} evidence snapshots, found ${snapshotById.size}`);
  const requiredContent = [
    ["SURECHEMBL_LICENSE", ["Attribution 4.0 International", "Creative Commons Attribution 4.0 International Public License"]],
    ["SURECHEMBL_BULK_README", ["data content in SureChEMBL is licensed", "CC BY 4.0"]],
    ["SURECHEMBL_API_DOC", ["API endpoints can be used to access the SureChEMBL programmatically"]],
    ["SURECHEMBL_BULK_DOC", ["machine-readable", "updated every 2 weeks"]],
    ["SURECHEMBL_DOCS_ROBOTS", ["User-agent: *", "Allow: /"]],
    ["EMBL_EBI_TERMS", ["Last revised:", "places no additional restrictions on the use or redistribution", "original data may be subject to rights claimed by third parties"]],
  ];
  for (const [id, fragments] of requiredContent) {
    const content = capturedTextById.get(id) ?? "";
    for (const fragment of fragments) if (!content.includes(fragment)) errors.push(`${id}: required evidence text missing: ${fragment}`);
  }
  if (errors.length) throw new Error(`Enforcement source review verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`Enforcement source review evidence verified: ${targets.length} snapshots, internal-development approval ${approval.approvalId}`);
}

if (!verifyOnly) await collect();
await verify();
