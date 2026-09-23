import { expect, test } from "@playwright/test";
import { installDevelopmentSourceRoutes } from "./development-source-fixture.mjs";

const pages = [
  ["consultation", "知识产权咨询", "知识产权咨询"],
  ["search/patents", "专利检索", "专利相似度检索"],
  ["compare/patents", "专利对照", "专利技术对照"],
  ["search/trademarks", "商标检索", "商标相似度检索"],
  ["compare/trademarks", "商标比对", "商标多维对照"],
  ["libraries/patents", "专利库", "专利资料库"],
  ["libraries/trademarks", "商标库", "商标资料库"],
  ["libraries/cases", "案例库", "知识产权案例库"],
  ["enforcement", "执法", "执法辅助工作台"],
  ["adjudication", "裁决", "裁决辅助工作台"]
];

async function capture(page, name) {
  await page.screenshot({ path: `target/development-acceptance/${name}.png`, fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), name).toBeLessThanOrEqual(0);
}

test.describe.serial("development usability acceptance", () => {
  test.beforeEach(async ({ page }) => installDevelopmentSourceRoutes(page));
  test("walks the complete desktop development flow", async ({ page }) => {
    test.setTimeout(90_000);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.route("**/api/v0/patent-search-tasks/preparations", route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ suggestedFeatures: ["水性粘结剂", "双区烘干", "在线测厚"], limitation: "开发期前端验收夹具，必须人工确认。" })
    }));
    await page.route("**/api/v0/trademark-search-tasks", route => route.fulfill({
      status: route.request().method() === "POST" ? 202 : 200,
      contentType: "application/json",
      body: route.request().method() === "POST" ? JSON.stringify({
        taskId: "trademark-search-task-1", status: "SUCCEEDED", attempt: 1, dataMode: "PUBLIC_LOCAL",
        rankingVersion: "PUBLIC-LOCAL-TRADEMARK-V1", inputSnapshot: { originalName: "清源" },
        actualScope: "本地公开商标开发样本", totalCandidates: 1, limitation: "公开开发样本候选，需人工核对。", traceId: "trademark-search-task-1",
        candidates: [{ sourceId: "TM-TEST-1", registrationNumber: "TEST-001", name: "清源", categories: ["3"], goodsServices: ["化妆品"], score: "候选", dimensions: [{ dimension: "TEXT", state: "EXECUTED", basis: "文字完全匹配" }] }]
      }) : "[]"
    }));

    await page.goto("consultation");
    await page.getByLabel("咨询问题").fill("CPC Y02E 光伏 电池");
    await page.getByRole("button", { name: "发送", exact: true }).click();
    await expect(page.locator(".reply-message")).toBeVisible();
    await capture(page, "consultation-result-desktop");

    await page.goto("search/patents");
    await page.getByRole("button", { name: "提取特征" }).click();
    await expect(page.locator(".search-confirmation-shelf, .ant-alert-error").first()).toBeVisible({ timeout: 20_000 });
    await capture(page, "patent-search-preparation-desktop");

    await page.goto("search/trademarks");
    await page.getByRole("button", { name: "开始检索" }).click();
    await expect(page.locator(".search-result-stage").getByText("已完成", { exact: true })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("heading", { name: "候选记录" })).toBeVisible();
    await expect(page.locator(".candidate-card").first()).toBeVisible();
    await capture(page, "trademark-search-result-desktop");

    await page.goto("compare/patents");
    await page.route("**/api/v0/patent-comparisons/preparations", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ dataMode: "PUBLIC_LOCAL", sourceVersion: "fixture-v1", ruleVersion: "fixture-v1", suggestedFeatures: [] }) }));
    await page.getByRole("button", { name: "准备对照" }).click();
    await expect(page.getByText("资料不完整，无法创建对照任务", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "确认并创建任务" })).toHaveCount(0);
    await capture(page, "patent-comparison-blocked-desktop");

    await page.goto("compare/trademarks");
    await page.route("**/api/v0/trademark-comparisons/preparations", route => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ dataMode: "PUBLIC_LOCAL", sourceVersion: "fixture-v1", ruleVersion: "fixture-v1", suggestedCategories: [], suggestedGoodsServices: [], selectedDimensions: ["TEXT"] }) }));
    await page.getByRole("button", { name: "准备对照" }).click();
    await expect(page.getByText("资料不完整，无法创建对照任务", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "确认并创建任务" })).toHaveCount(0);
    await capture(page, "trademark-comparison-blocked-desktop");

    for (const [path, name] of [["libraries/patents", "patent-library"], ["libraries/trademarks", "trademark-library"], ["libraries/cases", "case-library"]]) {
      await page.goto(path);
      await expect(page.getByText(/共 \d+ 条/).first()).toBeVisible();
      const detail = page.getByRole("button", { name: "查看详情" }).first();
      if (await detail.isVisible()) {
        await detail.click();
        await expect(page.locator(".ant-drawer")).toBeVisible();
      }
      await capture(page, `${name}-detail-desktop`);
    }

    await page.goto("enforcement");
    await expect(page.getByRole("button", { name: "创建工作项" })).toBeEnabled();
    await capture(page, "enforcement-workflow-entry-desktop");

    await page.goto("adjudication");
    await expect(page.getByRole("button", { name: "创建裁决工作项" })).toBeEnabled();
    await capture(page, "adjudication-workflow-entry-desktop");
  });

  test("reviews every workbench at 390px", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    for (const [path, name, heading] of pages) {
      await page.goto(path);
      await expect(page.getByRole("heading", { name: heading })).toBeVisible();
      if (path === "enforcement") await expect(page.getByRole("button", { name: "创建工作项" })).toBeEnabled();
      if (path === "adjudication") await expect(page.getByRole("button", { name: "创建裁决工作项" })).toBeEnabled();
      if (path.startsWith("libraries/")) await expect(page.getByText(/共 \d+ 条/).first()).toBeVisible();
      if (path.startsWith("compare/")) await expect(page.getByRole("button", { name: "准备对照" })).toBeEnabled();
      await capture(page, `${name}-mobile`);
    }
  });

  test("does not repeat adjudication development boundaries as a global page banner", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto("adjudication");
    await expect(page.getByText("合成开发数据", { exact: true })).toHaveCount(0);
    await expect(page.getByText("非正式决定", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "创建裁决工作项" })).toBeEnabled();
  });
});
