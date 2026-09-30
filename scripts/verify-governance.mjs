import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const DEFAULT_ROOT = resolve(SCRIPT_DIR, "..");
const EXPECTED_IDS = [
  ...Array.from({ length: 12 }, (_, index) => `CON-${String(index + 1).padStart(2, "0")}`),
  ...Array.from({ length: 17 }, (_, index) => `ENF-${String(index + 1).padStart(2, "0")}`),
  ...Array.from({ length: 12 }, (_, index) => `ADJ-${String(index + 1).padStart(2, "0")}`),
];
const REQUIREMENT_STATES = new Set([
  "未建契约",
  "契约草稿",
  "待业务决定",
  "需求就绪",
  "基线已变化",
]);
const DECISION_OWNERS = new Set(["AI", "RULE", "HUMAN", "AUTHORITY_SYSTEM"]);

function normalizePath(path) {
  return path.split(sep).join("/");
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function listMarkdownFiles(path) {
  const result = [];
  for (const entry of readdirSync(path, { withFileTypes: true })) {
    const target = resolve(path, entry.name);
    if (entry.isDirectory()) result.push(...listMarkdownFiles(target));
    else if (entry.isFile() && entry.name.endsWith(".md")) result.push(target);
  }
  return result.sort();
}

function parseJson(path, errors, label) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    errors.push(`${label} is not valid JSON: ${error.message}`);
    return null;
  }
}

function extractLinks(value) {
  return [...value.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)].map((match) => match[1]);
}

function isNotApplicable(value) {
  return /^不适用[:：]\S+/.test(value);
}

function evidenceExists(value, planDirectory, errors, id, column) {
  if (value === "-" || value === "未执行" || value === "未验收") return false;
  if (isNotApplicable(value)) return true;
  const links = extractLinks(value);
  if (links.length === 0) {
    errors.push(`${id} ${column} must contain an artifact link or an explicit 不适用 reason`);
    return false;
  }
  for (const link of links) {
    const filePart = link.split("#", 1)[0];
    if (!existsSync(resolve(planDirectory, filePart))) {
      errors.push(`${id} ${column} link does not exist: ${link}`);
    }
  }
  return true;
}

