import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const datasetDir = join(projectRoot, "data", "patents", "public-pilot-v1");
const recordsPath = join(datasetDir, "records.json");
const reviewPath = join(datasetDir, "review-set-v0.json");

const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const recordsText = await readFile(recordsPath, "utf8");
const sourceRecords = JSON.parse(recordsText).records ?? [];
const review = JSON.parse(await readFile(reviewPath, "utf8"));
const errors = [];

if (review.datasetId !== "CON-04-PUBLIC-DATA-REVIEW-V0") errors.push("unexpected datasetId");
if (review.sourceDataset?.recordsSha256 !== sha256(recordsText)) errors.push("source records digest mismatch");
if (review.boundaries?.expertLabeled !== false) errors.push("review set must not contain expert labels");
if (review.boundaries?.formalDesignEvidence !== false) errors.push("review set cannot be formal design evidence");
if (review.boundaries?.formalVerificationEvidence !== false) errors.push("review set cannot be formal verification evidence");
if (review.boundaries?.businessAcceptanceEvidence !== false) errors.push("review set cannot be business acceptance evidence");
if (review.boundaries?.formalRecallMetricEligible !== false) errors.push("review set cannot be a formal Recall@K benchmark");
if (review.boundaries?.replaceBeforeProduction !== true) errors.push("review set must require replacement before production");

const entries = review.entries ?? [];
if (entries.length < 10 || entries.length > 15) errors.push(`expected 10-15 entries, found ${entries.length}`);
const sourceById = new Map(sourceRecords.map((record) => [record.sourceRecordId, record]));
const allowedBands = new Set(["STRONG_CANDIDATE", "GENERAL_CANDIDATE", "NEGATIVE_CONTROL"]);
const counts = new Map();
const selected = new Set();

for (const [index, entry] of entries.entries()) {
  const prefix = `entries[${index}]`;
  const source = sourceById.get(entry.sourceRecordId);
  if (!source) errors.push(`${prefix} references a missing source record`);
  if (selected.has(entry.sourceRecordId)) errors.push(`${prefix} duplicates sourceRecordId`);
  selected.add(entry.sourceRecordId);
  if (source && source.sourceVersion !== entry.sourceVersion) errors.push(`${prefix} sourceVersion mismatch`);
  if (!allowedBands.has(entry.expectedDiscoveryBand)) errors.push(`${prefix} invalid expectedDiscoveryBand`);
  counts.set(entry.expectedDiscoveryBand, (counts.get(entry.expectedDiscoveryBand) ?? 0) + 1);
  if (entry.labelNature !== "V0_DEVELOPMENT_HEURISTIC_NON_EXPERT") errors.push(`${prefix} must retain non-expert label nature`);
  if (!Array.isArray(entry.observedSignals) || entry.observedSignals.length === 0) errors.push(`${prefix} missing observedSignals`);
  if (!Array.isArray(entry.sourceLocators) || entry.sourceLocators.length === 0) errors.push(`${prefix} missing sourceLocators`);
  for (const forbidden of ["expertLabel", "legalConclusion", "claimScope", "formalRelevance"]) {
    if (forbidden in entry) errors.push(`${prefix} contains forbidden ${forbidden}`);
  }
}

for (const band of allowedBands) {
  if (!counts.get(band)) errors.push(`missing ${band} coverage`);
}

if (errors.length) throw new Error(`Public patent review-set verification failed:\n- ${errors.join("\n- ")}`);
console.log(`Public patent V0 review set verified: ${entries.length} records (${counts.get("STRONG_CANDIDATE")} strong, ${counts.get("GENERAL_CANDIDATE")} general, ${counts.get("NEGATIVE_CONTROL")} negative controls).`);
