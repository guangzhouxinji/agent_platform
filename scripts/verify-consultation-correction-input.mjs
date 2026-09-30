import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dir = resolve(root, "data/consultation/dev-correction-input-v1");
const read = path => readFileSync(resolve(root, path));
const manifest = JSON.parse(readFileSync(resolve(dir, "manifest.json")));
const raw = readFileSync(resolve(dir, "input.json"));
const payload = JSON.parse(raw);
const sourceRaw = read("data/consultation/dev-comparison-input-v1/manifest.json");
const source = JSON.parse(sourceRaw);
const sourceInput = JSON.parse(read("data/consultation/dev-comparison-input-v1/input.json"));
const sha = value => createHash("sha256").update(value).digest("hex");
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const check = (ok, why) => { if (!ok) throw new Error("Consultation correction input: " + why); };

function verify(policy, data, bytes, parentBytes, parent, parentInput, use = "CORRECTION_INPUT", now = "2026-09-26T10:00:00+08:00") {
  check(policy.schemaVersion === 1 && data.schemaVersion === 1
    && policy.manifestId === "DEV-CON-CORRECTION-INPUT-01" && data.datasetId === policy.manifestId
    && policy.origin === "PROJECT_AUTHORED_SYNTHETIC" && data.origin === policy.origin
    && policy.environment === "isolated-development-only" && policy.status === "SYNTHETIC_DEV_ONLY"
    && policy.approvalRef === "docs/02-待确认事项清单.md#32-2026-09-25-项目数据处理与功能体验优先决定"
    && policy.organizationRef === "dev-con-isolated"
    && eq(policy.allowedPrincipalRefs, ["dev-con-a", "dev-con-b"])
    && policy.withdrawalVersion === 0 && policy.payloadFile === "input.json"
    && Date.parse(policy.validFrom) <= Date.parse(now) && Date.parse(now) < Date.parse(policy.validUntil),
  "scope, expiry or withdrawal");
  check(sha(bytes) === policy.payloadSha256, "payload digest");
  check(policy.sourceManifest?.manifestId === "DEV-CON-COMPARE-INPUT-01"
    && policy.sourceManifest.sha256 === sha(parentBytes)
    && parent.manifestId === policy.sourceManifest.manifestId
    && parent.status === "SYNTHETIC_DEV_ONLY"
    && parentInput.datasetId === parent.manifestId
    && parent.payloadSha256 === sha(read("data/consultation/dev-comparison-input-v1/input.json")),
  "source provenance");
  check(eq(Object.keys(data), ["schemaVersion", "datasetId", "origin", "correction"])
    && eq(Object.keys(data.correction ?? {}), ["sourceField", "oldText", "oldFragment", "newFragment", "newText", "userMessage"]),
  "payload shape");
  const c = data.correction;
  check(c.sourceField === "target.supplement" && c.oldText === parentInput.target.supplement
    && c.oldFragment === "阀门" && c.newFragment === "电磁阀"
    && c.newText === "补充：该方案使用温度传感器、自动控制器和电磁阀。"
    && c.userMessage === "纠正：补充里的阀门应为电磁阀，其他不变。"
    && c.oldText.split(c.oldFragment).length === 2
    && c.oldText.replace(c.oldFragment, c.newFragment) === c.newText,
  "exact correction");
  check(eq(policy.fields, {
    "correction.oldText": ["CORRECTION_INPUT"],
    "correction.newText": ["SAVE", "DISPLAY", "CORRECTION_INPUT"],
    "correction.userMessage": ["SAVE", "DISPLAY", "CORRECTION_INPUT"],
  }) && Object.values(policy.fields).every(uses => uses.includes(use))
    && eq(policy.deniedUses, ["AUTO_CONFIRM", "PROFESSIONAL_RELATION_OUTPUT", "LEGAL_CONCLUSION", "OFFICIAL_WORKFLOW", "MODEL_INPUT"]),
  "field use");
}

verify(manifest, payload, raw, sourceRaw, source, sourceInput);
if (process.argv.includes("--self-test")) {
  function rejects(label, mutate, why, use, now) {
    const p = structuredClone(manifest), d = structuredClone(payload);
    const s = structuredClone(source), si = structuredClone(sourceInput);
    mutate(p, d, s, si);
    const bytes = Buffer.from(JSON.stringify(d));
    p.payloadSha256 = sha(bytes);
    try { verify(p, d, bytes, sourceRaw, s, si, use, now); }
    catch (error) { check(error.message.includes(why), `${label}: unexpected rejection ${error.message}`); return; }
    throw new Error("Consultation correction input accepted " + label);
  }
  rejects("replacement drift", (_, d) => { d.correction.newText += "修改"; }, "exact correction");
  rejects("injected conclusion", (_, d) => { d.professionalRelation = "MATCH"; }, "payload shape");
  rejects("old source drift", (_, __, ___, si) => { si.target.supplement += "修改"; }, "exact correction");
  rejects("old version expansion", p => { p.sourceManifest.sha256 = "0".repeat(64); }, "source provenance");
  rejects("automatic confirmation", p => { p.fields["correction.newText"] = ["AUTO_CONFIRM"]; }, "field use");
  rejects("withdrawal", p => { p.withdrawalVersion = 1; }, "scope, expiry or withdrawal");
  rejects("principal drift", p => { p.allowedPrincipalRefs = ["dev-con-a"]; }, "scope, expiry or withdrawal");
  rejects("expired", () => {}, "scope, expiry or withdrawal", "CORRECTION_INPUT", "2027-03-24T00:00:00+08:00");
  rejects("professional output", () => {}, "field use", "PROFESSIONAL_RELATION_OUTPUT");
  console.log("Consultation correction input self-test passed: exact bytes, provenance, uses, tamper, withdrawal, expiry");
}
console.log("Consultation correction input verified: project-authored development input only");
