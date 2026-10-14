import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { agentBoundaryViolations } from "./lib/agent-boundaries.mjs";

const root = path.resolve(import.meta.dirname, "..");
const policy = JSON.parse(readFileSync(path.join(root, "governance/structure-policy.json"), "utf8"));
const failures = [];
const warnings = [];

const requiredModules = [
  "backend/kernel", "backend/consultation", "backend/patent", "backend/trademark",
  "backend/case-reference", "backend/enforcement", "backend/adjudication",
  "backend/adapters/persistence-dm8", "backend/adapters/model-deepseek",
  "backend/adapters/center-client", "backend/adapters/public-source",
  "backend/apps/platform-api", "backend/apps/mock-center-api"
];

for (const modulePath of requiredModules) {
  if (!existsSync(path.join(root, modulePath, "pom.xml"))) failures.push(`Missing module build: ${modulePath}/pom.xml`);
}
if (existsSync(path.join(root, "src"))) failures.push("Legacy root src/ must not return; backend code belongs under backend modules.");

function filesUnder(relativeRoot) {
  const start = path.join(root, relativeRoot);
  if (!existsSync(start)) return [];
  const files = [];
  const visit = (directory) => {
    for (const name of readdirSync(directory)) {
      // Maven outputs are not repository source and may be replaced during another build.
      if (name === "target" && existsSync(path.join(directory, "pom.xml"))) continue;
      const candidate = path.join(directory, name);
      statSync(candidate).isDirectory() ? visit(candidate) : files.push(candidate);
    }
  };
  visit(start);
  return files;
}

function limitFor(relativePath) {
  if (/\/src\/test\//.test(relativePath)) return { soft: 250, hard: 400 };
  if (relativePath.endsWith(".css")) return { soft: 200, hard: 300 };
  if (relativePath.endsWith(".tsx")) return { soft: 150, hard: 220 };
  if (relativePath.endsWith(".ts")) return { soft: 180, hard: 300 };
  if (relativePath.endsWith(".java")) {
    if (/Controller\.java$/.test(relativePath)) return { soft: 150, hard: 220 };
    if (/Configuration\.java$/.test(relativePath)) return { soft: 120, hard: 180 };
    return { soft: 200, hard: 300 };
  }
  return null;
}

function productionJavaUnder(relativeRoot) {
  return filesUnder(relativeRoot).filter((file) => /[\\/]src[\\/]main[\\/]java[\\/].+\.java$/.test(file));
}

function dependencyArtifacts(modulePath) {
  const pom = readFileSync(path.join(root, modulePath, "pom.xml"), "utf8");
  return [...pom.matchAll(/<dependency>[\s\S]*?<artifactId>([^<]+)<\/artifactId>[\s\S]*?<\/dependency>/g)]
    .map((match) => match[1].trim());
}

const businessModules = [
  "backend/kernel", "backend/consultation", "backend/patent", "backend/trademark",
  "backend/case-reference", "backend/enforcement", "backend/adjudication"
];
const adapterArtifacts = new Set([
  "agent-platform-persistence-dm8", "agent-platform-model-deepseek",
  "agent-platform-center-client", "agent-platform-public-source", "agent-platform-mock-center-api"
]);

for (const modulePath of businessModules) {
  for (const artifact of dependencyArtifacts(modulePath)) {
    if (adapterArtifacts.has(artifact)) failures.push(`${modulePath}: business module must not depend on adapter ${artifact}`);
  }
}

if (dependencyArtifacts("backend/apps/platform-api").includes("agent-platform-mock-center-api")) {
  failures.push("backend/apps/platform-api: formal platform must communicate with mock center over HTTP, not a module dependency");
}

for (const file of productionJavaUnder("backend/adapters/center-client")) {
  const source = readFileSync(file, "utf8");
  if (/\bconsultation\.mockcenter\b/.test(source)) {
    failures.push(`${path.relative(root, file)}: center client must not import mock-center implementation packages`);
  }
}

for (const file of productionJavaUnder("backend/apps/mock-center-api")) {
  const source = readFileSync(file, "utf8");
  if (/^import\s+cn\.xinji\.agentplatform\./m.test(source)) {
    failures.push(`${path.relative(root, file)}: mock center must not import formal platform domain packages`);
  }
}

// Keep three independently owned Agents and a domain-free shared kernel.
for (const owner of ["kernel", "consultation", "enforcement", "adjudication"]) {
  const modulePath = `backend/${owner}`;
  for (const violation of agentBoundaryViolations(owner, "", dependencyArtifacts(modulePath)))
    failures.push(`${modulePath}: ${violation}`);
  for (const file of productionJavaUnder(modulePath)) {
    for (const violation of agentBoundaryViolations(owner, readFileSync(file, "utf8")))
      failures.push(`${path.relative(root, file)}: ${violation}`);
  }
}

const candidates = [
  ...filesUnder("backend").filter((file) => /[\\/]src[\\/](main|test)[\\/].+\.java$/.test(file)),
  ...filesUnder("frontend/src").filter((file) => /\.(ts|tsx|css)$/.test(file))
];

for (const file of candidates) {
  const relativePath = path.relative(root, file).split(path.sep).join("/");
  const limit = limitFor(relativePath);
  if (!limit) continue;
  const lines = readFileSync(file, "utf8").split(/\r?\n/).length;
  const baseline = policy.temporaryOversizeBaselines[relativePath];
  if (lines > limit.hard) {
    if (baseline === undefined) failures.push(`${relativePath}: ${lines} lines exceeds hard limit ${limit.hard}`);
    else if (lines > baseline) failures.push(`${relativePath}: temporary baseline grew from ${baseline} to ${lines} lines`);
    else warnings.push(`${relativePath}: temporary oversize baseline ${lines}/${limit.hard}`);
  } else if (lines > limit.soft) {
    warnings.push(`${relativePath}: ${lines} lines exceeds soft limit ${limit.soft}`);
  }
}

for (const relativePath of Object.keys(policy.temporaryOversizeBaselines)) {
  if (!existsSync(path.join(root, relativePath))) warnings.push(`Remove resolved temporary baseline: ${relativePath}`);
}

if (failures.length) {
  console.error(`Structure verification failed (${failures.length}):`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}
console.log(`Structure verification passed: ${requiredModules.length} module entries, ${warnings.length} size warnings.`);
for (const warning of warnings) console.log(`- ${warning}`);
