import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const oldDirectory = resolve(root, "data/enforcement/dev-synthetic-material-v1");
const directory = resolve(root, "data/enforcement/dev-synthetic-material-v2");
const oldManifestBytes = readFileSync(resolve(oldDirectory, "manifest.json"));
const oldPayloadBytes = readFileSync(resolve(oldDirectory, "observations.json"));
const manifest = JSON.parse(readFileSync(resolve(directory, "manifest.json")));
const payloadBytes = readFileSync(resolve(directory, "observations.json"));
const hash = value => createHash("sha256").update(value).digest("hex");
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const check = (condition, message) => {
  if (!condition) throw new Error("Enforcement synthetic v2: " + message);
};
const fields = ["observationId", "observationVersion", "text", "subjectMentions",
  "factMentions", "materialMentions", "missingItems"];
const basic = ["QUERY", "SAVE", "DISPLAY"];
const indexed = [...basic, "INDEX", "CANDIDATE_COMPARE_INPUT"];
const compared = [...basic, "CANDIDATE_COMPARE_INPUT"];
const denied = ["DOWNLOAD", "MODEL_USE", "CROSS_DOMAIN", "NOTIFY", "OFFICIAL_WORKFLOW",
  "FORMAL_EVIDENCE", "HUMAN_VERIFICATION", "LEGAL_CONCLUSION", "OFFICIAL_STATE_WRITE"];
const oldDenied = ["INDEX", "DOWNLOAD", "MODEL_USE", "CROSS_DOMAIN", "NOTIFY", "OFFICIAL_WORKFLOW"];
const compareScope = "Compare only two signed synthetic observation versions, stated roles, text locators, material mentions and missing items; output is unconfirmed development candidate, never accepted evidence or violation finding";
const signedBytes = {
  oldManifest: "a51ac5b8b71693e00308d2950081f13e310fabc9bbb944322bd7c7d717f656d5",
  oldPayload: "ad54c86c411ab858ca624125850e8aca68f3bed8d5a7e0a6e182ec2983b0b4b0",
  newPayload: "ad54c86c411ab858ca624125850e8aca68f3bed8d5a7e0a6e182ec2983b0b4b0",
};

function verify(policy, payload, bytes, oldManifestRaw, oldPayloadRaw,
                field = "text", use = "CANDIDATE_COMPARE_INPUT", withdrawal = 0,
                now = Date.parse("2026-09-25T12:00:00+08:00")) {
  check(hash(oldManifestRaw) === signedBytes.oldManifest
    && hash(oldPayloadRaw) === signedBytes.oldPayload
    && hash(bytes) === signedBytes.newPayload, "signed byte anchor");
  const old = JSON.parse(oldManifestRaw), oldRecords = JSON.parse(oldPayloadRaw);
  check(old.manifestId === "DEV-ENF-MATERIAL-INPUT-01"
    && old.datasetId === "DEV-ENF-SYN-MATERIAL-01" && old.manifestVersion === 1
    && old.origin === "PROJECT_AUTHORED_SYNTHETIC" && old.status === "ACTIVE"
    && old.withdrawalVersion === 0 && old.organizationRef === "dev-enf-isolated"
    && equal(old.allowedPrincipalRefs, ["dev-enf-a"])
    && equal(Object.keys(old.fields), fields)
    && fields.every(name => equal(old.fields[name], basic))
    && equal(old.deniedUses, oldDenied)
    && hash(oldPayloadRaw) === old.payloadSha256, "old version permission or payload drift");
  check(policy.schemaVersion === 2 && policy.manifestId === "DEV-ENF-MATERIAL-INPUT-02"
    && policy.datasetId === "DEV-ENF-SYN-MATERIAL-02" && policy.manifestVersion === 2
    && policy.origin === "PROJECT_AUTHORED_SYNTHETIC"
    && policy.environment === "isolated-development-only"
    && policy.approvalRef === "docs/02-待确认事项清单.md#32-2026-09-25-项目数据处理与功能体验优先决定"
    && policy.approvalScope === "Only project-authored synthetic observation text for isolated internal development; no external or client material"
    && policy.status === "SYNTHETIC_DEV_ONLY" && policy.withdrawalVersion === withdrawal
    && policy.organizationRef === old.organizationRef
    && equal(policy.allowedPrincipalRefs, old.allowedPrincipalRefs)
    && policy.payloadFile === "observations.json"
    && Number.isFinite(now) && Date.parse(policy.validFrom) <= now
    && now < Date.parse(policy.validUntil)
    && Date.parse(policy.validFrom) >= Date.parse(old.validFrom)
    && Date.parse(policy.validUntil) <= Date.parse(old.validUntil), "scope, term or withdrawal");
  check(policy.sourceManifest?.manifestId === old.manifestId
    && policy.sourceManifest.sha256 === hash(oldManifestRaw)
    && policy.sourceManifest.payloadSha256 === hash(oldPayloadRaw), "source digest");
  check(equal(Object.keys(policy.fields), fields)
    && fields.every(name => equal(policy.fields[name],
      ["observationId", "observationVersion", "text"].includes(name) ? indexed : compared))
    && policy.candidateCompareScope === compareScope
    && equal(policy.deniedUses, denied)
    && !denied.includes(use) && policy.fields[field]?.includes(use), "field or use denied");
  check(hash(bytes) === policy.payloadSha256
    && equal(payload, oldRecords)
    && Array.isArray(payload) && payload.length === 2
    && equal(policy.records, old.records)
    && payload.every((record, index) => hash(JSON.stringify(record)) === policy.records[index].sha256)
    && policy.batchSha256 === old.batchSha256, "payload, record or batch drift");
  for (const record of payload) {
    check(record.subjectMentions?.length === 2
      && record.subjectMentions.every(item => item.creditCode === null)
      && record.materialMentions?.length === 1
      && record.materialMentions[0].availability === "MENTION_ONLY_NO_BYTES"
      && equal(record.missingItems, ["MATERIAL_BYTES", "COLLECTION_DETAILS", "CURRENT_CASE_AUTHORITY"]),
    "missing material or unsupported evidence");
  }
}

