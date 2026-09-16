import { expect, test } from "@playwright/test";

test("queries synthetic trademark library and keeps history states distinct", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "商标库", exact: true }).click();
  const workspace = page.locator("#trademark-library-workspace");
  await expect(workspace.getByText("商标库查询完成")).toBeVisible();
  await expect(workspace.locator(".library-row")).toHaveCount(21);

  await workspace.getByLabel("注册号").fill("76810010");
  await workspace.getByRole("button", { name: "查询商标库" }).click();
  await expect(workspace.locator(".library-row")).toHaveCount(2);
  await workspace.getByRole("button", { name: "查看详情" }).click();
  await expect(workspace.locator("#trademark-library-detail")).toContainText("第 35 类");
  await workspace.getByRole("button", { name: "异议信息" }).click();
  await expect(workspace.locator("#trademark-library-history-result")).toContainText("异议申请");
  await expect(workspace.locator("#trademark-library-history-result")).toContainText("尚无决定结果");
});

test("shows validation errors and remains usable on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "商标库", exact: true }).click();
  const workspace = page.locator("#trademark-library-workspace");
  await expect(workspace.getByText("商标库查询完成")).toBeVisible();
  await workspace.getByLabel("注册日起").fill("2025-01-02");
  await workspace.getByLabel("注册日止").fill("2025-01-01");
  await workspace.getByRole("button", { name: "查询商标库" }).click();
  await expect(workspace.getByText("注册日期起始日期不能晚于结束日期")).toBeVisible();
  await expect(workspace.getByLabel("商标库数据模式").getByText("SYNTHETIC_V0")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
});
