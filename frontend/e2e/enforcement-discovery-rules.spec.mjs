import { expect, test } from "@playwright/test";

const envelope = data => ({ requestId: "req-rule-e2e", resultType: "DEVELOPMENT_SUPPORT", data, limitations: ["DEVELOPMENT_ONLY", "NOT_PROFESSIONAL_RULE"] });

async function installRoutes(page) {
  const sources = [{ sourceId: "local-enforcement-feed", sourceName: "本地执法采集源", enabled: true, healthStatus: "HEALTHY" }];
  const rules = [];
  const runs = new Map();
  await page.route("**/api/v0/enforcement/source-configurations**", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(sources)) }));
  await page.route("**/api/v0/enforcement/discovery-rules**", async route => {
    const request = route.request();
    const segments = new URL(request.url()).pathname.split("/").filter(Boolean);
    const ruleId = segments[4];
    const version = Number(segments[6]);
    const operation = segments[7];
    if (!ruleId && request.method() === "GET") return route.fulfill(json(envelope(rules)));
    if (!ruleId && request.method() === "POST") {
      const value = request.postDataJSON();
      const rule = { ...value, ruleId: "ENF-RULE-001", organizationRef: "ORG-XINJI-DEMO", ruleVersion: 1, revision: 1, status: "DRAFT", engineType: "DETERMINISTIC_V1", usageScope: "DEVELOPMENT_ONLY", contentDigest: "a".repeat(64), updatedAt: "2026-09-20T08:00:00Z" };
      rules.push(rule); return route.fulfill(json(envelope(rule)));
    }
    const rule = rules.find(item => item.ruleId === ruleId && item.ruleVersion === version);
    if (operation === "dry-runs" && request.method() === "GET") return route.fulfill(json(envelope(runs.get(`${ruleId}:${version}`) ?? [])));
    if (operation === "dry-runs" && request.method() === "POST") {
      const value = request.postDataJSON();
      const run = { runId: "run-001", sampleId: value.sampleId, sampleVersion: value.sampleVersion, resultStatus: "SUCCEEDED", matched: true, executedAt: "2026-09-20T08:02:00Z", conditionResults: [{ nodeId: "condition-1", operator: "TEXT_CONTAINS", fieldPath: "content", matched: true, reasonCode: "MATCHED", actualValue: value.payload.content }, { nodeId: "condition-2", operator: "EXISTS", fieldPath: "subject.name", matched: true, reasonCode: "MATCHED", actualValue: value.payload.subject?.name }, { nodeId: "root", operator: "AND", matched: true, reasonCode: "MATCHED" }] };
      runs.set(`${ruleId}:${version}`, [run]); return route.fulfill(json(envelope(run)));
    }
    if (operation === "publish" && request.method() === "POST") { Object.assign(rule, { status: "PUBLISHED", revision: 2 }); return route.fulfill(json(envelope(rule))); }
    return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ code: "NOT_FOUND" }) });
  });
}

test("discovery rule workflow covers create, deterministic run, publish, and mobile layout", async ({ page }) => {
  await installRoutes(page);
  await page.goto("enforcement/rules");
  await expect(page.getByText("暂无规则，请新建草稿")).toBeVisible();
  await page.getByRole("button", { name: "新建规则" }).click();
  await page.getByLabel("规则名称").fill("专利文字线索开发规则");
  await page.getByLabel("本地数据源").click();
  await page.getByText("本地执法采集源 · 健康").click();
  await page.getByRole("button", { name: "保存草稿" }).click();
  await expect(page.getByText("规则草稿已创建")).toBeVisible();
  await expect(page.getByRole("heading", { name: "专利文字线索开发规则", level: 4 })).toBeVisible();

  await page.getByRole("button", { name: "试运行" }).click();
  await page.getByLabel("内容字段").fill("商品页面展示专利字样");
  await page.getByLabel("主体名称").fill("合成主体甲");
  await page.getByRole("button", { name: "执行试运行" }).click();
  await expect(page.getByText("规则命中")).toBeVisible();
  await expect(page.getByText("命中").first()).toBeVisible();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "开发期发布" }).click();
  await expect(page.getByText("已完成开发期发布")).toBeVisible();
  await expect(page.getByText("开发期发布").first()).toBeVisible();
  await expect(page.locator(".ant-message")).toHaveCount(0, { timeout: 5000 });
  await page.screenshot({ path: "target/enforcement-discovery-rules-desktop.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)).toBe(false);
  await expect(page.getByRole("button", { name: "复制版本" })).toBeVisible();
  await page.screenshot({ path: "target/enforcement-discovery-rules-mobile.png", fullPage: true });
});

const json = body => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