function validateContract(contractPath, id, baseline, root, errors) {
  const contract = parseJson(contractPath, errors, `${id} contract`);
  if (!contract) return;
  const requiredArrays = [
    "clauses",
    "inputs",
    "outputs",
    "permissions",
    "states",
    "sourceAndVersionRules",
    "errorsAndExceptions",
    "decisionPoints",
    "acceptanceScenarios",
    "derivedDesignDecisions",
    "openQuestions",
  ];
  if (contract.requirementId !== id) errors.push(`${id} contract requirementId mismatch`);
  if (contract.baselineId !== baseline.baselineId) errors.push(`${id} contract baselineId mismatch`);
  for (const field of requiredArrays) {
    if (!Array.isArray(contract[field])) errors.push(`${id} contract field ${field} must be an array`);
  }
  const source = contract.source ?? {};
  const baselineSource = baseline.files.find((file) => file.path === source.path);
  if (!baselineSource) errors.push(`${id} contract source is not in the baseline: ${source.path ?? "missing"}`);
  else if (source.sha256 !== baselineSource.sha256) errors.push(`${id} contract source digest mismatch`);
  if (!Array.isArray(source.sections) || source.sections.length === 0) {
    errors.push(`${id} contract must cite at least one source section`);
  }
  const contractSupporting = Array.isArray(contract.supportingSources) ? contract.supportingSources : [];
  const requiredSupporting = (baseline.supportingFiles ?? []).filter((entry) => entry.consumers?.includes(id));
  for (const required of requiredSupporting) {
    const cited = contractSupporting.find((entry) => entry.path === required.path);
    if (!cited) {
      errors.push(`${id} contract does not cite required supporting source: ${required.path}`);
      continue;
    }
    const baselineEntry = baseline.files.find((entry) => entry.path === required.path);
    if (cited.sha256 !== baselineEntry?.sha256) errors.push(`${id} supporting source digest mismatch: ${required.path}`);
    if (!Array.isArray(cited.sections) || cited.sections.length === 0) {
      errors.push(`${id} supporting source lacks section anchors: ${required.path}`);
    }
  }
  const clauses = Array.isArray(contract.clauses) ? contract.clauses : [];
  if (clauses.length === 0) errors.push(`${id} contract has no clauses`);
  const clauseIds = new Set();
  for (const clause of clauses) {
    if (!clause.id || clauseIds.has(clause.id)) errors.push(`${id} contract has a missing or duplicate clause id`);
    clauseIds.add(clause.id);
    if (!["MUST", "MUST_NOT", "SHOULD"].includes(clause.type)) {
      errors.push(`${id} clause ${clause.id ?? "unknown"} has an invalid type`);
    }
    if (!clause.statement || !Array.isArray(clause.sourceAnchors) || clause.sourceAnchors.length === 0) {
      errors.push(`${id} clause ${clause.id ?? "unknown"} lacks statement or source anchors`);
    }
  }
  for (const point of Array.isArray(contract.decisionPoints) ? contract.decisionPoints : []) {
    if (!DECISION_OWNERS.has(point.owner)) {
      errors.push(`${id} decision ${point.id ?? "unknown"} has invalid owner ${point.owner ?? "missing"}`);
    }
  }
  for (const scenario of Array.isArray(contract.acceptanceScenarios) ? contract.acceptanceScenarios : []) {
    for (const clauseId of scenario.sourceClauseIds ?? []) {
      if (!clauseIds.has(clauseId)) errors.push(`${id} scenario ${scenario.id ?? "unknown"} references unknown clause ${clauseId}`);
    }
  }
  const review = contract.independentReview ?? {};
  if (contract.status === "ready") {
    if (review.status !== "passed" || review.reviewedBaselineId !== baseline.baselineId) {
      errors.push(`${id} ready contract lacks a passed independent review for the current baseline`);
    }
    for (const field of ["missingClauses", "contradictions", "weakenedConstraints", "unsupportedExtensions"]) {
      if (!Array.isArray(review[field]) || review[field].length !== 0) {
        errors.push(`${id} ready contract independentReview.${field} must be empty`);
      }
    }
  }
  if (source.path && !existsSync(resolve(root, source.path))) errors.push(`${id} contract source file is missing`);
}

function validateDriftReport(reportPath, id, baseline, errors) {
  const report = parseJson(reportPath, errors, `${id} drift report`);
  if (!report) return;
  if (report.requirementId !== id) errors.push(`${id} drift report requirementId mismatch`);
  if (report.baselineId !== baseline.baselineId) errors.push(`${id} drift report baselineId mismatch`);
  if (report.status !== "passed") errors.push(`${id} drift report status must be passed`);
  if (!report.reviewMethod || !Array.isArray(report.reviewedArtifacts) || report.reviewedArtifacts.length === 0) {
    errors.push(`${id} drift report must identify the independent method and reviewed artifacts`);
  }
  const counts = report.counts ?? {};
  for (const field of [
    "uncoveredMust",
    "violatedMustNot",
    "unsupportedBusinessBehavior",
    "brokenEvidenceLinks",
    "contradictions",
    "weakenedConstraints",
    "openHighRiskDecisions",
  ]) {
    if (counts[field] !== 0) errors.push(`${id} drift report ${field} must be 0`);
  }
  if (!report.reviewedAt || !Array.isArray(report.reviewEvidence) || report.reviewEvidence.length === 0) {
    errors.push(`${id} drift report must include review time and evidence`);
  }
}

