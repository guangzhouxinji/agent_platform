import { expect, test } from "@playwright/test";

const envelope = data => ({ requestId: "req-clue-e2e", resultType: "DEVELOPMENT_SUPPORT", data, limitations: ["DEVELOPMENT_ONLY", "PENDING_VERIFICATION", "NOT_AN_INFRINGEMENT_FINDING", "LOCAL_QUERY_ONLY"] });
const json = body => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

test("clue discovery runs a frozen local batch and supports human verification on desktop and mobile", async ({ page }) => {
  const sources = [{ sourceId: "local-feed", sourceName: "本地投诉采集源" }];
  const sourceTasks = [{ taskId: "sync-001", status: "succeeded", processedCount: 2, importedCount: 2, failedCount: 0, createdAt: "2026-09-20T07:00:00Z" }];
  const rules = [{ ruleId: "rule-001", ruleVersion: 1, ruleName: "专利标识核实规则", category: "COUNTERFEIT_PATENT", status: "PUBLISHED" }];
  const discoveryTasks = [];
  const leads = [];

  await page.route("**/api/v0/enforcement/**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path === "/api/v0/enforcement/source-configurations" && request.method() === "GET") return route.fulfill(json(envelope(sources)));
    if (path === "/api/v0/enforcement/source-configurations/local-feed/sync-tasks" && request.method() === "GET") return route.fulfill(json(envelope(sourceTasks)));
    if (path === "/api/v0/enforcement/discovery-rules" && request.method() === "GET") return route.fulfill(json(envelope(rules)));
    if (path === "/api/v0/enforcement/discovery-tasks" && request.method() === "GET") return route.fulfill(json(envelope(discoveryTasks)));
    if (path === "/api/v0/enforcement/discovery-tasks" && request.method() === "POST") {
      const task = { taskId: "discovery-001", status: "succeeded", processedCount: 1, matchedCount: 1, leadCount: 1, failedCount: 0, createdAt: "2026-09-20T08:00:00Z", items: [{ sequence: 1, sourceRecordId: "report-001", ruleId: "rule-001", status: "MATCHED", reasonCode: "RULE_MATCHED", retryable: false, leadId: "lead-001" }], leadIds: ["lead-001"] };
      discoveryTasks.unshift(task);
      leads.unshift({ leadId: "lead-001", sourceId: "local-feed", sourceRecordId: "report-001", sourceVersion: "v1", sourceAvailability: "AVAILABLE", sourceExcerpt: "商品页面展示专利标识，待人工核实来源事实。", suspectedTypes: ["COUNTERFEIT_PATENT"], processingStatus: "PENDING_VERIFICATION", reviewRequired: false, usageScope: "DEVELOPMENT_ONLY", conclusionScope: "NOT_AN_INFRINGEMENT_FINDING", version: 1, updatedAt: "2026-09-20T08:00:00Z" });
      return route.fulfill(json(envelope(task)));
    }
    if (path === "/api/v0/enforcement/leads" && request.method() === "GET") return route.fulfill(json(envelope({ items: leads, total: leads.length, page: 1, pageSize: 100 })));
    if (path === "/api/v0/enforcement/leads/lead-001" && request.method() === "GET") return route.fulfill(json(envelope({ lead: leads[0], matches: [{ ruleId: "rule-001", ruleVersion: 1, suspectedType: "COUNTERFEIT_PATENT", conditionResults: [{ nodeId: "condition-1", operator: "TEXT_CONTAINS", fieldPath: "content", matched: true, reasonCode: "MATCHED" }] }], history: [{ leadVersion: 1, toStatus: "PENDING_VERIFICATION", actor: "v0-authorized-user", occurredAt: "2026-09-20T08:00:00Z" }], duplicateLinks: [], notificationCapability: "LOCAL_QUERY_ONLY" })));
    if (path === "/api/v0/enforcement/leads/lead-001/start-verification" && request.method() === "POST") {
      Object.assign(leads[0], { processingStatus: "VERIFYING", version: 2 });
      return route.fulfill(json(envelope(leads[0])));
    }
    return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ error: { code: "NOT_FOUND" } }) });
  });

  await page.goto("enforcement/discovery");
  await expect(page.getByText("暂无发现任务")).toBeVisible();
  await page.getByLabel("同步批次").click();
  await page.getByText(/本地投诉采集源/).click();
  await page.getByLabel("发现规则").click();
  await page.getByText(/专利标识核实规则/).click();
  await page.getByRole("button", { name: "运行发现" }).click();
  await expect(page.getByText("发现完成").first()).toBeVisible();
  await expect(page.getByText("report-001").first()).toBeVisible();
  await expect(page.getByText("商品页面展示专利标识，待人工核实来源事实。")).toBeVisible();
  await expect(page.getByText("本地授权列表查询")).toBeVisible();
  await page.getByRole("button", { name: "开始核实" }).click();
  await page.getByRole("button", { name: "确认操作" }).click();
  await expect(page.getByText("状态已更新为核实中")).toBeVisible();
  await expect(page.getByText("核实中").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "转处置" })).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.locator(".ant-message")).toHaveCount(0, { timeout: 5000 });
  await page.screenshot({ path: "target/enforcement-clue-discovery-desktop.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false);
  await expect(page.getByText("规则驱动线索发现")).toBeVisible();
  await page.screenshot({ path: "target/enforcement-clue-discovery-mobile.png", fullPage: true });
});
