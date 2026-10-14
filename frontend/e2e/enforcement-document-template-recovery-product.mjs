import { chromium } from "playwright";
import { mkdir, readFile, writeFile, rename } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { createHash } from "node:crypto";
const runId = process.env.ENF_FOLLOWUP_RUN_ID;
if (!runId || !/^[A-Za-z0-9_-]+$/.test(runId)) throw new Error("Fresh execution ID required");
const output = resolve("target/enforcement-document-template-recovery", runId);
await mkdir(dirname(output), { recursive: true });
await mkdir(output, { recursive: false });
const templateId = process.env.ENF_FOLLOWUP_TEMPLATE_ID;
const restoredBase = process.env.ENF_RESTORED_BASE ?? "https://127.0.0.1:5445";
const sourceBase = "https://127.0.0.1:5444";
const baseline = JSON.parse(await readFile(process.env.ENF_BROWSER_BASELINE, "utf8"));
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ ignoreHTTPSErrors: true });
const page = await context.newPage();
let token;
page.on("response", async response => {
  if (new URL(response.url()).pathname === "/oauth2/token" && response.status() === 200)
    token = (await response.json()).access_token;
});
const results = [];
try {
  await page.goto("https://127.0.0.1:5174/app/enforcement/document-templates");
  await page.getByRole("button", { name: "登录" }).click();
  await page.locator('input[name="username"]').fill(process.env.ENF_FOLLOWUP_LOGIN);
  await page.locator('input[name="password"]').fill(process.env.ENF_FOLLOWUP_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.getByRole("heading", { name: "上传模板草稿" }).waitFor();
  if (!token) throw new Error("No actual OAuth/PKCE access token");
  if (process.env.ENF_RECOVERY_GATE) {
    const gate = resolve(process.env.ENF_RECOVERY_GATE);
    await writeFile(gate + ".session-ready", JSON.stringify({ runId, at: new Date().toISOString() }), { flag: "wx" });
    const deadline = Date.now() + 1_200_000;
    while (Date.now() < deadline) {
      try { await readFile(gate + ".restore-ready"); break; } catch {}
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    await readFile(gate + ".restore-ready");
  }
  const path = "/v2/enforcement/document-templates/" + templateId;
  const get = async (base, suffix) => context.request.get(base + path + suffix,
    { headers: { Authorization: "Bearer " + token } });
  for (const base of [sourceBase, restoredBase]) {
    const state = await get(base, "/revisions/1");
    if (state.status() !== 200) throw new Error("Restored product read: " + state.status());
    const revision = await state.json();
    if (revision.state !== "DISABLED") throw new Error("Restored state differs");
    const original = await get(base, "/revisions/1/file");
    const bytes = await original.body();
    const sha = createHash("sha256").update(bytes).digest("hex");
    if (original.status() !== 200 || sha !== baseline.downloadedSha256) throw new Error("Restored original mismatch");
    const previewPath = baseline.requests.find(r => r.method === "GET" && r.path.includes("/previews/")).path;
    const preview = await context.request.get(base + previewPath, { headers: { Authorization: "Bearer " + token } });
    const previewSha = createHash("sha256").update(await preview.body()).digest("hex");
    if (preview.status() !== 200 || previewSha !== baseline.previewSha256) throw new Error("Restored preview mismatch");
    results.push({ base, state: revision.state, originalStatus: original.status(), originalSha: sha,
      previewStatus: preview.status(), previewSha });
    if (base === restoredBase) {
      const file = resolve(process.env.ENF_RESTORED_FILE_ROOT, revision.fileKey);
      const fileRoot = resolve(process.env.ENF_RESTORED_FILE_ROOT);
      if (!file.startsWith(fileRoot + "/") && !file.startsWith(fileRoot + "\\")) throw new Error("Unsafe restored file key");
      const held = file + ".held-" + runId;
      await rename(file, held);
      try {
        const missing = await get(base, "/revisions/1/file");
        if (missing.status() !== 503) throw new Error("Missing original not fail-closed: " + missing.status());
        results.push({ scenario: "MISSING_ORIGINAL", status: missing.status() });
      } finally { await rename(held, file); }
      const originalBytes = await readFile(file);
      try {
        await writeFile(file, Buffer.from("CONTROLLED_INTEGRITY_FAILURE"));
        const changed = await get(base, "/revisions/1/file");
        if (changed.status() !== 503) throw new Error("SHA mismatch not fail-closed: " + changed.status());
        results.push({ scenario: "SHA_MISMATCH", status: changed.status() });
      } finally { await writeFile(file, originalBytes); }
      const repeated = await get(base, "/revisions/1/file");
      if (repeated.status() !== 200 || createHash("sha256").update(await repeated.body()).digest("hex") !== sha)
        throw new Error("Restored original not usable after fault removal");
      results.push({ scenario: "FAILURE_REMOVED_REPEAT_READ", status: repeated.status(), sha });
    }
  }
  if (process.env.ENF_RECOVERY_GATE) {
    const gate = resolve(process.env.ENF_RECOVERY_GATE);
    await writeFile(gate + ".downloads-verified", JSON.stringify({ runId, results }), { flag: "wx" });
    const deadline = Date.now() + 1_200_000;
    while (Date.now() < deadline) {
      try { await readFile(gate + ".revoked"); break; } catch {}
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    await readFile(gate + ".revoked");
    for (const [base, expected] of [[sourceBase, 403], [restoredBase, 503]]) {
      const denied = await get(base, "/revisions/1/file");
      if (denied.status() !== expected) throw new Error("Old permission failure semantics: " + base + " " + denied.status());
      if ((await denied.body()).subarray(0, 2).equals(Buffer.from("PK"))) throw new Error("Denied response leaked DOCX");
      results.push({ scenario: "CURRENT_REVOKED_OLD_BACKUP", base, status: denied.status(), docxLeaked: false });
    }
  }
  await writeFile(resolve(output, "result.json"), JSON.stringify({ runId, checkedAt: new Date().toISOString(),
    inputNature: "FUNCTIONAL_VALIDATION", templateId, results }, null, 2));
  console.log("ENF12_RESTORED_HTTPS_OK " + JSON.stringify(results));
} catch (failure) {
  await writeFile(resolve(output, "failure.json"), JSON.stringify({ runId, message: failure.message, results }, null, 2));
  throw failure;
} finally { await browser.close(); }
