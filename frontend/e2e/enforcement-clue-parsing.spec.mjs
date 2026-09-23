import { expect, test } from "@playwright/test";

const envelope = data => ({ requestId: "req-parse-e2e", resultType: "ENFORCEMENT_CLUE_PARSE", data, limitations: ["DEVELOPMENT_ONLY", "PENDING_HUMAN_REVIEW", "NOT_A_LEGAL_FINDING", "NO_MODEL_USED", "EVIDENCE_NOT_ACCEPTED"] });
const json = body => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

test("clue parsing creates, revises, confirms, and remains usable on mobile", async ({ page }) => {
  const lead = { leadId: "lead-001", sourceRecordId: "report-001", sourceVersion: "v1", sourceAvailability: "AVAILABLE", suspectedTypes: ["COUNTERFEIT_PATENT"], processingStatus: "PENDING_VERIFICATION", reviewRequired: false, version: 1 };
  const tasks = [];
  const results = [];
  const machineFields = [
    { kind: "SUBJECT", order: 1, role: "投诉人", rawValue: "甲方科技有限公司", sourceFragment: "甲方科技有限公司", fieldPath: "subjects[0]", sourceKind: "SOURCE_SNAPSHOT" },
    { kind: "SUBJECT", order: 2, role: "销售方", rawValue: "乙方商贸中心", creditCode: "91310000TEST000001", sourceFragment: "乙方商贸中心", fieldPath: "subjects[1]", sourceKind: "SOURCE_SNAPSHOT" },
    { kind: "FACT", order: 3, rawValue: "商品包装标注专利字样", sourceFragment: "商品包装标注专利字样", fieldPath: "facts[0]", sourceKind: "SOURCE_SNAPSHOT" },
    { kind: "TIME", order: 4, rawValue: "2026年9月19日", normalizedValue: "2026-09-19", sourceFragment: "2026年9月19日", fieldPath: "eventTimes[0]", sourceKind: "SOURCE_SNAPSHOT" },
    { kind: "EVIDENCE_REFERENCE", order: 5, rawValue: "EVIDENCE-001", sourceFragment: "EVIDENCE-001", fieldPath: "evidenceRefs[0]", sourceKind: "SOURCE_SNAPSHOT", referenceStatus: "PENDING_CONNECTION" },
    { kind: "CLASSIFICATION", order: 6, rawValue: "COUNTERFEIT_PATENT", sourceFragment: "COUNTERFEIT_PATENT", fieldPath: "lead.suspectedTypes", sourceKind: "RULE_MATCH" }
  ];
  const makeResult = (version, revisionKind = "MACHINE", fields = machineFields) => ({ resultId: `result-${version}`, leadId: lead.leadId, version, previousResultId: version > 1 ? `result-${version - 1}` : null, revisionKind, reviewStatus: "PENDING_REVIEW", parserVersion: "STRUCTURED_LOCAL_V1", modelUsage: "NO_MODEL_USED", priorityLevel: "TO_BE_EVALUATED", priorityReason: "FACTORS_NOT_CONFIRMED", notLegalProbability: true, reviewRequired: false, fields, issues: version === 1 ? [{ kind: "MISSING", fieldPath: "places", reasonCode: "PLACES_MISSING" }] : [], createdBy: "v0-authorized-user", createdAt: "2026-09-20T09:00:00Z", updatedBy: "v0-authorized-user", updatedAt: "2026-09-20T09:00:00Z" });

  await page.route("**/api/v0/enforcement/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/v0/enforcement/leads" && request.method() === "GET") return route.fulfill(json(envelope({ items: [lead], total: 1, page: 1, pageSize: 100 })));
    if (path === "/api/v0/enforcement/leads/lead-001/parse-tasks" && request.method() === "GET") return route.fulfill(json(envelope(tasks)));
    if (path === "/api/v0/enforcement/leads/lead-001/parses" && request.method() === "GET") return route.fulfill(json(envelope(results)));
    if (path === "/api/v0/enforcement/leads/lead-001/parse-tasks" && request.method() === "POST") {
      const result = makeResult(1);
      results.unshift(result);
      const task = { taskId: "parse-001", leadId: lead.leadId, status: "partial_failed", resultVersion: 1, fieldCount: result.fields.length, issueCount: 1, parserVersion: "STRUCTURED_LOCAL_V1", createdAt: "2026-09-20T09:00:00Z", completedAt: "2026-09-20T09:00:01Z" };
      tasks.unshift(task);
      return route.fulfill(json(envelope(task)));
    }
    if (path === "/api/v0/enforcement/leads/lead-001/parses/1/revisions" && request.method() === "POST") {
      const body = request.postDataJSON();
      const revised = makeResult(2, "HUMAN_REVISION", body.fields.map((field, index) => ({ ...field, order: index + 1, sourceFragment: field.rawValue, fieldPath: `humanRevision.fields[${index}]`, sourceKind: "HUMAN_REVISION", referenceStatus: field.kind === "EVIDENCE_REFERENCE" ? "PENDING_CONNECTION" : null })));
      results.unshift(revised);
      return route.fulfill(json(envelope(revised)));
    }
    if (path === "/api/v0/enforcement/leads/lead-001/parses/2/confirm" && request.method() === "POST") {
      results[0].reviewStatus = "CONFIRMED";
      return route.fulfill(json(envelope(results[0])));
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ error: { code: "NOT_FOUND" } }) });
  });

  await page.goto("enforcement/parsing");
  await expect(page.getByText("report-001").first()).toBeVisible();
  await expect(page.getByText("尚无解析版本，可运行一次结构化解析。")).toBeVisible();
  await page.getByRole("button", { name: "运行解析" }).click();
  await expect(page.getByText("部分完成").last()).toBeVisible();
  await expect(page.getByText("甲方科技有限公司")).toBeVisible();
  await expect(page.getByText("2026-09-19")).toBeVisible();
  await expect(page.getByText("待接入")).toBeVisible();
  await expect(page.getByText("字段缺失")).toBeVisible();

  await page.getByRole("button", { name: "人工修订" }).click();
  await page.getByLabel("修订原因").fill("核对来源后修正结构字段");
  await page.getByRole("button", { name: "生成修订版本" }).click();
  await expect(page.getByText("已生成修订版本 V2")).toBeVisible();
  await expect(page.getByText("人工修订").first()).toBeVisible();
  await page.getByRole("button", { name: "确认版本" }).click();
  await expect(page.getByText("确认后该版本不可修改，但不会形成违法认定或正式状态。")).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "确认版本" }).click();
  await expect(page.getByText("解析版本已确认")).toBeVisible();
  await expect(page.getByText("已确认").first()).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.locator(".ant-message")).toHaveCount(0, { timeout: 5000 });
  await page.screenshot({ path: "target/enforcement-clue-parsing-desktop.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false);
  await expect(page.getByText("线索结构化解析")).toBeVisible();
  await page.screenshot({ path: "target/enforcement-clue-parsing-mobile.png", fullPage: true });
});
