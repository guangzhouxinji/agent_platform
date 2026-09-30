import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const readJson = path => JSON.parse(readFileSync(path, "utf8"));
const sha256 = path => createHash("sha256").update(readFileSync(path)).digest("hex");
const fail = code => { throw new Error(code); };
const templatePath = "data/enforcement/document-template-v2-d-input-v1/manifest.json";
const legalPath = "data/enforcement/legal-reference-v2-d-input-v1/manifest.json";
const penaltyPath = "data/enforcement/penalty-v2-d-input-v1/manifest.json";
const rulePath = "data/enforcement/document-rule-v2-d-input-v1/manifest.json";
const template = readJson(templatePath);
const legal = readJson(legalPath);
const penalty = readJson(penaltyPath);
const rule = readJson(rulePath);

function archive(path, operation, entry) {
  const result = spawnSync("tar", [operation, path, ...(entry ? [entry] : [])], { encoding: "utf8" });
  if (result.status !== 0) fail("DOCX_ARCHIVE");
  return result.stdout;
}

function verifyTemplate(input = template) {
  if (input.schemaVersion !== "enf-document-template-v2-d-input-v1" ||
      input.status !== "INPUT_ONLY_NOT_APPROVED_TEMPLATE" ||
      input.origin !== "PROJECT_AUTHORED" ||
      !input.deniedUses.includes("OFFICIAL_NUMBER") ||
      input.template.formalTemplateApproval !== "NOT_ASSERTED") fail("TEMPLATE_SCOPE");
  const docx = `data/enforcement/document-template-v2-d-input-v1/${input.artifact.file}`;
  if (sha256(docx) !== input.artifact.sha256) fail("TEMPLATE_SHA");
  const entries = archive(docx, "-tf").split(/\r?\n/).filter(Boolean);
  for (const required of ["[Content_Types].xml", "word/document.xml", "word/styles.xml", "word/header1.xml"])
    if (!entries.includes(required)) fail("DOCX_PARTS");
  if (entries.some(entry => /vbaProject|activeX|embeddings/i.test(entry))) fail("EXECUTABLE_PART");
  const documentXml = archive(docx, "-xOf", "word/document.xml");
  const relationshipXml = archive(docx, "-xOf", "word/_rels/document.xml.rels");
  if (/TargetMode="External"/i.test(relationshipXml)) fail("EXTERNAL_RELATIONSHIP");
  const placeholders = [...documentXml.matchAll(/\{\{([^{}]+)\}\}/g)].map(match => match[1]);
  const configured = input.template.placeholders.map(item => item.id);
  if (new Set(placeholders).size !== placeholders.length ||
      JSON.stringify(placeholders.sort()) !== JSON.stringify([...configured].sort())) fail("PLACEHOLDERS");
  if (!input.template.placeholders.every(item => item.id && item.type && typeof item.required === "boolean") ||
      input.template.placeholders.filter(item => item.repeat).length !== 2) fail("FIELD_DEFINITIONS");
  if (JSON.stringify(input.scenarios.map(item => item.id)) !== JSON.stringify(["T01", "T02", "T03", "T04", "T05", "T06"]) ||
      !input.scenarios.every(item => item.input && item.expected && item.forbidden.length)) fail("TEMPLATE_SCENARIOS");
}

function verifyLegal(input = legal) {
  if (input.schemaVersion !== "enf-legal-reference-v2-d-input-v1" ||
      input.status !== "INPUT_ONLY_REVIEW_REQUIRED" ||
      !input.deniedUses.includes("ASSERT_LEGAL_APPLICABILITY") ||
      input.projectTarget.legalApplicability !== "UNCONFIRMED") fail("LEGAL_SCOPE");
  const sourceManifest = readJson(input.sourceCatalog.manifest);
  if (sourceManifest.catalogId !== input.sourceCatalog.catalogId ||
      sourceManifest.reviewStatus !== "REVIEW_REQUIRED" ||
      sourceManifest.boundaries.formalTemplateImportAllowed !== false) fail("SOURCE_CATALOG");
  for (const reference of input.sourceCatalog.references) {
    const source = sourceManifest.snapshots.find(item => item.id === reference.id);
    if (!source || source.reviewStatus !== "REVIEW_REQUIRED" || source.sha256 !== reference.sha256 ||
        sha256(reference.file) !== reference.sha256) fail("LEGAL_SOURCE_SHA");
  }
  if (JSON.stringify(input.scenarios.map(item => item.id)) !== JSON.stringify(["L01", "L02", "L03", "L04", "L05", "L06"]) ||
      !input.scenarios.every(item => item.input && item.expected && item.forbidden.length)) fail("LEGAL_SCENARIOS");
}

function verifyPenalty(input = penalty) {
  if (input.schemaVersion !== "enf-penalty-v2-d-input-v1" ||
      input.status !== "INPUT_ONLY_NOT_PUBLISHED" || input.origin !== "PROJECT_AUTHORED_FICTIONAL" ||
      !input.deniedUses.includes("FORMAL_PENALTY_DECISION") ||
      input.ruleCandidate.state !== "DRAFT_INPUT_NOT_PUBLISHED" ||
      input.ruleCandidate.legalBasis.status !== "REVIEW_REQUIRED") fail("PENALTY_SCOPE");
  const semantics = input.ruleCandidate.amountSemantics;
  if (!semantics.kind || !semantics.unit || !Number.isInteger(semantics.scale) || !semantics.rounding ||
      input.ruleCandidate.formula.type !== "MULTIPLY" ||
      input.ruleCandidate.formula.left.zeroPolicy !== "VALID_IF_CONFIRMED") fail("FORMULA_SEMANTICS");
  if (input.upstream.conductReviewState !== "UNREVIEWED" || input.upstream.confirmedFacts.length !== 0)
    fail("UPSTREAM_NOT_CONFIRMED");
  if (JSON.stringify(input.scenarios.map(item => item.id)) !== JSON.stringify(["PEN01", "PEN02", "PEN03", "PEN04", "PEN05", "PEN06"]) ||
      !input.scenarios.every(item => item.input && item.expected && item.forbidden.length)) fail("PENALTY_SCENARIOS");
}