const oldManifest = JSON.parse(oldManifestBytes);
const payload = JSON.parse(payloadBytes);
verify(manifest, payload, payloadBytes, oldManifestBytes, oldPayloadBytes);
if (process.argv.includes("--self-test")) {
  function rejects(label, mutate, reason, field = "text", use = "CANDIDATE_COMPARE_INPUT",
                   withdrawal = 0, now = Date.parse("2026-09-25T12:00:00+08:00")) {
    const policy = structuredClone(manifest), data = structuredClone(payload);
    let sourceManifest = Buffer.from(oldManifestBytes), sourcePayload = Buffer.from(oldPayloadBytes);
    const sources = {
      changeManifest(value) { sourceManifest = Buffer.from(JSON.stringify(value)); },
      changePayload(value) { sourcePayload = Buffer.from(JSON.stringify(value)); },
    };
    mutate(policy, data, sources);
    const bytes = equal(data, payload) ? payloadBytes : Buffer.from(JSON.stringify(data));
    policy.payloadSha256 = hash(bytes);
    try { verify(policy, data, bytes, sourceManifest, sourcePayload, field, use, withdrawal, now); }
    catch (error) {
      check(error.message.includes(reason), `wrong rejection for ${label}: ${error.message}`);
      return;
    }
    throw new Error("Enforcement synthetic v2 accepted " + label);
  }
  rejects("tampered text", (_, data) => { data[0].text += "篡改"; }, "signed byte anchor");
  rejects("injected official status", (_, data) => { data[0].officialState = "CONFIRMED"; }, "signed byte anchor");
  rejects("source manifest drift", (_, __, source) => {
    source.changeManifest({ ...oldManifest, approvalScope: "changed" });
  }, "signed byte anchor");
  rejects("old version permission expanded with updated binding", (policy, _, source) => {
    const changed = structuredClone(oldManifest);
    changed.fields.text.push("INDEX");
    const raw = Buffer.from(JSON.stringify(changed));
    source.changeManifest(changed);
    policy.sourceManifest.sha256 = hash(raw);
  }, "signed byte anchor");
  rejects("old source payload drift", (policy, _, source) => {
    const changed = JSON.parse(oldPayloadBytes);
    changed[0].text += "篡改";
    const raw = Buffer.from(JSON.stringify(changed));
    source.changePayload(changed);
    policy.sourceManifest.payloadSha256 = hash(raw);
  }, "signed byte anchor");
  rejects("coordinated re-signing", (policy, data, source) => {
    data[0].text += "测试补充";
    const changedOld = structuredClone(oldManifest);
    const recordSha = hash(JSON.stringify(data[0]));
    changedOld.records[0].sha256 = recordSha;
    changedOld.payloadSha256 = hash(Buffer.from(JSON.stringify(data)));
    const batch = data.map((record, index) =>
      `${record.observationId}\t${record.observationVersion}\t${changedOld.records[index].sha256}\n`).join("");
    changedOld.batchSha256 = hash(batch);
    source.changePayload(data);
    source.changeManifest(changedOld);
    policy.sourceManifest.sha256 = hash(Buffer.from(JSON.stringify(changedOld)));
    policy.sourceManifest.payloadSha256 = changedOld.payloadSha256;
    policy.records = structuredClone(changedOld.records);
    policy.batchSha256 = changedOld.batchSha256;
  }, "signed byte anchor");
  rejects("new source binding drift", policy => {
    policy.sourceManifest.sha256 = "0".repeat(64);
  }, "source digest");
  rejects("field index overreach", () => {}, "field or use denied", "materialMentions", "INDEX");
  rejects("notification", () => {}, "field or use denied", "text", "NOTIFY");
  rejects("formal evidence", () => {}, "field or use denied", "text", "FORMAL_EVIDENCE");
  rejects("official state", () => {}, "field or use denied", "text", "OFFICIAL_STATE_WRITE");
  rejects("model use", () => {}, "field or use denied", "text", "MODEL_USE");
  rejects("withdrawal", () => {}, "scope, term or withdrawal", "text", "QUERY", 1);
  rejects("expiry", () => {}, "scope, term or withdrawal", "text", "QUERY", 0,
    Date.parse(manifest.validUntil));
  check(!oldManifest.fields.text.includes("INDEX")
    && !oldManifest.fields.text.includes("CANDIDATE_COMPARE_INPUT")
    && oldManifest.deniedUses.includes("INDEX"), "old version expanded");
  console.log("Enforcement synthetic v2 self-test passed: signed bytes, coordinated tamper, old-use isolation, scope, withdrawal");
}
console.log("Enforcement synthetic v2 verified: project-authored observation input, no formal evidence or status");
