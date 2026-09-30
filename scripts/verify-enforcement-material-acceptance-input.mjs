import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const manifestPath = "data/enforcement/material-v2-acceptance-v1/manifest.json";
const sourcePath = "data/enforcement/material-dev-product-contract-v1/manifest.json";
const observationPath = "data/enforcement/material-dev-product-contract-v1/observations.json";
const materialPath = "data/enforcement/material-dev-product-contract-v1/original-M-01.txt";
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const fail = (code) => { throw new Error(code); };
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);

function verify(manifest, source, bytes) {
  if (manifest.schemaVersion !== "enf-material-v2-acceptance-input-v1" ||
      manifest.inputId !== "ENF-MATERIAL-V2-ACCEPTANCE-01" ||
      manifest.status !== "INPUT_PREPARED_NOT_D_ACCEPTED" ||
      manifest.origin !== "PROJECT_AUTHORED_FICTIONAL" ||
      !manifest.rightsBasis.includes("docs/02 section 43") ||
      manifest.sourcePackage.path !== sourcePath || !manifest.sourcePackage.originalScopePreserved ||
      manifest.sourcePackage.sha256 !== hash(bytes.source) ||
      source.scope !== "ENF-01..05 development contract verification only; not a real case, source, evidence finding or legal conclusion" ||
      source.origin !== manifest.origin) fail("SCOPE");
  if (!same(manifest.deniedUses, ["MODEL", "SHARE", "OFFICIAL_EVIDENCE", "OFFICIAL_DECISION", "PRODUCTION_SOURCE"]) ||
      !same(manifest.limits, { realCase: false, thirdPartyRights: false, legalEffect: false,
        realOfficer: false, productionRecoveryApproval: false }) || manifest.expiresAt !== null ||
      manifest.inputs.length !== 2) fail("LIMITS");
  const expected = [
    ["SOURCE_OBSERVATIONS", observationPath, bytes.observations, source.observations, ["QUERY", "SAVE", "INDEX", "DISPLAY"]],
    ["ORIGINAL_MATERIAL", materialPath, bytes.material, source.material, ["SAVE", "PREVIEW", "DOWNLOAD"]]
  ];
  for (const [index, [kind, path, value, original, uses]] of expected.entries()) {
    const input = manifest.inputs[index];
    if (input.kind !== kind || input.path !== path || input.sha256 !== hash(value) ||
        input.sha256 !== original.sha256 || !same(input.uses, uses) ||
        !same(input.uses, original.allowedUses) ||
        (input.byteLength !== undefined && input.byteLength !== value.length) ||
        input.uses.some((use) => manifest.deniedUses.includes(use))) fail("INPUT_DIGEST_OR_USE");
  }
  if (manifest.inputs[1].byteLength !== 317 || !bytes.material.toString("utf8").includes("不是现案证据"))
    fail("MATERIAL_SCOPE");
}

const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const bytes = { source: readFileSync(sourcePath), observations: readFileSync(observationPath),
  material: readFileSync(materialPath) };
const source = JSON.parse(bytes.source);
verify(manifest, source, bytes);

if (process.argv.includes("--self-test")) {
  const rejects = (code, changed, changedBytes = bytes) => {
    try { verify(changed, source, changedBytes); } catch (error) { if (error.message === code) return; }
    fail(`SELF_TEST_${code}`);
  };
  const copy = () => structuredClone(manifest);
  const official = copy(); official.limits.realCase = true; rejects("LIMITS", official);
  const scope = copy(); scope.sourcePackage.originalScopePreserved = false; rejects("SCOPE", scope);
  const use = copy(); use.inputs[1].uses.push("OFFICIAL_EVIDENCE"); rejects("INPUT_DIGEST_OR_USE", use);
  rejects("INPUT_DIGEST_OR_USE", manifest, { ...bytes, material: Buffer.concat([bytes.material, Buffer.from("x")]) });
  console.log("Enforcement MATERIAL acceptance input self-test: 1 valid, 4 rejected; no product action");
} else {
  console.log("Enforcement MATERIAL acceptance input verified: 2 pinned project-authored files; not D/B approval");
}
