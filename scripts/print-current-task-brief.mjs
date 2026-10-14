import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { currentTaskBrief, renderCurrentTaskBrief } from "./lib/current-task-brief.mjs";

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
try {
  const source = fs.readFileSync(path.join(rootDir, "docs", "08-总开发实现计划.md"));
  const plan = JSON.parse(fs.readFileSync(path.join(rootDir, "docs", "project-plan.json"), "utf8"));
  const brief = currentTaskBrief(source, plan);
  console.log(renderCurrentTaskBrief(brief, plan));
} catch (error) {
  console.error(`Current task brief unavailable: ${error.message}`);
  process.exitCode = 1;
}
