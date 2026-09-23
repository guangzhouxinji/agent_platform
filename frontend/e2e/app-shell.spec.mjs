import { expect, test } from "@playwright/test";

const routes = [
  ["consultation", "知识产权咨询"],
  ["search/patents", "专利检索"],
  ["compare/patents", "专利对照"],
  ["search/trademarks", "商标检索"],
  ["search/cases", "案例检索"],
  ["compare/trademarks", "商标比对"],
  ["libraries/patents", "专利库"],
  ["libraries/trademarks", "商标库"],
  ["libraries/cases", "案例库"],
  ["enforcement", "执法工作台"],
  ["adjudication", "裁决工作台"],
  ["consultation/flow", "咨询流程"],
  ["consultation/intents", "意图测试"],
  ["consultation/agent-configuration", "模型配置"]
];

test("desktop hub separates agent workspaces and public resources", async ({ page }) => {
  await page.goto("./");

  await expect(page.getByRole("heading", { name: "智能体工作台", level: 1 })).toBeVisible();
  await expect(page.getByRole("link", { name: "进入咨询智能体" })).toBeVisible();
  await expect(page.getByRole("link", { name: "进入执法智能体" })).toBeVisible();
  await expect(page.getByRole("link", { name: "进入裁决智能体" })).toBeVisible();

  await page.getByRole("link", { name: "进入咨询智能体" }).click();
  await expect(page.getByRole("menuitem", { name: "知识产权咨询", exact: true })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "执法工作台", exact: true })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "专利库", exact: true })).toHaveCount(0);
  await page.getByRole("menuitem", { name: "咨询流程", exact: true }).click();
  await expect(page).toHaveURL(/\/app\/consultation\/flow$/);
  await page.getByRole("button", { name: "收起导航" }).click();
  await expect(page.getByRole("button", { name: "展开导航" })).toBeVisible();
  await page.getByRole("button", { name: "展开导航" }).click();
  await page.getByRole("button", { name: "返回智能体工作台" }).click();
  await page.getByRole("link", { name: "打开资料库" }).click();
  await expect(page).toHaveURL(/\/app\/libraries\/patents$/);
  await expect(page.getByRole("heading", { name: "专利库", level: 1 })).toBeVisible();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  expect(overflow).toBe(false);
});

test("mobile shell uses a drawer and has no horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("enforcement");

  await page.getByRole("button", { name: "打开导航" }).click();
  const drawer = page.getByRole("dialog");
  await expect(drawer).toBeVisible();
  await expect(drawer.getByText("线索发现", { exact: true })).toBeVisible();
  await expect(drawer.getByText("案例库", { exact: true })).toHaveCount(0);
  await drawer.getByText("证据材料", { exact: true }).click();
  await expect(page).toHaveURL(/\/app\/enforcement\/evidence$/);
  await expect(page.getByRole("heading", { name: "证据材料", level: 1 })).toBeVisible();
  await expect(page.getByRole("dialog")).toBeHidden();

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
  expect(overflow).toBe(false);
});

test("every workbench route renders without horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });

  for (const [path, heading] of routes) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: heading, level: 1 })).toBeVisible();
    await expect(page.getByText("使用边界", { exact: true })).toHaveCount(0);
    await expect(page.getByText("当前能力范围", { exact: true })).toHaveCount(0);
    await expect(page.getByText("开发验证", { exact: true })).toHaveCount(0);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
    expect(overflow, `${path} should fit the mobile viewport`).toBe(false);
  }
});

test("desktop task history stays sticky inside the result container", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 800 });
  await page.goto("search/patents");

  const grid = page.locator(".search-results-layout");
  const panel = page.locator(".search-history-rail");
  const result = page.locator(".search-result-stage");
  await result.evaluate((element) => { element.style.minHeight = "1600px"; });
  await expect(panel).toHaveCSS("position", "sticky");

  await page.evaluate(() => window.scrollTo(0, 620));
  await expect.poll(async () => (await panel.boundingBox())?.y).toBeGreaterThanOrEqual(19);
  expect((await panel.boundingBox()).y).toBeLessThanOrEqual(21);

  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  const panelBox = await panel.boundingBox();
  const gridBox = await grid.boundingBox();
  expect(panelBox.y + panelBox.height).toBeLessThanOrEqual(gridBox.y + gridBox.height + 1);
});
