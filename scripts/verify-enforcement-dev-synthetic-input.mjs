import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const directory = join(root, "data/enforcement/dev-synthetic-material-v1");
const sha256 = value => createHash("sha256").update(value).digest("hex");
const fail = reason => { throw new Error(`Enforcement synthetic input: ${reason}`); };
const same = (actual, expected) => JSON.stringify(actual) === JSON.stringify(expected);
const actions = ["QUERY", "SAVE", "DISPLAY"];
const denied = ["INDEX", "DOWNLOAD", "MODEL_USE", "CROSS_DOMAIN", "NOTIFY", "OFFICIAL_WORKFLOW"];
const fields = ["observationId", "observationVersion", "text", "subjectMentions", "factMentions", "materialMentions", "missingItems"];

function validate(manifest, records, payloadBytes, use = "QUERY", withdrawalVersion = 0) {
  if (manifest.datasetId !== "DEV-ENF-SYN-MATERIAL-01" || manifest.origin !== "PROJECT_AUTHORED_SYNTHETIC"
      || manifest.purpose !== "ENF_MATERIAL_CI_ONLY" || manifest.environment !== "isolated-development-only"
      || manifest.payloadFile !== "observations.json" || manifest.status !== "ACTIVE"
      || manifest.withdrawalVersion !== withdrawalVersion) fail("scope or withdrawal changed");
  if (!Number.isInteger(manifest.manifestVersion) || manifest.manifestVersion < 1
      || !manifest.author || !manifest.approvalRef || !manifest.organizationRef
      || !same(manifest.allowedPrincipalRefs, ["dev-enf-a"])
      || !(Date.parse(manifest.validFrom) < Date.parse(manifest.validUntil))) fail("manifest identity or validity missing");
  if (!same(Object.keys(manifest.fields), fields) || !fields.every(field => same(manifest.fields[field], actions))
      || !same(manifest.deniedUses, denied) || !actions.includes(use)) fail("field or operation not allowed");
  if (!Array.isArray(records) || records.length !== 2 || manifest.records?.length !== records.length
      || sha256(payloadBytes) !== manifest.payloadSha256) fail("payload missing or modified");
  const fingerprints = [];
  for (const [index, record] of records.entries()) {
    const version = `V${index + 1}`;
    if (!same(Object.keys(record), fields) || record.observationId !== "DEV-ENF-OBS-001"
        || record.observationVersion !== version || !record.text?.startsWith("虚构")
        || !same(record.subjectMentions?.map(item => item.roleAsStated), ["投诉方", "展示方"])
        || !record.subjectMentions.every(item => item.creditCode === null)
        || record.factMentions?.length !== 1 || record.materialMentions?.length !== 1
        || !same(record.missingItems, ["MATERIAL_BYTES", "COLLECTION_DETAILS", "CURRENT_CASE_AUTHORITY"])) fail("record fields or gaps changed");
    for (const item of [...record.subjectMentions, ...record.factMentions, ...record.materialMentions]) {
      const fragment = item.label ?? item.text;
      if (item.locator !== `text:${fragment}` || record.text.split(fragment).length !== 2) fail("field locator missing or ambiguous");
    }
    if (record.materialMentions[0].availability !== "MENTION_ONLY_NO_BYTES") fail("material promoted to evidence");
    const fingerprint = sha256(JSON.stringify(record));
    if (!same(manifest.records[index], { observationId: record.observationId,
      observationVersion: version, sha256: fingerprint })) fail("record digest or order changed");
    fingerprints.push(`${record.observationId}\t${version}\t${fingerprint}\n`);
  }
  if (sha256(fingerprints.join("")) !== manifest.batchSha256) fail("batch digest changed");
  return true;
}

const payloadBytes = readFileSync(join(directory, "observations.json"));
const manifest = JSON.parse(readFileSync(join(directory, "manifest.json"), "utf8"));
const records = JSON.parse(payloadBytes);
validate(manifest, records, payloadBytes);

if (process.argv.includes("--self-test")) {
  const rejects = (label, run) => {
    try { run(); } catch { return; }
    fail(`negative test unexpectedly accepted: ${label}`);
  };
  rejects("tampered text", () => validate(manifest, records.map((row, index) => index ? row : { ...row, text: `${row.text}篡改` }), payloadBytes));
  rejects("missing gaps", () => validate(manifest, [{ ...records[0], missingItems: [] }, records[1]], payloadBytes));
  rejects("forbidden index", () => validate(manifest, records, payloadBytes, "INDEX"));
  rejects("forbidden model", () => validate(manifest, records, payloadBytes, "MODEL_USE"));
  rejects("withdrawn", () => validate(manifest, records, payloadBytes, "QUERY", 1));
  rejects("ambiguous locator", () => validate(manifest, [{ ...records[0], subjectMentions: [{ ...records[0].subjectMentions[0], locator: "text:其他" }, records[0].subjectMentions[1]] }, records[1]], payloadBytes));
  console.log("Enforcement synthetic input self-test passed: tamper, missing gaps, forbidden uses, withdrawal, locator");
} else {
  console.log("Enforcement synthetic input verified: 2 project-authored versions, QUERY/SAVE/DISPLAY only");
}
