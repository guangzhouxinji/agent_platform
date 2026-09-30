import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const root = "data/enforcement/evidence-v2-acceptance-v1/";
const manifest = JSON.parse(readFileSync(`${root}manifest.json`, "utf8"));
const names = ["record-a.txt", "record-b.txt", "record-c.txt"];
const bytes = Object.fromEntries(names.map((name) => [name, readFileSync(root + name)]));
const hash = (value) => createHash("sha256").update(value).digest("hex");
const fail = (reason) => { throw new Error(reason); };
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

function verify(input, files = bytes) {
  if (input.schemaVersion !== "enf-evidence-v2-acceptance-input-v1" ||
      input.status !== "INPUT_ONLY_NOT_PRODUCT_EVIDENCE" ||
      input.origin !== "PROJECT_AUTHORED_FICTIONAL" ||
      !input.rightsBasis.includes("Project owner instruction") ||
      !input.scope.includes("no real case") ||
      !same(input.allowedUses, ["SAVE", "PREVIEW", "ANALYZE", "DISPLAY"]) ||
      !same(input.deniedUses, ["MODEL", "SHARE", "OFFICIAL_EVIDENCE", "OFFICIAL_DECISION", "PRODUCTION_SOURCE"]) ||
      input.expiresAt !== null) fail("SCOPE_OR_USE");
  if (input.caseInput.authority !== "PROJECT_TEST_INPUT_NOT_CENTER_CASE" ||
      input.caseInput.caseType !== "PROJECT_DOCUMENT_REVIEW" ||
      input.caseInput.organization !== "ORG-ENF-PROJECT" ||
      input.caseInput.eventRef !== "PROJECT-EVENT-001" ||
      input.ruleInput.status !== "DRAFT_INPUT_NOT_PUBLISHED" ||
      input.ruleInput.caseType !== input.caseInput.caseType ||
      !input.ruleInput.basis.includes("not legal authority") ||
      !same(input.ruleInput.matters.map((matter) => [matter.id, matter.required]),
        [["ITEM_IDENTITY", true], ["EVENT_TIME", true]]) ||
      input.ruleInput.missingPolicy !== "UNVERIFIED_WITH_GAP" ||
      input.ruleInput.conflictPolicy !== "KEEP_BOTH_PENDING_HUMAN_REVIEW" ||
      input.expectedOnlyNotAnAnalysisResult !== true) fail("RULE_OR_CASE");
  if (input.materials.length !== 3 || !same(input.materials.map((item) => item.path), names))
    fail("MATERIAL_SET");
  for (const item of input.materials) {
    if (item.status !== "INPUT_ONLY_NOT_VERIFIED" || item.version !== "1" ||
        item.sha256 !== hash(files[item.path]) || item.byteLength !== files[item.path]?.length)
      fail("MATERIAL_DIGEST");
    const lines = files[item.path].toString("utf8").split(/\r?\n/);
    for (const [matter, locator] of Object.entries(item.locators)) {
      const line = Number(/^line (\d+)$/.exec(locator)?.[1]);
      if (!line || !lines[line - 1] ||
          (matter === "ITEM_IDENTITY" && !lines[line - 1].includes("labeled")) ||
          (matter === "EVENT_TIME" && !lines[line - 1].includes("event time")))
        fail("LOCATOR");
    }
  }
  const expected = [
    ["PROJECT-RECORD-A@1", "ITEM_IDENTITY", "PROVE", "line 3"],
    ["PROJECT-RECORD-A@1", "EVENT_TIME", "PROVE", "line 4"],
    ["PROJECT-RECORD-B@1", "ITEM_IDENTITY", "CORROBORATE", "line 3"],
    ["PROJECT-RECORD-B@1", "EVENT_TIME", "CONTRADICT", "line 4"],
    ["PROJECT-RECORD-C@1", "ITEM_IDENTITY", "IRRELEVANT", "line 3"]
  ];
  if (!same(input.expectedRelations.map((row) =>
    [row.material, row.matter, row.relation, row.locator]), expected) ||
      !same(input.negativeInputs, ["MISSING_REQUIRED_MATTER", "DUPLICATE_MATTER", "EMPTY_MAPPING",
        "UNKNOWN_CASE_TYPE", "DRAFT_RULE", "MISSING_BYTES", "WITHDRAWN", "CROSS_CASE",
        "STALE_VERSION", "SOURCE_UNAVAILABLE", "EXCLUDED_AFTER_RUN", "RULE_CHANGED",
        "HUMAN_CORRECTION_RERUN"])) fail("EXPECTATIONS");
}

verify(manifest);
if (process.argv.includes("--self-test")) {
  const rejects = (code, changed, files = bytes) => {
    try { verify(changed, files); } catch (error) { if (error.message === code) return; }
    fail(`SELF_TEST_${code}`);
  };
  const clone = () => structuredClone(manifest);
  const scope = clone(); scope.allowedUses.push("OFFICIAL_DECISION"); rejects("SCOPE_OR_USE", scope);
  const caseInput = clone(); caseInput.caseInput.authority = "CENTER_CASE"; rejects("RULE_OR_CASE", caseInput);
  const published = clone(); published.ruleInput.status = "PUBLISHED"; rejects("RULE_OR_CASE", published);
  rejects("MATERIAL_DIGEST", manifest, { ...bytes, "record-b.txt": Buffer.concat([bytes["record-b.txt"], Buffer.from("x")]) });
  const locator = clone(); locator.materials[0].locators.EVENT_TIME = "line 3"; rejects("LOCATOR", locator);
  const relation = clone(); relation.expectedRelations.pop(); rejects("EXPECTATIONS", relation);
  console.log("Evidence acceptance input self-test: 1 valid, 6 rejected; no product action");
} else {
  console.log("Evidence acceptance input verified: 3 project-authored files; draft expectations only");
}
