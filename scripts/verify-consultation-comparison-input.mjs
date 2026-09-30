import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const base = "data/consultation/";
const paths = [
  base + "dev-synthetic-v1/task-input-manifest.json",
  base + "dev-feature-candidates-v1/manifest.json",
  base + "dev-synthetic-v1/manifest.json",
  base + "dev-feature-candidates-v1/candidates.json",
];
const originals = paths.map(path => {
  const raw = readFileSync(resolve(root, path));
  return { raw, data: JSON.parse(raw) };
});
const directory = resolve(root, base + "dev-comparison-input-v1");
const manifest = JSON.parse(readFileSync(resolve(directory, "manifest.json")));
const rawPayload = readFileSync(resolve(directory, "input.json"));
const payload = JSON.parse(rawPayload);
const hash = value => createHash("sha256").update(value).digest("hex");
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const check = (condition, message) => {
  if (!condition) throw new Error("Consultation comparison input: " + message);
};
const fields = ["target.goal", "target.supplement", "featureCandidates", "claimCandidates"];
const uses = ["QUERY", "SAVE", "DISPLAY", "COMPARE_INPUT"];
const denied = ["AUTO_CONFIRM", "PROFESSIONAL_RELATION_OUTPUT", "LEGAL_CONCLUSION", "OFFICIAL_WORKFLOW"];

function verify(policy, data, bytes, source, use = "COMPARE_INPUT", withdrawalVersion = 0) {
  const [input, candidates, patent] = source.map(item => item.data);
  check(policy.schemaVersion === 1 && data.schemaVersion === 1
    && policy.manifestId === data.datasetId && policy.manifestId === "DEV-CON-COMPARE-INPUT-01"
    && policy.origin === data.origin && policy.origin === "PROJECT_AUTHORED_SYNTHETIC"
    && policy.environment === "isolated-development-only" && policy.status === "SYNTHETIC_DEV_ONLY"
    && policy.withdrawalVersion === withdrawalVersion && policy.payloadFile === "input.json"
    && policy.organizationRef === "dev-con-isolated"
    && equal(policy.allowedPrincipalRefs, ["dev-con-a", "dev-con-b"])
    && policy.approvalRef === "docs/02-待确认事项清单.md#32-2026-09-25-项目数据处理与功能体验优先决定"
    && Date.parse(policy.validFrom) < Date.parse(policy.validUntil), "scope or withdrawal");
  check(equal(Object.keys(policy.fields), fields)
    && fields.every(field => equal(policy.fields[field], uses))
    && equal(policy.deniedUses, denied) && uses.includes(use), "unapproved field or use");
  check(equal(Object.keys(data), ["schemaVersion", "datasetId", "origin", "target",
    "featureCandidates", "claimCandidates"]), "unexpected payload field");
  check(hash(bytes) === policy.payloadSha256, "payload digest");
  check(equal(policy.sourceManifests?.map(item => item.manifestId),
    ["DEV-CON-INPUT-01", "DEV-CON-FEATURE-CANDIDATES-01", "DEV-CON-BOUNDARY-01"])
    && policy.sourceManifests.every((item, index) => item.sha256 === hash(source[index].raw))
    && hash(source[3].raw) === candidates.payloadSha256,
  "source manifest digest");

  check(input.entries?.length === 2 && input.entries[0].kind === "GOAL"
    && input.entries[1].kind === "SUPPLEMENT"
    && input.entries.every(entry => entry.sha256 === hash(Buffer.from(entry.text, "utf8")))
    && equal(Object.keys(data.target), ["goal", "supplement"])
    && data.target.goal === input.entries[0].text
    && data.target.supplement === input.entries[1].text
    && equal(input.entries.map(entry => entry.uses), [["SAVE"], ["SAVE"]]),
  "target provenance");
  check(candidates.deniedUses?.includes("PROFESSIONAL_COMPARE")
    && candidates.manifestId === "DEV-CON-FEATURE-CANDIDATES-01"
    && data.featureCandidates?.length === 3, "old feature permission or count");
  const oldCandidates = source[3].data.candidates;
  const supplement = Buffer.from(data.target.supplement, "utf8");
  for (const [index, item] of data.featureCandidates.entries()) {
    const old = oldCandidates[index];
    check(equal(Object.keys(item), ["featureId", "exactText", "byteStart", "byteEnd", "status"])
      && item.featureId === old.featureId && item.exactText === old.exactText
      && item.byteStart === old.byteStart && item.byteEnd === old.byteEnd
      && item.status === old.status && item.status === "UNCONFIRMED"
      && supplement.subarray(item.byteStart, item.byteEnd)
        .equals(Buffer.from(item.exactText, "utf8")), "feature source span");
  }
  const records = patent.datasets?.find(item => item.datasetId === "DEV-CON-SYN-01")?.records;
  check(data.claimCandidates?.length === 2 && records?.length === 2,
    "claim source count");
  for (const [index, item] of data.claimCandidates.entries()) {
    const old = records[index];
    check(equal(Object.keys(item), ["recordId", "sourceVersion", "locator", "claimText"])
      && item.recordId === old.recordId && item.sourceVersion === old.sourceVersion
      && item.locator === old.locator && item.claimText === old.claimText
      && hash(Buffer.from(item.claimText, "utf8")) === old.contentSha256,
    "claim source version or digest");
  }
}

verify(manifest, payload, rawPayload, originals);
if (process.argv.includes("--self-test")) {
  function rejects(label, mutate, reason, use = "COMPARE_INPUT", withdrawal = 0) {
    const policy = structuredClone(manifest), data = structuredClone(payload);
    const sources = originals.map(item => ({ raw: item.raw, data: structuredClone(item.data) }));
    mutate(policy, data, sources);
    policy.payloadSha256 = hash(Buffer.from(JSON.stringify(data)));
    try { verify(policy, data, Buffer.from(JSON.stringify(data)), sources, use, withdrawal); }
    catch (error) {
      check(error.message.includes(reason), `wrong rejection for ${label}: ${error.message}`);
      return;
    }
    throw new Error("Consultation comparison input accepted " + label);
  }
  rejects("target drift", (_, data) => { data.target.supplement += "修改"; }, "target provenance");
  rejects("feature drift", (_, data) => { data.featureCandidates[0].exactText = "传感器"; }, "feature source span");
  rejects("claim drift", (_, data) => { data.claimCandidates[1].claimText += "修改"; }, "claim source version or digest");
  rejects("injected conclusion", (_, data) => { data.professionalRelation = "MATCH"; }, "unexpected payload field");
  rejects("old permission mutation", (_, __, source) => {
    source[1].data.deniedUses = [];
  }, "old feature permission or count");
  rejects("source digest drift", (policy) => {
    policy.sourceManifests[0].sha256 = "0".repeat(64);
  }, "source manifest digest");
  rejects("old candidate payload drift", (_, __, source) => {
    source[3].data.candidates[0].exactText = "篡改";
    source[3].raw = Buffer.from(JSON.stringify(source[3].data));
  }, "source manifest digest");
  rejects("unsupported output use", () => {}, "unapproved field or use", "PROFESSIONAL_RELATION_OUTPUT");
  rejects("withdrawal", () => {}, "scope or withdrawal", "COMPARE_INPUT", 1);
  console.log("Consultation comparison input self-test passed: provenance, permissions, injection, withdrawal");
}
console.log("Consultation comparison input verified: project-authored data for development input only");
