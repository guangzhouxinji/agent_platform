import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDir = join(root, "data", "adjudication", "source-review", "spc-ip-court-v1");
const rawDir = join(outputDir, "raw");
const manifestPath = join(outputDir, "manifest.json");
const approvalPath = join(outputDir, "approval.json");
const verifyOnly = process.argv.includes("--verify-only");
const targets = [
  { id: "SPC_IP_COURT_HOME", url: "https://ipc.court.gov.cn/zh-cn/index.html", file: "raw/home.html", status: 200 },
  { id: "SPC_SELECTED_JUDGMENTS", url: "https://ipc.court.gov.cn/zh-cn/news/more-5-25.html", file: "raw/selected-judgments.html", status: 200 },
  { id: "SPC_IP_COURT_ROBOTS_PROBE", url: "https://ipc.court.gov.cn/robots.txt", file: "raw/robots-probe.html", status: 404 },
];
const sha256 = (value) => createHash("sha256").update(value).digest("hex");

async function collect() {
  await mkdir(rawDir, { recursive: true });
  const capturedAt = new Date().toISOString();
  const snapshots = [];
  for (const target of targets) {
    const response = await fetch(target.url, {
      headers: { Accept: "text/html,text/plain", "User-Agent": "agent-platform-source-review/1.0" },
      redirect: "follow",
      signal: AbortSignal.timeout(60_000),
    });
    const content = Buffer.from(await response.arrayBuffer());
    if (response.status !== target.status) throw new Error(`${target.id}: expected HTTP ${target.status}, received ${response.status}`);
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
    reviewId: "ADJ-SOURCE-REVIEW-SPC-IP-COURT-V1",
    capturedAt,
    source: {
      sourceSystem: "SPC_IP_COURT_SELECTED_JUDGMENTS",
      provider: "最高人民法院知识产权法庭",
      websiteUrl: "https://ipc.court.gov.cn/zh-cn/index.html",
      categoryUrl: "https://ipc.court.gov.cn/zh-cn/news/more-5-25.html",
    },
    evidenceAssessment: {
      officialJudicialPublicationSite: true,
      selectedJudgmentsPublished: true,
      copyrightNoticePresent: true,
      explicitProjectReuseLicenseIdentified: false,
      robotsPolicy: "NOT_PUBLISHED_AT_PROBED_PATH",
      publicAccessDoesNotEstablishIndexingPermission: true,
    },
    intendedUse: {
      dataMode: "PUBLIC_PILOT",
      usageLimitation: "INTERNAL_DEVELOPMENT_PILOT_ONLY",
      categories: ["PUBLIC_JUDGMENT_REFERENCE"],
      fields: ["stableSourceId", "publicDocketNumber", "publicationDate", "institution", "documentType", "sourceUrl", "capturedAt"],
      excludes: ["fullText", "parties", "personalInformation", "facts", "evidence", "holdings", "reasoning", "outcome", "legalCitations"],
      externalModelAllowed: false,
      productionUseAllowed: false,
      dm8ImportAllowed: false,
    },
    unresolvedApproval: {
      responsibleParty: null,
      decisionDate: null,
      indexingPermission: null,
      retentionPeriod: null,
      withdrawalHandling: null,
      attribution: null,
    },
    reviewStatus: "REVIEW_REQUIRED",
    approvalStatus: "NOT_APPROVED",
    importDecision: "BLOCKED_SOURCE_REVIEW",
    snapshots,
    conclusion: "The captured pages establish an official public judgment source and a copyright notice, but do not establish this project's permission to index and retain the derived catalog. Responsible-party approval remains required before DM8 import.",
  };
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

async function verify() {
  const [manifestText, approvalText] = await Promise.all([
    readFile(manifestPath, "utf8"), readFile(approvalPath, "utf8"),
  ]);
  const manifest = JSON.parse(manifestText);
  const approval = JSON.parse(approvalText);
  const errors = [];
  if (manifest.reviewId !== "ADJ-SOURCE-REVIEW-SPC-IP-COURT-V1") errors.push("reviewId mismatch");
  if (manifest.reviewStatus !== "REVIEW_REQUIRED" || manifest.approvalStatus !== "NOT_APPROVED"
      || manifest.importDecision !== "BLOCKED_SOURCE_REVIEW") errors.push("review must remain blocked pending approval");
  if (manifest.intendedUse?.dm8ImportAllowed !== false || manifest.intendedUse?.externalModelAllowed !== false
      || manifest.intendedUse?.productionUseAllowed !== false) errors.push("intended-use boundary changed");
  if (manifest.evidenceAssessment?.explicitProjectReuseLicenseIdentified !== false
      || manifest.evidenceAssessment?.publicAccessDoesNotEstablishIndexingPermission !== true) errors.push("reuse assessment changed");
  for (const value of Object.values(manifest.unresolvedApproval ?? {})) if (value !== null) errors.push("approval was asserted without a separate decision record");
  if (approval.reviewId !== manifest.reviewId || approval.decision !== "APPROVED") errors.push("approval does not resolve this review");
  if (approval.responsibleParty?.role !== "PROJECT_OWNER_AND_DATA_LEGAL_RESPONSIBLE_PARTY"
      || approval.decisionDate !== "2026-09-20") errors.push("approval responsibility/date evidence is incomplete");
  if (approval.scope?.sourceSystem !== "SPC_IP_COURT_SELECTED_JUDGMENTS"
      || approval.scope?.sourceDatasetId !== "ADJ-PUBLIC-REFERENCE-PILOT-V1"
      || approval.scope?.recordCount !== 100) errors.push("approval dataset scope mismatch");
  if (approval.scope?.retentionPeriod !== "UNTIL_DEVELOPMENT_PILOT_END") errors.push("approval retention mismatch");
  for (const use of ["INTERNAL_DEVELOPMENT_PILOT", "LOCAL_DM8_STORAGE", "LOCAL_DM8_INDEXING"]) {
    if (!approval.scope?.allowedUses?.includes(use)) errors.push(`missing approved use ${use}`);
  }
  for (const use of ["EXTERNAL_MODEL_PROCESSING", "PRODUCTION_USE", "EXTERNAL_REDISTRIBUTION",
    "FORMAL_CASE_INPUT", "FORMAL_MATERIAL_INPUT", "LEGAL_APPLICABILITY_INPUT", "FORMAL_DRAFT_INPUT",
    "ADJUDICATION_WORKFLOW_INPUT"]) {
    if (!approval.scope?.forbiddenUses?.includes(use)) errors.push(`missing forbidden use ${use}`);
  }
  if (!approval.scope?.withdrawalHandling?.includes("Immediately mark unavailable")
      || !approval.scope?.withdrawalHandling?.includes("remove from local indexes")) errors.push("withdrawal handling is incomplete");
  if (!approval.scope?.attribution?.includes("Supreme People's Court Intellectual Property Court")
      || !approval.scope?.attribution?.includes("中华人民共和国最高人民法院 版权所有")) errors.push("attribution decision is incomplete");
  const byId = new Map((manifest.snapshots ?? []).map((snapshot) => [snapshot.id, snapshot]));
  const content = new Map();
  for (const target of targets) {
    const snapshot = byId.get(target.id);
    if (!snapshot || snapshot.requestedUrl !== target.url || snapshot.httpStatus !== target.status) {
      errors.push(`${target.id}: snapshot trace mismatch`);
      continue;
    }
    try {
      const bytes = await readFile(join(outputDir, snapshot.file));
      if (bytes.length !== snapshot.bytes || sha256(bytes) !== snapshot.sha256) errors.push(`${target.id}: snapshot digest mismatch`);
      content.set(target.id, bytes.toString("utf8"));
    } catch {
      errors.push(`${target.id}: snapshot file missing`);
    }
  }
  if (!(content.get("SPC_IP_COURT_HOME") ?? "").includes("最高人民法院知识产权法庭")) errors.push("official site identity missing");
  const judgments = content.get("SPC_SELECTED_JUDGMENTS") ?? "";
  if (!judgments.includes("精品裁判") || !judgments.includes("中华人民共和国最高人民法院 版权所有")) errors.push("judgment publication/copyright evidence missing");
  if (errors.length) throw new Error(`Adjudication source review verification failed:\n- ${errors.join("\n- ")}`);
  console.log(`Adjudication source review evidence verified: ${targets.length} snapshots, internal-development approval ${approval.approvalId}`);
}

if (!verifyOnly) await collect();
await verify();
