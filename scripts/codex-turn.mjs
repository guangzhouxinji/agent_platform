import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const mode = process.argv[2];
if (!new Set(["preflight", "closeout"]).has(mode)) {
  throw new Error("Usage: node scripts/codex-turn.mjs <preflight|closeout>");
}

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(scriptDir, "..");

function run(args) {
  const result = spawnSync(process.execPath, args, { cwd: rootDir, stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

if (mode === "preflight") {
  run(["scripts/generate-project-progress.mjs"]);
  run(["scripts/print-current-task-brief.mjs"]);
  run(["scripts/verify-all.mjs"]);
} else {
  console.log("Closeout requires docs/08 to be updated before this command runs.");
  run(["scripts/verify-all.mjs"]);
  run(["scripts/print-current-task-brief.mjs"]);
}
