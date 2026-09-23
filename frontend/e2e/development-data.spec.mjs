import { expect, test } from "@playwright/test";

test("the retired synthetic center route redirects to the agent hub", async ({ page }) => {
  await page.goto("development/synthetic-center");
  await expect(page).toHaveURL(/\/app\/?$/);
  await expect(page.getByRole("heading", { name: "智能体工作台" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "开发数据", exact: true })).toHaveCount(0);
});

test("the retired synthetic center stays absent from mobile navigation", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("consultation");
  await page.getByRole("button", { name: "打开导航" }).click();
  await expect(page.getByRole("menuitem", { name: "开发数据", exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0);
});