function verifyDocumentRule(input = rule) {
  if (input.schemaVersion !== "enf-document-rule-v2-d-input-v1" ||
      input.status !== "INPUT_ONLY_NOT_PUBLISHED" || input.origin !== "PROJECT_AUTHORED_FICTIONAL" ||
      !input.deniedUses.includes("INVENT_INTERVIEW") ||
      input.templateRef.status !== "PROJECT_INPUT_ONLY" ||
      input.schemaRegistry.status !== "PROJECT_TEST_NOT_CENTER_SCHEMA" ||
      input.ruleCandidate.state !== "DRAFT_INPUT_NOT_PUBLISHED") fail("RULE_SCOPE");
  const fields = new Map(input.schemaRegistry.fields.map(item => [item.path, item]));
  if (fields.size !== input.schemaRegistry.fields.length ||
      !input.ruleCandidate.mappings.every(item => fields.has(item.sourcePath) &&
        ["DIRECT", "DETERMINISTIC", "AUXILIARY_DRAFT"].includes(item.mode) &&
        ["BLOCK", "BLANK_WITH_WARNING", "OMIT_OPTIONAL"].includes(item.missing))) fail("RULE_MAPPINGS");
  if (fields.get("interview.questionsAnswers")?.confirmed !== false ||
      fields.get("penalty.amount")?.confirmed !== false ||
      fields.get("legal.references[]")?.confirmed !== false) fail("UNCONFIRMED_INPUTS");
  if (JSON.stringify(input.scenarios.map(item => item.id)) !== JSON.stringify(["R01", "R02", "R03", "R04", "R05", "R06"]) ||
      !input.scenarios.every(item => item.input && item.expected && item.forbidden.length)) fail("RULE_SCENARIOS");
}

verifyTemplate();
verifyLegal();
verifyPenalty();
verifyDocumentRule();

if (process.argv.includes("--self-test")) {
  let rejected = 0;
  const rejects = (verify, source, mutate, code) => {
    const copy = structuredClone(source); mutate(copy);
    try { verify(copy); } catch (error) { if (error.message === code) { rejected++; return; } }
    fail(`SELF_TEST_${code}`);
  };
  rejects(verifyTemplate, template, item => { item.status = "APPROVED"; }, "TEMPLATE_SCOPE");
  rejects(verifyTemplate, template, item => { item.artifact.sha256 = "0".repeat(64); }, "TEMPLATE_SHA");
  rejects(verifyTemplate, template, item => { item.template.placeholders.pop(); }, "PLACEHOLDERS");
  rejects(verifyTemplate, template, item => { item.scenarios[1].forbidden = []; }, "TEMPLATE_SCENARIOS");
  rejects(verifyLegal, legal, item => { item.projectTarget.legalApplicability = "CONFIRMED"; }, "LEGAL_SCOPE");
  rejects(verifyLegal, legal, item => { item.sourceCatalog.references[0].sha256 = "0".repeat(64); }, "LEGAL_SOURCE_SHA");
  rejects(verifyLegal, legal, item => { item.scenarios[2].forbidden = []; }, "LEGAL_SCENARIOS");
  rejects(verifyPenalty, penalty, item => { item.ruleCandidate.state = "ACTIVE"; }, "PENALTY_SCOPE");
  rejects(verifyPenalty, penalty, item => { item.ruleCandidate.amountSemantics.rounding = null; }, "FORMULA_SEMANTICS");
  rejects(verifyPenalty, penalty, item => { item.upstream.conductReviewState = "REVIEWED"; }, "UPSTREAM_NOT_CONFIRMED");
  rejects(verifyPenalty, penalty, item => { item.scenarios[4].forbidden = []; }, "PENALTY_SCENARIOS");
  rejects(verifyDocumentRule, rule, item => { item.status = "PUBLISHED"; }, "RULE_SCOPE");
  rejects(verifyDocumentRule, rule, item => { item.ruleCandidate.mappings[0].sourcePath = "missing.path"; }, "RULE_MAPPINGS");
  rejects(verifyDocumentRule, rule, item => { item.schemaRegistry.fields.find(field => field.path === "interview.questionsAnswers").confirmed = true; }, "UNCONFIRMED_INPUTS");
  rejects(verifyDocumentRule, rule, item => { item.scenarios[3].forbidden = []; }, "RULE_SCENARIOS");
  assert.equal(rejected, 15);
  console.log(`ENF-12/13/17/15-16 D inputs: 4 intact, ${rejected} rejected mutations; no product execution`);
} else {
  console.log("ENF-12/13/17/15-16 D inputs verified; input contracts only, no product execution");
}
for (const path of [templatePath, rulePath, legalPath, penaltyPath]) console.log(`${path} sha256=${sha256(path)}`);
