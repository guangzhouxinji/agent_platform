import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const directory = resolve(root, "data/consultation/dev-feature-candidates-v1");
const bytes = readFileSync(resolve(directory, "candidates.json"));
const payload = JSON.parse(bytes);
const manifest = JSON.parse(readFileSync(resolve(directory, "manifest.json"), "utf8"));
const input = JSON.parse(readFileSync(resolve(root,
  "data/consultation/dev-synthetic-v1/task-input-manifest.json"), "utf8"));
const hash = value => createHash("sha256").update(value).digest("hex");
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const check = (condition, message) => {
  if (!condition) throw new Error("Consultation feature input: " + message);
};
const uses = ["QUERY", "SAVE", "DISPLAY"];
const fields = ["featureId", "exactText", "byteStart", "byteEnd", "status"];
const denied = ["INDEX", "DOWNLOAD", "MODEL_USE", "CROSS_DOMAIN", "PROFESSIONAL_COMPARE", "AUTO_CONFIRM"];
const expected = [["F1", "温度传感器"], ["F2", "自动控制器"], ["F3", "阀门"]];

function verify(policy, data, raw, source, use = "QUERY", currentWithdrawalVersion = 0) {
  check(policy.schemaVersion === 1 && data.schemaVersion === 1
    && policy.manifestId === data.datasetId && policy.manifestId === "DEV-CON-FEATURE-CANDIDATES-01"
    && policy.origin === "PROJECT_AUTHORED_SYNTHETIC"
    && policy.environment === "isolated-development-only"
    && policy.status === "SYNTHETIC_DEV_ONLY" && policy.withdrawalVersion === currentWithdrawalVersion
    && policy.payloadFile === "candidates.json" && policy.organizationRef === "dev-con-isolated"
    && equal(policy.allowedPrincipalRefs, ["dev-con-a", "dev-con-b"])
    && policy.approvalRef === "docs/08-总开发实现计划.md#DATA-CON-FEATURE-INPUT-01"
    && Date.parse(policy.validFrom) < Date.parse(policy.validUntil), "scope or withdrawal");
  check(equal(Object.keys(policy.fields), fields)
    && fields.every(field => equal(policy.fields[field], uses))
    && equal(policy.deniedUses, denied) && uses.includes(use), "unapproved field or use");
  check(source.manifestId === "DEV-CON-INPUT-01" && source.status === "SYNTHETIC_DEV_ONLY"
    && data.source?.inputManifestId === source.manifestId && data.source.kind === "SUPPLEMENT",
  "source manifest");
  const supplement = source.entries.find(entry => entry.kind === "SUPPLEMENT");
  check(supplement?.sha256 === hash(Buffer.from(supplement.text, "utf8"))
    && data.source.inputSha256 === supplement.sha256 && equal(supplement.uses, ["SAVE"]), "source input drift");
  check(hash(raw) === policy.payloadSha256 && data.candidates?.length === 3
    && policy.candidateDigests?.length === 3, "payload digest or count");
  const sourceBytes = Buffer.from(supplement.text, "utf8");
  const fingerprints = [];
  for (const [index, candidate] of data.candidates.entries()) {
    const [id, exactText] = expected[index];
    const fragment = Buffer.from(exactText, "utf8");
    const start = sourceBytes.indexOf(fragment);
    check(equal(Object.keys(candidate), fields) && candidate.featureId === id
      && candidate.exactText === exactText && candidate.status === "UNCONFIRMED"
      && start >= 0 && sourceBytes.indexOf(fragment, start + 1) < 0
      && candidate.byteStart === start && candidate.byteEnd === start + fragment.length
      && sourceBytes.subarray(candidate.byteStart, candidate.byteEnd).equals(fragment),
    "unconfirmed source span " + id);
    const digest = hash(JSON.stringify(candidate));
    check(equal(policy.candidateDigests[index], { featureId: id, sha256: digest }), "candidate digest " + id);
    fingerprints.push(id + "\t" + digest + "\n");
  }
  check(hash(fingerprints.join("")) === policy.batchSha256, "batch digest");
}

verify(manifest, payload, bytes, input);
if (process.argv.includes("--self-test")) {
  function rejects(label, mutate, expectedError, use = "QUERY", withdrawal = 0, resign = true) {
    const policy = structuredClone(manifest), data = structuredClone(payload), source = structuredClone(input);
    mutate(policy, data, source);
    const raw = resign ? Buffer.from(JSON.stringify(data)) : bytes;
    if (resign) {
      policy.payloadSha256 = hash(raw);
      policy.candidateDigests = data.candidates.map(candidate =>
        ({ featureId: candidate.featureId, sha256: hash(JSON.stringify(candidate)) }));
      policy.batchSha256 = hash(policy.candidateDigests.map(item =>
        item.featureId + "\t" + item.sha256 + "\n").join(""));
    }
    try { verify(policy, data, raw, source, use, withdrawal); }
    catch (error) {
      check(error.message.includes(expectedError), "wrong rejection for " + label + ": " + error.message);
      return;
    }
    throw new Error("Consultation feature input accepted " + label);
  }
  rejects("tampered text", (_, data) => { data.candidates[0].exactText = "伪造传感器"; }, "unconfirmed source span");
  rejects("wrong UTF-8 offset", (_, data) => { data.candidates[1].byteStart++; }, "unconfirmed source span");
  rejects("automatic confirmation", (_, data) => { data.candidates[2].status = "CONFIRMED"; }, "unconfirmed source span");
  rejects("missing feature", (_, data) => { data.candidates.pop(); }, "payload digest or count");
  rejects("source drift", (_, __, source) => { source.entries[1].text += "变更"; }, "source input drift");
  rejects("forbidden model use", () => {}, "unapproved field or use", "MODEL_USE");
  rejects("withdrawal", () => {}, "scope or withdrawal", "QUERY", 1);
  rejects("payload digest", policy => { policy.payloadSha256 = "0".repeat(64); },
    "payload digest or count", "QUERY", 0, false);
  console.log("Consultation feature candidate self-test passed: drift, spans, unconfirmed status, uses, withdrawal");
}
console.log("Consultation feature candidates verified: 3 unconfirmed spans of signed synthetic supplement");
