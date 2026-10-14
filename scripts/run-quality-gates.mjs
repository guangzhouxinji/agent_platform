import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, lstatSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import process from "node:process";
import { runQualityStages } from "./lib/verification-stages.mjs";

const rootDir = path.resolve(import.meta.dirname, "..");
const excluded = new Set(["target", "node_modules", "dist", "dist-consultation", "dist-adjudication", "dist-enforcement", "build", ".git", "__pycache__", ".cache", ".pytest_cache", ".vite", ".vitest", "test-results", "playwright-report"]);
const sourceExtensions = /\.(?:java|sql|xml|mjs|js|cjs|ts|tsx|jsx|json|css|html|ps1|py|yaml|yml|md|svg|png|jpg|jpeg|ico|webp|woff2?)$/i;

export function qualityGateCommands(platform = process.platform, node = process.execPath) {
  const npm = platform === "win32" ? "npm.cmd" : "npm";
  const maven = platform === "win32" ? "mvn.cmd" : "mvn";
  return {
    preflight: { label: "Governance and project-plan preflight", command: node, args: ["scripts/verify-all.mjs", "--test-health-exit-code"] },
    stages: [
      { label: "React frontend component tests", command: npm, args: ["run", "frontend:test"] },
      { label: "React frontend production build", command: npm, args: ["run", "frontend:build"] },
      { label: "React frontend browser shell", command: npm, args: ["run", "test:frontend-browser"] },
      { label: "Java unit and integration tests", command: maven, args: ["-q", "test"] },
    ],
    postflight: { label: "Current evidence and governance postflight", command: node, args: ["scripts/verify-all.mjs", "--test-health-exit-code"] },
  };
}

export function sourceFingerprint(root) {
  const entries = [];
  const visit = file => {
    const stat = lstatSync(file);
    if (stat.isSymbolicLink()) throw new Error("SOURCE_SYMLINK_REFUSED");
    if (stat.isDirectory()) {
      for (const name of readdirSync(file).sort()) if (!excluded.has(name)) visit(path.join(file, name));
    } else if (stat.isFile() && sourceExtensions.test(file)) {
      entries.push([path.relative(root, file).replaceAll("\\", "/"), createHash("sha256").update(readFileSync(file)).digest("hex")]);
    }
  };
  for (const directory of ["requirements", "scripts", "backend", "frontend", "governance"]) visit(path.join(root, directory));
  for (const name of readdirSync(root).sort()) {
    const file = path.join(root, name);
    if (!lstatSync(file).isDirectory() && sourceExtensions.test(name)) visit(file);
  }
  visit(path.join(root, "docs", "08-总开发实现计划.md"));
  return createHash("sha256").update(JSON.stringify(entries.sort((a,b) => a[0].localeCompare(b[0])))).digest("hex");
}

export function javaReports(root, validateCounts = true) {
  const reports = [];
  const visit = dir => {
    if (!existsSync(dir)) return;
    for (const name of readdirSync(dir).sort()) {
      const file = path.join(dir, name), stat = lstatSync(file);
      if (stat.isSymbolicLink()) throw new Error("REPORT_SYMLINK_REFUSED");
      if (!stat.isDirectory()) continue;
      if (name === "target") {
        const reportDir = path.join(file, "surefire-reports");
        if (!existsSync(reportDir)) continue;
        if (lstatSync(reportDir).isSymbolicLink()) throw new Error("REPORT_SYMLINK_REFUSED");
        for (const report of readdirSync(reportDir).sort()) {
          if (!/^TEST-.*\.xml$/.test(report)) continue;
          const full = path.join(reportDir, report), info = lstatSync(full);
          if (!info.isFile() || info.isSymbolicLink()) throw new Error("REGULAR_REPORT_REQUIRED");
          if (!validateCounts) { reports.push({ path: path.relative(root, full), mtimeMs: info.mtimeMs }); continue; }
          const header = readFileSync(full, "utf8").match(/<testsuite\b[^>]*>/)?.[0];
          if (!header) throw new Error("TEST_SUITE_REQUIRED");
          const counts = {};
          for (const key of ["tests", "failures", "errors", "skipped"]) {
            const value = header.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1];
            if (value === undefined || !Number.isSafeInteger(Number(value))) throw new Error("TEST_COUNT_REQUIRED");
            counts[key] = Number(value);
          }
          if (counts.failures + counts.errors + counts.skipped > counts.tests) throw new Error("TEST_COUNTS_INVALID");
          reports.push({ path: path.relative(root, full), mtimeMs: info.mtimeMs, ...counts });
        }
      } else if (!excluded.has(name)) visit(file);
    }
  };
  visit(path.join(root, "backend"));
  return reports;
}

export function freshJavaReports(before, after, startedAt) {
  if (!after.length || after.reduce((n,r) => n + r.tests, 0) === 0) return false;
  const old = new Map(before.map(report => [report.path, report]));
  return after.every(report => ['tests', 'failures', 'errors', 'skipped'].every(key => Number.isSafeInteger(report[key]) && report[key] >= 0)
    && report.failures + report.errors + report.skipped <= report.tests && Number.isFinite(report.mtimeMs) && report.mtimeMs >= startedAt && report.mtimeMs > (old.get(report.path)?.mtimeMs ?? -Infinity)
    && report.failures === 0 && report.errors === 0);
}

export function main() {
  if (process.argv.length !== 2) { console.error("Quality gate options are not supported"); return 1; }
  let before;
  try { before = javaReports(rootDir, false); } catch { console.error("Invalid prior report evidence; preserve and reconcile before quality run"); return 1; }
  const startedAt = Date.now();
  const result = runQualityStages({
    ...qualityGateCommands(), captureSource: () => sourceFingerprint(rootDir),
    checkEvidence: () => freshJavaReports(before, javaReports(rootDir), startedAt),
    run: stage => {
      console.log(`\n[required] ${stage.label}`);
      const executable = process.platform === "win32" ? (process.env.ComSpec || "cmd.exe") : stage.command;
      const args = process.platform === "win32" ? ["/d", "/s", "/c", stage.command, ...stage.args] : stage.args;
      return spawnSync(executable, args, { cwd: rootDir, env: process.env, stdio: "inherit" });
    },
    report: entry => console.log(`[result] ${entry.stage}: exit ${entry.status}${entry.detail ? ` (${entry.detail})` : ""}`),
  });
  console.log(result.exitCode === 0 ? "\nQuality gates passed (execution evidence only)." : "\nQuality gates FAILED; prior and current failures remain recorded above.");
  console.log("Skipped tests are not real integration or business acceptance. This entry does not promote the master-plan full-regression evidence.");
  return result.exitCode;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = main();