function verifyBaseline(root, baseline) {
  const errors = [];
  const expected = new Map((baseline.files ?? []).map((file) => [file.path, file]));
  const actualPaths = listMarkdownFiles(resolve(root, "requirements")).map((path) => normalizePath(relative(root, path)));
  if (baseline.requirementFileCount !== actualPaths.length) {
    errors.push(`requirement file count changed: baseline=${baseline.requirementFileCount}, actual=${actualPaths.length}`);
  }
  for (const path of actualPaths) {
    if (!expected.has(path)) errors.push(`untracked requirement file: ${path}`);
  }
  for (const [path, metadata] of expected) {
    const absolute = resolve(root, path);
    if (!existsSync(absolute)) errors.push(`baseline requirement file is missing: ${path}`);
    else if (sha256(absolute) !== metadata.sha256) errors.push(`requirement file digest changed: ${path}`);
  }
  if (baseline.archive?.path) {
    const archive = resolve(root, baseline.archive.path);
    if (!/^[a-f0-9]{64}$/.test(baseline.archive.sha256 ?? "")) {
      errors.push(`baseline archive historical digest is invalid: ${baseline.archive.path}`);
    }
    if (baseline.archive.status === "retired") {
      if (existsSync(archive)) errors.push(`retired baseline archive has reappeared: ${baseline.archive.path}`);
    } else if (baseline.archive.status === "active") {
      if (!existsSync(archive)) errors.push(`baseline archive is missing: ${baseline.archive.path}`);
      else if (sha256(archive) !== baseline.archive.sha256) errors.push(`baseline archive digest changed: ${baseline.archive.path}`);
    } else {
      errors.push(`unknown baseline archive status: ${baseline.archive.status ?? "missing"}`);
    }
  }
  return errors;
}

function parseLedger(planText) {
  return planText
    .split(/\r?\n/)
    .filter((line) => /^\| (?:CON|ENF|ADJ)-\d{2} \|/.test(line))
    .map((line) => line.split("|").slice(1, -1).map((cell) => cell.trim()));
}

