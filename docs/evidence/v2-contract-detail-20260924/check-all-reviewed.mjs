import { readFileSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { createHash } from "node:crypto";

const hash = path => createHash("sha256").update(readFileSync(path)).digest("hex");
const parse = path => JSON.parse(readFileSync(path, "utf8"));
const errors = [];
const contracts = [];
for (const file of readdirSync("governance/contracts").filter(x => x.endsWith(".json")).sort()) {
  const path = `governance/contracts/${file}`;
  const c = parse(path);
  const clauses = new Map(c.clauses.map(x => [x.id, x]));
  const scenarios = new Map(c.acceptanceScenarios.map(x => [x.id, x]));
  if (clauses.size !== c.clauses.length || scenarios.size !== c.acceptanceScenarios.length) errors.push(`${c.requirementId}:duplicate`);
  for (const x of c.clauses) {
    if (!x.acceptanceScenarioIds?.length) errors.push(`${x.id}:orphan`);
    for (const id of x.acceptanceScenarioIds ?? []) if (!scenarios.get(id)?.sourceClauseIds.includes(x.id)) errors.push(`${x.id}->${id}`);
  }
  for (const x of c.acceptanceScenarios) {
    if (!x.sourceClauseIds?.length) errors.push(`${x.id}:orphan`);
    for (const id of x.sourceClauseIds ?? []) if (!clauses.get(id)?.acceptanceScenarioIds.includes(x.id)) errors.push(`${x.id}->${id}`);
  }
  const sources = [c.source, ...c.supportingSources].map(s => {
    const digest = hash(s.path);
    if (digest !== s.sha256) errors.push(`${c.requirementId}:source digest:${s.path}`);
    return { path: s.path, sha256: digest };
  });
  if (c.status !== "ready" || c.independentReview?.status !== "passed" || !c.independentReview?.reviewEvidence?.every(existsSync)) errors.push(`${c.requirementId}:review evidence`);
  contracts.push({ id: c.requirementId, status: c.status, path, sha256: hash(path), clauses: clauses.size, scenarios: scenarios.size, sources, reviewEvidence: c.independentReview.reviewEvidence });
}
const protectedBefore = parse("docs/evidence/v2-assessment-20260924/protected-before.json");
let protectedExistingFilesChecked = 0;
for (const [path, digest] of Object.entries(protectedBefore)) {
  if (!/^(backend|frontend|requirements)[\\/]/.test(path)) continue;
  protectedExistingFilesChecked++;
  if (!existsSync(path) || hash(path) !== digest) errors.push(`pre-existing protected file changed:${path}`);
}
const plan = parse("docs/project-plan.json");
const result = {
  date: "2026-09-24", purpose: "Version binding and structural integrity only; not semantic, real-integration or business-acceptance proof",
  planVersion: plan.planVersion, currentTaskId: plan.currentTaskId, contractCount: contracts.length,
  protectedExistingFilesChecked, contracts, errors,
};
writeFileSync("docs/evidence/v2-contract-detail-20260924/all-reviewed-check.json", `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify({ planVersion: result.planVersion, contractCount: result.contractCount, protectedExistingFilesChecked, errors }, null, 2));
if (errors.length) process.exitCode = 1;
