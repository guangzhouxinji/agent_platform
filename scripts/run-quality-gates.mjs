import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";

const rootDir = path.resolve(import.meta.dirname, "..");
const node = process.execPath;
const maven = process.platform === "win32" ? "mvn.cmd" : "mvn";
const npm = process.platform === "win32" ? "npm.cmd" : "npm";
const dm8Variables = ["DM8_JDBC_URL", "DM8_JDBC_USER", "DM8_JDBC_PASSWORD"];
const missingDm8Variables = dm8Variables.filter((name) => !process.env[name]);

function run(label, command, args) {
  console.log(`\n[required] ${label}`);
  const executable = process.platform === "win32" ? (process.env.ComSpec || "cmd.exe") : command;
  const commandArgs = process.platform === "win32" ? ["/d", "/s", "/c", command, ...args] : args;
  const result = spawnSync(executable, commandArgs, { cwd: rootDir, env: process.env, stdio: "inherit" });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

function testSummary() {
  const reportDir = path.join(rootDir, "target", "surefire-reports");
  if (!existsSync(reportDir)) return null;
  return readdirSync(reportDir)
    .filter((name) => name.startsWith("TEST-") && name.endsWith(".xml"))
    .reduce((summary, name) => {
      const xml = readFileSync(path.join(reportDir, name), "utf8");
      for (const field of ["tests", "failures", "errors", "skipped"]) {
        summary[field] += Number(xml.match(new RegExp(`${field}="(\\d+)"`))?.[1] ?? 0);
      }
      if (name.includes("Dm8")) summary.dm8Reports += 1;
      return summary;
    }, { tests: 0, failures: 0, errors: 0, skipped: 0, dm8Reports: 0 });
}

run("Governance and project-plan checks", node, ["scripts/verify-all.mjs"]);
run("Java unit and integration tests", maven, ["-q", "test"]);
const summary = testSummary();
if (summary) {
  const dm8State = missingDm8Variables.length
    ? `DM8 JVM tests may be skipped: missing ${missingDm8Variables.join(", ")}`
    : `DM8 JVM tests enabled (${summary.dm8Reports} DM8 report files)`;
  console.log(`[result] Java: ${summary.tests} tests, ${summary.failures} failures, ${summary.errors} errors, ${summary.skipped} skipped. ${dm8State}`);
}
run("Browser workbench regression", npm, ["run", "test:browser"]);
console.log("\nQuality gates passed.");