function verifyLedger(root, planText, baseline) {
  const errors = [];
  const planDirectory = resolve(root, "docs");
  const rows = parseLedger(planText);
  const ids = rows.map((row) => row[0]);
  const ledgerSources = [];
  for (const id of EXPECTED_IDS) {
    if (!ids.includes(id)) errors.push(`missing requirement ledger row: ${id}`);
  }
  for (const [id, count] of Object.entries(ids.reduce((counts, id) => ({ ...counts, [id]: (counts[id] ?? 0) + 1 }), {}))) {
    if (count > 1) errors.push(`duplicate requirement ledger row: ${id}`);
  }
  for (const row of rows) {
    if (row.length !== 9) {
      errors.push(`${row[0] ?? "unknown"} ledger row must have 9 columns, found ${row.length}`);
      continue;
    }
    const [id, sourceCell, contractCell, designCell, apiDataCell, codeCell, testCell, driftCell, acceptanceCell] = row;
    const sourceLinks = extractLinks(sourceCell);
    if (sourceLinks.length !== 1) errors.push(`${id} must have exactly one source link`);
    else {
      const sourcePath = resolve(planDirectory, sourceLinks[0]);
      const sourceRelative = normalizePath(relative(root, sourcePath));
      ledgerSources.push(sourceRelative);
      if (!existsSync(sourcePath)) errors.push(`${id} source link does not exist: ${sourceLinks[0]}`);
      if (!(baseline.files ?? []).some((file) => file.path === sourceRelative)) {
        errors.push(`${id} source link is not part of the frozen baseline: ${sourceRelative}`);
      }
    }
    const contractState = [...REQUIREMENT_STATES].find((state) => contractCell.includes(state));
    if (!contractState) errors.push(`${id} has an invalid requirement contract state: ${contractCell}`);
    const contractLinks = extractLinks(contractCell);
    if (contractState !== "未建契约") {
      if (contractLinks.length !== 1) errors.push(`${id} ${contractState} must link exactly one contract`);
      else {
        const contractPath = resolve(planDirectory, contractLinks[0]);
        validateContract(contractPath, id, baseline, root, errors);
        if (existsSync(contractPath)) {
          let contract = null;
          try {
            contract = JSON.parse(readFileSync(contractPath, "utf8"));
          } catch {
            contract = null;
          }
          const expectedStatus = {
            "契约草稿": "draft",
            "待业务决定": "blocked",
            "需求就绪": "ready",
            "基线已变化": "stale",
          }[contractState];
          if (contract && contract.status !== expectedStatus) errors.push(`${id} ledger and contract status mismatch`);
        }
      }
    }
    const hasDesign = evidenceExists(designCell, planDirectory, errors, id, "design");
    const hasApiData = evidenceExists(apiDataCell, planDirectory, errors, id, "API/data");
    const hasCode = evidenceExists(codeCell, planDirectory, errors, id, "code");
    const hasTests = evidenceExists(testCell, planDirectory, errors, id, "tests");
    const driftPassed = /^\[通过\]/.test(driftCell);
    const accepted = acceptanceCell !== "未验收";
    if ((hasDesign || hasApiData || hasCode || hasTests || driftPassed || accepted) && contractState !== "需求就绪") {
      errors.push(`${id} has downstream evidence before passing the R gate`);
    }
    if ((hasApiData || hasCode) && !hasDesign) errors.push(`${id} has API/data or code without design evidence`);
    if (hasCode && !hasApiData) errors.push(`${id} has code without API/data evidence or an explicit 不适用 reason`);
    if (driftPassed && (!hasDesign || !hasApiData || !hasCode || !hasTests)) {
      errors.push(`${id} drift check cannot pass before design, API/data, code, and tests are evidenced`);
    }
    if (accepted && (!driftPassed || !hasTests)) errors.push(`${id} cannot be accepted before V gate passes`);
    if (driftPassed) {
      evidenceExists(driftCell, planDirectory, errors, id, "drift");
      const driftLinks = extractLinks(driftCell);
      if (driftLinks.length !== 1) errors.push(`${id} passed drift check must link exactly one report`);
      else validateDriftReport(resolve(planDirectory, driftLinks[0]), id, baseline, errors);
    }
    if (accepted) evidenceExists(acceptanceCell, planDirectory, errors, id, "acceptance");
  }
  for (const [source, count] of Object.entries(ledgerSources.reduce((counts, source) => ({ ...counts, [source]: (counts[source] ?? 0) + 1 }), {}))) {
    if (count > 1) errors.push(`multiple requirement IDs map to the same primary source: ${source}`);
  }
  const supporting = baseline.supportingFiles ?? [];
  const supportingPaths = new Set(supporting.map((entry) => entry.path));
  for (const entry of supporting) {
    if (!(baseline.files ?? []).some((file) => file.path === entry.path)) {
      errors.push(`supporting file is not in the baseline: ${entry.path}`);
    }
    if (!entry.role || !Array.isArray(entry.consumers) || entry.consumers.length === 0) {
      errors.push(`supporting file lacks role or consumers: ${entry.path}`);
    }
    for (const consumer of entry.consumers ?? []) {
      if (!EXPECTED_IDS.includes(consumer)) errors.push(`supporting file has unknown consumer ${consumer}: ${entry.path}`);
    }
  }
  for (const file of baseline.files ?? []) {
    if (!ledgerSources.includes(file.path) && !supportingPaths.has(file.path)) {
      errors.push(`unclassified baseline requirement file: ${file.path}`);
    }
  }
  return errors;
}

function verify(root, baselineOverride = null, planOverride = null) {
  const setupErrors = [];
  const baselinePath = resolve(root, "governance", "requirements-baseline.json");
  const baseline = baselineOverride ?? parseJson(baselinePath, setupErrors, "requirements baseline");
  if (!baseline) return setupErrors;
  const planText = planOverride ?? readFileSync(resolve(root, "docs", "08-总开发实现计划.md"), "utf8");
  return [...setupErrors, ...verifyBaseline(root, baseline), ...verifyLedger(root, planText, baseline)];
}

