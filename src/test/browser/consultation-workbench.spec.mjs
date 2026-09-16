import { expect, test } from "@playwright/test";

test("completes clarification and cited-answer workflow", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("咨询问题").fill("帮我查一下涂布工艺相关专利。");
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.locator('[data-outcome="NEEDS_CLARIFICATION"]')).toContainText("检索目的");
  await expect(page.locator('[data-outcome="NEEDS_CLARIFICATION"]')).toContainText("具体技术条件");

  await page.getByLabel("补充所需信息").fill("水性粘结剂、双区烘干；用于技术参考。");
  await page.getByRole("button", { name: "提交补充" }).click();
  await expect(page.locator('[data-outcome="ANSWER_READY"]')).toContainText("回答版本 2");
  await expect(page.locator('[data-outcome="ANSWER_READY"] .candidate')).toHaveCount(2);
  await page.screenshot({ path: "target/playwright-artifacts/consultation-desktop.png", fullPage: true });

  await page.getByRole("button", { name: "打开来源" }).first().click();
  await expect(page.getByRole("dialog")).toContainText("V0-SYN-PAT-001");
  await expect(page.getByRole("dialog")).toContainText("权利要求 1");
});

test("renders normal complete question without unnecessary clarification", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("咨询问题").fill("水性粘结剂、双区烘干、在线测厚反馈");
  await page.locator("#consultation-workspace").getByLabel("检索目的").selectOption("TECHNICAL_REFERENCE");
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.locator('[data-outcome="ANSWER_READY"]')).toContainText("不构成新颖性、侵权、有效性或授权结论");
  await expect(page.locator('[data-outcome="ANSWER_READY"] .candidate')).toHaveCount(2);
});

test("renders access denial without candidates", async ({ page }) => {
  await page.route("**/api/v0/consultations", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ consultationId: "denied", answerId: "denied-answer", answerVersion: 1, outcomeType: "ACCESS_DENIED", issues: [], candidates: [], sourceLinks: [], content: "当前无权访问相关资料。", limitation: "未读取来源内容" })
  }));
  await page.goto("/");
  await page.getByLabel("咨询问题").fill("水性粘结剂技术方案");
  await page.locator("#consultation-workspace").getByLabel("检索目的").selectOption("TECHNICAL_REFERENCE");
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.locator('[data-outcome="ACCESS_DENIED"]')).toContainText("当前无权访问");
  await expect(page.locator(".candidate")).toHaveCount(0);
});

test("renders unavailable source and remains usable on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route("**/api/v0/consultations", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ consultationId: "unavailable", answerId: "unavailable-answer", answerVersion: 1, outcomeType: "SOURCE_UNAVAILABLE", issues: [], candidates: [], sourceLinks: [], content: "来源当前不可用，无法形成有依据的回答。", limitation: "不等于没有相关技术" })
  }));
  await page.goto("/");
  await page.getByLabel("咨询问题").fill("水性粘结剂技术方案");
  await page.locator("#consultation-workspace").getByLabel("检索目的").selectOption("TECHNICAL_REFERENCE");
  await page.getByRole("button", { name: "开始检索" }).click();
  await expect(page.locator('[data-outcome="SOURCE_UNAVAILABLE"]')).toContainText("不等于没有相关技术");
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await expect(page.getByRole("button", { name: "新建咨询" })).toBeVisible();
  await page.screenshot({ path: "target/playwright-artifacts/consultation-mobile.png", fullPage: true });
});

test("runs filtered synthetic patent search and preserves task history", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "专利检索" }).click();
  await expect(page.getByRole("heading", { name: "专利相似度检索" })).toBeVisible();
  await expect(page.locator("#patent-search-workspace").getByText("SYNTHETIC DATA")).toBeVisible();
  await page.getByLabel("确认后的检索特征").fill("在线测厚, 激光测量");
  await page.getByLabel("发明", { exact: true }).uncheck();
  await page.getByLabel("实用新型", { exact: true }).check();
  await page.getByLabel("分类").fill("G01B");
  await page.getByRole("button", { name: "创建检索任务" }).click();

  await expect(page.locator("#patent-task-summary")).toContainText("检索完成");
  await expect(page.locator("#patent-task-summary")).toContainText("当前结果来自 120 条拟真合成专利文献");
  await expect(page.locator(".patent-candidate")).toHaveCount(1);
  await expect(page.locator(".patent-candidate")).toContainText("在线测厚反馈控制装置");
  await expect(page.locator(".patent-candidate")).toContainText("SYN-PAT-002");
  await page.getByRole("button", { name: "刷新历史任务" }).click();
  await expect(page.locator(".history-button")).toHaveCount(1);
  await page.screenshot({ path: "target/playwright-artifacts/patent-search-desktop.png", fullPage: true });
});

test("synthetic patent search remains usable without horizontal overflow on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "专利检索" }).click();
  await expect(page.getByRole("button", { name: "创建检索任务" })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await page.screenshot({ path: "target/playwright-artifacts/patent-search-mobile.png", fullPage: true });
});

test("runs synthetic trademark text search and discloses unavailable capability", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "商标检索" }).click();
  await expect(page.getByRole("heading", { name: "商标相似度检索" })).toBeVisible();
  await expect(page.getByText("图形和组合检索当前未配置")).toBeVisible();
  await page.getByRole("button", { name: "创建检索任务" }).click();
  await expect(page.locator("#trademark-task-summary")).toContainText("检索完成");
  await expect(page.locator("#trademark-task-summary")).toContainText("不代表真实商标库、近似结论、注册成功率或 Recall@K");
  await expect(page.locator(".trademark-candidate")).toHaveCount(5);
  await expect(page.locator(".trademark-candidate").first()).toContainText("清源");
  await expect(page.locator("#trademark-task-candidates")).toContainText("SEMANTIC · 未分析");
  await page.getByRole("button", { name: "刷新历史任务" }).click();
  await expect(page.locator("#trademark-history .history-button")).toHaveCount(1);
  await page.screenshot({ path: "target/playwright-artifacts/trademark-search-desktop.png", fullPage: true });
});

test("keeps trademark workbench usable on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "商标检索" }).click();
  await expect(page.getByRole("button", { name: "创建检索任务" })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await page.screenshot({ path: "target/playwright-artifacts/trademark-search-mobile.png", fullPage: true });
});
