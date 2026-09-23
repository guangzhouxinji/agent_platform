import { expect, test } from "@playwright/test";

const envelope = data => ({ requestId: "req-evidence-e2e", resultType: "ENFORCEMENT_EVIDENCE", data, limitations: ["DEVELOPMENT_ONLY", "NOT_EVIDENCE_ACCEPTANCE", "NO_MODEL_USED", "LOCAL_DM8_PRIMARY"] });
const json = body => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

test("evidence workspace registers immutable content and records human verification on desktop and mobile", async ({ page }) => {
  const records = [];
  const consoleErrors = [];
  const httpErrors = [];
  page.on("console", message => {
    if (message.type() === "error" && !message.text().startsWith("Failed to load resource:")) consoleErrors.push(message.text());
  });
  page.on("response", response => {
    if (response.status() >= 400) httpErrors.push(`${response.status()} ${response.url()}`);
  });
  await page.route("**/api/v0/enforcement/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/v0/enforcement/leads" && request.method() === "GET") return route.fulfill(json(envelope({ items: [{ leadId: "lead-001", sourceRecordId: "report-001" }], total: 1 })));
    if (path === "/api/v0/enforcement/evidence" && request.method() === "GET") return route.fulfill(json(envelope({ items: records.map(({ versions, metadataHistory, statusHistory, ...item }) => ({ ...item, latestContentVersion: versions[0].version })), total: records.length, page: 1, pageSize: 100 })));
    if (path === "/api/v0/enforcement/evidence" && request.method() === "POST") {
      const body = request.postDataJSON();
      const record = { evidenceId: "EVD-001", organizationRef: "org-v0", name: body.name, type: body.type, status: "PENDING_VERIFICATION", relatedLeadId: body.relatedLeadId, sourceChannel: "AUTHORIZED_UPLOAD", recordVersion: 1, collection: { missingReason: body.collectionMissingReason }, versions: [{ version: 1, derivedKind: "ORIGINAL", controlledResourceRef: "controlled://local-evidence/EVD-001/versions/1", fileName: body.fileName, mediaType: body.mediaType, size: 12, digestAlgorithm: "SHA-256", digest: "a".repeat(64), previewable: true, createdBy: "v0-authorized-user", createdAt: "2026-09-21T00:00:00Z" }], metadataHistory: [], statusHistory: [], createdAt: "2026-09-21T00:00:00Z", updatedAt: "2026-09-21T00:00:00Z" };
      records.push(record); return route.fulfill(json(envelope(record)));
    }
    if (path === "/api/v0/enforcement/evidence/EVD-001" && request.method() === "GET") return route.fulfill(json(envelope(records[0])));
    if (path === "/api/v0/enforcement/evidence/EVD-001/status" && request.method() === "POST") {
      const body = request.postDataJSON(); const record = records[0]; record.statusHistory.unshift({ fromStatus: record.status, toStatus: body.status, reason: body.reason, actor: "v0-enforcement-reviewer", occurredAt: "2026-09-21T00:01:00Z" }); record.status = body.status; record.recordVersion += 1; return route.fulfill(json(envelope(record)));
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ error: { code: "NOT_FOUND" } }) });
  });

  await page.goto("enforcement/evidence");
  await expect(page.getByText("开发辅助记录，不形成证据采信或执法结论")).toBeVisible();
  await page.getByRole("button", { name: "登记证据" }).click();
  await page.getByLabel("证据名称").fill("现场检查记录");
  await page.getByLabel("证据类型").click(); await page.getByText("书证").click();
  await page.getByLabel("关联线索").click(); await page.getByText(/report-001/).click();
  await page.getByLabel("收集信息缺失原因").fill("原授权包未提供收集人与时间");
  await page.locator('input[type="file"]').setInputFiles({ name: "inspection.txt", mimeType: "text/plain", buffer: Buffer.from("evidence v1") });
  await page.getByRole("button", { name: "登记并计算摘要" }).click();
  await expect(page.getByText("证据材料已登记，等待人工核验")).toBeVisible();
  await expect(page.getByText("SHA-256 " + "a".repeat(64))).toBeVisible();
  await page.getByRole("button", { name: "标记已核验" }).click();
  await expect(page.getByText("已核验（未采信）").first()).toBeVisible();
  await expect(page.getByText(/v0-enforcement-reviewer/)).toBeVisible();
  await page.waitForTimeout(3200);
  await page.screenshot({ path: "target/enforcement-evidence-desktop.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false);
  await expect(page.getByRole("button", { name: "登记证据" })).toBeVisible();
  await page.screenshot({ path: "target/enforcement-evidence-mobile.png", fullPage: true });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false);
  await page.setViewportSize({ width: 812, height: 375 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false);
  await expect(page.getByRole("button", { name: "登记证据" })).toBeVisible();
  expect(consoleErrors).toEqual([]);
  expect(httpErrors).toEqual([]);
});