function expectFailure(name, errors, expectedText, failures) {
  if (!errors.some((error) => error.includes(expectedText))) failures.push(`${name} did not detect ${expectedText}`);
}

function runSelfTest(root) {
  const failures = [];
  const baseline = JSON.parse(readFileSync(resolve(root, "governance", "requirements-baseline.json"), "utf8"));
  const plan = readFileSync(resolve(root, "docs", "08-总开发实现计划.md"), "utf8");
  const liveErrors = verify(root, baseline, plan);
  if (liveErrors.length) failures.push(`valid project state failed: ${liveErrors.join("; ")}`);

  const changedBaseline = structuredClone(baseline);
  changedBaseline.files[0].sha256 = "0".repeat(64);
  expectFailure("digest mutation", verify(root, changedBaseline, plan), "digest changed", failures);

  const reactivatedArchive = structuredClone(baseline);
  reactivatedArchive.archive.status = "active";
  expectFailure("retired archive reactivation", verifyBaseline(root, reactivatedArchive),
    "baseline archive is missing", failures);
  const invalidArchiveStatus = structuredClone(baseline);
  invalidArchiveStatus.archive.status = "unreviewed";
  expectFailure("unknown archive status", verifyBaseline(root, invalidArchiveStatus),
    "unknown baseline archive status", failures);

  const missingPlan = plan.replace(/^\| CON-02 \|.*\r?\n/m, "");
  expectFailure("missing row", verifyLedger(root, missingPlan, baseline), "missing requirement ledger row: CON-02", failures);

  const duplicatePlan = plan.replace(/^\| CON-02 \|/m, "| CON-01 |");
  expectFailure("duplicate row", verifyLedger(root, duplicatePlan, baseline), "duplicate requirement ledger row: CON-01", failures);

  const brokenPlan = plan.replace("../requirements/知识产权咨询助手/商标库管理需求文档.md", "../requirements/missing.md");
  expectFailure("broken source", verifyLedger(root, brokenPlan, baseline), "source link does not exist", failures);

  const unclassifiedBaseline = structuredClone(baseline);
  unclassifiedBaseline.supportingFiles.shift();
  expectFailure("unclassified supporting source", verifyLedger(root, plan, unclassifiedBaseline), "unclassified baseline requirement file", failures);

  const requirementLine = plan.match(/^\| (?:CON|ENF|ADJ)-\d+ \|.*$/m)?.[0];
  if (!requirementLine) {
    failures.push("fake completion could not find a requirement ledger row");
  } else {
    const cells = requirementLine.split("|");
    cells[3] = " 未建契约 ";
    cells[6] = " [impl](../AGENTS.md) ";
    const fakeCodePlan = plan.replace(requirementLine, cells.join("|"));
    expectFailure("fake completion", verifyLedger(root, fakeCodePlan, baseline), "downstream evidence before passing the R gate", failures);
  }

  if (failures.length) {
    console.error(`Governance self-test failed (${failures.length})`);
    for (const failure of failures) console.error(`- ${failure}`);
    return 1;
  }
  console.log("Governance self-test passed: live, digest, missing, duplicate, broken-link, source-classification, and fake-completion cases.");
  return 0;
}

const args = process.argv.slice(2);
const rootArgIndex = args.indexOf("--root");
const root = rootArgIndex >= 0 ? resolve(args[rootArgIndex + 1]) : DEFAULT_ROOT;
if (args.includes("--self-test")) {
  process.exitCode = runSelfTest(root);
} else {
  const errors = verify(root);
  if (errors.length) {
    console.error(`Governance verification failed (${errors.length})`);
    for (const error of errors) console.error(`- ${error}`);
    process.exitCode = 1;
  } else {
    console.log("Governance verification passed: 47 baseline files and 41 requirement ledger rows are consistent.");
  }
}
