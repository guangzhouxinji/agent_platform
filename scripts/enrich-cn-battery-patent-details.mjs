import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const datasetDir = join(root, "data", "patents", "cn-topic-public-pilot-v1");
const recordsPath = join(datasetDir, "records.json");
const manifestPath = join(datasetDir, "manifest.json");
const topicId = process.argv.find((argument) => argument.startsWith("--topic="))?.slice("--topic=".length) ?? "BATTERY";
if (!/^[A-Z_]+$/.test(topicId)) throw new Error("--topic must contain only uppercase letters and underscores");
const detailDir = join(datasetDir, "raw", `detail-${topicId.toLowerCase()}`);
const verifyOnly = process.argv.includes("--verify-only");
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const text = (value) => String(value ?? "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();

function sectionText(value, output = []) {
  if (Array.isArray(value)) value.forEach((item) => sectionText(item, output));
  else if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (key === "content" && typeof item === "string") output.push(text(item));
      else if (key !== "annotations") sectionText(item, output);
    }
  }
  return output.filter(Boolean);
}

function classifications(value, output = new Set()) {
  if (Array.isArray(value)) value.forEach((item) => classifications(item, output));
  else if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (key === "classification" && typeof item === "string") output.add(text(item));
      else classifications(item, output);
    }
  }
  return [...output];
}

async function download(id) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(`https://surechembl.org/api/document/${encodeURIComponent(id)}/contents`, {
        signal: AbortSignal.timeout(60_000),
        headers: { Accept: "application/json", Origin: "https://www.surechembl.org", "User-Agent": "agent-platform-public-pilot/1.0" },
      });
      const body = await response.text();
      if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
      const payload = JSON.parse(body);
      if (payload.status !== "OK") throw new Error(`source status ${payload.status}`);
      return body;
    } catch (error) {
      lastError = error;
      if (attempt < 3) await new Promise((resolveDelay) => setTimeout(resolveDelay, attempt * 1_000));
    }
  }
  throw lastError;
}

async function collect() {
  await mkdir(detailDir, { recursive: true });
  const payload = JSON.parse(await readFile(recordsPath, "utf8"));
  const records = payload.records ?? [];
  const selected = records.filter((record) => record.topicId === topicId);
  if (selected.length !== 100) throw new Error(`Expected 100 ${topicId} records, found ${selected.length}`);
  for (const [index, record] of selected.entries()) {
    const rawFile = `raw/detail-${topicId.toLowerCase()}/${record.sourceRecordId}.json`;
    const rawPath = join(datasetDir, rawFile);
    let raw;
    try { raw = await readFile(rawPath, "utf8"); } catch { raw = await download(record.sourceRecordId); await writeFile(rawPath, raw, "utf8"); }
    const document = JSON.parse(raw)?.data?.contents?.patentDocument ?? {};
    record.detailRawFile = rawFile;
    record.detailResponseSha256 = sha256(raw);
    record.abstractExcerpt = sectionText(document.abstracts).join(" ").slice(0, 1_500) || null;
    record.claimsExcerpt = sectionText(document.claimResponses).join(" ").slice(0, 5_000) || null;
    record.cpcClassifications = classifications(document.bibliographicData?.technicalData?.classificationsCpc).slice(0, 30);
    record.sourceLocators = ["document-root", ...(record.abstractExcerpt ? ["abstract"] : []), ...(record.claimsExcerpt ? ["claims"] : [])];
    if ((index + 1) % 10 === 0) console.log(`Enriched battery details: ${index + 1}/100`);
  }
  payload.records = records;
  const recordsText = `${JSON.stringify(payload, null, 2)}\n`;
  await writeFile(recordsPath, recordsText, "utf8");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  manifest.recordsSha256 = sha256(recordsText);
  manifest.detailedRecordCount = selected.filter((record) => record.detailResponseSha256).length;
  manifest.detailScope = `${topicId} topic only; raw source responses are development evidence, not model or production authorization.`;
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
}

async function verify() {
  const [recordsText, manifestText] = await Promise.all([readFile(recordsPath, "utf8"), readFile(manifestPath, "utf8")]);
  const records = JSON.parse(recordsText).records ?? [];
  const manifest = JSON.parse(manifestText);
  const selected = records.filter((record) => record.topicId === topicId);
  const errors = [];
  if (selected.length !== 100) errors.push(`expected 100 ${topicId} records, found ${selected.length}`);
  if (manifest.recordsSha256 !== sha256(recordsText)) errors.push("recordsSha256 mismatch");
  for (const record of selected) {
    if (!record.detailRawFile || !record.detailResponseSha256 || !record.claimsExcerpt) errors.push(`missing detail: ${record.sourceRecordId}`);
    else if (sha256(await readFile(join(datasetDir, record.detailRawFile), "utf8")) !== record.detailResponseSha256) errors.push(`raw digest mismatch: ${record.sourceRecordId}`);
  }
  if (errors.length) throw new Error(`Battery detail enrichment failed:\n- ${errors.join("\n- ")}`);
  console.log(`${topicId} detail enrichment verified: ${selected.length} records`);
}

if (!verifyOnly) await collect();
await verify();
