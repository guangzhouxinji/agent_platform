import { expect, test } from "@playwright/test";

test("queries patent library and opens version content and events", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "专利库", exact: true }).click();
  const workspace = page.locator("#patent-library-workspace");
  await expect(workspace.getByText("专利库查询完成")).toBeVisible();
  await expect(workspace.locator(".library-row")).toHaveCount(21);

  await workspace.getByLabel("申请号").fill("SYN202110010");
  await workspace.getByRole("button", { name: "查询专利库" }).click();
  await expect(workspace.locator(".library-row")).toHaveCount(2);
  await workspace.getByRole("button", { name: "查看详情" }).click();
  await expect(workspace.locator(".version-button")).toHaveCount(2);
  await workspace.locator(".version-button").last().click();
  await expect(workspace.locator("#patent-library-subresult")).toContainText("权利要求");
  await workspace.getByRole("button", { name: "查看历史事件" }).click();
  await expect(workspace.locator("#patent-library-subresult")).toContainText("无效宣告请求");
  await expect(workspace.locator("#patent-library-subresult")).toContainText("尚无决定");
});

test("shows date errors and content availability on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "专利库", exact: true }).click();
  const workspace = page.locator("#patent-library-workspace");
  await expect(workspace.getByText("专利库查询完成")).toBeVisible();
  await workspace.getByLabel("申请日起").fill("2025-01-02");
  await workspace.getByLabel("申请日止").fill("2025-01-01");
  await workspace.getByRole("button", { name: "查询专利库" }).click();
  await expect(workspace.getByText("申请日期起始日期不能晚于结束日期")).toBeVisible();
  await expect(workspace.getByLabel("专利库数据模式").getByText("SYNTHETIC_V0")).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
});
