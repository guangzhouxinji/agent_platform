import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const directory = resolve(dirname(fileURLToPath(import.meta.url)),
  "../data/consultation/dev-human-gap-v1");
const manifest = JSON.parse(readFileSync(resolve(directory, "manifest.json")));
const bytes = readFileSync(resolve(directory, "input.json"));
const payload = JSON.parse(bytes);
const digest = (value) => createHash("sha256").update(value).digest("hex");
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const check = (value, reason) => { if (!value) throw new Error(`Consultation human gap: ${reason}`); };
const expected = [
  ["REQUEST_GAP", "REQUEST", "请人工核对当前分析目的与证据缺口。"],
  ["OPINION_GAP", "INTERNAL_TEST_OPINION",
    "当前分析目的尚未确认，逐项专业对应方法尚无获准依据；本次不形成技术对应结论。"]
];
const denied = ["MODEL_USE", "EXTERNAL_DELIVERY", "PROFESSIONAL_RELATION_OUTPUT",
  "LEGAL_CONCLUSION", "OFFICIAL_WORKFLOW", "CROSS_DOMAIN"];

function verify(policy, data, raw, { principal = "dev-con-a", organization = "dev-con-isolated",
  use = "SAVE", field = "request.text", now = "2026-09-26T00:00:00+08:00",
  withdrawalVersion = 0 } = {}) {
  check(policy.schemaVersion === 1 && data.schemaVersion === 1
    && policy.manifestId === "DEV-CON-HUMAN-GAP-01" && data.datasetId === policy.manifestId
    && policy.origin === "PROJECT_AUTHORED_SYNTHETIC" && data.origin === policy.origin
    && policy.environment === "isolated-development-only" && policy.status === "SYNTHETIC_DEV_ONLY"
    && policy.approvalRef === "docs/02-待确认事项清单.md#32-2026-09-25-项目数据处理与功能体验优先决定"
    && policy.workflowRef === "docs/02-待确认事项清单.md#28-2026-09-24-开发期系统内人工闭环决定"
    && policy.payloadFile === "input.json" && policy.organizationRef === organization
    && same(policy.allowedPrincipalRefs, ["dev-con-a", "dev-con-b"])
    && policy.allowedPrincipalRefs.includes(principal)
    && policy.withdrawalVersion === withdrawalVersion && withdrawalVersion === 0
    && Date.parse(policy.validFrom) <= Date.parse(now)
    && Date.parse(now) < Date.parse(policy.validUntil), "scope, time or withdrawal");
  check(same(policy.fields, { "request.text": ["SAVE", "DISPLAY"],
    "opinion.text": ["SAVE", "DISPLAY"] }) && same(policy.deniedUses, denied)
    && policy.fields[field]?.includes(use) && !denied.includes(use), "unapproved field or use");
  check(digest(raw) === policy.payloadSha256, "payload digest");
  check(same(Object.keys(data), ["schemaVersion", "datasetId", "origin", "entries"])
    && data.entries?.length === 2, "payload shape");
  for (const [index, entry] of data.entries.entries()) {
    check(same(Object.keys(entry), ["id", "kind", "text"])
      && same([entry.id, entry.kind, entry.text], expected[index]), "unsigned opinion content");
  }
}

verify(manifest, payload, bytes);
if (process.argv.includes("--self-test")) {
  function rejects(name, mutate, expectedReason, options = {}) {
    const policy = structuredClone(manifest), data = structuredClone(payload);
    mutate(policy, data);
    const raw = Buffer.from(JSON.stringify(data));
    if (name !== "raw tamper") policy.payloadSha256 = digest(raw);
    try { verify(policy, data, raw, options); }
    catch (error) {
      check(error.message.includes(expectedReason), `${name}: unexpected rejection ${error.message}`);
      return;
    }
    throw new Error(`Consultation human gap accepted ${name}`);
  }
  rejects("raw tamper", (_, data) => { data.entries[1].text += "修改"; }, "payload digest");
  rejects("coordinated rewrite", (_, data) => { data.entries[1].text += "已确认对应"; }, "unsigned opinion content");
  rejects("injected conclusion", (_, data) => { data.professionalRelation = "MATCH"; }, "payload shape");
  rejects("extra use", policy => { policy.fields["opinion.text"].push("MODEL_USE"); }, "unapproved field or use");
  rejects("wrong principal", () => {}, "scope, time or withdrawal", { principal: "dev-agent" });
  rejects("wrong organization", () => {}, "scope, time or withdrawal", { organization: "another-org" });
  rejects("expired", () => {}, "scope, time or withdrawal", { now: "2027-03-24T00:00:00+08:00" });
  rejects("withdrawn", policy => { policy.withdrawalVersion = 1; }, "scope, time or withdrawal");
  rejects("forbidden use", () => {}, "unapproved field or use", { use: "PROFESSIONAL_RELATION_OUTPUT" });
  console.log("Consultation human gap self-test passed: digest, content, scope, use, time and withdrawal");
}
console.log("Consultation human gap input verified: project-authored development text only");
