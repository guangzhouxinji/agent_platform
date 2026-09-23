import { expect, test } from "@playwright/test";

async function blockPatentPreparation(page) {
  await page.route("**/api/v0/patent-comparisons/preparations", route => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({
      dataMode: "PUBLIC_LOCAL", sourceVersion: "fixture-v1", ruleVersion: "fixture-v1", suggestedFeatures: []
    })
  }));
}

test("patent comparison clearly blocks creation when public claims are unavailable", async ({ page }) => {
  await blockPatentPreparation(page);
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto("compare/patents");
  await page.getByRole("button", { name: "准备对照" }).click();
  await expect(page.getByText("资料不完整，无法创建对照任务", { exact: true })).toBeVisible();
  await expect(page.getByText("本地公开数据", { exact: true })).toHaveCount(1);
  await expect(page.getByRole("button", { name: "确认并创建任务" })).toHaveCount(0);
  await page.screenshot({ path: "target/comparison-ux-refine-desktop.png", fullPage: true });
});

test("patent comparison remains single-column without horizontal overflow on mobile", async ({ page }) => {
  await blockPatentPreparation(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("compare/patents");
  await expect(page.getByRole("button", { name: "准备对照" })).toBeVisible();
  await page.getByRole("button", { name: "准备对照" }).click();
  await expect(page.getByText("资料不完整，无法创建对照任务", { exact: true })).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await page.screenshot({ path: "target/comparison-ux-refine-mobile.png", fullPage: true });
});
